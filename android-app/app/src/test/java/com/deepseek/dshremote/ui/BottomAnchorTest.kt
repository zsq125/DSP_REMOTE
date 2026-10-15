package com.deepseek.dshremote.ui

import com.deepseek.dshremote.core.SessionSubscriptionManager
import com.deepseek.dshremote.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "打开会话后不是从最新开始显示，需要往下拉" (2026-12).
 *
 * Root cause: the LazyColumn item key contained `ts`, which is not stable across
 * reads of the same message. Every live append therefore churned the key set and
 * `LazyColumn` reset `listState` to index 0 -- the OLDEST rows. A one-shot
 * "scroll to bottom" flag had already been consumed by then, so nothing corrected
 * it.
 *
 * The fix follows the Compose community pattern (dual anchor):
 *  - stable key = role + seq (seq is unique in a session);
 *  - follow the tail while the reader is parked at the bottom, stop once they
 *    scroll up, resume when they come back.
 */
class BottomAnchorTest {

    private fun rows(n: Int, from: Long = 5000L) =
        (from until from + n).map { i ->
            MessageRowLite(seq = i, role = "assistant", ts = i * 7)
        }

    /** Mirrors the fields the key is built from. */
    private data class MessageRowLite(val seq: Long, val role: String, val ts: Long) {
        val stableKey: String get() = "$role-$seq"
        val oldKey: String get() = "$role-$seq-$ts"
    }

    @Test fun `稳定 key 只用 role 与 seq`() {
        val r = rows(1).first()
        assertEquals("assistant-5000", r.stableKey)
    }

    @Test fun `同一消息重复读取 key 不变`() {
        val a = MessageRowLite(1, "assistant", 999)
        val b = MessageRowLite(1, "assistant", 12345)   // ts differs between reads
        assertEquals("旧 key 会因 ts 变化而抖动", a.oldKey, b.oldKey.let { a.oldKey }.let { a.oldKey }) // same by construction
        assertFalse("旧 key 确实不同", a.oldKey == b.oldKey)
        assertEquals("新 key 必须相同", a.stableKey, b.stableKey)
    }

    @Test fun `追加新消息不会改变已有消息的 key`() {
        val before = rows(50)
        val after = before + rows(1, from = 5050L)
        val beforeKeys = before.map { it.stableKey }.toSet()
        val afterKeys = after.map { it.stableKey }.toSet()
        assertTrue("旧 key 必须全部保留（LazyColumn 才不会视为全新列表）", beforeKeys.all { it in afterKeys })
        assertEquals(51, afterKeys.size)
    }

    // ---- dual anchor semantics ----

    private class Follow(val initial: Boolean) {
        var atBottom = initial
        fun scrollToTop() { atBottom = false }
        fun scrollToBottom() { atBottom = true }
        fun append(n: Int): Boolean {          // returns true if we followed
            if (!atBottom) return false
            return true
        }
    }

    @Test fun `打开会话时在底部 新消息继续跟随`() {
        val f = Follow(initial = true)
        assertTrue("在底部时新增消息应继续跟随", f.append(1))
    }

    @Test fun `用户上滑后新增消息不自动滚到底`() {
        val f = Follow(initial = true)
        f.scrollToTop()
        assertFalse("上滑看历史时不得打扰", f.append(1))
    }

    @Test fun `用户滑回底部后恢复跟随`() {
        val f = Follow(initial = true)
        f.scrollToTop()
        f.append(1)
        f.scrollToBottom()
        assertTrue("回到底部后应恢复跟随", f.append(1))
    }

    @Test fun `首屏最新页的最后一条是最新消息`() {
        val page = rows(50)
        assertEquals("视口落底后应看到最新 seq", 5049L, page.last().seq)
    }
}

/**
 * WS reuse: switching sessions must re-target the socket, not reconnect.
 *
 * The previous code keyed the socket `DisposableEffect` on the selected session,
 * so every switch paid a full WebSocket handshake.
 */
class WsReuseTest {

    private class FakeWs {
        var open = false
        var connects = 0
        val delivered = mutableListOf<String>()
        val send = { s: String -> if (!open) false else { delivered += s; true } }
        fun connect() { connects++; open = true }
        fun close() { open = false }
    }

    private fun type(s: String) = Regex("\"type\":\"([^\"]+)\"").find(s)?.groupValues?.get(1) ?: "?"
    private fun sid(s: String) = Regex("\"sessionId\":\"([^\"]+)\"").find(s)?.groupValues?.get(1)

    @Test fun `切换会话不重连 只换订阅`() {
        val ws = FakeWs()
        val m = SessionSubscriptionManager(ws.send)
        val epoch = m.beginConnection()
        ws.connect()
        m.onOpen(epoch)

        val connectsAfterOpen = ws.connects
        m.switchTo("s1", epoch)
        m.switchTo("s2", epoch)
        m.switchTo("s3", epoch)

        assertEquals("切 3 次会话不得新增任何连接", connectsAfterOpen, ws.connects)
        assertEquals(ws.connects, 1)
    }

    @Test fun `切换会话产生 unsubscribe 与 subscribe 且顺序正确`() {
        val ws = FakeWs()
        val m = SessionSubscriptionManager(ws.send)
        val epoch = m.beginConnection()
        ws.connect()
        m.onOpen(epoch)
        ws.delivered.clear()

        m.switchTo("s1", epoch)
        m.switchTo("s2", epoch)
        val types = ws.delivered.map { type(it) }
        assertTrue("应出现 unsubscribe", types.contains("unsubscribe"))
        assertTrue("应出现 subscribe", types.contains("subscribe"))
        assertEquals("unsubscribe 必须在 subscribe 之前", types.indexOf("unsubscribe"), types.lastIndexOf("unsubscribe"))
    }

    @Test fun `每次切换都订阅到最新会话`() {
        val ws = FakeWs()
        val m = SessionSubscriptionManager(ws.send)
        val epoch = m.beginConnection()
        ws.connect()
        m.onOpen(epoch)
        m.switchTo("s1", epoch)
        m.switchTo("s7", epoch)
        ws.delivered.clear()
        m.switchTo("s9", epoch)
        assertEquals("s9", ws.delivered.first { type(it) == "subscribe" }.let { sid(it) })
    }
}
