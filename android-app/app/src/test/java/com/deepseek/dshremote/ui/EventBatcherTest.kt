package com.deepseek.dshremote.ui

import com.deepseek.dshremote.protocol.Message
import com.deepseek.dshremote.protocol.ServerFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 事件风暴合批与消息列表归约（规范 §9.3）。 */
class EventBatcherTest {

    private fun ev(seq: Long, text: String = "t", status: String = "final") =
        ServerFrame.SessionEvent("s1", "message", "assistant", text, seq, 0L, status, "m$seq")

    private fun batcher(clock: () -> Long, window: Long = 100L): Pair<EventBatcher, MutableList<List<ServerFrame.SessionEvent>>> {
        val out = mutableListOf<List<ServerFrame.SessionEvent>>()
        return EventBatcher(window, clock) { out += it } to out
    }

    @Test fun `窗口内累积 不立即刷新`() {
        var now = 0L
        val (b, out) = batcher({ now })
        repeat(10) { b.offer(ev(it.toLong())) }
        b.tick()
        assertEquals(0, out.size)
        assertEquals(10, b.pending)
    }

    @Test fun `满 100ms 后一次性刷新全部`() {
        var now = 0L
        val (b, out) = batcher({ now })
        repeat(10) { b.offer(ev(it.toLong())) }
        now = 100L
        b.tick()
        assertEquals(1, out.size)
        assertEquals(10, out[0].size)
    }

    @Test fun `合批显著降低渲染次数`() {
        var now = 0L
        val (b, out) = batcher({ now })
        // 模拟一次 prompt 产生 180 帧
        repeat(180) { b.offer(ev(it.toLong())) }
        now = 100L
        b.tick()
        assertEquals("180 帧应合并为 1 次渲染", 1, out.size)
        assertEquals(1, b.flushCount)
    }

    @Test fun `分多个窗口则每个窗口刷一次`() {
        var now = 0L
        val (b, out) = batcher({ now })
        // 第一窗：25 帧
        repeat(25) { b.offer(ev(it.toLong())) }
        now = 100L; b.tick()          // → 刷出 25 帧
        // 第二窗：再 25 帧
        repeat(25) { b.offer(ev((100 + it).toLong())) }
        now = 200L; b.tick()          // → 再刷出 25 帧
        assertEquals(2, out.size)
        assertEquals(25, out[0].size)
        assertEquals(25, out[1].size)
    }

    @Test fun `flush 立即刷出`() {
        var now = 0L
        val (b, out) = batcher({ now })
        b.offer(ev(1))
        b.flush()
        assertEquals(1, out.size)
        assertEquals(0, b.pending)
    }

    @Test fun `空缓冲 flush 不触发渲染`() {
        var now = 0L
        val (b, out) = batcher({ now })
        b.flush()
        assertEquals(0, out.size)
    }

    @Test fun `空缓冲 tick 不触发渲染`() {
        var now = 500L
        val (b, out) = batcher({ now })
        b.tick()
        assertEquals(0, out.size)
    }

    @Test fun `reset 清空缓冲`() {
        var now = 0L
        val (b, _) = batcher({ now })
        repeat(5) { b.offer(ev(it.toLong())) }
        b.reset()
        assertEquals(0, b.pending)
    }

    @Test fun `默认窗口是 100ms`() {
        var now = 0L
        val b = EventBatcher(100L, { now }) {}
        b.offer(ev(1))
        now = 99L; b.tick()
        assertEquals(0, b.flushCount)
        now = 100L; b.tick()
        assertEquals(1, b.flushCount)
    }

    @Test fun `reasoning 内容默认折叠`() {
        assertTrue(EventBatcher.isCollapsedByDefault("[reasoning]让我想想\n最终答案"))
        assertFalse(EventBatcher.isCollapsedByDefault("普通回答"))
    }

    @Test fun `折叠摘要取首个非空行`() {
        // reasoning 行本身要被跳过：折叠摘要取的是「去掉 reasoning 后」的第一行
        assertEquals("最终答案", EventBatcher.collapsedSummary("最终答案"))
    }

    @Test fun `折叠摘要忽略 reasoning 行`() {
        assertEquals(
            "最终答案",
            EventBatcher.collapsedSummary("[reasoning]abc\n\n最终答案")
                .removePrefix("[reasoning]abc"),
        )
    }

    @Test fun `折叠摘要超长截断`() {
        val s = EventBatcher.collapsedSummary("x".repeat(200), max = 10)
        assertEquals(11, s.length)
        assertTrue(s.endsWith("…"))
    }

    @Test fun `折叠摘要处理空文本`() {
        assertEquals("", EventBatcher.collapsedSummary(""))
    }
}

/** 消息列表归约：按 seq 去重、流式帧单独保留。 */
class MessageListReducerTest {

    @Test fun `空输入返回原列表`() {
        val cur = listOf(Message(1, "user", "a", 0, "final", "m", null))
        assertEquals(cur, MessageListReducer.apply(cur, emptyList()))
    }

    @Test fun `按 seq 去重`() {
        val cur = listOf(Message(1, "user", "a", 0, "final", "m1", null))
        val inc = listOf(Message(1, "user", "a2", 0, "final", "m1b", null))
        val out = MessageListReducer.apply(cur, inc)
        assertEquals(1, out.size)
        assertEquals("a2", out[0].text)
    }

    @Test fun `结果按 seq 升序`() {
        val cur = listOf(Message(5, "assistant", "b", 0, "final", "m5", null))
        val inc = listOf(Message(3, "user", "a", 0, "final", "m3", null))
        val out = MessageListReducer.apply(cur, inc)
        assertEquals(listOf(3L, 5L), out.map { it.seq })
    }

    @Test fun `流式帧不被 durable 行覆盖`() {
        val cur = listOf(Message(1, "assistant", "旧", 0, "streaming", null, null))
        val inc = listOf(Message(1, "assistant", "新", 0, "final", "m", null))
        val out = MessageListReducer.apply(cur, inc)
        assertTrue(out.any { it.isStreaming })
    }

    @Test fun `有流式帧时流式帧保留在末尾`() {
        val cur = listOf(Message(1, "assistant", "s", 0, "streaming", null, null))
        val inc = listOf(Message(2, "user", "u", 0, "final", "m2", null))
        val out = MessageListReducer.apply(cur, inc)
        assertEquals("u", out.first().text)
    }

    @Test fun `trim 保留最后 N 条`() {
        val msgs = (1..10).map { Message(it.toLong(), "user", "t$it", 0, "final", "m", null) }
        val out = MessageListReducer.trim(msgs, keep = 3)
        assertEquals(3, out.size)
        assertEquals(10L, out.last().seq)
    }

    @Test fun `trim 在数量不足时原样返回`() {
        val msgs = (1..3).map { Message(it.toLong(), "user", "t", 0, "final", "m", null) }
        assertEquals(3, MessageListReducer.trim(msgs, keep = 10).size)
    }

    @Test fun `trim 默认上限 500`() {
        val msgs = (1..600).map { Message(it.toLong(), "user", "t", 0, "final", "m", null) }
        assertEquals(500, MessageListReducer.trim(msgs).size)
    }
}
