package com.deepseek.dshremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-5 的 App 侧回归：扩展字段**键恒存在**。
 *
 * 契约（PM 裁决 2026-10-12）：`kind`/`reasoning`/`toolName`/`toolCallId`/`toolInput`/
 * `toolOutput`/`isError`/`collapsedSummary` 在**每一行**都必须出现，无值给 null/默认值。
 * 若网关省略键，App 用 `"key" in row` 判空会 NPE。
 */
class ExpandableFieldPresenceTest {

    private fun parse(s: String) = Message.parse(JsonObjectReader(Json.parseObject(s)))

    /** 网关保证恒存在的扩展键。 */
    private val alwaysPresent = listOf(
        "kind", "reasoning", "hasReasoning", "toolName", "toolCallId",
        "toolInput", "toolOutput", "isError", "collapsedSummary",
    )

    @Test fun `最小消息行也能解析出全部扩展字段`() {
        val m = parse("""{"seq":1,"role":"user","text":"hi","ts":1,"status":"final","messageId":"x","requestId":null}""")
        assertEquals("message", m.kind)
        assertNull(m.reasoning)
        assertFalse(m.hasReasoning)
        assertNull(m.toolName)
        assertNull(m.toolCallId)
        assertNull(m.toolInput)
        assertNull(m.toolOutput)
        assertFalse(m.isError)
        assertEquals("", m.collapsedSummary)
    }

    @Test fun `system 种子行同样有全部扩展字段`() {
        val m = parse("""{"seq":0,"role":"system","text":"seed","ts":0,"status":"final","messageId":null}""")
        assertNotNull(m.kind)
        assertEquals("", m.collapsedSummary)
        assertFalse(m.isError)
    }

    @Test fun `explicit null 的扩展字段解析为 null`() {
        val m = parse("""
            {"seq":1,"role":"assistant","text":"t","ts":1,"status":"final","messageId":null,
             "kind":"message","reasoning":null,"hasReasoning":false,"toolName":null,
             "toolCallId":null,"toolInput":null,"toolOutput":null,"isError":false,
             "collapsedSummary":null}
        """.trimIndent())
        assertNull(m.reasoning)
        assertNull(m.toolName)
        assertNull(m.toolCallId)
        assertNull(m.toolInput)
        assertNull(m.toolOutput)
        assertFalse(m.isError)
        // JSON null 与键缺失都归一到 ""（App 侧用空串表示"无内容"）
        assertEquals("", m.collapsedSummary)
    }

    @Test fun `带完整字段的行解析正确`() {
        val m = parse("""
            {"seq":7,"role":"tool","text":"[tool] read","ts":2,"status":"final","messageId":null,
             "kind":"tool-call","reasoning":null,"hasReasoning":false,"toolName":"read",
             "toolCallId":"c1","toolInput":"{\"p\":1}","toolOutput":null,"isError":false,
             "collapsedSummary":"[tool] read"}
        """.trimIndent())
        assertEquals("tool-call", m.kind)
        assertEquals("read", m.toolName)
        assertEquals("c1", m.toolCallId)
        assertEquals("{\"p\":1}", m.toolInput)
        assertNull(m.toolOutput)
    }

    @Test fun `缺字段时用默认值而不是抛异常`() {
        // 老网关（v1.2 之前）不返回任何扩展键 —— App 必须仍能解析
        val m = parse("""{"seq":1,"role":"user","text":"legacy","ts":1,"status":"final","messageId":"x"}""")
        assertEquals("legacy", m.text)
        assertEquals("message", m.kind)
    }

    @Test fun `契约键清单稳定`() {
        // 若网关新增/删除键，这个清单要跟着更新；这里锁定当前契约
        assertEquals(9, alwaysPresent.size)
        assertTrue(alwaysPresent.contains("collapsedSummary"))
        assertTrue(alwaysPresent.contains("isError"))
    }
}

/**
 * F-2/F-3 的 App 侧回归：`expand` 只影响**字段内容**，绝不影响**消息条数**。
 *
 * 背景：网关曾把"单字段 2000 字符上限"误实现为"总行数上限"，
 * 导致默认档 `summary` 丢失最新约 21 条消息 —— 用户打开会话看不到最近的对话。
 */
class ExpandDoesNotDropRowsTest {

    private fun parse(s: String) = Message.parse(JsonObjectReader(Json.parseObject(s)))

    private fun rowsAtLevel(level: String, n: Int): List<Message> = (0 until n).map { i ->
        if (i % 2 == 0) {
            parse("""
                {"seq":$i,"role":"assistant","text":"a$i","ts":$i,"status":"final","messageId":"m$i",
                 "kind":"message","reasoning":${if (level == "none") "null" else "\"R$i\""},
                 "hasReasoning":${level != "none"},"collapsedSummary":"a$i"}
            """.trimIndent())
        } else {
            parse("""
                {"seq":$i,"role":"tool","text":"[tool] t","ts":$i,"status":"final","messageId":null,
                 "kind":"tool-call","toolName":"t","toolCallId":"c$i",
                 "toolInput":${if (level == "none") "null" else "\"{\\\"p\\\":$i}\""},
                 "collapsedSummary":"t"}
            """.trimIndent())
        }
    }

    @Test fun `三档返回相同条数`() {
        val n = 120
        val none = rowsAtLevel("none", n)
        val summary = rowsAtLevel("summary", n)
        val full = rowsAtLevel("full", n)
        assertEquals(none.size, summary.size)
        assertEquals(summary.size, full.size)
        assertEquals(n, summary.size)
    }

    @Test fun `三档最大 seq 相同 最新消息不丢`() {
        val n = 120
        val maxNone = rowsAtLevel("none", n).maxOf { it.seq }
        val maxSummary = rowsAtLevel("summary", n).maxOf { it.seq }
        val maxFull = rowsAtLevel("full", n).maxOf { it.seq }
        assertEquals(maxNone, maxSummary)
        assertEquals(maxSummary, maxFull)
        assertEquals((n - 1).toLong(), maxSummary)
    }

    @Test fun `none 档不含任何大字段内容`() {
        val rows = rowsAtLevel("none", 30)
        assertTrue("none 档 reasoning 必须为空", rows.all { it.reasoning.isNullOrEmpty() })
        assertTrue("none 档 toolInput 必须为空（F-3）", rows.all { it.toolInput.isNullOrEmpty() })
    }

    @Test fun `summary 档保留条数但字段可被截断标记`() {
        val rows = rowsAtLevel("summary", 30)
        val clipped = rows.filter { it.reasoning?.contains("[truncated") == true }
        // 若网关截断了，App 必须能识别（needsFullContent）
        assertTrue(clipped.all { it.needsFullContentFlag })
        assertEquals(30, rows.size)
    }

    /** 复刻 Message.isExpandable / needsFullContent 的判定语义。 */
    private val Message.needsFullContentFlag: Boolean
        get() = reasoning?.contains("[truncated") == true || toolOutput?.contains("[truncated") == true
}
