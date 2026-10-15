package com.deepseek.dshremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词投递模式（需求 1：用户消息优先级最高 ⇒ 默认 steer）。
 *
 * 依据 DSH 原文：
 *  - `session.d.ts:137-139`  `'queue' appends a turn; 'steer' interrupts it`
 *  - `dsh-agent/lib/types/runtime-types.d.ts:194`  steer ⇒ "An idle driver starts a turn"
 */
class PromptModeTest {

    @Test fun `steer 常量正确`() {
        assertEquals("steer", PromptMode.STEER)
    }

    @Test fun `queue 常量正确`() {
        assertEquals("queue", PromptMode.QUEUE)
    }

    @Test fun `null 归一为 steer 向后兼容`() {
        assertEquals(PromptMode.STEER, PromptMode.normalize(null))
    }

    @Test fun `非法值归一为 steer`() {
        assertEquals(PromptMode.STEER, PromptMode.normalize("bogus"))
        assertEquals(PromptMode.STEER, PromptMode.normalize(""))
    }

    @Test fun `合法值原样保留`() {
        assertEquals(PromptMode.QUEUE, PromptMode.normalize(PromptMode.QUEUE))
        assertEquals(PromptMode.STEER, PromptMode.normalize(PromptMode.STEER))
    }

    @Test fun `isValid 只接受两种取值`() {
        assertTrue(PromptMode.isValid("steer"))
        assertTrue(PromptMode.isValid("queue"))
        assertFalse(PromptMode.isValid("queue2"))
        assertFalse(PromptMode.isValid(null))
    }

    @Test fun `steer 能唤醒 idle 会话`() {
        assertTrue(PromptMode.wakesIdle(PromptMode.STEER))
        assertFalse(PromptMode.wakesIdle(PromptMode.QUEUE))
    }

    @Test fun `steer 能打断 running 回合`() {
        assertTrue(PromptMode.interruptsRunning(PromptMode.STEER))
        assertFalse(PromptMode.interruptsRunning(PromptMode.QUEUE))
    }

    @Test fun `queue 两种场景都不触发`() {
        assertFalse(PromptMode.wakesIdle(PromptMode.QUEUE))
        assertFalse(PromptMode.interruptsRunning(PromptMode.QUEUE))
    }

    @Test fun `缺省即 steer 满足用户消息优先`() {
        // 不传 mode 时（App 的默认路径）必须是 steer
        assertTrue(PromptMode.wakesIdle(PromptMode.normalize(null)))
        assertTrue(PromptMode.interruptsRunning(PromptMode.normalize(null)))
    }

    // ---- F-4（PM 裁决 2026-10-12）：语义须与网关 normalizeMode 一致 ----

    @Test fun `F-4 显式 null 归一为 steer`() {
        // App 侧保持宽松（不崩）；网关对显式 null 返回 400
        assertEquals(PromptMode.STEER, PromptMode.normalize(null))
    }

    @Test fun `F-4 大小写敏感 STEER 非法`() {
        assertFalse(PromptMode.isValid("STEER"))
        assertFalse(PromptMode.isValid("Queue"))
    }

    @Test fun `F-4 空串非法`() {
        assertFalse(PromptMode.isValid(""))
    }

    @Test fun `F-4 默认值常量恒为 steer`() {
        assertEquals(PromptMode.STEER, PromptMode.DEFAULT_MODE)
    }
}
