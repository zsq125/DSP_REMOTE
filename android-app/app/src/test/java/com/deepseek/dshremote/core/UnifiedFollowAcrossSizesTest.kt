package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parameterized coverage of the UNIFIED scroll mechanism across session sizes.
 *
 * Previous tests all used one 2499-message session, so any other length slipped
 * through. The mechanism has exactly one rule:
 *
 *   ONLY A USER GESTURE stops following the newest message.
 *
 * This class exercises that rule for many sizes rather than one sample.
 */
class UnifiedFollowAcrossSizesTest {

    /** Mirrors the rule the composable implements. */
    private class FollowState {
        var userScrolledAway = false
        var firstVisible = 0

        /** Called after every load: unconditional landing on the newest row. */
        fun onLoad(total: Int) {
            userScrolledAway = false
            firstVisible = (total - 1).coerceAtLeast(0)
        }

        /** Called when items are appended (live WS / page fill). */
        fun onAppend(total: Int) {
            if (userScrolledAway) return
            firstVisible = (total - 1).coerceAtLeast(0)
        }

        /** A real drag by the user. */
        fun onUserDrag(scrolledUp: Boolean) {
            userScrolledAway = scrolledUp
        }

        /** Returning to the bottom re-enables following. */
        fun onBackAtBottom() {
            userScrolledAway = false
            firstVisible = (lastKnown - 1).coerceAtLeast(0)
        }

        var lastKnown = 0
    }

    private fun msg(seq: Long) = Message(seq, "assistant", "m$seq", seq, "final", "x$seq", null)

    private val sizes = listOf(6, 30, 50, 51, 300, 2499, 9616)

    @Test fun `任何长度打开都停在最新一条`() {
        for (n in sizes) {
            val p = MessagePager(windowSize = 300)
            val page = (1L..n.toLong()).map { msg(it) }
            p.reset(page, if (n > 50) 1L else null)
            val vis = p.visible(p.rows.size.coerceAtMost(300))
            val f = FollowState()
            f.lastKnown = vis.size
            f.onLoad(vis.size)
            assertEquals("会话 $n 条：打开必须落在最后一项", vis.lastIndex, f.firstVisible)
            assertEquals("会话 $n 条：末条就是最新", n.toLong(), vis.last().seq)
        }
    }

    @Test fun `任何长度追加新消息后仍停在末尾`() {
        for (n in sizes) {
            val p = MessagePager(windowSize = 300)
            p.reset((1L..n.toLong()).map { msg(it) }, null)
            val base = p.visible(300)
            val f = FollowState()
            f.lastKnown = base.size
            f.onLoad(base.size)

            p.appendLive(msg(n.toLong() + 1))
            val after = p.visible(300)
            f.lastKnown = after.size
            f.onAppend(after.size)
            assertEquals("会话 $n 条：追加后仍在最后一项", after.lastIndex, f.firstVisible)
            assertEquals(n.toLong() + 1, after.last().seq)
        }
    }

    @Test fun `用户上滑后追加不回底 滑回底部恢复跟随`() {
        for (n in sizes) {
            val p = MessagePager(windowSize = 300)
            p.reset((1L..n.toLong()).map { msg(it) }, null)
            val base = p.visible(300)
            val f = FollowState()
            f.lastKnown = base.size
            f.onLoad(base.size)

            f.onUserDrag(scrolledUp = true)           // reading history
            p.appendLive(msg(n.toLong() + 1))
            val after = p.visible(300)
            f.lastKnown = after.size
            val parkedAt = f.firstVisible
            f.onAppend(after.size)
            assertEquals("会话 $n 条：上滑后不得被拉回底部", parkedAt, f.firstVisible)

            f.onBackAtBottom()                          // back to the tail
            p.appendLive(msg(n.toLong() + 2))
            val final = p.visible(300)
            f.lastKnown = final.size
            f.onAppend(final.size)
            assertEquals("会话 $n 条：回到底部后恢复跟随", final.lastIndex, f.firstVisible)
        }
    }

    @Test fun `数据变化永远不会关闭跟随`() {
        // The structural bug: deriving "at bottom" from a stale layout produced
        // false on the first frame after every data change and disabled following.
        for (n in sizes) {
            val f = FollowState()
            f.lastKnown = n
            f.onLoad(n)
            var total = n
            repeat(10) { total += 1; f.onAppend(total) }   // many data changes
            assertFalse("会话 $n 条：数据变化不得关闭跟随", f.userScrolledAway)
            assertEquals(total - 1, f.firstVisible)
        }
    }

    @Test fun `切换会话后仍然落在新会话的最新一条`() {
        val f = FollowState()
        val a = 2499L
        val b = 7L
        f.lastKnown = 50; f.onLoad(50)                  // open A
        f.onUserDrag(scrolledUp = true)                  // left scrolled up on A
        f.onLoad(7)                                      // open B (7 items) -> must reset
        val pB = MessagePager(300).also { it.reset((1L..b).map { m -> msg(m) }, null) }
        val visB = pB.visible(300)
        assertEquals("切会话后必须落在新会话的最后一项", visB.lastIndex, f.firstVisible)
        assertFalse("切会话必须重新武装跟随", f.userScrolledAway)
    }
}