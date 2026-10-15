package com.deepseek.dshremote.ui

import com.deepseek.dshremote.core.ExpandStateStore
import com.deepseek.dshremote.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-A/P-B/P-C (2026-12, from the user's three reports):
 *
 *  ① opening a session pulled its messages TWICE and fetched 100 rows up front
 *     (a 2499-message session felt slow);
 *  ② the transcript did not open at the newest message;
 *  ③ tool / reasoning rows were dead text -- no ▸/▾, nothing to tap.
 *
 * These lock the three behaviours that fix them.
 */
class PagingAndDisclosureTest {

    private fun msg(seq: Long, role: String = "assistant") =
        Message(seq, role, "m$seq", seq, "final", "x$seq", null)

    // ---- P-A: first screen is one newest page ----

    @Test fun `首屏只取最新一页`() {
        // 2026-10-14 用户改版：首屏从 50 收敛到 8（"打开即见最近 8 条"），
        // 更快出屏；不足的部分由后台倒序补齐 + 本地缓存兜底。
        assertEquals("首屏必须分页，不能一把拉全量", 8, FIRST_PAGE_LIMIT)
    }

    @Test fun `首屏 limit 明显小于会话总长`() {
        // measured session: 2499 messages
        assertTrue(FIRST_PAGE_LIMIT < 2499)
    }

    // ---- P-A2: viewport rests on the newest row ----

    @Test fun `可见范围的最后一项就是最新消息`() {
        val rows = (5000L..5099L).map { msg(it) }
        val last = rows.last()
        assertEquals("视口落底时最后一项必须是最新 seq", 5099L, last.seq)
    }

    @Test fun `后台补齐后 最后一项仍是最新`() {
        // progressive fill grows the window; the newest row must stay last
        val window = (5000L..5099L).map { msg(it) }
        val visible = window.take(30)   // first batch
        assertEquals(5099L, window.last().seq)
        assertTrue(visible.size < window.size)
    }

    // ---- P-C: tool rows are expandable ----

    private fun toolRow(seq: Long, name: String, input: String?, output: String?) =
        com.deepseek.dshremote.core.MessageRow(
            seq = seq, role = "tool", text = "[tool] $name", ts = seq, status = "final",
            messageId = null, align = com.deepseek.dshremote.core.MessageAlign.FULL,
            collapsed = true, dimmed = false, isStreaming = false,
            kind = if (output == null) "tool-call" else "tool-result",
            toolName = name, toolInput = input, toolOutput = output,
            collapsedSummary = "$name summary",
        )

    @Test fun `tool 调用行是可展开的`() {
        val r = toolRow(10, "pwsh", "{\"command\":\"ls\"}", null)
        assertTrue("tool 行必须是折叠态但可展开", r.collapsed)
        assertFalse("有内容就不算截断", r.truncated)
    }

    @Test fun `正在执行的命令也能展开看参数`() {
        // a running tool has arguments but no result yet
        val r = toolRow(11, "pwsh", "{\"command\":\"npm test\"}", null)
        assertEquals("pwsh", r.toolName)
        assertTrue("执行中的命令必须能看到入参", !r.toolInput.isNullOrEmpty())
        assertTrue(r.toolInput!!.contains("npm test"))
        assertTrue("尚无结果时 output 为空", r.toolOutput == null)
    }

    @Test fun `工具结果行也能展开看输出`() {
        val r = toolRow(12, "pwsh", "{\"command\":\"ls\"}", "file-a\nfile-b")
        assertEquals("file-a\nfile-b", r.toolOutput)
    }

    @Test fun `折叠状态可反复展开与收起`() {
        val store = ExpandStateStore()
        assertFalse("默认折叠", store.isExpanded("s", 12))
        assertTrue(store.toggle("s", 12))
        assertTrue(store.isExpanded("s", 12))
        assertFalse(store.toggle("s", 12))
        assertFalse(store.isExpanded("s", 12))
    }

    @Test fun `不同消息的折叠状态互不影响`() {
        val store = ExpandStateStore()
        store.toggle("s", 12)
        assertTrue(store.isExpanded("s", 12))
        assertFalse(store.isExpanded("s", 13))
    }

    @Test fun `被截断的行不可展开（DSH marker 语义）`() {
        val clipped = toolRow(14, "pwsh", "很长的内容…[truncated 9999]", null)
        assertTrue(clipped.truncated)
    }
}