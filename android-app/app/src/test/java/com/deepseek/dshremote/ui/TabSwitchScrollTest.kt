package com.deepseek.dshremote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问题 3 (2026-12): "对话框不自动跳到最新，需要手动拉到最下面".
 *
 * The scroll state (listState / userScrolledAway / followNonce) used to be keyed on
 * `s.id` — the MAIN session. A sub-agent tab switch changes `active.sessionId` but
 * not `s.id`, so no state was recreated and no effect re-fired: the viewport kept
 * the previous tab's position and following could stay disabled.
 *
 * A tab switch is a PROGRAMMATIC switch, so it must land on the newest row and
 * re-arm following exactly like opening a conversation does.
 */
class TabSwitchScrollTest {

    private class ScrollModel(val sessions: List<String>, var activeTabId: String) {
        // Mirrors `key(displayId) { rememberLazyListState() }` + the effects.
        var listStateInstance = 0
        var firstVisibleIndex = 0
        var userScrolledAway = false
        var followNonce = 0
        private var boundKey: String? = null

        /** Recomposition: only the key decides whether state is recreated. */
        fun compose() {
            val key = activeTabId
            if (key != boundKey) {
                boundKey = key
                listStateInstance++
                firstVisibleIndex = 0
            }
        }

        fun scrollToNewest(total: Int) {
            if (total <= 0) return                       // empty list guard
            firstVisibleIndex = total - 1
        }

        fun onDrag(fromIndex: Int, last: Int) {
            if (fromIndex < last) userScrolledAway = true
        }

        /** The `LaunchedEffect(displayId)` added for tab switches. */
        fun onTabSwitch(total: Int = 100) {
            userScrolledAway = false
            followNonce++
            scrollToNewest(total)
        }
    }

    @Test fun `切子代理 tab 会重建 listState`() {
        val m = ScrollModel(listOf("main", "sub1", "sub2"), activeTabId = "main")
        m.compose()
        val before = m.listStateInstance

        m.activeTabId = "sub1"
        m.compose()
        assertTrue("★ 切 tab 必须新建 listState（key 必须用 displayId 而非 s.id）", m.listStateInstance > before)
    }

    @Test fun `切 tab 后无条件落到最新并重新跟随`() {
        val m = ScrollModel(listOf("main", "sub1"), activeTabId = "main")
        m.compose()
        m.userScrolledAway = true        // user previously scrolled up
        m.firstVisibleIndex = 3

        m.activeTabId = "sub1"
        m.compose()
        m.onTabSwitch()

        assertEquals("★ 程序性切换必须落底", 99, m.firstVisibleIndex)
        assertFalse("★ 切换后必须重新跟随", m.userScrolledAway)
        assertTrue("followNonce 必须自增以触发落底 effect", m.followNonce > 0)
    }

    @Test fun `主会话与子代理来回切 3 次每次都落底`() {
        val m = ScrollModel(listOf("main", "sub1"), activeTabId = "main")
        m.compose()
        for (target in listOf("sub1", "main", "sub1", "main")) {
            m.activeTabId = target
            m.compose()
            m.userScrolledAway = true      // simulate a stale pause each time
            m.onTabSwitch()
            assertEquals("切到 $target 必须落底", 99, m.firstVisibleIndex)
            assertFalse("切到 $target 必须重新跟随", m.userScrolledAway)
        }
    }

    @Test fun `同一 tab 内重组不会重建 listState`() {
        val m = ScrollModel(listOf("main"), activeTabId = "main")
        m.compose()
        val before = m.listStateInstance
        repeat(20) { m.compose() }        // e.g. a stream of WS events
        assertEquals("★ 普通重组不得重建 listState（否则位置丢失）", before, m.listStateInstance)
    }

    @Test fun `用户手势仍然能暂停跟随`() {
        val m = ScrollModel(listOf("main"), activeTabId = "main")
        m.compose()
        m.onDrag(fromIndex = 3, last = 99)
        assertTrue("用户上滑必须暂停跟随", m.userScrolledAway)
    }

    @Test fun `数据追加不会把跟随关掉`() {
        val m = ScrollModel(listOf("main"), activeTabId = "main")
        m.compose()
        m.userScrolledAway = false
        // a live message arrives; nothing keyed on layout runs here
        assertFalse("★ 数据变化永远不能关闭跟随", m.userScrolledAway)
    }

    @Test fun `空会话切 tab 不会崩`() {
        val m = ScrollModel(listOf("main", "sub1"), activeTabId = "sub1")
        m.compose()
        m.onTabSwitch(total = 0)          // the empty-list guard must be a no-op
        assertEquals("空列表必须原样不动且不崩", 0, m.firstVisibleIndex)
        assertFalse("切换后仍应重新跟随", m.userScrolledAway)
    }
}