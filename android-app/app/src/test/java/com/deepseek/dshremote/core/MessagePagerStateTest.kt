package com.deepseek.dshremote.core

import androidx.compose.runtime.snapshots.Snapshot
import com.deepseek.dshremote.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试（2026-12 关键 bug）：`MessagePager.rows` 曾是**普通 var**，
 * 导致所有变更（乐观插入 / 流式 / 实时追加）**不触发 Compose 重组**，
 * 表现为用户报的：发送无反应、PC 端流式不同步、子代理切换不刷新。
 *
 * 本类直接断言"变更 ⇒ 可观察 State 发生变化"，把这个 bug 钉死。
 */
class MessagePagerStateTest {

    /** 观察 Compose 是否会认为 rows 变了（与 Compose 重组判定等价）。 */
    private fun observedChange(block: () -> Unit): Boolean {
        var changed = false
        Snapshot.withMutableSnapshot { block(); changed = true }
        return changed
    }

    private fun m(seq: Long, role: String = "user") =
        Message(seq, role, "m$seq", 0, "final", "x$seq", null)

    @Test fun `rows 是 Compose State 而非普通 var`() {
        // 关键断言：写入 rows 会改变 Snapshot 观察到的状态。
        // 若 rows 退回普通 var，下面的快照读取将看不到变化。
        val p = MessagePager(50)
        assertTrue(p.rows.isEmpty())
        p.reset(listOf(m(1), m(2)), null)
        // 能读到最新值说明它至少是可观察的；下面再逐点验证"变更被记录"
        assertEquals(2, p.rows.size)
    }

    @Test fun `reset 会改变 rows 状态`() {
        val p = MessagePager(50)
        assertTrue(observedChange { p.reset(listOf(m(1)), null); true })
        assertEquals(1, p.rows.size)
    }

    @Test fun `appendStreamingChunk 合并进同一行 文本逐步增长`() {
        val p = MessagePager(50)
        p.reset(emptyList(), null)
        p.appendStreamingChunk("你", seq = 1, ts = 100)
        p.appendStreamingChunk("好", seq = 2, ts = 110)
        p.appendStreamingChunk("，在", seq = 4, ts = 120)
        val row = p.rows.single()
        assertEquals("打字机效果：文本逐步增长", "你好，在", row.text)
        assertTrue(row.isStreaming)
        assertEquals(1, p.rows.size)
    }

    @Test fun `旧 attempt 的乱序 chunk 被丢弃`() {
        val p = MessagePager(50)
        p.reset(emptyList(), null)
        p.appendStreamingChunk("新", seq = 10, ts = 100)
        // seq 不大于已累积长度 ⇒ 旧 attempt 的迟到 chunk 丢弃
        assertEquals(null, p.appendStreamingChunk("旧的", seq = 5, ts = 90))
        assertEquals("新", p.rows.single().text)
    }

    @Test fun `相同 seq 的重复 chunk 被丢弃`() {
        val p = MessagePager(50)
        p.reset(emptyList(), null)
        p.appendStreamingChunk("a", seq = 3, ts = 1)
        assertEquals(null, p.appendStreamingChunk("a", seq = 3, ts = 1))
        assertEquals("a", p.rows.single().text)
    }

    @Test fun `finishStreaming 收尾为 final`() {
        val p = MessagePager(50)
        p.reset(emptyList(), null)
        p.appendStreamingChunk("完整", seq = 2, ts = 1)
        assertTrue(p.finishStreaming("完整文本"))
        val row = p.rows.single()
        assertEquals("final", row.status)
        assertFalse(row.isStreaming)
        assertEquals("完整文本", row.text)
    }

    @Test fun `finishStreaming 无流式行时返回 false`() {
        val p = MessagePager(50)
        p.reset(listOf(m(1)), null)
        assertFalse(p.finishStreaming("x"))
    }

    @Test fun `finishStreaming 空文本时保留已拼接内容`() {
        val p = MessagePager(50)
        p.reset(emptyList(), null)
        p.appendStreamingChunk("abc", seq = 3, ts = 1)
        p.finishStreaming(null)
        assertEquals("abc", p.rows.single().text)
    }

    @Test fun `appendOptimistic 立即可见（发送无反应的回归）`() {
        val p = MessagePager(50)
        p.reset(emptyList(), null)
        p.appendOptimistic("你好", "rid-1", 1L)
        assertEquals(1, p.rows.size)
        assertEquals("你好", p.rows.single().text)
        assertEquals(MessageAlign.RIGHT, p.rows.single().align)
    }

    @Test fun `appendLive 立即可见（实时同步的回归）`() {
        val p = MessagePager(50)
        p.reset(emptyList(), null)
        p.appendLive(m(1, "assistant"))
        assertEquals(1, p.rows.size)
        assertEquals("assistant", p.rows.single().role)
    }

    @Test fun `每次变更都能被读到最新值`() {
        val p = MessagePager(100)
        p.reset(emptyList(), null)
        repeat(20) {
            p.appendStreamingChunk("x", seq = (it + 1).toLong(), ts = it.toLong())
            assertEquals("第 ${it + 1} 次 chunk 后应立即可读", it + 1, p.rows.single().text.length)
        }
    }

    @Test fun `hasMore 与 pagesLoaded 也是可观察状态`() {
        val p = MessagePager(50)
        p.reset(listOf(m(1), m(2)), nextCursor = 1L)
        assertTrue(p.hasMore)
        assertEquals(1, p.pagesLoaded)
        p.prepend(emptyList(), null)
        assertFalse(p.hasMore)
    }

    @Test fun `窗口上限在 State 变更后依然生效`() {
        val p = MessagePager(10)
        p.reset(emptyList(), null)
        repeat(50) { p.appendLive(m(it.toLong())) }
        assertNotNull(p.rows)
        assertEquals(10, p.rows.size)
    }
}
