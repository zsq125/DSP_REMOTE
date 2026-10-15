package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.Json
import com.deepseek.dshremote.protocol.JsonObjectReader
import com.deepseek.dshremote.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 需求 2：对话区「照抄 DSH」的节点流行为。
 *
 * 参考源码（已实际阅读，不是凭印象）：
 *  - `dsh-client-ui-conversation/lib/types/client/contract/records.d.ts`
 *  - `dsh-client-ui-chat/lib/types/client/chat/use-scroll-follow.d.ts`
 *  - `dsh-client-ui-chat/lib/types/client/chat/use-chat-reading.d.ts`
 */
class ConversationNodesTest {

    private fun parse(s: String) = Message.parse(JsonObjectReader(Json.parseObject(s)))

    private val userMsg = parse("""
        {"seq":10,"role":"user","text":"帮我改一下","ts":100,"status":"final","messageId":"u1","requestId":"r1"}
    """.trimIndent())

    private val assistantWithReasoning = parse("""
        {"seq":20,"role":"assistant","text":"好的，已修改","ts":200,"status":"final","messageId":"a1",
         "kind":"message","hasReasoning":true,"reasoning":"先读文件，再改","collapsedSummary":"好的，已修改"}
    """.trimIndent())

    private val toolCall = parse("""
        {"seq":21,"role":"tool","text":"[tool] read","ts":210,"status":"final","messageId":null,
         "kind":"tool-call","toolName":"read","toolCallId":"c1","toolInput":"{\"path\":\"a.kt\"}",
         "isError":false,"collapsedSummary":"[tool] read"}
    """.trimIndent())

    private val toolResult = parse("""
        {"seq":22,"role":"tool","text":"[tool] result","ts":260,"status":"final","messageId":"tr1",
         "kind":"tool-result","toolCallId":"c1","toolOutput":"file content","isError":false,
         "collapsedSummary":"file content"}
    """.trimIndent())

    // ── 节点流 ──────────────────────────────────────
    @Test fun `assistant 的 reasoning 与正文是两个独立节点`() {
        val nodes = ConversationNodeBuilder.build(listOf(assistantWithReasoning))
        assertEquals(2, nodes.size)
        assertEquals(NodeKind.REASONING, nodes[0].kind)
        assertEquals(NodeKind.TEXT, nodes[1].kind)
    }

    @Test fun `reasoning 节点带全文`() {
        val n = ConversationNodeBuilder.build(listOf(assistantWithReasoning))[0]
        assertEquals("先读文件，再改", n.reasoning)
        assertTrue(n.expandable)
    }

    @Test fun `user 消息成为 user 节点`() {
        val n = ConversationNodeBuilder.build(listOf(userMsg)).single()
        assertEquals(NodeKind.USER, n.kind)
        assertEquals("帮我改一下", n.text)
    }

    @Test fun `节点按 seq 升序`() {
        val nodes = ConversationNodeBuilder.build(listOf(toolResult, userMsg, toolCall))
        assertEquals(10L, nodes[0].seq)
        assertEquals(21L, nodes[1].seq)
        assertEquals(22L, nodes[2].seq)
    }

    @Test fun `wire 名称与网关字段对齐`() {
        assertEquals("tool-call", NodeKind.TOOL_CALL.wire)
        assertEquals("tool-result", NodeKind.TOOL_RESULT.wire)
        assertEquals("reasoning", NodeKind.REASONING.wire)
    }

    // ── 窗口截断 ⇒ 不可展开 marker ──────────────────────
    @Test fun `被截断的 reasoning 不可展开`() {
        val clipped = parse("""
            {"seq":5,"role":"assistant","text":"t","ts":1,"status":"final","kind":"message",
             "hasReasoning":true,"reasoning":"很长…[truncated 999]"}
        """.trimIndent())
        val n = ConversationNodeBuilder.build(listOf(clipped)).first { it.kind == NodeKind.REASONING }
        assertFalse("窗口截断后 marker 不可展开（DSH 语义）", n.expandable)
        assertTrue(n.truncated)
    }

    @Test fun `未截断的 reasoning 可展开`() {
        val n = ConversationNodeBuilder.build(listOf(assistantWithReasoning)).first()
        assertTrue(n.expandable)
        assertFalse(n.truncated)
    }

    // ── tool 卡片配对 ────────────────────────────────
    @Test fun `call 与 result 合成一张卡片`() {
        val nodes = ConversationNodeBuilder.build(listOf(toolCall, toolResult))
        val cards = ConversationNodeBuilder.toolCards(nodes)
        assertEquals(1, cards.size)
        assertEquals("c1", cards[0].callId)
        assertEquals("read", cards[0].name)
        assertEquals("file content", cards[0].output)
    }

    @Test fun `卡片头显示工具名`() {
        val card = ConversationNodeBuilder.toolCards(
            ConversationNodeBuilder.build(listOf(toolCall, toolResult))
        ).single()
        assertEquals("read", card.head)
    }

    @Test fun `call 落在窗口外时卡片头退回 callId`() {
        // 只有 result、没有 call（窗口截断）
        val cards = ConversationNodeBuilder.toolCards(
            ConversationNodeBuilder.build(listOf(toolResult.copy(toolName = null)))
        )
        val card = cards.single()
        assertNull("call 不在窗口内时 call=null（DSH ToolResultNode.call）", card.call)
        assertEquals("c1", card.head)
    }

    @Test fun `卡片计算耗时`() {
        val card = ConversationNodeBuilder.toolCards(
            ConversationNodeBuilder.build(listOf(toolCall, toolResult))
        ).single()
        assertEquals(50L, card.durationMs)   // 260 - 210
    }

    @Test fun `缺时间端点时耗时为 null`() {
        val card = ConversationNodeBuilder.toolCards(
            ConversationNodeBuilder.build(listOf(toolResult))
        ).single()
        assertNull(card.durationMs)
    }

    @Test fun `错误结果标记 isError`() {
        val bad = toolResult.copy(isError = true)
        val card = ConversationNodeBuilder.toolCards(
            ConversationNodeBuilder.build(listOf(toolCall, bad))
        ).single()
        assertTrue(card.isError)
    }

    @Test fun `被截断的卡片不可展开`() {
        val clipped = toolCall.copy(toolInput = "很长…[truncated 999]")
        val card = ConversationNodeBuilder.toolCards(ConversationNodeBuilder.build(listOf(clipped))).single()
        assertTrue("网关在 content 里带了 [truncated] 标记", card.truncated)
        assertFalse("截断后卡片不可展开（DSH marker 语义）", card.expandable)
    }

    @Test fun `多个工具生成多张卡片`() {
        val c2 = toolCall.copy(seq = 30, toolCallId = "c2", toolName = "pwsh")
        val cards = ConversationNodeBuilder.toolCards(
            ConversationNodeBuilder.build(listOf(toolCall, toolResult, c2))
        )
        assertEquals(2, cards.size)
    }

    // ── 分组 ────────────────────────────────────────
    @Test fun `一条 assistant 消息的所有块归为同一组`() {
        // DSH common.d.ts: "Assistant, its follow-up Nodes" ⇒ 组内零间距
        val groups = ConversationNodeBuilder.group(
            ConversationNodeBuilder.build(listOf(userMsg, assistantWithReasoning, toolCall, toolResult))
        )
        assertEquals("user 一组 + assistant 组", 2, groups.size)
        assertEquals("reasoning+text+tool-call+tool-result 同组", 4, groups[1].size)
    }

    @Test fun `分组保持原有顺序`() {
        // 输入刻意乱序，验证 build() 会按 seq 重排
        val nodes = ConversationNodeBuilder.build(listOf(userMsg, toolCall, assistantWithReasoning))
        val groups = ConversationNodeBuilder.group(nodes)
        assertEquals("seq=10 的 user 最前", NodeKind.USER, groups[0].first().kind)
        // DSH common.d.ts: "Assistant, its follow-up Nodes" ⇒ tool 是 assistant 的
        // follow-up 节点，与 assistant 同组（组内零间距）
        assertEquals(2, groups.size)
        assertEquals(NodeKind.REASONING, groups[1].first().kind)
        assertEquals(NodeKind.TEXT, groups[1][1].kind)
        assertEquals(NodeKind.TOOL_CALL, groups[1][2].kind)
    }

    @Test fun `同一消息的块共享同一个真实 seq`() {
        val nodes = ConversationNodeBuilder.build(listOf(assistantWithReasoning))
        assertEquals("对外 seq 不变（契约），块序由 order 区分", nodes[0].seq, nodes[1].seq)
        assertTrue(nodes[0].order < nodes[1].order)
    }

    @Test fun `空输入分组为空`() {
        assertTrue(ConversationNodeBuilder.group(emptyList()).isEmpty())
    }
}

/** 底部跟随控制器（对齐 DSH `ScrollFollow` / `use-chat-reading`）。 */
class ScrollFollowControllerTest {

    @Test fun `初始处于跟随`() {
        assertTrue(ScrollFollowController().active)
    }

    @Test fun `用户上滚后停止跟随`() {
        val c = ScrollFollowController()
        c.sample(distanceFromBottom = 500f, movedByReader = true)
        assertFalse("用户离开底部 ⇒ 停止跟随（DSH）", c.active)
    }

    @Test fun `用户仍在底部内 保持跟随`() {
        val c = ScrollFollowController()
        c.sample(distanceFromBottom = 1f, movedByReader = true)
        assertTrue(c.active)
    }

    @Test fun `阈值外视为离开底部`() {
        val c = ScrollFollowController(threshold = 2f)
        assertFalse(c.nearBottom(10f))
        assertTrue(c.nearBottom(1f))
    }

    @Test fun `程序滚动不改变跟随意图`() {
        val c = ScrollFollowController()
        // 内容增长导致位置变化，但不是读者操作 ⇒ 意图不变
        c.sample(distanceFromBottom = 900f, movedByReader = false)
        assertTrue("只有读者操作才改变跟随意图", c.active)
    }

    @Test fun `离开底部后内容增长不跟随`() {
        val c = ScrollFollowController()
        c.sample(500f, movedByReader = true)
        assertFalse(c.shouldFollowGrowth(500f))
    }

    @Test fun `在底部时内容增长跟随`() {
        val c = ScrollFollowController()
        assertTrue(c.shouldFollowGrowth(0f))
    }

    @Test fun `回到底部恢复跟随`() {
        val c = ScrollFollowController()
        c.sample(500f, movedByReader = true)
        assertFalse(c.active)
        c.followTail()
        assertTrue(c.active)
    }

    @Test fun `显式导航释放跟随`() {
        val c = ScrollFollowController()
        c.releaseFollow()
        assertFalse(c.active)
    }

    @Test fun `reset 可重置`() {
        val c = ScrollFollowController(initial = false)
        c.reset(true)
        assertTrue(c.active)
    }

    @Test fun `active 与 following 同源`() {
        val c = ScrollFollowController()
        c.sample(500f, movedByReader = true)
        assertEquals(c.following, c.active)
    }
}
