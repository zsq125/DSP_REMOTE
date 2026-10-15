package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.ApiError
import com.deepseek.dshremote.protocol.Message
import com.deepseek.dshremote.protocol.RestResult
import com.deepseek.dshremote.protocol.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun sess(id: String, cwd: String, status: String = "idle", msg: Int = 0, title: String = "") =
    SessionSummary(id, title, status, 0L, 0L, cwd, msg, "", false)

/** 工作区过滤与缓存（需求 §4.2 / §4.3；「标题数字 == 列表条数」的同源保证）。 */
class WorkspaceRepoTest {

    private fun src(
        ws: List<Workspace>? = null,
        sessionsByPath: Map<String, List<SessionSummary>> = emptyMap(),
        wsError: Boolean = false,
    ) = object : WorkspaceSource {
        var wsCalls = 0
        var sessionCalls = 0
        override suspend fun loadWorkspaces(): RestResult<List<Workspace>> {
            wsCalls++
            return if (wsError) RestResult.Err(503, ApiError("dsh_unavailable", "x")) else RestResult.Ok(ws ?: emptyList())
        }
        override suspend fun loadSessions(workspacePath: String): RestResult<List<SessionSummary>> {
            sessionCalls++
            return RestResult.Ok(sessionsByPath[workspacePath] ?: emptyList())
        }
    }

    @Test fun `按 cwd 精确过滤会话`() {
        val rows = listOf(
            sess("s1", "F:\\p\\aaa"),
            sess("s2", "F:\\p\\bbb"),
            sess("s3", "F:\\p\\aaa"),
        )
        val got = sessionsForWorkspace(rows, "F:\\p\\aaa")
        assertEquals(2, got.size)
        assertEquals(listOf("s1", "s3"), got.map { it.id })
    }

    @Test fun `过滤对大小写敏感 与网关统计同源`() {
        val rows = listOf(sess("s1", "F:\\P\\AAA"))
        assertEquals(0, sessionsForWorkspace(rows, "F:\\p\\aaa").size)
        assertEquals(1, sessionsForWorkspace(rows, "F:\\P\\AAA").size)
    }

    @Test fun `过滤空路径等于不过滤`() {
        // 网关的实时统计同样用 `cwd == path` 全等比较；空 path 是"未分组"，
        // 行为必须与网关一致 ⇒ 不过滤（而不是自作聪明地丢弃）。
        assertEquals(1, sessionsForWorkspace(listOf(sess("s1", "")), "").size)
        assertEquals(0, sessionsForWorkspace(listOf(sess("s1", "F:\\p")), "").size)
    }

    @Test fun `过滤空列表不报错`() {
        assertTrue(sessionsForWorkspace(emptyList(), "F:\\p").isEmpty())
    }

    @Test fun `标题数字等于列表条数`() {
        // 网关的 sessionCount 与这里的过滤都基于 cwd ⇒ 必然一致
        val all = listOf(
            sess("a1", "F:\\p\\aaa"), sess("a2", "F:\\p\\aaa"), sess("a3", "F:\\p\\aaa"),
            sess("b1", "F:\\p\\bbb"),
        )
        val live = sessionsForWorkspace(all, "F:\\p\\aaa").size
        val ws = Workspace("w", "aaa", "F:\\p\\aaa", live, 0)
        assertEquals(ws.sessionCount, sessionsForWorkspace(all, ws.path).size)
    }

    @Test fun `workspaces 有缓存时不再请求网络`() {
        val s = src(ws = listOf(Workspace("w1", "t", "F:\\p", 3, 0)))
        val repo = WorkspaceRepo(s)
        runBlockingTest { repo.refreshWorkspaces() }
        assertEquals(1, s.wsCalls)
        runBlockingTest { repo.workspaces() }
        assertEquals("命中缓存不应再请求", 1, s.wsCalls)
    }

    @Test fun `workspaces 无缓存时才请求网络`() {
        val s = src(ws = listOf(Workspace("w1", "t", "F:\\p", 3, 0)))
        val repo = WorkspaceRepo(s)
        val r = runBlockingTest { repo.workspaces() }
        assertTrue(r is RestResult.Ok)
        assertEquals(1, s.wsCalls)
    }

    @Test fun `刷新会更新缓存`() {
        var n = 1
        val s = object : WorkspaceSource {
            override suspend fun loadWorkspaces() = RestResult.Ok(listOf(Workspace("w", "t$n", "F:\\p", n, 0)))
            override suspend fun loadSessions(p: String) = RestResult.Ok(emptyList<SessionSummary>())
        }
        val repo = WorkspaceRepo(s)
        runBlockingTest { repo.refreshWorkspaces() }
        assertEquals("t1", repo.cached().first().title)
        n = 2
        runBlockingTest { repo.refreshWorkspaces() }
        assertEquals("t2", repo.cached().first().title)
    }

    @Test fun `hasCache 反映缓存状态`() {
        val repo = WorkspaceRepo(src(ws = listOf(Workspace("w", "t", "F:\\p", 1, 0))))
        assertFalse(repo.hasCache())
        runBlockingTest { repo.workspaces() }
        assertTrue(repo.hasCache())
    }

    @Test fun `网络错误向上传递`() {
        val repo = WorkspaceRepo(src(wsError = true))
        val r = runBlockingTest { repo.workspaces() }
        assertTrue(r is RestResult.Err)
        assertEquals("dsh_unavailable", (r as RestResult.Err).code)
    }

    @Test fun `sessionsFor 委派给 source`() {
        val s = src(sessionsByPath = mapOf("F:\\p\\a" to listOf(sess("x", "F:\\p\\a"))))
        val repo = WorkspaceRepo(s)
        val r = runBlockingTest { repo.sessionsFor(Workspace("w", "a", "F:\\p\\a", 1, 0)) }
        assertEquals(1, (r as RestResult.Ok).value.size)
        assertEquals(1, s.sessionCalls)
    }
}

/** subagent 识别（需求 §4.5：能区分即可，拿不到父子关系就平铺、不阻塞）。 */
class SubagentTaggerTest {

    @Test fun `识别「你是…」开头的 subagent 会话`() {
        assertTrue(SubagentTagger.isSubagent(sess("s", "F:\\p", title = "你是本项目的开发工程师（唯一开发 subagent）")))
    }

    @Test fun `识别带前导空白的`() {
        assertTrue(SubagentTagger.isSubagent(sess("s", "F:\\p", title = "  你是某个角色")))
    }

    @Test fun `普通会话不算 subagent`() {
        assertFalse(SubagentTagger.isSubagent(sess("s", "F:\\p", title = "修复登录超时")))
        assertFalse(SubagentTagger.isSubagent(sess("s", "F:\\p", title = "需求：手机端互联")))
    }

    @Test fun `空标题不算 subagent`() {
        assertFalse(SubagentTagger.isSubagent(sess("s", "F:\\p", title = "")))
    }

    @Test fun `subagent 标题加前缀标记`() {
        val t = SubagentTagger.displayTitle(sess("s", "F:\\p", title = "你是开发工程师"))
        assertTrue(t.startsWith("【子代理】"))
        assertTrue(t.contains("你是开发工程师"))
    }

    @Test fun `普通会话标题原样显示`() {
        assertEquals("修复登录超时", SubagentTagger.displayTitle(sess("s", "F:\\p", title = "修复登录超时")))
    }

    @Test fun `空标题显示占位`() {
        assertEquals("(无标题)", SubagentTagger.displayTitle(sess("s", "F:\\p", title = "")))
    }

    @Test fun `tagAll 逐条打标`() {
        val tagged = SubagentTagger.tagAll(
            listOf(sess("a", "F:\\p", title = "你是X"), sess("b", "F:\\p", title = "普通"))
        )
        assertEquals(listOf(true, false), tagged.map { it.isSubagent })
        assertEquals(listOf("a", "b"), tagged.map { it.session.id })
    }
}

/** 渲染模型（需求 §4.4）。 */
class MessageRendererTest {

    private fun m(seq: Long, role: String, text: String = "t", status: String = "final") =
        Message(seq, role, text, 0L, status, "m$seq", null)

    @Test fun `user 右对齐`() {
        assertEquals(MessageAlign.RIGHT, MessageRenderer.alignFor("user"))
        assertTrue(MessageRenderer.render(m(1, "user")).isUser)
    }

    @Test fun `assistant 左对齐`() {
        assertEquals(MessageAlign.LEFT, MessageRenderer.alignFor("assistant"))
        assertFalse(MessageRenderer.render(m(1, "assistant")).isUser)
    }

    @Test fun `tool 整行且折叠`() {
        val r = MessageRenderer.render(m(1, "tool", "[tool] pwsh"))
        assertEquals(MessageAlign.FULL, r.align)
        assertTrue(r.collapsed)
    }

    @Test fun `tool 文本规整为一行摘要`() {
        assertEquals("[tool] pwsh", MessageRenderer.toolSummary("[tool] pwsh"))
        assertTrue(MessageRenderer.toolSummary("pwsh\n更多输出").startsWith("[tool] "))
        assertFalse(MessageRenderer.toolSummary("pwsh\n更多").contains("\n"))
    }

    @Test fun `system 弱化且整行`() {
        val r = MessageRenderer.render(m(1, "system", "系统提示"))
        assertTrue(r.dimmed)
        assertEquals(MessageAlign.FULL, r.align)
    }

    @Test fun `streaming 状态被标记`() {
        assertTrue(MessageRenderer.render(m(1, "assistant", "x", "streaming")).isStreaming)
    }

    @Test fun `批量渲染保持顺序`() {
        val rows = MessageRenderer.renderAll(listOf(m(1, "user"), m(2, "assistant"), m(3, "system")))
        assertEquals(listOf("user", "assistant", "system"), rows.map { it.role })
    }
}

/** 消息分页器：长会话不 OOM（需求 §六.7，实测 4654 条）。 */
class MessagePagerTest {

    private fun page(from: Long, count: Int, role: String = "user") =
        (from until from + count).map { Message(it, role, "m$it", it, "final", "x$it", null) }

    @Test fun `首屏取最新一页`() {
        val p = MessagePager(10)
        p.reset(page(100, 5), nextCursor = 95L)
        assertEquals(5, p.retainedCount)
        assertEquals("窗口内最小 seq 是本页第一条", 100L, p.oldestSeq)
        assertTrue("nextCursor 非空 ⇒ 还有更早的", p.hasMore)
        assertEquals(1, p.pagesLoaded)
    }

    @Test fun `首屏超过窗口时只保留最新`() {
        val p = MessagePager(3)
        p.reset(page(1, 10), null)
        assertEquals(3, p.retainedCount)
        assertEquals(listOf(8L, 9L, 10L), p.rows.map { it.seq })
    }

    @Test fun `nextCursor 为 null 时没有更多`() {
        val p = MessagePager(10)
        p.reset(page(1, 3), null)
        assertFalse(p.hasMore)
    }

    @Test fun `向上分页把更早的加在前面`() {
        val p = MessagePager(10)
        p.reset(page(100, 3), 97L)
        val n = p.prepend(page(97, 3), 94L)
        assertEquals(3, n)
        assertEquals(listOf(97L, 98L, 99L, 100L, 101L, 102L), p.rows.map { it.seq })
    }

    @Test fun `重复页不产生重复行`() {
        val p = MessagePager(10)
        p.reset(page(100, 3), 97L)
        assertEquals(0, p.prepend(page(100, 3), null))
        assertEquals(3, p.retainedCount)
    }

    @Test fun `空页终止加载`() {
        val p = MessagePager(10)
        p.reset(page(100, 3), 97L)
        assertEquals(0, p.prepend(emptyList(), 94L))
        assertFalse(p.hasMore)
    }

    @Test fun `窗口上限被严格遵守`() {
        val p = MessagePager(5)
        p.reset(page(100, 3), 97L)
        repeat(10) { p.prepend(page(97L - it * 3L, 3), 90L - it) }
        assertEquals("内存占用必须是 O(window)", 5, p.retainedCount)
    }

    @Test fun `4654 条会话 反复翻页 内存仍有界`() {
        val p = MessagePager(300)
        // 模拟首屏 + 16 次上拉（共 1700 条，远小于全量 4654）
        p.reset((4554L until 4654L).map { Message(it, "user", "m$it", it, "final", "x$it", null) }, 4554L)
        repeat(16) { round ->
            val start = 4354L - round * 200L
            p.prepend((start until start + 200L).map { Message(it, "user", "m$it", it, "final", "x$it", null) }, start)
        }
        assertTrue("窗口应远小于 4654", p.retainedCount <= 300)
        assertEquals(300, p.retainedCount)
    }

    @Test fun `实时追加去重`() {
        val p = MessagePager(10)
        p.reset(page(1, 3), null)
        assertTrue(p.appendLive(Message(4, "user", "new", 4, "final", "m4", null)))
        assertFalse(p.appendLive(Message(4, "user", "new", 4, "final", "m4", null)))
        assertEquals(4, p.retainedCount)
    }

    @Test fun `实时追加受窗口约束`() {
        val p = MessagePager(3)
        p.reset(page(1, 3), null)
        repeat(5) { p.appendLive(Message(10L + it, "user", "n", 0, "final", "m", null)) }
        assertEquals(3, p.retainedCount)
    }

    @Test fun `流式增量合并进同一行`() {
        val p = MessagePager(10)
        p.reset(page(1, 2), null)
        val r = p.appendStreamingChunk("你", seq = 1, ts = 1L)!!
        assertTrue(r.isStreaming)
        assertEquals("user", p.rows[0].role)
        assertEquals("assistant", r.role)
        assertEquals(3, p.rows.size)      // 2 条 durable + 1 条 streaming
    }

    @Test fun `空流式增量被忽略`() {
        val p = MessagePager(10)
        p.reset(page(1, 2), null)
        assertEquals(null, p.appendStreamingChunk("", seq = 1, ts = 1L))
    }

    @Test fun `pagesLoaded 记录翻页次数`() {
        val p = MessagePager(10)
        p.reset(page(100, 3), 97L)
        p.prepend(page(97, 3), 94L)
        p.prepend(page(94, 3), null)
        assertEquals(3, p.pagesLoaded)
    }
}

/** 极简 runBlocking 包装，避免测试里到处 import。 */
private fun <T> runBlockingTest(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
