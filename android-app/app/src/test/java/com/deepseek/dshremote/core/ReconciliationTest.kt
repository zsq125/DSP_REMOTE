package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 崩溃恢复对账（规范 §9.7）。 */
class ReconciliationTest {

    private fun user(seq: Long, rid: String?, text: String = "t") =
        Message(seq, "user", text, 0, "final", "m$seq", rid)

    private fun assistant(seq: Long, text: String = "a") =
        Message(seq, "assistant", text, 0, "final", "ma$seq", null)

    @Test fun `durable 中找到 requestId 判定已落库`() {
        val msgs = listOf(user(9, "r1"), assistant(10))
        val cmd = PendingCommand("r1", "s1", "hi", 1L)
        assertEquals(Reconciliation.Outcome.ALREADY_PERSISTED, Reconciliation.reconcile(msgs, cmd))
    }

    @Test fun `durable 中找不到判定仍在路上`() {
        val msgs = listOf(user(9, "other"), assistant(10))
        val cmd = PendingCommand("r1", "s1", "hi", 1L)
        assertEquals(Reconciliation.Outcome.STILL_PENDING, Reconciliation.reconcile(msgs, cmd))
    }

    @Test fun `同一 requestId 出现多行判定为重复`() {
        val msgs = listOf(user(9, "r1"), user(10, "r1"))
        val cmd = PendingCommand("r1", "s1", "hi", 1L)
        assertEquals(Reconciliation.Outcome.DUPLICATED, Reconciliation.reconcile(msgs, cmd))
    }

    @Test fun `只统计 role 为 user 的行`() {
        val msgs = listOf(Message(1, "assistant", "x", 0, "final", "m", "r1"))
        val cmd = PendingCommand("r1", "s1", "hi", 1L)
        assertEquals(Reconciliation.Outcome.STILL_PENDING, Reconciliation.reconcile(msgs, cmd))
    }

    @Test fun `countDurable 统计出现次数`() {
        val msgs = listOf(user(1, "r"), user(2, "r"), user(3, "x"))
        assertEquals(2, Reconciliation.countDurable(msgs, "r"))
    }

    @Test fun `countDurable 对不存在的 requestId 返回 0`() {
        assertEquals(0, Reconciliation.countDurable(listOf(user(1, "a")), "b"))
    }

    @Test fun `批量对账只移除已落库的条目`() {
        val msgs = listOf(user(9, "r1"))
        val pending = listOf(
            PendingCommand("r1", "s", "a", 1L),
            PendingCommand("r2", "s", "b", 2L),
        )
        val drop = Reconciliation.reconcileAll(msgs, pending)
        assertEquals(setOf(0), drop)
    }

    @Test fun `批量对账保留仍在路上的条目`() {
        val pending = listOf(PendingCommand("r9", "s", "a", 1L))
        assertTrue(Reconciliation.reconcileAll(emptyList(), pending).isEmpty())
    }

    @Test fun `批量对账不移除重复条目`() {
        val msgs = listOf(user(9, "r1"), user(10, "r1"))
        val pending = listOf(PendingCommand("r1", "s", "a", 1L))
        assertTrue(Reconciliation.reconcileAll(msgs, pending).isEmpty())
    }

    @Test fun `批量对账全部落库时全部移除`() {
        val msgs = listOf(user(1, "r1"), user(2, "r2"))
        val pending = listOf(PendingCommand("r1", "s", "a", 1L), PendingCommand("r2", "s", "b", 2L))
        assertEquals(setOf(0, 1), Reconciliation.reconcileAll(msgs, pending))
    }

    @Test fun `空队列对账不报错`() {
        assertTrue(Reconciliation.reconcileAll(emptyList(), emptyList()).isEmpty())
    }

    @Test fun `空 durable 历史对账为仍在路上`() {
        val pending = listOf(PendingCommand("r1", "s", "a", 1L))
        assertTrue(Reconciliation.reconcileAll(emptyList(), pending).isEmpty())
    }

    @Test fun `合并时已落库的乐观条目被 durable 行取代`() {
        val optimistic = listOf(Message(9, "user", "hi", 0, "final", null, "r1"))
        val durable = listOf(user(9, "r1"))
        val merged = Reconciliation.mergeByRequestId(optimistic, durable)
        assertEquals(1, merged.size)
        assertEquals("m9", merged[0].messageId)
    }

    @Test fun `合并时未落库的乐观条目被保留`() {
        val optimistic = listOf(Message(99, "user", "hi", 0, "final", null, "r1"))
        val durable = listOf(user(9, "other"))
        val merged = Reconciliation.mergeByRequestId(optimistic, durable)
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.requestId == "r1" })
    }

    @Test fun `合并结果按 seq 升序`() {
        val optimistic = listOf(Message(20, "user", "later", 0, "final", null, "r2"))
        val durable = listOf(user(9, "r1"))
        val merged = Reconciliation.mergeByRequestId(optimistic, durable)
        assertEquals(listOf(9L, 20L), merged.map { it.seq })
    }

    @Test fun `合并时无 requestId 的乐观条目不参与去重`() {
        val optimistic = listOf(Message(99, "user", "x", 0, "final", null, null))
        val durable = listOf(user(9, "r1"))
        assertEquals(2, Reconciliation.mergeByRequestId(optimistic, durable).size)
    }

    @Test fun `三种 id 的职责在代码注释中明确`() {
        // 本测试锁定语义：messageId 是受理 id，不用于判断落库
        val ackId = "gateway-accepted-id"
        val durableId = "78b3c95b-real-durable-id"
        val msgs = listOf(user(9, "r1").copy(messageId = durableId))
        val cmd = PendingCommand("r1", "s", "hi", 1L)
        assertTrue(ackId != durableId)
        assertEquals(Reconciliation.Outcome.ALREADY_PERSISTED, Reconciliation.reconcile(msgs, cmd))
        assertFalse(durableId == ackId)
    }
}
