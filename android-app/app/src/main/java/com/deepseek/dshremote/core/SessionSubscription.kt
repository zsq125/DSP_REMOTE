package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.ClientFrame
import com.deepseek.dshremote.protocol.ServerFrame
import com.deepseek.dshremote.protocol.parseServerFrame

/**
 * 会话订阅管理（bug2 + bug3 的共同根因修复，2026-10-12）。
 *
 * ## 之前错在哪
 * `DetailPane` 原来在 `DisposableEffect` 的 `onOpen` 里直接发一次 `subscribe`，
 * 且 `onClosed` / `onError` 都是空实现：
 *  - **切换会话**：`DisposableEffect(activeSession.id, …)` 会重建 effect，于是
 *    `onDispose { transport.close() }` 关掉旧连接 —— **但没有 `unsubscribe`**，
 *    服务端仍保留旧订阅；
 *  - **断线重连**：`onOpen` 只在首次连接触发一次，断线后**永不重订阅**，
 *    于是 PC 端再更新也推不过来（用户说的"PC 更新了手机永远不变"）；
 *  - **事件归属**：原来只有 `if (f.sessionId != activeSession.id) return`，
 *    事件确实不会错投，但**旧会话的事件被静默丢弃**，切回去时又没有补拉，
 *    看起来就是"来回切内容从不更新"。
 *
 * ## 现在怎么做
 * 1. **一个连接、按会话切换订阅**：切换时先 `unsubscribe` 旧的，再 `subscribe` 新的，
 *    服务端不会残留旧订阅（无泄漏、无重复投递）。
 * 2. **重连后自动重订阅**：`onOpen` 每次都会触发（含重连），重订阅的是
 *    **当前**目标会话，而不是当初首次那个。
 * 3. **事件严格归属**：`sessionId` 不等于当前订阅的一律丢弃并计数，绝不 append
 *    到当前会话（宁可丢，不可错投）。
 * 4. **切回某会话时标记需要补拉**：因为丢弃的事件不会回来，UI 必须在重新
 *    获得关注时补一次 `/messages`（见 [needsCatchUp]）。
 *
 * 纯逻辑，不依赖 Compose / OkHttp，因此可完整单测。
 */
/** Debug-only trace (Log.e + BuildConfig.DEBUG) for the C4 subscribe hunt. */
internal object DshTrace {
    private val on: Boolean = try {
        Class.forName("com.deepseek.dshremote.BuildConfig").getField("DEBUG").getBoolean(null)
    } catch (e: Throwable) {
        false
    }
    fun log(msg: String) { if (on) android.util.Log.e("DshBoot", msg) }
}

class SessionSubscriptionManager(
    /**
     * Sends one frame and reports whether it **actually went out**.
     *
     * C4 (2026-10-12): the return value is load-bearing. A lambda returning
     * `Unit` cannot express "the socket is not ready", which is exactly the signal
     * the manager needs to queue instead of drop. `OkHttpTransport.send()` now
     * returns `false` for "never connected" as well as "socket dead".
     */
    private val send: (String) -> Boolean,
    /** Called whenever the UI should re-fetch, e.g. after switching or reconnecting. */
    private val onCatchUpNeeded: () -> Unit = {},
) {
    /** The session we currently want events for; null means "nothing subscribed". */
    var current: String? = null
        private set

    /** Consecutive events dropped because they belonged to another session. */
    var foreignEventsDropped: Int = 0
        private set

    /** Reconnects observed (onOpen fired again on the same manager). */
    var reconnects: Int = 0
        private set

    /** Whether the current subscription has been acknowledged by the gateway. */
    var subscribed: Boolean = false
        private set

    private var opened = false

    /**
     * Connection generation. Terminal callbacks carrying an OLD generation are
     * ignored: OkHttp delivers them asynchronously, and a late one from the
     * socket we just closed must not clear frames queued for the next one.
     */
    private var generation = 0

    /** Starts a new connection and returns its generation. */
    fun beginConnection(): Int {
        generation++
        opened = false
        return generation
    }

    /** The generation of the current connection (used when re-targeting an open socket). */
    fun currentGeneration(): Int = generation

    /**
     * Frames produced before the socket is actually open.
     *
     * C4 (2026-10-12): `switchTo()` used to be called BEFORE `transport.connect()`,
     * so the `unsubscribe(old)` frame was handed to a socket that did not exist yet
     * and was silently dropped. `onOpen()` then re-subscribed the new session, so
     * content looked fine while the OLD subscription was never revoked -- exactly
     * the leak/cross-talk the test was looking for.
     *
     * Buffering makes the manager independent of when `switchTo()` happens:
     * anything queued is flushed, in order, as soon as the socket opens.
     */
    private val pending = ArrayDeque<String>()

    /**
     * Session whose `subscribe` was already delivered by the last flush, so
     * [onOpen] must not emit a redundant duplicate for it. One-shot.
     */
    private var flushedSubscribeTarget: String? = null

    /** Frames waiting for an open socket (diagnostics + tests). */
    val pendingCount: Int get() = pending.size

    /** Frame id for the current subscribe, so a re-subscribe can replace it. */
    private fun frameId(sessionId: String) = "sub-" + sessionId

    /**
     * Send now if the transport really accepted it, otherwise queue for the next
     * [onOpen].
     *
     * C4 (2026-10-12): this used to trust the `opened` flag alone. `opened` only
     * says "onOpen fired once", which stays true while the socket is dying -- and
     * `OkHttpTransport.send()` returns the same `false` for "never connected" and
     * "socket dead", so the caller could not tell them apart. Frames pushed into a
     * dying socket were lost AND not queued, which is why the gateway counter
     * showed "connect but subscribe=0" forever.
     *
     * The send RESULT is now the source of truth.
     */
    private fun emit(frame: String) {
        val result = if (opened) send(frame) else false
        DshTrace.log("emit opened=$opened sent=$result pendingBefore=${pending.size} frame=" + frame.take(90))
        if (opened && result) return
        pending.addLast(frame)
        DshTrace.log("emit QUEUED pending=${pending.size}")
    }

    /**
     * Called from `Transport.Listener.onOpen`.
     *
     * Runs on EVERY open, including reconnects -- that is what fixes "PC updated
     * but the phone never sees it": the previous code only subscribed once.
     */
    fun onOpen(epoch: Int = generation) {
        if (epoch != generation) return   // stale connection
        opened = true
        DshTrace.log("onOpen epoch=$epoch gen=$generation pending=${pending.size} current=$current")
        reconnects++                       // the first open counts as a connection

        // 1) flush anything queued while the socket was down, preserving order.
        //    A frame that still cannot be sent is KEPT for the next open rather
        //    than silently dropped.
        while (pending.isNotEmpty()) {
            val frame = pending.first()
            if (!send(frame)) break        // still not deliverable: stop and retry later
            pending.removeFirst()
            // Remember a flushed subscribe so onOpen does not add a duplicate.
            if (frame.contains("\"type\":\"subscribe\"")) {
                flushedSubscribeTarget =
                    Regex("\"sessionId\":\"([^\"]+)\"").find(frame)?.groupValues?.get(1)
            }
        }

        // 2) Guarantee the CURRENT target is subscribed on this fresh connection.
        //    Only needed when the flush did not already deliver a subscribe for it,
        //    otherwise we would emit a redundant duplicate and disturb the
        //    unsubscribe -> subscribe ordering the C4 check looks for.
        val s = current
        if (s != null) {
            val flushedSubscribeForCurrent = flushedSubscribeTarget
            if (flushedSubscribeForCurrent != s) {
                subscribed = false
                emit(ClientFrame.Subscribe(frameId(s), s).encode())
            }
        }
        flushedSubscribeTarget = null      // one-shot: only this open consumed it
    }

    /**
     * Point the subscription at [sessionId].
     *
     * Unsubscribes the previous target first so the server drops it (no leak, no
     * duplicate delivery), then subscribes the new one.
     */
    /**
     * Re-targets an ALREADY OPEN socket: only the frames change, the connection
     * is reused (a reconnect per switch cost a full handshake on every tap).
     */
    fun switchTo(sessionId: String?, epoch: Int = generation) {
        if (epoch != generation) return
        val previous = current
        DshTrace.log("switchTo prev=$previous new=$sessionId opened=$opened pending=${pending.size}")
        if (previous == sessionId) {
            // Same target: still (re)subscribe, queued if the socket is not open yet.
            if (sessionId != null) emit(ClientFrame.Subscribe(frameId(sessionId), sessionId).encode())
            return
        }
        if (previous != null) {
            // Queued when the socket is not open yet; flushed by onOpen() in order.
            emit(ClientFrame.Unsubscribe("unsub-" + previous, previous).encode())
        }
        current = sessionId
        subscribed = false
        if (sessionId != null) {
            emit(ClientFrame.Subscribe(frameId(sessionId), sessionId).encode())
        }
        // Whatever happened to the old session while we were away is gone for good,
        // so the UI must re-fetch to show the latest content.
        onCatchUpNeeded()
    }

    fun onClosed(code: Int, reason: String, epoch: Int = generation) {
        // ★ A late callback from a socket we already replaced must be ignored,
        //   otherwise it wipes the frames queued for the NEW connection.
        if (epoch != generation) { DshTrace.log("onClosed STALE epoch=$epoch gen=$generation ignored"); return }
        DshTrace.log("onClosed epoch=$epoch pendingBefore=${pending.size} current=$current")
        opened = false
        subscribed = false
        // Frames describing a dead socket are useless; drop them. The next
        // onOpen re-derives subscribe(current) when the flush did not cover it.
        pending.clear()
    }

    fun onError(t: Throwable, epoch: Int = generation) {
        if (epoch != generation) return
        opened = false
        subscribed = false
        pending.clear()
    }

    /** True when [sessionId] is the session we want right now. */
    fun owns(sessionId: String?): Boolean = sessionId != null && sessionId == current

    /** Consume a raw server frame; returns the events belonging to the current session. */
    fun onText(text: String): List<ServerFrame.SessionEvent> {
        val f = parseServerFrame(text)
        return when (f) {
            is ServerFrame.Ack -> {
                if (f.id?.startsWith("sub-") == true && f.ok == true) subscribed = true
                emptyList()
            }
            is ServerFrame.SessionEvent -> {
                // Strict ownership: never let another session's event leak in.
                if (!owns(f.sessionId)) {
                    foreignEventsDropped++
                    return emptyList()
                }
                listOf(f)
            }
            else -> emptyList()
        }
    }

    /**
     * Whether the UI should re-fetch on becoming visible again.
     *
     * True after a reconnect, because events emitted while the socket was down
     * were never delivered.
     */
    var needsCatchUp: Boolean = false
        private set

    fun markCatchUpNeeded() { needsCatchUp = true }

    /** Call after a successful re-fetch. */
    fun clearCatchUp() { needsCatchUp = false }
}