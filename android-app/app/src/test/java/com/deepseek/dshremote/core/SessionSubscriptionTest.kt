package com.deepseek.dshremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C4 (2026-10-12): switching sessions must `unsubscribe(旧)` + `subscribe(新)`,
 * and an event from another session must never reach the current one.
 *
 * The gateway records both frames, so these assertions are directly verifiable
 * against `GET /rc/v1/_debug/requests`.
 */
class SessionSubscriptionTest {

    private class Recorder {
        val frames = mutableListOf<String>()
        fun send(s: String): Boolean { frames += s; return true }
        fun types() = frames.map { typeOf(it) }
        fun sessionOf(i: Int) = Regex("\"sessionId\":\"([^\"]+)\"").find(frames[i])?.groupValues?.get(1)
        fun typeOf(s: String) = Regex("\"type\":\"([^\"]+)\"").find(s)?.groupValues?.get(1) ?: "?"
    }

    private fun frame(type: String, sid: String) =
        """{"v":1,"id":"x","type":"$type","sessionId":"$sid"}"""

    private fun event(sid: String) =
        """{"v":1,"type":"session.event","sessionId":"$sid","kind":"message","role":"user","text":"hi","seq":1,"status":"final"}"""

    // ---- switchTo: the C4 contract ----

    @Test fun `切换会话 先 unsubscribe 旧 再 subscribe 新`() {
        val r = Recorder()
        val m = SessionSubscriptionManager(r::send)
        m.onOpen()
        m.switchTo("s1")
        r.frames.clear()

        m.switchTo("s2")
        assertEquals(listOf("unsubscribe", "subscribe"), r.types())
        assertEquals("s1", r.sessionOf(0))
        assertEquals("s2", r.sessionOf(1))
    }

    @Test fun `首次订阅不产生 unsubscribe`() {
        val r = Recorder()
        val m = SessionSubscriptionManager(r::send)
        m.onOpen()
        m.switchTo("s1")
        assertEquals(listOf("subscribe"), r.types())
        assertEquals("s1", r.sessionOf(0))
    }

    @Test fun `重复切到同一会话不产生 unsubscribe`() {
        val r = Recorder()
        val m = SessionSubscriptionManager(r::send)
        m.onOpen()
        m.switchTo("s1")
        r.frames.clear()
        m.switchTo("s1")
        assertFalse("切到自身不得发 unsubscribe", r.types().contains("unsubscribe"))
    }

    @Test fun `反复切 5 次 订阅数与取消数一致`() {
        val r = Recorder()
        val m = SessionSubscriptionManager(r::send)
        m.onOpen()
        m.switchTo("s0")
        r.frames.clear()
        val ids = listOf("s1", "s2", "s3", "s1", "s2")
        for (id in ids) m.switchTo(id)
        val subs = r.types().count { it == "subscribe" }
        val unsubs = r.types().count { it == "unsubscribe" }
        assertEquals("每次换目标都应成对出现", ids.size, subs)
        assertEquals("每次换目标都应取消旧订阅", ids.size, unsubs)
    }

    @Test fun `切走再切回 最终订阅的是最新目标`() {
        val r = Recorder()
        val m = SessionSubscriptionManager(r::send)
        m.onOpen()
        m.switchTo("s1")
        m.switchTo("s2")
        m.switchTo("s1")
        assertEquals("s1", m.current)
    }

    // ---- reconnect (bug2) ----

    @Test fun `断线重连后自动重新订阅当前会话`() {
        val r = Recorder()
        val m = SessionSubscriptionManager(r::send)
        m.onOpen()
        m.switchTo("s1")
        r.frames.clear()

        m.onClosed(1006, "gone")
        m.onOpen()                       // reconnect
        assertEquals(listOf("subscribe"), r.types())
        assertEquals("s1", r.sessionOf(0))
    }

    @Test fun `重连时补发的是当前目标而非首次目标`() {
        val r = Recorder()
        val m = SessionSubscriptionManager(r::send)
        m.onOpen()
        m.switchTo("s1")
        m.switchTo("s9")
        r.frames.clear()
        m.onClosed(1006, "gone")
        m.onOpen()
        assertEquals("s9", r.sessionOf(0))
    }

    @Test fun `断线期间的切换在重连后生效`() {
        val r = Recorder()
        val m = SessionSubscriptionManager(r::send)
        m.onOpen()
        m.switchTo("s1")
        m.onClosed(1006, "gone")
        m.switchTo("s5")                 // switch while the socket is down
        assertEquals("s5", m.current)
        r.frames.clear()
        m.onOpen()
        val sub = r.frames.indexOfFirst { r.typeOf(it) == "subscribe" }
        assertTrue("重连后必须发出 subscribe", sub >= 0)
        assertEquals("s5", r.sessionOf(sub))
    }

    @Test fun `未连接时切换不发帧`() {
        val r = Recorder()
        val m = SessionSubscriptionManager(r::send)
        m.switchTo("s1")                  // before onOpen
        assertTrue("未连接不应发帧", r.frames.isEmpty())
        assertEquals("s1", m.current)
    }

    // ---- event ownership (no cross-talk) ----

    @Test fun `只接收当前会话的事件`() {
        val m = SessionSubscriptionManager({ true })
        m.onOpen()
        m.switchTo("s1")
        assertTrue(m.owns("s1"))
        assertFalse(m.owns("s2"))
        assertEquals(1, m.onText(event("s1")).size)
        assertEquals(0, m.onText(event("s2")).size)
        assertEquals(1, m.foreignEventsDropped)
    }

    @Test fun `两个会话同时有事件时不串台`() {
        val m = SessionSubscriptionManager({ true })
        m.onOpen()
        m.switchTo("s1")
        m.onText(event("s2"))             // noise from another session
        val mine = m.onText(event("s1"))
        assertEquals(1, mine.size)
        assertEquals("s1", mine.single().sessionId)
    }

    @Test fun `切换后旧会话事件被丢弃`() {
        val m = SessionSubscriptionManager({ true })
        m.onOpen()
        m.switchTo("s1")
        m.switchTo("s2")
        assertEquals(0, m.onText(event("s1")).size)
        assertEquals(1, m.onText(event("s2")).size)
    }

    @Test fun `ack 确认订阅成功`() {
        val m = SessionSubscriptionManager({ true })
        m.onOpen()
        m.switchTo("s1")
        assertFalse(m.subscribed)
        m.onText("""{"v":1,"type":"ack","id":"sub-s1","ok":true}""")
        assertTrue(m.subscribed)
    }

    @Test fun `切会话会触发补拉提示`() {
        var catchUps = 0
        val m = SessionSubscriptionManager({ true }, onCatchUpNeeded = { catchUps++ })
        m.onOpen()
        m.switchTo("s1")
        assertEquals(1, catchUps)
        m.switchTo("s2")
        assertEquals("切换后必须补拉，否则丢掉的事件回不来", 2, catchUps)
    }

    // ---- C4 timing: frames produced before the socket opens must not be lost ----

    /** A transport that silently DROPS anything sent before it is open. */
    private class DroppingTransport {
        var open = false
        val delivered = mutableListOf<String>()
        val send = { s: String -> if (!open) false else { delivered += s; true } }
    }

    private fun typeOf(s: String) = Regex("\"type\":\"([^\"]+)\"").find(s)?.groupValues?.get(1) ?: "?"
    private fun sessionOf(s: String) = Regex("\"sessionId\":\"([^\"]+)\"").find(s)?.groupValues?.get(1)

    @Test fun `socket 未 open 时 switchTo 的两帧在 onOpen 后按序补发`() {
        val t = DroppingTransport()
        val m = SessionSubscriptionManager(t.send)

        // A session was subscribed, the socket dropped, and the user switched
        // BEFORE the reconnect finished -- the real-world C4 timing.
        t.open = true
        m.onOpen()
        m.switchTo("s1")
        t.delivered.clear()

        m.onClosed(1006, "screen off")
        t.open = false
        m.switchTo("s2")
        assertEquals("未 open 时不得投递任何帧", 0, t.delivered.size)

        t.open = true
        m.onOpen()

        val types = t.delivered.map { typeOf(it) }
        assertTrue(
            "onOpen 之后必须真的补发（此前 unsubscribe 被静默丢弃）",
            types.contains("unsubscribe") && types.contains("subscribe"),
        )
        assertTrue(
            "顺序必须 unsubscribe 在 subscribe 之前，实际=$types",
            types.indexOf("unsubscribe") in 0 until types.indexOf("subscribe"),
        )
        assertEquals("s1", sessionOf(t.delivered.first { typeOf(it) == "unsubscribe" }))
        assertEquals("s2", sessionOf(t.delivered.first { typeOf(it) == "subscribe" }))
    }

    @Test fun `冷启动时 switchTo 的 subscribe 在 onOpen 后补发`() {
        val t = DroppingTransport()
        val m = SessionSubscriptionManager(t.send)
        m.switchTo("s1")                   // before any socket exists
        assertEquals(0, t.delivered.size)

        t.open = true
        m.onOpen()
        assertTrue(
            "冷启动的 subscribe 必须在 onOpen 后补发，实际=${t.delivered}",
            t.delivered.any { typeOf(it) == "subscribe" && sessionOf(it) == "s1" },
        )
    }

    @Test fun `断线清空待发队列 重连后按当前目标重新订阅`() {
        val t = DroppingTransport()
        val m = SessionSubscriptionManager(t.send)
        m.switchTo("s1")
        t.open = true
        m.onOpen()
        t.delivered.clear()

        m.onClosed(1006, "gone")           // socket dead: frames queued now are stale

        // Queue something while dead, THEN close again to prove the queue is purged.
        m.onClosed(1006, "gone again")
        m.switchTo("s2")                   // produced after the purge => must survive
        t.delivered.clear()
        t.open = true
        m.onOpen()

        val text = t.delivered.joinToString("\n")
        val subscribes = t.delivered.filter { typeOf(it) == "subscribe" }
        assertTrue("重连后必须订阅当前目标 s2，实际=$text", subscribes.any { sessionOf(it) == "s2" })
        assertFalse(
            "关闭后不得重放【关闭之前】排队的老订阅（s1 的 subscribe）",
            subscribes.any { sessionOf(it) == "s1" },
        )
    }

    @Test fun `socket 关闭后清除待发帧`() {
        val t = DroppingTransport()
        val m = SessionSubscriptionManager(t.send)
        m.switchTo("s1")                   // queued, socket not open
        assertEquals(1, m.pendingCount)
        m.onClosed(1000, "client closing")
        assertEquals("关闭后不得保留失效 socket 的待发帧", 0, m.pendingCount)
    }

    // ---- C4 root cause (b): close() must reset `opened` synchronously ----

    /**
     * Regression for the real failure: `OkHttpTransport.close()` used to close the
     * socket WITHOUT notifying the listener, so `opened` stayed true. `emit()`
     * then pushed frames into a closing socket -- they were dropped and, crucially,
     * NOT queued, so the gateway counter showed "connect but subscribe=0" forever.
     */
    @Test fun `close 未通知监听者时帧会被丢弃 通知后必须补发`() {
        val frames = mutableListOf<String>()
        var isOpen = false
        val silentTransport = { s: String -> if (!isOpen) false else { frames += s; true } }
        val m = SessionSubscriptionManager(silentTransport)

        isOpen = true
        m.onOpen()
        m.switchTo("s1")
        isOpen = false
        // Pre-fix: transport.close() with no callback => the manager still thinks the
        // socket is open (this is what `onDispose { t.close() }` did on every switch).

        frames.clear()
        m.switchTo("s2")                   // must NOT be pushed into a dead socket
        assertEquals("socket 已死时不得把帧发给它（否则帧永久丢失）", 0, frames.size)

        // Fixed behaviour: close() notifies synchronously, so opened resets.
        m.onClosed(1000, "client closing")
        isOpen = true
        m.onOpen()
        assertTrue(
            "修复后 close() 复位 opened，帧被入队并在 onOpen 补发，实际=$frames",
            frames.any { typeOf(it) == "subscribe" && sessionOf(it) == "s2" },
        )
    }

    @Test fun `onClosed 必须把 opened 复位`() {
        val t = DroppingTransport()
        val m = SessionSubscriptionManager(t.send)
        t.open = true
        m.onOpen()
        m.switchTo("s1")
        m.onClosed(1000, "client closing")

        t.open = false
        t.delivered.clear()
        m.switchTo("s3")
        assertEquals("关闭后不得投递", 0, t.delivered.size)
        assertTrue("关闭后新帧必须入队", m.pendingCount > 0)
    }

    // ---- C4 (2): the send RESULT decides queueing, not the `opened` flag ----

    /** (1) opened=true but send() returns false => must queue, never drop. */
    @Test fun `send 返回 false 时必须入队 即使 opened 为真`() {
        var deliver = false
        val frames = mutableListOf<String>()
        val m = SessionSubscriptionManager({ s ->
            if (!deliver) false else { frames += s; true }
        })
        m.onOpen()                          // opened == true
        m.switchTo("s1")
        assertEquals("socket 不接受帧时不得投递", 0, frames.size)
        assertTrue("即使 opened 为真，帧也必须入队", m.pendingCount > 0)

        deliver = true
        frames.clear()
        m.onOpen()
        assertTrue(
            "socket 恢复后必须补发，实际=$frames",
            frames.any { typeOf(it) == "subscribe" && sessionOf(it) == "s1" },
        )
    }

    /** (2) onOpen: if the first pending frame cannot be sent, KEEP it and retry. */
    @Test fun `flush 失败时保留帧供下次重试`() {
        var deliver = true
        val frames = mutableListOf<String>()
        val m = SessionSubscriptionManager({ s ->
            if (!deliver) false else { frames += s; true }
        })
        m.switchTo("s1")                     // queued (socket not open)

        deliver = false                      // ...and it still cannot be sent
        m.onOpen()
        assertEquals("发不出去就不算已 flush", 0, frames.size)
        assertTrue("帧必须保留等待下次 onOpen", m.pendingCount > 0)

        deliver = true
        frames.clear()
        m.onOpen()
        assertTrue("下次 onOpen 必须重试成功", frames.any { typeOf(it) == "subscribe" })
    }

    /** (3) after close() purges the queue, a new target must queue and flush again. */
    @Test fun `close 清空队列后 新目标仍可入队并被 flush`() {
        val t = DroppingTransport()
        val m = SessionSubscriptionManager(t.send)
        t.open = true
        m.onOpen()
        m.switchTo("s1")
        t.delivered.clear()

        m.onClosed(1000, "client closing")   // queue purged
        assertEquals(0, m.pendingCount)

        m.switchTo("s2")                     // must queue again
        assertTrue("close 后新帧仍需入队", m.pendingCount > 0)

        t.open = true
        t.delivered.clear()
        m.onOpen()
        assertTrue(
            "重连后必须补发新目标，实际=${t.delivered}",
            t.delivered.any { typeOf(it) == "subscribe" && sessionOf(it) == "s2" },
        )
    }

    /** (4) end-to-end: switchTo -> close -> connect -> onOpen really sends the frame. */
    @Test fun `端到端 switchTo-重连 订阅帧确实发出`() {
        val t = DroppingTransport()
        val m = SessionSubscriptionManager(t.send)

        t.open = true
        m.onOpen()
        m.switchTo("s1")
        t.delivered.clear()

        // MainActivity tears the socket down on every session switch
        t.open = false
        m.onClosed(1000, "client closing")
        m.switchTo("s2")

        // ...and a brand new socket comes up
        t.open = true
        t.delivered.clear()
        m.onOpen()

        val types = t.delivered.map { typeOf(it) }
        assertTrue("重连后 subscribe 必须真的发出，实际=$types", types.contains("subscribe"))
        assertTrue(
            "订阅的必须是新目标 s2，实际=${t.delivered}",
            t.delivered.any { typeOf(it) == "subscribe" && sessionOf(it) == "s2" },
        )
    }

    // ---- C4 final: late callbacks from a dying socket (generation guard) ----

    /**
     * Regression for the race that made subscribe=0 forever:
     *   switchTo(new) queues subscribe(new)
     *   -> the OLD socket's ASYNCHRONOUS onClosed arrives
     *   -> pending.clear() wiped the frame we had just queued
     *   -> the new socket's onOpen had nothing to flush.
     *
     * Connections therefore carry a generation; stale terminal callbacks are ignored.
     */
    @Test fun `迟到的 onClosed 不得抹掉新一代的待发帧`() {
        val t = DroppingTransport()
        val m = SessionSubscriptionManager(t.send)

        val gen1 = m.beginConnection()
        t.open = true
        m.onOpen(gen1)
        m.switchTo("s1")
        t.delivered.clear()

        // MainActivity closes the old socket and starts a new one.
        t.open = false
        val gen2 = m.beginConnection()      // next connection
        m.switchTo("s2")                    // queues subscribe(s2) for gen2

        // *** The old socket's onClosed arrives LATE ***
        m.onClosed(1000, "client closing", epoch = gen1)
        assertTrue("迟到的回调不得清空新一代的帧", m.pendingCount > 0)

        // The new connection comes up and must actually deliver the frame.
        t.open = true
        t.delivered.clear()
        m.onOpen(gen2)
        assertTrue(
            "新一代连接必须补发 subscribe(s2)，实际=${t.delivered}",
            t.delivered.any { typeOf(it) == "subscribe" && sessionOf(it) == "s2" },
        )
    }

    @Test fun `迟到的 onError 同样被忽略`() {
        val t = DroppingTransport()
        val m = SessionSubscriptionManager(t.send)
        val gen1 = m.beginConnection()
        t.open = true
        m.onOpen(gen1)
        val gen2 = m.beginConnection()
        m.switchTo("s9")

        m.onError(RuntimeException("late"), epoch = gen1)
        assertTrue("迟到的 onError 不得清空队列", m.pendingCount > 0)
    }

    @Test fun `当前世代的 onClosed 仍会清理并按当前目标重新推导订阅`() {
        val t = DroppingTransport()
        val m = SessionSubscriptionManager(t.send)
        val g = m.beginConnection()
        t.open = true
        m.onOpen(g)
        m.switchTo("s1")

        m.onClosed(1000, "gone", epoch = g)   // same generation: a real close
        t.open = true
        t.delivered.clear()
        m.onOpen(m.beginConnection())
        assertTrue(
            "重连后必须按 current 重新订阅 s1，实际=${t.delivered}",
            t.delivered.any { typeOf(it) == "subscribe" && sessionOf(it) == "s1" },
        )
    }
}
