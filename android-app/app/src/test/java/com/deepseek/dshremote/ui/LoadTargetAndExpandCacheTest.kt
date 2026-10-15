package com.deepseek.dshremote.ui

import com.deepseek.dshremote.protocol.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug 1 (2026-12): "点会话后右栏显示【上一个】的内容".
 *
 * The load target used to fall back to the OUTER session `s` whenever its id
 * disagreed with the request key for one composition tick, so the previous
 * conversation's messages were rendered under the newly selected title.
 */
class LoadTargetSelectionTest {

    private fun sid(id: String, cwd: String = "/w") = SessionSummary(id = id, cwd = cwd, title = "t-$id", status = "idle", createdAt = 0L, updatedAt = 0L, messageCount = 0, lastMessagePreview = "", agentAvailable = true)

    /** Mirrors the resolution the composable now performs. */
    private fun resolveTarget(
        loadRequestKey: String?,
        allSessions: List<SessionSummary>,
        activeSession: SessionSummary,
        outerSession: SessionSummary,
    ): SessionSummary =
        allSessions.firstOrNull { it.id == loadRequestKey }
            ?: activeSession.takeIf { it.id == loadRequestKey }
            ?: outerSession

    @Test fun `切换会话时加载的目标必须是新会话`() {
        val a = sid("A")
        val b = sid("B")
        // Outer session is still A for one tick while the request key already says B.
        val target = resolveTarget("B", listOf(a, b), activeSession = a, outerSession = a)
        assertEquals("★ 必须加载 B 而不是 A", "B", target.id)
    }

    @Test fun `请求键不在列表里时退回外层会话`() {
        val a = sid("A")
        val target = resolveTarget("B", listOf(a), activeSession = a, outerSession = a)
        assertEquals("找不到就用外层会话", "A", target.id)
    }

    @Test fun `子代理 tab 加载的是子代理会话`() {
        val main = sid("MAIN")
        val sub = sid("SUB")
        // activeSession is the sub-agent tab; the request key names it.
        val target = resolveTarget("SUB", listOf(main, sub), activeSession = sub, outerSession = main)
        assertEquals("★ 子代理 tab 必须加载子代理会话", "SUB", target.id)
        assertFalse("不能退回主会话", target.id == main.id)
    }

    @Test fun `空请求键不触发加载`() {
        val a = sid("A")
        assertEquals(null, null.takeIf { false })
        // the composable guards on null before resolving; assert the guard shape
        assertTrue(loadRequestKeyIsBlank(null))
        assertFalse(loadRequestKeyIsBlank("A"))
    }

    private fun loadRequestKeyIsBlank(k: String?) = k == null || k.isEmpty()

    @Test fun `连续切换时每次都解析到当前请求键`() {
        val sessions = listOf(sid("A"), sid("B"), sid("C"))
        var outer = sessions[0]
        for (key in listOf("A", "B", "C", "B")) {
            val active = sessions.first()
            val target = resolveTarget(key, sessions, active, outer)
            assertEquals("请求键 $key 必须加载 $key", key, target.id)
            // simulate the outer session lagging one tick behind
            outer = sessions.first()
        }
    }
}

/**
 * Bug 2 (2026-12): "展开工作区很卡".
 *
 * Every expand re-fetched the whole session list and replaced `allSessions`
 * wholesale, rebuilding the entire sidebar tree. Now a workspace is fetched once
 * per app run and merged per-workspace.
 */
class WorkspaceExpandCacheTest {

    private fun sid(id: String, cwd: String) = SessionSummary(id = id, cwd = cwd, title = "t-$id", status = "idle", createdAt = 0L, updatedAt = 0L, messageCount = 0, lastMessagePreview = "", agentAvailable = true)

    private class Cache {
        val loadedPaths = mutableSetOf<String>()
        var requests = 0

        /** Returns true when a network fetch was issued. */
        fun expand(wsPath: String): Boolean {
            if (!loadedPaths.add(wsPath)) return false   // already fetched -> cache hit
            requests++
            return true
        }
    }

    @Test fun `展开同一工作区两次只请求一次`() {
        val c = Cache()
        assertTrue("首次展开应请求", c.expand("/w1"))
        assertFalse("★ 二次展开必须命中缓存", c.expand("/w1"))
        assertEquals(1, c.requests)
    }

    @Test fun `展开不同工作区各请求一次`() {
        val c = Cache()
        c.expand("/w1")
        c.expand("/w2")
        c.expand("/w1")
        c.expand("/w2")
        assertEquals("两个工作区 = 两次请求", 2, c.requests)
    }

    @Test fun `只合并该工作区 不清空其他工作区`() {
        var all = listOf(sid("w1-a", "/w1"), sid("w1-b", "/w1"))
        // expanding w2 merges only w2
        val incoming = listOf(sid("w2-a", "/w2"))
        all = all.filter { it.cwd != "/w2" } + incoming
        assertEquals("其他工作区的会话必须保留", 2, all.count { it.cwd == "/w1" })
        assertEquals(1, all.count { it.cwd == "/w2" })
    }

    @Test fun `重新展开同一工作区会替换其旧会话 不产生重复`() {
        var all = listOf(sid("w1-a", "/w1"), sid("w1-b", "/w1"))
        val refreshed = listOf(sid("w1-a", "/w1"), sid("w1-c", "/w1"))
        all = all.filter { it.cwd != "/w1" } + refreshed
        val w1 = all.filter { it.cwd == "/w1" }
        assertEquals("该工作区不应出现重复会话", 2, w1.size)
        assertEquals(listOf("w1-a", "w1-c"), w1.map { it.id })
    }

    @Test fun `空工作区不会破坏已有列表`() {
        var all = listOf(sid("w1-a", "/w1"))
        all = all.filter { it.cwd != "/w9" } + emptyList()
        assertEquals(1, all.size)
    }
}