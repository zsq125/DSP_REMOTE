package com.deepseek.dshremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 退避（500ms → 30s，抖动 0–50%）与连接状态枚举。 */
class BackoffTest {

    @Test fun `初始退避 500ms`() {
        assertEquals(500L, Backoff.baseDelayMs(0))
    }

    @Test fun `第二次翻倍为 1000ms`() {
        assertEquals(1_000L, Backoff.baseDelayMs(1))
    }

    @Test fun `第三次 2000ms`() {
        assertEquals(2_000L, Backoff.baseDelayMs(2))
    }

    @Test fun `指数序列 500 1000 2000 4000 8000 16000 30000`() {
        val seq = (0..7).map { Backoff.baseDelayMs(it) }
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), seq)
    }

    @Test fun `上限 30s`() {
        assertEquals(30_000L, Backoff.baseDelayMs(50))
    }

    @Test fun `抖动为 0 时等于基础值 下边界`() {
        assertEquals(500L, Backoff.delayMs(0, 0.0))
        assertEquals(8_000L, Backoff.delayMs(4, 0.0))
    }

    @Test fun `抖动为 1 时为基础值加 50% 上边界`() {
        assertEquals(750L, Backoff.delayMs(0, 1.0))
        assertEquals(12_000L, Backoff.delayMs(4, 1.0))
    }

    @Test fun `抖动中点为基础值加 25 percent`() {
        assertEquals(625L, Backoff.delayMs(0, 0.5))
    }

    @Test fun `抖动不会超过 30s 上限`() {
        // 即使 attempt 已经到上限、随机数最大，也不能超过 30s
        assertEquals(30_000L, Backoff.delayMs(20, 1.0))
        assertEquals(30_000L, Backoff.delayMs(20, 0.999))
    }

    @Test fun `抖动始终在基础值到基础值加 50 percent 之间`() {
        for (attempt in 0..8) {
            val base = Backoff.baseDelayMs(attempt)
            for (r in listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.999)) {
                val d = Backoff.delayMs(attempt, r)
                assertTrue("attempt=$attempt r=$r d=$d", d >= base)
                assertTrue("attempt=$attempt r=$r d=$d", d <= (base + base * Backoff.JITTER_RATIO).toLong())
            }
        }
    }

    @Test fun `负 attempt 抛异常`() {
        try { Backoff.baseDelayMs(-1); org.junit.Assert.fail("应抛异常") } catch (e: IllegalArgumentException) { /* 预期 */ }
    }

    @Test fun `随机数越界抛异常`() {
        try { Backoff.delayMs(0, 1.5); org.junit.Assert.fail("应抛异常") } catch (e: IllegalArgumentException) { /* 预期 */ }
    }

    @Test fun `抖动比率常量是 0_5`() {
        assertEquals(0.5, Backoff.JITTER_RATIO, 1e-9)
    }

    @Test fun `初始值与上限常量符合规范`() {
        assertEquals(500L, Backoff.INITIAL_MS)
        assertEquals(30_000L, Backoff.MAX_MS)
    }
}

/** 六个状态全部存在且可区分（规范 §4 第 3 条要求 UI 可见）。 */
class ConnectionStateTest {

    @Test fun `恰好六个状态`() {
        assertEquals(6, ConnectionState.values().size)
    }

    @Test fun `包含 DISCONNECTED`() { assertEquals("DISCONNECTED", ConnectionState.DISCONNECTED.name) }
    @Test fun `包含 CONNECTING`() { assertEquals("CONNECTING", ConnectionState.CONNECTING.name) }
    @Test fun `包含 CONNECTED`() { assertEquals("CONNECTED", ConnectionState.CONNECTED.name) }
    @Test fun `包含 WAITING_FOR_RETRY`() { assertEquals("WAITING_FOR_RETRY", ConnectionState.WAITING_FOR_RETRY.name) }
    @Test fun `包含 BACKING_OFF`() { assertEquals("BACKING_OFF", ConnectionState.BACKING_OFF.name) }
    @Test fun `包含 DRAINING_QUEUE`() { assertEquals("DRAINING_QUEUE", ConnectionState.DRAINING_QUEUE.name) }

    @Test fun `每个状态都有非空中文标签`() {
        ConnectionState.values().forEach { assertTrue(it.label.isNotBlank()) }
    }

    @Test fun `标签互不相同`() {
        assertEquals(6, ConnectionState.values().map { it.label }.toSet().size)
    }

    @Test fun `只有 DISCONNECTED 是非活跃`() {
        assertFalse(ConnectionState.DISCONNECTED.isActive)
        ConnectionState.values().filter { it != ConnectionState.DISCONNECTED }
            .forEach { assertTrue(it.isActive) }
    }

    @Test fun `状态名集合稳定`() {
        assertEquals(
            setOf("DISCONNECTED", "CONNECTING", "CONNECTED", "WAITING_FOR_RETRY", "BACKING_OFF", "DRAINING_QUEUE"),
            ConnectionState.values().map { it.name }.toSet(),
        )
    }
}
