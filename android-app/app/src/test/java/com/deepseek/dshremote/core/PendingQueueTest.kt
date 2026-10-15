package com.deepseek.dshremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 待发队列（契约 GAP-15/16/17 + 顺序 ack 排空）。 */
class PendingQueueTest {

    private fun q(store: QueueStore = InMemoryQueueStore()) = PendingQueue(store)

    @Test fun `新队列为空`() {
        assertTrue(q().isEmpty)
        assertEquals(0, q().size)
    }

    @Test fun `入队后 size 加一`() {
        val q = q()
        q.enqueue("s1", "hi", 1L)
        assertEquals(1, q.size)
        assertEquals(false, q.isEmpty)
    }

    @Test fun `peek 返回队首但不弹出`() {
        val q = q()
        val a = q.enqueue("s1", "first", 1L)
        q.enqueue("s1", "second", 2L)
        assertEquals(a.requestId, q.peek()!!.requestId)
        assertEquals(2, q.size)
    }

    @Test fun `严格 FIFO 顺序`() {
        val q = q()
        val a = q.enqueue("s", "1", 1L)
        val b = q.enqueue("s", "2", 2L)
        val c = q.enqueue("s", "3", 3L)
        assertEquals(listOf("1", "2", "3"), q.snapshot().map { it.text })
        q.ack(a.requestId); q.ack(b.requestId); q.ack(c.requestId)
        assertTrue(q.isEmpty)
    }

    @Test fun `ack 只接受队首的 requestId`() {
        val q = q()
        val a = q.enqueue("s", "1", 1L)
        val b = q.enqueue("s", "2", 2L)
        assertNull(q.ack(b.requestId))          // 非队首 → 不弹出
        assertEquals(2, q.size)
        assertNotNull(q.ack(a.requestId))
        assertEquals(1, q.size)
    }

    @Test fun `未知 requestId 的 ack 被忽略`() {
        val q = q()
        q.enqueue("s", "1", 1L)
        assertNull(q.ack("nope"))
        assertEquals(1, q.size)
    }

    @Test fun `空队列 ack 返回 null`() {
        assertNull(q().ack("x"))
    }

    // ── GAP-16：上限 200 ─────────────────────────────
    @Test fun `可入队 200 条`() {
        val q = q()
        repeat(QUEUE_MAX_SIZE) { q.enqueue("s", "t$it", it.toLong()) }
        assertEquals(200, q.size)
    }

    @Test fun `第 201 条被拒绝`() {
        val q = q()
        repeat(QUEUE_MAX_SIZE) { q.enqueue("s", "t$it", it.toLong()) }
        try { q.enqueue("s", "overflow", 999L); fail("应抛 QueueFullException") }
        catch (e: QueueFullException) { assertEquals(200, e.size) }
    }

    @Test fun `被拒绝时队列长度不变 不静默丢弃`() {
        val q = q()
        repeat(QUEUE_MAX_SIZE) { q.enqueue("s", "t$it", it.toLong()) }
        try { q.enqueue("s", "overflow", 999L) } catch (_: QueueFullException) { }
        assertEquals(200, q.size)
        assertEquals("t0", q.snapshot().first().text)
    }

    @Test fun `出队后可继续入队`() {
        val q = q()
        repeat(QUEUE_MAX_SIZE) { q.enqueue("s", "t$it", it.toLong()) }
        val head = q.headRequestId()!!
        q.ack(head)
        q.enqueue("s", "new", 1L)
        assertEquals(200, q.size)
    }

    @Test fun `上限常量是 200`() {
        assertEquals(200, QUEUE_MAX_SIZE)
    }

    // ── GAP-15：session_busy 重试 ───────────────────────
    @Test fun `session_busy 保留在队首并计次`() {
        val q = q()
        val a = q.enqueue("s", "x", 1L)
        val updated = q.fail(a.requestId, "session_busy", "locked")
        assertNotNull(updated)
        assertEquals(1, updated!!.attempts)
        assertEquals(false, updated.failed)
        assertEquals(a.requestId, q.headRequestId())   // 仍在队首 ⇒ FIFO 不破
        assertEquals(1, q.size)
    }

    @Test fun `session_busy 最多重试 3 次`() {
        val q = q()
        val a = q.enqueue("s", "x", 1L)
        repeat(BUSY_MAX_RETRIES) {
            val u = q.fail(a.requestId, "session_busy", "locked")
            assertNotNull(u)
            assertEquals(false, u!!.failed)
        }
        val last = q.fail(a.requestId, "session_busy", "locked")
        assertNotNull(last)
        assertEquals(true, last!!.failed)          // 第 4 次后标记失败
        assertTrue(q.isEmpty)                       // 并从队列移除
    }

    @Test fun `重试间隔常量为 2s`() {
        assertEquals(2_000L, BUSY_RETRY_INTERVAL_MS)
        assertEquals(3, BUSY_MAX_RETRIES)
    }

    @Test fun `其他 4xx 直接失败不重试`() {
        for (code in listOf("unauthorized", "invalid_request", "not_found", "dsh_unavailable")) {
            val q = q()
            val a = q.enqueue("s", "x", 1L)
            val r = q.fail(a.requestId, code, "err")
            assertNotNull("$code 应返回条目", r)
            assertEquals("$code 不应重试", true, r!!.failed)
            assertEquals("$code 不应计入重试次数", 0, r.attempts)
            assertTrue(q.isEmpty)
        }
    }

    @Test fun `internal 错误也直接失败不重试`() {
        val q = q()
        val a = q.enqueue("s", "x", 1L)
        val r = q.fail(a.requestId, "internal", "boom")!!
        assertTrue(r.failed)
        assertTrue(q.isEmpty)
    }

    @Test fun `未知错误码不重试`() {
        val q = q()
        val a = q.enqueue("s", "x", 1L)
        val r = q.fail(a.requestId, "whatever", "?")!!
        assertTrue(r.failed)
    }

    @Test fun `fail 记录最近错误文本`() {
        val q = q()
        val a = q.enqueue("s", "x", 1L)
        assertEquals("locked", q.fail(a.requestId, "session_busy", "locked")!!.lastError)
    }

    @Test fun `fail 忽略非队首 requestId`() {
        val q = q()
        q.enqueue("s", "1", 1L)
        val b = q.enqueue("s", "2", 2L)
        assertNull(q.fail(b.requestId, "session_busy", "x"))
        assertEquals(2, q.size)
    }

    // ── GAP-17：持久化 ───────────────────────────────
    @Test fun `入队即写盘`() {
        val store = InMemoryQueueStore()
        val q = PendingQueue(store)
        val before = store.saveCount
        q.enqueue("s", "x", 1L)
        assertTrue(store.saveCount > before)
    }

    @Test fun `ack 后写盘`() {
        val store = InMemoryQueueStore()
        val q = PendingQueue(store)
        val a = q.enqueue("s", "x", 1L)
        val before = store.saveCount
        q.ack(a.requestId)
        assertTrue(store.saveCount > before)
    }

    @Test fun `重启后可从存储恢复队列`() {
        val store = InMemoryQueueStore()
        val q1 = PendingQueue(store)
        q1.enqueue("s1", "a", 1L)
        q1.enqueue("s1", "b", 2L)
        // 模拟进程被杀后重建
        val q2 = PendingQueue(InMemoryQueueStore(store.load()))
        assertEquals(2, q2.size)
        assertEquals(listOf("a", "b"), q2.snapshot().map { it.text })
    }

    @Test fun `重启后 requestId 不变 可继续对账`() {
        val store = InMemoryQueueStore()
        val q1 = PendingQueue(store)
        val a = q1.enqueue("s1", "a", 1L)
        val q2 = PendingQueue(InMemoryQueueStore(store.load()))
        assertEquals(a.requestId, q2.headRequestId())
        assertNotNull(q2.ack(a.requestId))
    }

    @Test fun `重启后顺序保持`() {
        val store = InMemoryQueueStore()
        val q1 = PendingQueue(store)
        repeat(5) { q1.enqueue("s", "t$it", it.toLong()) }
        val q2 = PendingQueue(InMemoryQueueStore(store.load()))
        assertEquals((0..4).map { "t$it" }, q2.snapshot().map { it.text })
    }

    @Test fun `断网 n 次入队不丢消息`() {
        val store = InMemoryQueueStore()
        val q = PendingQueue(store)
        repeat(10) { round ->
            // 每轮"断网"：入队多条
            repeat(3) { q.enqueue("s", "r${round}m$it", 0L) }
        }
        assertEquals(30, q.size)
        val revived = PendingQueue(InMemoryQueueStore(store.load()))
        assertEquals(30, revived.size)
        assertEquals(30, revived.snapshot().map { it.text }.toSet().size)
    }

    @Test fun `clearFailed 清掉已失败条目`() {
        val q = q()
        val a = q.enqueue("s", "x", 1L)
        q.fail(a.requestId, "invalid_request", "e")
        val b = q.enqueue("s", "y", 2L)
        assertEquals(1, q.size)
        // 失败的已被 fail 移出；此用例确认 clearFailed 幂等
        assertEquals(0, q.clearFailed())
        assertEquals(b.requestId, q.headRequestId())
    }

    @Test fun `clear 清空队列`() {
        val q = q()
        repeat(3) { q.enqueue("s", "t$it", it.toLong()) }
        q.clear()
        assertTrue(q.isEmpty)
    }

    @Test fun `不同会话的消息混排仍按入队顺序`() {
        val q = q()
        val a = q.enqueue("s1", "a", 1L)
        val b = q.enqueue("s2", "b", 2L)
        val c = q.enqueue("s1", "c", 3L)
        assertEquals(listOf("s1", "s2", "s1"), q.snapshot().map { it.sessionId })
        assertEquals(listOf(a.requestId, b.requestId, c.requestId), q.snapshot().map { it.requestId })
    }

    @Test fun `snapshot 返回不可变副本`() {
        val q = q()
        q.enqueue("s", "x", 1L)
        val snap = q.snapshot()
        q.enqueue("s", "y", 2L)
        assertEquals(1, snap.size)
    }

    @Test fun `enqueuedAt 被保留 供持久化后排序`() {
        val q = q()
        val a = q.enqueue("s", "x", 12345L)
        assertEquals(12345L, a.enqueuedAt)
    }
}
