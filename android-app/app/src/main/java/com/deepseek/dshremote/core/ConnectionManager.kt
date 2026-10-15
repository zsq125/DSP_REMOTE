package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.ServerFrame
import java.util.concurrent.atomic.AtomicReference

/**
 * 底层传输抽象（WebSocket）。Android 侧用 OkHttp 实现；单测用假实现。
 *
 * 之所以抽出接口：连接状态机 / 心跳 / 退避 / 队列排空必须能在**纯 JVM**下用虚拟时钟测，
 * 不能依赖真实 socket。
 */
interface Transport {
    fun connect(url: String, listener: Listener)
    fun send(text: String): Boolean
    fun close()

    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClosed(code: Int, reason: String)
        fun onError(t: Throwable)
    }
}

/** 状态机的可观察快照（供 UI 渲染）。 */
data class ConnectionSnapshot(
    val state: ConnectionState,
    val attempt: Int,
    val nextRetryInMs: Long,
    val lastError: String?,
    val queued: Int,
)

/**
 * 连接管理器：把「状态机 + 心跳 + 退避重连 + 队列排空」串起来。
 *
 * 规范 §4：
 *  - 心跳 ping 25s；**等 pong 超时 10s**（只由 pong 复位，其它入站帧不复位 —— GAP-14）；
 *  - 重连初始 500ms、指数退避上限 30s、抖动 0–50%；
 *  - 断线期间指令入队；重连后先 `DRAINING_QUEUE`，逐条等 ack 再发下一条。
 */
class ConnectionManager(
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val random: RandomSource,
    private val transportFactory: (String) -> Transport,
    private val queue: PendingQueue,
    private val onSnapshot: (ConnectionSnapshot) -> Unit = {},
    private val onFrame: (ServerFrame) -> Unit = {},
) {
    companion object {
        const val PING_INTERVAL_MS = 25_000L
        const val PONG_TIMEOUT_MS = 10_000L

        /**
         * R-1 (2026-10-12): how long a TCP/WS connect may take before we call it dead.
         *
         * Device evidence: `SocketTimeoutException ... after 8000ms`. 10s matches
         * OkHttp's own connect timeout and stays well under the 25s heartbeat.
         */
        const val CONNECT_TIMEOUT_MS = 10_000L

        /**
         * R-1: how often the liveness watchdog re-checks the connection.
         * Deliberately short so a Doze-silenced socket is noticed quickly once
         * timers run again (right after the screen turns back on).
         */
        const val LIVENESS_WATCHDOG_MS = 15_000L

        /**
         * R-1: silence (no inbound frame) after which we assume the link is dead.
         * The gateway pushes session.status/frames and answers our 25s ping, so
         * 60s of total silence cannot be healthy.
         */
        const val LIVENESS_SILENCE_LIMIT_MS = 60_000L

        /** 逐条等 ack 的上限（超时则重排下一条，避免卡死）。 */
        const val ACK_TIMEOUT_MS = 15_000L
    }

    private val stateRef = AtomicReference(ConnectionState.DISCONNECTED)
    val state: ConnectionState get() = stateRef.get()

    private var attempt = 0
    private var url: String? = null
    private var transport: Transport? = null

    /** 队首已发出、正在等 ack 的 requestId。 */
    private var inflightRequestId: String? = null

    private var pingTimer: Cancellable? = null
    private var connectTimer: Cancellable? = null
    private var watchdogTimer: Cancellable? = null

    /** R-1: timestamp of the last frame received from the gateway. */
    private var lastInboundAtMs: Long = 0L
    private var pongTimer: Cancellable? = null
    private var retryTimer: Cancellable? = null
    private var ackTimer: Cancellable? = null
    private var lastError: String? = null
    private var nextRetryAt = 0L

    private val snapshot: ConnectionSnapshot
        get() = ConnectionSnapshot(
            state = state,
            attempt = attempt,
            nextRetryInMs = (nextRetryAt - clock.nowMs()).coerceAtLeast(0),
            lastError = lastError,
            queued = queue.size,
        )

    private fun setState(s: ConnectionState) {
        if (stateRef.getAndSet(s) != s) onSnapshot(snapshot)
        else onSnapshot(snapshot)
    }

    /** 设置连接目标并开始连接。 */
    fun connect(streamUrl: String) {
        url = streamUrl
        attempt = 0
        lastError = null
        openTransport()
    }

    /** 用户主动断开（不再自动重连）。 */
    fun disconnect() {
        cancelTimers()
        transport?.close()
        transport = null
        inflightRequestId = null
        setState(ConnectionState.DISCONNECTED)
    }

    /** 网络恢复（NetworkCallback）——立刻重试一次，跳过剩余退避。 */
    fun onNetworkAvailable() {
        if (state == ConnectionState.DISCONNECTED || state == ConnectionState.WAITING_FOR_RETRY || state == ConnectionState.BACKING_OFF) {
            retryTimer?.cancel()
            retryTimer = null
            attempt = 0
            openTransport()
        }
    }

    private fun openTransport() {
        val u = url ?: return
        cancelTimers()
        inflightRequestId = null
        setState(ConnectionState.CONNECTING)
        val t = transportFactory(u)
        transport = t

        // R-1 (2026-10-12): a TCP connect can hang indefinitely. Observed on the
        // device: "SocketTimeoutException ... after 8000ms" with the socket parked
        // in SYN_SENT. The heartbeat only starts in `onOpen`, so without this timer a
        // dead connect never reaches `handleDisconnect` and therefore never retries.
        connectTimer = scheduler.schedule(CONNECT_TIMEOUT_MS) {
            if (state == ConnectionState.CONNECTING) {
                lastError = "connect timeout after ${CONNECT_TIMEOUT_MS}ms"
                transport?.close()          // abandon the half-open socket first
                transport = null
                handleDisconnect(lastError!!)
            }
        }

        t.connect(u, object : Transport.Listener {
            override fun onOpen() {
                connectTimer?.cancel(); connectTimer = null
                lastError = null
                attempt = 0
                // R-1: start the silence clock now. A link that just opened is
                // alive by definition, and leaving lastInboundAtMs at 0 would make
                // the watchdog treat it as infinitely stale and drop it instantly.
                lastInboundAtMs = clock.nowMs()
                setState(ConnectionState.CONNECTED)
                startHeartbeat()
                startDraining()
                startLivenessWatchdog()
            }

            override fun onText(text: String) {
                lastInboundAtMs = clock.nowMs()   // R-1: proof the link is alive
                val frame = com.deepseek.dshremote.protocol.parseServerFrame(text)
                onFrame(frame)
                when (frame) {
                    is ServerFrame.Pong -> onPong()
                    is ServerFrame.Ack -> onAck(frame)
                    is ServerFrame.Error -> onErrorFrame(frame)
                    else -> Unit
                }
            }

            override fun onClosed(code: Int, reason: String) = handleDisconnect("closed code=$code $reason")

            override fun onError(t: Throwable) = handleDisconnect(t.message ?: t::class.java.simpleName)
        })
    }

    /**
     * Liveness watchdog (R-1).
     *
     * OkHttp's own WebSocket keepalive now detects a dead peer, and the heartbeat
     * detects a dead application. This covers the third case: **no callback
     * arrives at all**, which is what Doze produces when the screen is off (the
     * system freezes our timers *and* the socket may be reaped without OkHttp
     * reporting it). Measured symptom: screen off 60s -> zero reconnects in 30s.
     *
     * A periodic check verifies the socket is still open and that we have heard
     * something recently; if not, we reconnect proactively instead of waiting for
     * an event that may never come.
     */
    private fun startLivenessWatchdog() {
        watchdogTimer?.cancel()
        watchdogTimer = scheduler.schedule(LIVENESS_WATCHDOG_MS) {
            val connected = state == ConnectionState.CONNECTED ||
                state == ConnectionState.DRAINING_QUEUE
            if (connected && transport == null) {
                handleDisconnect("watchdog: transport vanished while connected")
            } else if (connected) {
                val silence = clock.nowMs() - lastInboundAtMs
                if (silence > LIVENESS_SILENCE_LIMIT_MS) {
                    handleDisconnect("watchdog: no inbound frame for ${silence}ms")
                }
            }
            if (state != ConnectionState.DISCONNECTED) startLivenessWatchdog()
        }
    }

    // ── 心跳（GAP-14：只由 pong 复位） ────────────────────────────────
    private fun startHeartbeat() {
        pingTimer?.cancel()
        pingTimer = scheduler.schedule(PING_INTERVAL_MS) {
            transport?.send(com.deepseek.dshremote.protocol.ClientFrame.Ping(ts = clock.nowMs()).encode())
            // 发 ping 后开始等 pong；10s 内没有 pong 视为连接失效
            pongTimer?.cancel()
            pongTimer = scheduler.schedule(PONG_TIMEOUT_MS) { handleDisconnect("pong timeout") }
            // 下一个 ping
            pingTimer = scheduler.schedule(PING_INTERVAL_MS) {
                if (state == ConnectionState.CONNECTED || state == ConnectionState.DRAINING_QUEUE) startHeartbeat()
            }
        }
    }

    private fun onPong() {
        pongTimer?.cancel()
        pongTimer = null
    }

    // ── 队列排空 ─────────────────────────────────────────────────────
    private fun startDraining() {
        if (queue.isEmpty) {
            setState(ConnectionState.CONNECTED)
            return
        }
        setState(ConnectionState.DRAINING_QUEUE)
        sendHead()
    }

    /** 发出队首（若尚未在飞）。 */
    private fun sendHead() {
        if (inflightRequestId != null) return
        val head = queue.peek() ?: run {
            setState(ConnectionState.CONNECTED)
            return
        }
        inflightRequestId = head.requestId
        val id = "q-${head.requestId}"
        val ok = transport?.send(
            com.deepseek.dshremote.protocol.ClientFrame.Prompt(
                id = id,
                sessionId = head.sessionId,
                text = head.text,
                requestId = head.requestId,
            ).encode()
        ) ?: false
        if (!ok) {
            inflightRequestId = null
            handleDisconnect("send failed")
            return
        }
        ackTimer?.cancel()
        ackTimer = scheduler.schedule(ACK_TIMEOUT_MS) {
            if (inflightRequestId == head.requestId) {
                inflightRequestId = null
                handleDisconnect("ack timeout")
            }
        }
    }

    private fun onAck(frame: ServerFrame.Ack) {
        // 只认队首 requestId 的 ack（GAP-13：ack 仅回发起连接，这里再对账一次）
        val head = queue.headRequestId() ?: return
        if (frame.requestId != null && frame.requestId != head) return
        ackTimer?.cancel()
        ackTimer = null
        queue.ack(head)
        inflightRequestId = null
        onSnapshot(snapshot)
        // 逐条等 ack 后再发下一条
        if (queue.isEmpty) setState(ConnectionState.CONNECTED) else sendHead()
    }

    private fun onErrorFrame(frame: ServerFrame.Error) {
        val head = queue.headRequestId() ?: return
        // frame.id 形如 "q-<requestId>"
        val fromId = frame.id?.removePrefix("q-")
        if (fromId != null && fromId != head) return
        ackTimer?.cancel()
        ackTimer = null
        queue.fail(head, frame.code, frame.message)
        inflightRequestId = null
        onSnapshot(snapshot)
        if (queue.isEmpty) setState(ConnectionState.CONNECTED) else sendHead()
    }

    // ── 断线与退避 ───────────────────────────────────────────────────
    private fun handleDisconnect(reason: String) {
        val wasBackingOff =
            state == ConnectionState.WAITING_FOR_RETRY || state == ConnectionState.BACKING_OFF
        cancelTimers()
        inflightRequestId = null
        transport?.close()
        transport = null
        lastError = reason
        if (state == ConnectionState.DISCONNECTED) return
        // R-2 (2026-10-12): the early `return` here used to abandon the pending
        // retry -- `cancelTimers()` had already killed the scheduled reconnect, so
        // nothing was left to fire. A disconnect that arrived *while backing off*
        // therefore stopped all retries permanently (measured: 0 reconnects in 30s
        // after a screen-off drop). We must schedule a fresh attempt instead.
        if (wasBackingOff) {
            setState(ConnectionState.WAITING_FOR_RETRY)
            scheduleReconnect()
            return
        }
        setState(ConnectionState.WAITING_FOR_RETRY)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val delay = Backoff.delayMs(attempt, random.nextDouble())
        val at = clock.nowMs() + delay
        setState(ConnectionState.BACKING_OFF)
        retryTimer = scheduler.schedule(delay) {
            attempt++
            openTransport()
        }
        nextRetryAt = at
        onSnapshot(snapshot)
    }

    private fun cancelTimers() {
        pingTimer?.cancel(); pingTimer = null
        pongTimer?.cancel(); pongTimer = null
        retryTimer?.cancel(); retryTimer = null
        ackTimer?.cancel(); ackTimer = null
        connectTimer?.cancel(); connectTimer = null      // R-1: never leak a connect timer
        watchdogTimer?.cancel(); watchdogTimer = null    // R-1
    }

    /** 仅供测试：当前快照。 */
    fun currentSnapshot(): ConnectionSnapshot = snapshot
}
