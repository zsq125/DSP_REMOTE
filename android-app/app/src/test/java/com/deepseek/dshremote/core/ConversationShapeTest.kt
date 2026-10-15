package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conversation shapes the batch tool used to skip, most importantly the EMPTY one.
 *
 * A brand-new conversation is the default state and one of the highest-frequency
 * paths. Opening it must not crash: `scrollToNewest()` used to risk
 * `scrollToItem(-1)` when there is no newest row.
 */
class ConversationShapeTest {

    private fun msg(seq: Long, role: String = "user") =
        Message(seq, role, "m$seq", seq, "final", "x$seq", null)

    /** Mirrors scrollToNewest(): returns null when it must skip (empty list). */
    private fun newestIndexOrNull(visible: Int, retained: Int): Int? {
        val last = minOf(visible, retained) - 1
        return if (last < 0) null else last
    }

    // ---- A. empty conversation (brand new) ----

    @Test fun `空会话 reset 后没有任何行`() {
        val p = MessagePager(300)
        p.reset(emptyList(), null)
        assertEquals(0, p.rows.size)
        assertEquals(0, p.retainedCount)
        assertFalse("空会话不得声称还有更早历史", p.hasMore)
    }

    @Test fun `空会话不会要求滚动 不会崩溃`() {
        val p = MessagePager(300)
        p.reset(emptyList(), null)
        val visible = p.visible(0)
        assertEquals(
            "空列表必须跳过滚动（scrollToItem(-1) 会抛异常）",
            null, newestIndexOrNull(visible.size, p.retainedCount),
        )
    }

    @Test fun `空会话发出第一条消息后能显示`() {
        val p = MessagePager(300)
        p.reset(emptyList(), null)
        assertTrue(p.appendLive(msg(1)))
        assertEquals(1, p.rows.size)
        assertEquals(1L, p.rows.first().seq)
        assertEquals("首条之后应能落底到 index 0", 0, newestIndexOrNull(p.visible(1).size, p.retainedCount))
    }

    @Test fun `空会话连续发送都能累积`() {
        val p = MessagePager(300)
        p.reset(emptyList(), null)
        (1L..5L).forEach { assertTrue(p.appendLive(msg(it))) }
        assertEquals(5, p.rows.size)
        assertEquals(5L, p.rows.last().seq)
    }

    // ---- B. single message ----

    @Test fun `单条会话落底到 index 0`() {
        val p = MessagePager(300)
        p.reset(listOf(msg(1)), null)
        val vis = p.visible(1)
        assertEquals(1, vis.size)
        assertEquals(0, newestIndexOrNull(vis.size, p.retainedCount))
    }

    // ---- C. exactly limit ----

    @Test fun `恰好 limit 条时落底到最后一条`() {
        val p = MessagePager(300)
        val page = (1L..50L).map { msg(it) }
        p.reset(page, null)
        val vis = p.visible(50)
        assertEquals(49, newestIndexOrNull(vis.size, p.retainedCount))
        assertEquals(50L, vis.last().seq)
    }

    // ---- D. limit + 1 ----

    @Test fun `超出 limit 一条时窗口取最新 50 条`() {
        val p = MessagePager(300)
        val page = (1L..51L).map { msg(it) }
        p.reset(page, 1L)
        val vis = p.visible(50)
        assertEquals(50, vis.size)
        assertEquals("落底到窗口最后一条 = 真实最新", 51L, vis.last().seq)
        assertEquals(49, newestIndexOrNull(vis.size, p.retainedCount))
    }

    @Test fun `上滑翻页能拿到最早那一条`() {
        val p = MessagePager(300)
        p.reset((1L..51L).map { msg(it) }, 1L)
        assertTrue(p.hasMore)
        val added = p.prepend(listOf(msg(0L)), null)
        assertEquals(1, added)
        val all = p.visible(100)
        assertEquals("翻页后最早那条在最前", 0L, all.first().seq)
        assertEquals("最新仍在末尾", 51L, all.last().seq)
        assertFalse("翻完就没有更早的了", p.hasMore)
    }
}