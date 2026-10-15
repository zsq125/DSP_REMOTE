package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送失败的**三种语义**（最后一轮收尾需求）。
 *
 * 网关把 DSH 的 `session/writer-held` 与 `session/agent-busy` 统一映射为 `session_busy`，
 * 因此 App 必须结合「目标会话是不是子代理」才能区分：
 *   1) 子代理只读  → 禁用输入框，**不提供重试**
 *   2) PC 端占用    → 明确不是网络故障，保留重试
 *   3) 其它真失败   → 红条 + 重试
 *
 * 判定**依据响应体的 error.code**，不靠猜。
 */
class SendFailureTest {

    private val mainSession = SessionSummary(
        "m1", "t", "idle", 0, 0, "F:\\p", 1, "", false,
        parentSessionId = null, isSubagent = false, subagentLabel = null,
    )
    private val subSession = SessionSummary(
        "s1", "你是开发工程师", "idle", 0, 0, "F:\\p", 1, "", false,
        parentSessionId = "m1", isSubagent = true, subagentLabel = "Android 开发工程师",
    )

    // ── ① 子代理只读 ────────────────────────────────────
    @Test fun `session_busy 且目标是子代理 判为只读`() {
        assertEquals(SendFailure.SubagentReadOnly, SendFailure.classify("session_busy", subSession))
    }

    @Test fun `子代理只读 不提供重试`() {
        assertFalse(SendFailure.SubagentReadOnly.retryable)
    }

    @Test fun `子代理只读 禁用输入框`() {
        assertTrue(SendFailure.SubagentReadOnly.disablesInput)
    }

    @Test fun `子代理只读 文案引导去主会话`() {
        assertEquals("子代理会话只读，请在主会话中操作", SendFailure.SubagentReadOnly.userMessage)
    }

    @Test fun `仅 parentSessionId 非空也判为子代理`() {
        val onlyParent = mainSession.copy(parentSessionId = "m1")
        assertEquals(SendFailure.SubagentReadOnly, SendFailure.classify("session_busy", onlyParent))
    }

    @Test fun `仅 isSubagent 标记也判为子代理`() {
        val onlyFlag = mainSession.copy(isSubagent = true)
        assertEquals(SendFailure.SubagentReadOnly, SendFailure.classify("session_busy", onlyFlag))
    }

    // ── ② PC 端占用（主会话） ─────────────────────────────
    @Test fun `session_busy 且目标是主会话 判为 PC 端占用`() {
        assertEquals(SendFailure.SessionBusyOnPc, SendFailure.classify("session_busy", mainSession))
    }

    @Test fun `PC 端占用 保留重试`() {
        assertTrue(SendFailure.SessionBusyOnPc.retryable)
    }

    @Test fun `PC 端占用 不禁用输入框`() {
        assertFalse(SendFailure.SessionBusyOnPc.disablesInput)
    }

    @Test fun `PC 端占用 文案明确不是网络故障`() {
        val m = SendFailure.SessionBusyOnPc.userMessage
        assertTrue(m.contains("PC"))
        assertFalse("不应被当成网络问题", m.contains("网络"))
    }

    // ── ③ 其它真失败 ────────────────────────────────────
    @Test fun `未授权 判为其它失败`() {
        assertEquals(SendFailure.Other, SendFailure.classify("unauthorized", mainSession))
    }

    @Test fun `not_found 判为其它失败`() {
        assertEquals(SendFailure.Other, SendFailure.classify("not_found", mainSession))
    }

    @Test fun `internal 5xx 判为其它失败`() {
        assertEquals(SendFailure.Other, SendFailure.classify("internal", mainSession))
    }

    @Test fun `dsh_unavailable 判为其它失败`() {
        assertEquals(SendFailure.Other, SendFailure.classify("dsh_unavailable", mainSession))
    }

    @Test fun `网络错误 code 判为其它失败`() {
        assertEquals(SendFailure.Other, SendFailure.classify("network", mainSession))
    }

    @Test fun `code 为 null 判为其它失败`() {
        assertEquals(SendFailure.Other, SendFailure.classify(null, mainSession))
    }

    @Test fun `子代理上的其它错误 仍是其它失败 不是只读`() {
        // 只有 session_busy 才代表只读；子代理上的网络错不能被误报成"只读"
        assertEquals(SendFailure.Other, SendFailure.classify("network", subSession))
    }

    @Test fun `其它失败 保留重试`() {
        assertTrue(SendFailure.Other.retryable)
    }

    @Test fun `会话为 null 时 session_busy 归为 PC 端占用`() {
        assertEquals(SendFailure.SessionBusyOnPc, SendFailure.classify("session_busy", null))
    }

    // ── SendState 与三种失败的联动 ─────────────────────────
    @Test fun `只读失败后输入框被禁用且不提供重试`() {
        val s = SendState()
        s.begin("r1", "hi")
        s.fail(SendFailure.SubagentReadOnly)
        assertTrue(s.inputDisabled)
        assertFalse(s.canRetry)
        assertEquals(SendFailure.SubagentReadOnly.userMessage, s.error)
    }

    @Test fun `PC 端占用后输入框仍可用且提供重试`() {
        val s = SendState()
        s.begin("r1", "hi")
        s.fail(SendFailure.SessionBusyOnPc)
        assertFalse(s.inputDisabled)
        assertTrue(s.canRetry)
        assertEquals("r1", s.retryRequestId())
    }

    @Test fun `其它失败后提供重试且复用同一 requestId`() {
        val s = SendState()
        s.begin("r9", "hi")
        s.fail(SendFailure.Other)
        assertTrue(s.canRetry)
        assertEquals("重试必须复用同一 requestId（幂等）", "r9", s.retryRequestId())
    }

    @Test fun `成功后清空失败提示`() {
        val s = SendState()
        s.begin("r1", "hi")
        s.fail(SendFailure.Other)
        s.success()
        assertNull(s.failure)
        assertNull(s.error)
        assertEquals(SendState.Phase.Sent, s.phase)
    }

    @Test fun `reset 清除失败分类`() {
        val s = SendState()
        s.begin("r1", "hi"); s.fail(SendFailure.SubagentReadOnly)
        s.reset()
        assertNull(s.failure)
        assertFalse(s.inputDisabled)
        assertTrue(s.canRetry)
    }

    @Test fun `未失败时默认可重试`() {
        val s = SendState()
        assertTrue(s.canRetry)
        assertFalse(s.inputDisabled)
    }
}
