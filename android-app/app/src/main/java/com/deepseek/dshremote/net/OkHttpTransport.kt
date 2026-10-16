package com.deepseek.dshremote.net

import com.deepseek.dshremote.core.Cancellable
import com.deepseek.dshremote.core.Clock
import com.deepseek.dshremote.core.Transport
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * OkHttp implementation of [Transport] (Android side).
 *
 * R-1 (2026-10-12): OkHttp's protocol-level WebSocket keepalive.
 *
 * It used to be disabled ("the app does its own heartbeat per contract 4"), but
 * that removed the only liveness mechanism that survives Doze. With the screen
 * off the system freezes our timers, so the app-level ping/pong never fires and a
 * dead socket produces no callback at all -- hence no reconnect. Measured:
 * screen off 60s -> zero reconnects in the following 30s.
 *
 * 15s keeps us well inside the gateway's own heartbeat window while detecting a
 * dead peer in a fraction of the 25s app-level ping interval.
 */
const val WS_PING_INTERVAL_SECONDS = 45L

/** OkHttp 实现的 [Transport]（Android 侧）。 */
class OkHttpTransport(
    private val clock: Clock = Clock.SYSTEM,
) : Transport {

    private val client = OkHttpClient.Builder()
        // 2026-10-16：15s 太紧 —— 网关跑在 DSH 进程里，事件循环被同步重活（搜索索引/
        // 历史缓存）挡住时 pong 会迟到 ⇒ OkHttp 直接判死连接（真机实测 50 次重连、
        // 每次断流都丢一段实时帧，表现为"流式会话跟不上/一直在从旧的加载"）。
        // 放宽到 45s：既能容忍瞬时卡顿，又能在真死链路上及时发现。
        .pingInterval(WS_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // 长连接不读超时
        .build()

    private var ws: WebSocket? = null

    /** Listener of the CURRENT connection; needed so close() can notify it. */
    private var listener: Transport.Listener? = null

    /** C4: ensures close() notifies the listener at most once. */
    private var notifiedClose = false

    /**
     * C4: has onOpen fired for the CURRENT connection? Until it does, `send()`
     * must report failure so the caller queues the frame instead of dropping it.
     */
    private var everOpened = false

    override fun connect(url: String, listener: Transport.Listener) {
        val request = Request.Builder().url(url).build()
        // OkHttp may deliver onClosing and then onClosed; a double onClosed would
        // make the manager schedule two reconnects.
        this.listener = listener
        this.notifiedClose = false
        this.everOpened = false       // C4: a fresh connect has not opened yet
        com.deepseek.dshremote.core.DshTrace.log("ws connect() issued url=$url")
        var notified = false
        fun once(block: () -> Unit) {
            if (notified) return
            notified = true
            block()
        }
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                everOpened = true      // C4: from here on frames can go out
                com.deepseek.dshremote.core.DshTrace.log("ws onOpen code=${response.code}")
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                com.deepseek.dshremote.core.DshTrace.log("ws onClosing code=$code reason=$reason")
                webSocket.close(code, reason)
                once { listener.onClosed(code, reason) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                com.deepseek.dshremote.core.DshTrace.log("ws onClosed code=$code reason=$reason")
                once { listener.onClosed(code, reason) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                com.deepseek.dshremote.core.DshTrace.log("ws onFailure err=${t.javaClass.simpleName} msg=${t.message}")
                once { listener.onError(t) }
            }
        })
    }

    /**
     * C4 (2026-10-12): the result must be meaningful to the caller.
     *
     * `ws?.send(...) ?: false` returned `false` both for "never connected yet" and
     * "socket already dead", so SessionSubscriptionManager could not tell them
     * apart and dropped frames instead of queueing them (the gateway counter then
     * showed "connect but subscribe=0" forever).
     */
    override fun send(text: String): Boolean {
        val s = ws ?: return false          // not connected yet
        if (!everOpened) return false        // connect() issued but onOpen never fired
        return s.send(text)
    }

    /**
     * C4 (2026-10-12): closing must notify the listener **synchronously**.
     *
     * OkHttp delivers `onClosed` asynchronously, so after [close] returns the
     * caller's `opened` flag was still true for a while. `SessionSubscription.emit()`
     * therefore believed it had a live socket and pushed frames into a socket that
     * was already closing -- they were dropped AND never queued, so the gateway
     * counter showed "connect but subscribe=0" forever.
     *
     * `close(code, reason)` is still issued so the peer sees a clean close; we just
     * also fire [Transport.Listener.onClosed] right away (the `once` guard makes the
     * later real callback a no-op).
     */
    override fun close() {
        val previous = ws
        ws = null
        previous?.cancel()
        notifyClose(1000, "client closing")
    }

    /** Fire the terminal close callback at most once per connection. */
    private fun notifyClose(code: Int, reason: String) {
        if (notifiedClose) return
        notifiedClose = true
        val l = listener
        listener = null
        if (l != null) l.onClosed(code, reason)
    }
}
