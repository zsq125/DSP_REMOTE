package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "打开会话显示的不是最新内容" (2026-12, reported twice by the user).
 *
 * These assertions use the actual seq numbers measured on the gateway
 * (`limit=50` => 5084..5184, ascending) instead of relying on "it compiled and
 * the tests pass".
 */
class NewestContentOrderTest {

    private fun msg(seq: Long, role: String = "tool") =
        Message(seq, role, "m$seq", seq, "final", "x$seq", null)

    /** The exact shape the gateway returns for the measured session. */
    private fun gatewayPage(): List<Message> = (5084L..5184L).map { msg(it) }

    // ---- 1. ordering is preserved (no reversal anywhere) ----

    @Test fun `首屏顺序是升序 首条最旧 末条最新`() {
        val p = MessagePager(300)
        p.reset(gatewayPage(), null)
        // The window holds everything (101 <= 300), so its head is the oldest row.
        assertEquals("窗口首条应是 5084（较旧）", 5084L, p.rows.first().seq)
        assertEquals("窗口末条应是 5184（最新）", 5184L, p.rows.last().seq)
        assertTrue(p.rows.first().seq < p.rows.last().seq)
        // visible(n) is takeLast(n): the last 50 of a 101-row window.
        assertEquals("visible(50) 从 5135 开始", 5135L, p.visible(50).first().seq)
        assertEquals(5184L, p.visible(50).last().seq)
    }

    @Test fun `visible 返回的仍是升序`() {
        val p = MessagePager(300)
        p.reset(gatewayPage(), null)
        val seqs = p.visible(50).map { it.seq }
        assertEquals(seqs.sorted(), seqs)
    }

    // ---- 2. live appends land at the END ----

    @Test fun `appendLive 把新消息放在末尾`() {
        val p = MessagePager(300)
        p.reset(gatewayPage(), null)
        assertTrue(p.appendLive(msg(5185)))
        assertEquals("新消息必须追加在末尾", 5185L, p.rows.last().seq)
        assertEquals("原窗口首条不受影响", 5084L, p.rows.first().seq)
        assertEquals(102, p.rows.size)
    }

    @Test fun `流式增量也在末尾`() {
        val p = MessagePager(300)
        p.reset(gatewayPage(), null)
        p.appendStreamingChunk("新内容", seq = 10, ts = 9_000L)
        val vis = p.visible(51)
        assertEquals("流式行必须追加在末尾", 5184L, vis[vis.size - 2].seq)
        assertTrue(vis.last().isStreaming)
    }

    @Test fun `重复 seq 不会产生重复行`() {
        val p = MessagePager(300)
        p.reset(gatewayPage(), null)
        assertFalse(p.appendLive(msg(5184)))
        assertEquals(50, p.visible(50).size)
    }

    // ---- 3. switching sessions leaves nothing behind ----

    @Test fun `reset 后不含上一个会话的任何 seq`() {
        val a = MessagePager(300)
        a.reset(gatewayPage(), null)                       // session A: 5084..5184
        val b = MessagePager(300)                          // session B
        b.reset((100L..149L).map { msg(it) }, null)

        val bSeqs = b.visible(50).map { it.seq }.toSet()
        assertTrue("不得残留上一个会话的 seq", bSeqs.none { it in 5084L..5184L })
        assertEquals(100L, b.visible(50).first().seq)
        assertEquals(149L, b.visible(50).last().seq)
    }

    @Test fun `切换后首屏仍是升序且末条最新`() {
        val b = MessagePager(300)
        b.reset((200L..249L).map { msg(it) }, null)
        val vis = b.visible(50)
        assertTrue(vis.first().seq < vis.last().seq)
        assertEquals(249L, vis.last().seq)
    }

    // ---- 4. window keeps the NEWEST rows, never the oldest ----

    @Test fun `窗口超限保留最新 而不是最旧`() {
        val p = MessagePager(50)
        p.reset((1L..200L).map { msg(it) }, null)
        val vis = p.visible(50)
        assertEquals("必须保留最新 50 条", 200L, vis.last().seq)
        assertEquals(151L, vis.first().seq)
    }

    @Test fun `visible 取的是末尾 N 条`() {
        val p = MessagePager(300)
        p.reset((1L..100L).map { msg(it) }, null)
        val vis = p.visible(30)
        assertEquals(100L, vis.last().seq)
        assertEquals(71L, vis.first().seq)
    }

    // ---- 5. prepend keeps ordering (older history) ----

    @Test fun `prepend 更早的内容放在头部 末尾不变`() {
        val p = MessagePager(300)
        p.reset((100L..149L).map { msg(it) }, 100L)
        p.prepend((50L..99L).map { msg(it) }, null)
        val vis = p.visible(100)
        assertEquals(50L, vis.first().seq)
        assertEquals("最新的仍在末尾", 149L, vis.last().seq)
        assertEquals(vis.map { it.seq }.sorted(), vis.map { it.seq })
    }
}