package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问题 4：「每次界面上显示的最新会话，似乎都是前几轮的对话」。
 *
 * 根因不是分页方向（`before` 确实是"往更旧翻"，且 App 只在首屏传 `before=null`），
 * 而是**滚动定位**：渐进加载先渲染 30 条再补到 100 条，而滚动只发生了一次
 * （定位到第 29 项）。LazyColumn 随后继续补内容，视口就停在**较旧**的位置，
 * 看起来正是"前几轮对话"。
 *
 * 这里锁定"窗口只保留最新 N 条 + 首屏定位到最后一条"这两个不变量。
 */
class NewestFirstRenderingTest {

    private fun msg(seq: Long) = Message(seq, "assistant", "m$seq", seq, "final", "x$seq", null)

    /** 网关契约：limit 不带 before ⇒ 返回最新 N 条（实测 seq 递增到最大值）。 */
    @Test fun `首屏重置后保留的是最新一页而不是最旧一页`() {
        val p = MessagePager(300)
        val page = (5000L..5099L).map { msg(it) }      // 100 条，seq 5000..5099
        p.reset(page, nextCursor = 5000L)
        assertEquals(100, p.rows.size)
        assertEquals("首屏必须是最新一条开头", 5000L, p.rows.first().seq)
        assertEquals("首屏必须以最新一条结尾", 5099L, p.rows.last().seq)
    }

    @Test fun `窗口超出上限时丢弃最旧的 保留最新`() {
        val p = MessagePager(50)
        p.reset((1L..200L).map { msg(it) }, null)
        assertEquals(50, p.rows.size)
        assertEquals("必须保留最新 50 条", 200L, p.rows.last().seq)
        assertEquals(151L, p.rows.first().seq)
    }

    /** 渐进：先渲染最新 30，再补到全量；补完后视口必须落在最后一条。 */
    @Test fun `渐进补齐后可见数量与窗口一致 可滚动到最底部`() {
        val p = MessagePager(300)
        val progress = ProgressiveLoadState(firstBatch = 30)
        p.reset((5000L..5099L).map { msg(it) }, null)

        progress.onFirstBatch(p.retainedCount)
        assertEquals("先出 30 条", 30, progress.visibleCount)
        // 首屏停在 item 29，此时背景补齐……
        progress.onBackgroundFilled(p.retainedCount)
        assertEquals("补齐后可滚动到最后一条", p.retainedCount - 1, progress.visibleCount - 1)
    }

    @Test fun `补齐后 visibleCount 必须能到达末尾`() {
        val p = MessagePager(300)
        val progress = ProgressiveLoadState(firstBatch = 30)
        p.reset((1L..100L).map { msg(it) }, null)
        progress.onFirstBatch(p.retainedCount)
        progress.onBackgroundFilled(p.retainedCount)
        // 修好后：滚动定位在补齐之后执行，末尾索引 = retained-1
        assertEquals(p.retainedCount - 1, progress.visibleCount - 1)
        assertEquals(100L, p.rows[progress.visibleCount - 1].seq)
    }

    @Test fun `实时追加后仍停在最新一条`() {
        val p = MessagePager(300)
        val progress = ProgressiveLoadState(firstBatch = 30)
        p.reset((1L..50L).map { msg(it) }, null)
        progress.onFirstBatch(p.retainedCount)
        progress.onBackgroundFilled(p.retainedCount)

        p.appendLive(msg(51))
        progress.onLiveAppend(p.retainedCount)
        assertEquals(51L, p.rows.last().seq)
        assertEquals(p.retainedCount - 1, progress.visibleCount - 1)
    }

    /** `before` 语义：只用于"加载更早"，不得用于"拉更新"。 */
    @Test fun `prepend 只把更早的内容放到前面 不影响末尾`() {
        val p = MessagePager(300)
        p.reset((100L..199L).map { msg(it) }, nextCursor = 100L)
        p.prepend((50L..99L).map { msg(it) }, nextCursor = 50L)
        assertEquals("更早的内容在前", 50L, p.rows.first().seq)
        assertEquals("最新一条仍在末尾", 199L, p.rows.last().seq)
    }

    @Test fun `hasMore 反映是否还有更早历史`() {
        val p = MessagePager(300)
        p.reset((100L..199L).map { msg(it) }, nextCursor = 100L)
        assertTrue(p.hasMore)
        p.prepend((50L..99L).map { msg(it) }, nextCursor = null)
        assertFalse("nextCursor=null 表示没有更早的了", p.hasMore)
    }

    // ---- 工作区按需加载（问题 1） ----

    @Test fun `focusWorkspace 可把左栏收成全折叠`() {
        val t = WorkspaceTreeState()
        t.focusWorkspace(null)
        assertFalse("冷启动应全部折叠，用户点开才加载", t.isExpanded("ws1"))
    }

    @Test fun `focusWorkspace 只展开指定工作区`() {
        val t = WorkspaceTreeState()
        t.focusWorkspace("ws1")
        assertTrue(t.isExpanded("ws1"))
        assertFalse(t.isExpanded("ws2"))
    }

    @Test fun `focusSession 仍只展开会话所属工作区`() {
        val t = WorkspaceTreeState()
        t.focusSession("s1", mapOf("s1" to "ws2"))
        assertTrue(t.isExpanded("ws2"))
        assertFalse(t.isExpanded("ws1"))
    }
}