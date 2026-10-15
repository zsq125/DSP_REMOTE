package com.deepseek.dshremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "同一会话被重复拉 14 次 /messages" (2026-12).
 *
 * The "already loaded" guard used to live in `remember(s.id)` **inside**
 * DetailPane. That state is destroyed whenever the pane leaves composition — and
 * it does, because MainActivity renders the pane only while `selected != null`,
 * and `selected` comes from `allSessions`, which is rebuilt on every refresh.
 * The effect then re-ran for the *same* session id, re-fetching messages and
 * resetting the viewport each time, so the transcript stayed parked on an old
 * checkpoint instead of the newest message.
 *
 * The guard therefore has to live OUTSIDE the pane. This pins that contract with
 * the same logic the UI now uses.
 */
class LoadOnceGuardTest {

    /** Mirrors MainActivity's caller-owned guard. */
    private class Guard {
        var loaded: String? = null
        private val loads = mutableListOf<String>()

        /** Returns true when a load should actually run. */
        fun shouldLoad(requestKey: String?): Boolean {
            if (requestKey == null || requestKey == loaded) return false
            loaded = requestKey
            loads += requestKey
            return true
        }

        fun forceRetry() { loaded = null }

        /**
         * The pane was really unmounted for [sessionId]: its in-memory content is
         * gone, so the guard must be invalidated or the remount would skip the
         * reload and render a blank transcript.
         */
        fun onPaneUnmounted(sessionId: String) {
            if (loaded == sessionId) loaded = null
        }

        /** A recomposition is not an unmount: the guard must survive it. */
        fun onRecompose() { /* nothing to invalidate */ }

        val loadCount: Int get() = loads.size
        val loadedSessions: List<String> get() = loads.toList()
    }

    @Test fun `同一会话只加载一次`() {
        val g = Guard()
        assertTrue("首次应加载", g.shouldLoad("s1"))
        assertFalse("同一会话重复请求不得再加载", g.shouldLoad("s1"))
        assertEquals(1, g.loadCount)
    }

    @Test fun `反复重组（同一 key）不触发重复加载`() {
        val g = Guard()
        g.shouldLoad("s1")
        repeat(50) { g.shouldLoad("s1") }
        assertEquals("重组 50 次也只加载 1 次", 1, g.loadCount)
    }

    @Test fun `会话对象换了实例但 id 相同 不重复加载`() {
        val g = Guard()
        g.shouldLoad("s1")
        // A refreshed session list produces a NEW object with the SAME id.
        assertFalse("id 相同即视为同一会话", g.shouldLoad("s1"))
        assertEquals(1, g.loadCount)
    }

    @Test fun `切换到别的会话会重新加载`() {
        val g = Guard()
        g.shouldLoad("s1")
        assertTrue("新会话应加载", g.shouldLoad("s2"))
        assertEquals(listOf("s1", "s2"), g.loadedSessions)
    }

    @Test fun `切回旧会话会重新加载（内容可能已变）`() {
        val g = Guard()
        g.shouldLoad("s1"); g.shouldLoad("s2")
        assertTrue("切回必须重新取最新内容", g.shouldLoad("s1"))
        assertEquals(listOf("s1", "s2", "s1"), g.loadedSessions)
    }

    @Test fun `收到大量 liveEvents 不触发重复加载`() {
        val g = Guard()
        g.shouldLoad("s1")
        // Live WS traffic changes state many times; each one recomposes the pane.
        repeat(100) { g.shouldLoad("s1") }
        assertEquals("实时事件不得导致重复拉取", 1, g.loadCount)
    }

    @Test fun `显式重试可以强制重新加载`() {
        val g = Guard()
        g.shouldLoad("s1")
        g.forceRetry()
        assertTrue("重试后应重新加载", g.shouldLoad("s1"))
        assertEquals(2, g.loadCount)
    }

    @Test fun `null 请求（尚未选中会话）不加载`() {
        val g = Guard()
        assertFalse(g.shouldLoad(null))
        assertEquals(0, g.loadCount)
    }

    @Test fun `守卫记录的就是最后一次加载的会话`() {
        val g = Guard()
        g.shouldLoad("s1")
        assertEquals("s1", g.loaded)
        g.shouldLoad("s2")
        assertEquals("s2", g.loaded)
    }

    // ---- unmount invalidation (2026-12 residual risk) ----
    //
    // The guard lives in the caller so it survives recomposition, but it describes
    // state that lives INSIDE the pane. If the pane unmounts (selected momentarily
    // null while allSessions is rebuilt) its messages are gone while the guard
    // still says "loaded" => blank transcript. Unmount must invalidate it.

    @Test fun `面板卸载后守卫作废 必须重新加载`() {
        val g = Guard()
        g.shouldLoad("s1")
        assertEquals(1, g.loadCount)

        g.onPaneUnmounted("s1")           // selected became null for a frame
        assertNull("卸载后守卫必须作废", g.loaded)

        assertTrue("重新挂载必须重新加载（否则右栏空白）", g.shouldLoad("s1"))
        assertEquals(2, g.loadCount)
    }

    @Test fun `仅仅重组不会作废守卫`() {
        val g = Guard()
        g.shouldLoad("s1")
        repeat(20) { g.onRecompose() }    // recomposition is NOT an unmount
        assertEquals("重组不得导致重载", 1, g.loadCount)
        assertFalse("重组后仍不得加载", g.shouldLoad("s1"))
    }

    @Test fun `切换到别的会话再切回 会重新加载`() {
        val g = Guard()
        g.shouldLoad("s1")
        g.shouldLoad("s2")
        assertTrue("切回 s1 必须重新加载", g.shouldLoad("s1"))
        assertEquals(listOf("s1", "s2", "s1"), g.loadedSessions)
    }

    @Test fun `卸载另一个会话的守卫不影响当前会话`() {
        val g = Guard()
        g.shouldLoad("s1")
        g.shouldLoad("s2")                 // now showing s2
        g.onPaneUnmounted("s1")            // stale dispose from the previous pane
        assertEquals("不该清掉当前会话的守卫", "s2", g.loaded)
        assertFalse(g.shouldLoad("s2"))
    }
}
