package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.Json
import com.deepseek.dshremote.protocol.JsonObjectReader
import com.deepseek.dshremote.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 需求 3：reasoning / tool 的字段解析、折叠状态、动态加载判定、tool 卡片配对。 */
class ExpandableContentTest {

    private fun parse(s: String) = Message.parse(JsonObjectReader(Json.parseObject(s)))

    private val assistantFull = """
        {"seq":3974,"role":"assistant","text":"FINAL","ts":1,"status":"final","messageId":"m1",
         "kind":"message","reasoning":"REASONING-FULL-TEXT","hasReasoning":true,
         "collapsedSummary":"FINAL"}
    """.trimIndent()

    private val toolCall = """
        {"seq":3970,"role":"tool","text":"[tool] send_message","ts":2,"status":"final","messageId":null,
         "kind":"tool-call","toolName":"send_message","toolCallId":"call_1",
         "toolInput":"{\"a\":1}","isError":false,"collapsedSummary":"[tool] send_message"}
    """.trimIndent()

    private val toolResult = """
        {"seq":3971,"role":"tool","text":"[tool] result","ts":3,"status":"final","messageId":"r1",
         "kind":"tool-result","toolCallId":"call_1","toolOutput":"DELIVERED","isError":false,
         "collapsedSummary":"DELIVERED"}
    """.trimIndent()

    // ── 解析 ────────────────────────────────────
    @Test fun `解析 reasoning 全文`() {
        val m = parse(assistantFull)
        assertEquals("REASONING-FULL-TEXT", m.reasoning)
        assertTrue(m.hasReasoning)
    }

    @Test fun `解析 tool 入参与 callId`() {
        val m = parse(toolCall)
        assertEquals("send_message", m.toolName)
        assertEquals("call_1", m.toolCallId)
        assertEquals("{\"a\":1}", m.toolInput)
    }

    @Test fun `解析 tool 输出`() {
        assertEquals("DELIVERED", parse(toolResult).toolOutput)
    }

    @Test fun `老网关无新字段时向后兼容`() {
        val m = parse("""{"seq":1,"role":"user","text":"hi","ts":1,"status":"final","messageId":"x","requestId":null}""")
        assertEquals("hi", m.text)
        assertNull(m.reasoning)
        assertNull(m.toolName)
        assertFalse(m.hasReasoning)
        assertFalse(m.isExpandable)
    }

    @Test fun `kind 缺省为 message`() {
        assertEquals("message", parse("""{"seq":1,"role":"user","text":"a","ts":1,"status":"final"}""").kind)
    }

    @Test fun `isError 解析`() {
        val bad = parse("""{"seq":1,"role":"tool","text":"t","ts":1,"status":"failed","kind":"tool-result","isError":true}""")
        assertTrue(bad.isError)
    }

    // ── 可展开判定 ────────────────────────────────
    @Test fun `带 reasoning 的 assistant 可展开`() {
        assertTrue(parse(assistantFull).isExpandable)
    }

    @Test fun `tool 行可展开`() {
        assertTrue(parse(toolCall).isExpandable)
        assertTrue(parse(toolResult).isExpandable)
    }

    @Test fun `普通消息不可展开`() {
        val m = parse("""{"seq":1,"role":"user","text":"hi","ts":1,"status":"final","messageId":"x"}""")
        assertFalse(m.isExpandable)
    }

    @Test fun `hasReasoning 但无正文时仍可展开`() {
        val m = parse("""{"seq":1,"role":"assistant","text":"","ts":1,"status":"final","kind":"message","hasReasoning":true,"reasoning":"x"}""")
        assertTrue(m.isExpandable)
    }

    // ── 折叠状态记忆 ──────────────────────────────
    @Test fun `默认全部折叠`() {
        val st = ExpandStateStore()
        assertFalse(st.isExpanded("s1", 10L))
        assertEquals(0, st.expandedCount)
    }

    @Test fun `toggle 可展开与收起`() {
        val st = ExpandStateStore()
        assertTrue(st.toggle("s1", 10L))
        assertTrue(st.isExpanded("s1", 10L))
        assertFalse(st.toggle("s1", 10L))
        assertFalse(st.isExpanded("s1", 10L))
    }

    @Test fun `折叠状态按会话隔离`() {
        val st = ExpandStateStore()
        st.toggle("s1", 10L)
        assertTrue(st.isExpanded("s1", 10L))
        assertFalse(st.isExpanded("s2", 10L))
    }

    @Test fun `折叠状态可单独收起`() {
        val st = ExpandStateStore()
        st.toggle("s1", 1L); st.toggle("s1", 2L)
        st.collapse("s1", 1L)
        assertFalse(st.isExpanded("s1", 1L))
        assertTrue(st.isExpanded("s1", 2L))
    }

    @Test fun `collapseAll 清空`() {
        val st = ExpandStateStore()
        st.toggle("s1", 1L)
        st.collapseAll()
        assertEquals(0, st.expandedCount)
    }

    @Test fun `展开状态数量有上限`() {
        val st = ExpandStateStore(maxTracked = 20)
        repeat(100) { st.toggle("s", it.toLong()) }
        assertTrue("展开记录必须封顶", st.expandedCount <= 20)
    }

    // ── 动态加载判定 ──────────────────────────────
    @Test fun `未截断的内容无需再取`() {
        val st = ExpandStateStore()
        assertFalse(st.needsFullContent(parse(assistantFull)))
    }

    @Test fun `被截断的内容需要再取全文`() {
        val st = ExpandStateStore()
        val clipped = parse("""
            {"seq":1,"role":"assistant","text":"t","ts":1,"status":"final","kind":"message",
             "hasReasoning":true,"reasoning":"long…[truncated 9999]"}
        """.trimIndent())
        assertTrue("截断过的 reasoning 必须能按需再取", st.needsFullContent(clipped))
    }

    @Test fun `tool 输出被截断时需要再取`() {
        val st = ExpandStateStore()
        val clipped = parse("""
            {"seq":1,"role":"tool","text":"t","ts":1,"status":"final","kind":"tool-result",
             "toolOutput":"out…[truncated 9999]"}
        """.trimIndent())
        assertTrue(st.needsFullContent(clipped))
    }

    @Test fun `不可展开的行不需要全文`() {
        val st = ExpandStateStore()
        assertFalse(st.needsFullContent(parse("""{"seq":1,"role":"user","text":"hi","ts":1,"status":"final"}""")))
    }

    // ── tool 卡片配对 ──────────────────────────────
    @Test fun `call 与 result 配对成一张卡`() {
        val cards = ToolCardBuilder.build(listOf(parse(toolCall), parse(toolResult)))
        assertEquals(1, cards.size)
        assertEquals("call_1", cards[0].callId)
        assertEquals("send_message", cards[0].name)
        assertEquals("{\"a\":1}", cards[0].input)
        assertEquals("DELIVERED", cards[0].output)
        assertFalse(cards[0].isError)
    }

    @Test fun `没有 result 时仍成卡 输出为 null`() {
        val cards = ToolCardBuilder.build(listOf(parse(toolCall)))
        assertEquals(1, cards.size)
        assertNull(cards[0].output)
    }

    @Test fun `错误结果标记 isError`() {
        val bad = parse("""
            {"seq":9,"role":"tool","text":"t","ts":4,"status":"failed","kind":"tool-result",
             "toolCallId":"call_1","toolOutput":"boom","isError":true}
        """.trimIndent())
        val cards = ToolCardBuilder.build(listOf(parse(toolCall), bad))
        assertTrue(cards[0].isError)
    }

    @Test fun `多个工具生成多张卡并按 seq 排序`() {
        val a = parse(toolCall)
        val b = parse(toolCall.replace("call_1", "call_2").replace("send_message", "pwsh").replace("\"seq\":3970", "\"seq\":3900"))
        val cards = ToolCardBuilder.build(listOf(a, b))
        assertEquals(2, cards.size)
        assertTrue(cards[0].callSeq < cards[1].callSeq)
    }

    @Test fun `无 tool 消息时卡片为空`() {
        assertTrue(ToolCardBuilder.build(listOf(parse(assistantFull))).isEmpty())
    }

    @Test fun `有内容的卡片可展开`() {
        val cards = ToolCardBuilder.build(listOf(parse(toolCall), parse(toolResult)))
        assertTrue(ToolCardBuilder.hasExpandableContent(cards[0]))
    }
}
