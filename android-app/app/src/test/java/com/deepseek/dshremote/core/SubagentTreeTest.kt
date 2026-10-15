package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun s(
    id: String,
    parent: String? = null,
    label: String? = null,
    title: String = "t",
    createdAt: Long = 0,
    cwd: String = "F:\\p",
) = SessionSummary(id, title, "idle", createdAt, createdAt, cwd, 1, "", false, parent, parent != null, label)

/**
 * 父子关系与右栏子代理切换器（用户问题 2）。
 *
 * 数据来源已查清：DSH `SessionSummary` 自带 `parentSessionId` / `origin='subagent'`
 * （`dsh-api-session-controller/lib/types/types.d.ts:166-167`），网关现已透出。
 */
class SubagentTreeTest {

    private val main1 = s("main-1", createdAt = 100)
    private val sub1 = s("sub-1", parent = "main-1", label = "Android 开发工程师", createdAt = 200)
    private val sub2 = s("sub-2", parent = "main-1", label = "测试工程师", createdAt = 150)
    private val main2 = s("main-2", createdAt = 300)
    private val all = listOf(main1, sub2, sub1, main2)

    @Test fun `识别主会话`() {
        assertTrue(SubagentTree.isMainSession(main1))
        assertFalse(SubagentTree.isMainSession(sub1))
    }

    @Test fun `有 parentSessionId 即为子代理`() {
        // 只打 isSubagent=true 但没有 parent 的情况：仍视为子代理（网关两个字段都会给）
        val markedOnly = main1.copy(isSubagent = true, parentSessionId = null)
        assertFalse(SubagentTree.isMainSession(markedOnly))
    }

    @Test fun `按 parentId 找直接子代理`() {
        assertEquals(setOf("sub-1", "sub-2"), SubagentTree.childrenOf("main-1", all).map { it.id }.toSet())
    }

    @Test fun `子代理按创建时间升序 切换器顺序稳定`() {
        val ordered = SubagentTree.childrenOf("main-1", all)
        assertEquals(listOf("sub-2", "sub-1"), ordered.map { it.id })
    }

    @Test fun `没有子代理时返回空`() {
        assertTrue(SubagentTree.childrenOf("main-2", all).isEmpty())
    }

    @Test fun `左栏只放主会话 子代理被排除`() {
        val mains = SubagentTree.mainSessionsOnly(all)
        assertEquals(setOf("main-1", "main-2"), mains.map { it.id }.toSet())
        assertFalse("子代理不得进左栏", mains.any { it.id == "sub-1" })
    }

    @Test fun `左栏按最近活跃排序`() {
        assertEquals(listOf("main-2", "main-1"), SubagentTree.mainSessionsOnly(all).map { it.id })
    }

    @Test fun `切换器第 0 个永远是主会话`() {
        val tabs = SubagentTree.tabsFor(main1, all)
        assertEquals("main-1", tabs[0].sessionId)
        assertTrue(tabs[0].isMain)
    }

    @Test fun `切换器包含全部子代理`() {
        val tabs = SubagentTree.tabsFor(main1, all)
        assertEquals(3, tabs.size)
        assertEquals(setOf("sub-1", "sub-2"), tabs.drop(1).map { it.sessionId }.toSet())
        assertTrue(tabs.drop(1).none { it.isMain })
    }

    @Test fun `切换器优先用 durable 的 label`() {
        val labels = SubagentTree.tabsFor(main1, all).drop(1).map { it.label }
        assertEquals(setOf("Android 开发工程师", "测试工程师"), labels.toSet())
    }

    @Test fun `label 缺失时从标题提取`() {
        val noLabel = s("sub-x", parent = "main-1", title = "你是本项目的**测试工程师**（唯一）", createdAt = 1)
        val tabs = SubagentTree.tabsFor(main1, listOf(main1, noLabel))
        assertEquals("测试工程师", tabs[1].label)
    }

    @Test fun `标题没有 markdown 时回退截断`() {
        val odd = s("sub-y", parent = "main-1", title = "某个奇怪的会话标题很长很长", createdAt = 1)
        val tabs = SubagentTree.tabsFor(main1, listOf(main1, odd))
        assertTrue(tabs[1].label.isNotBlank())
    }

    @Test fun `无子代理时切换器只有一个 tab`() {
        val tabs = SubagentTree.tabsFor(main2, all)
        assertEquals(1, tabs.size)
    }

    @Test fun `deriveLabel 提取中文角色名`() {
        assertEquals("开发工程师", SubagentTree.deriveLabel("你是本项目的**开发工程师**（唯一开发 subagent）"))
    }

    @Test fun `deriveLabel 空标题不崩`() {
        assertTrue(SubagentTree.deriveLabel("").isNotBlank())
    }

    @Test fun `不同主会话的子代理互不混入`() {
        val otherSub = s("sub-3", parent = "main-2", createdAt = 400)
        val tabs2 = SubagentTree.tabsFor(main2, all + otherSub)
        assertEquals(2, tabs2.size)
        assertEquals("sub-3", tabs2[1].sessionId)
    }
}

/** 发送状态（用户问题 1：点发送毫无反应）。 */
class SendStateTest {

    @Test fun `初始为空闲`() {
        val s = SendState()
        assertEquals(SendState.Phase.Idle, s.phase)
        assertFalse(s.isSending)
        assertFalse(s.showError)
    }

    @Test fun `begin 进入发送中并记录 requestId 与原文`() {
        val s = SendState()
        s.begin("rid-1", "hello")
        assertEquals(SendState.Phase.Sending, s.phase)
        assertTrue(s.isSending)
        assertEquals("rid-1", s.pendingRequestId)
        assertEquals("hello", s.pendingText)
    }

    @Test fun `成功进入已发送`() {
        val s = SendState()
        s.begin("rid-1", "hello")
        s.success()
        assertEquals(SendState.Phase.Sent, s.phase)
        assertNull(s.error)
    }

    @Test fun `失败必须可见且保留原文`() {
        val s = SendState()
        s.begin("rid-1", "hello")
        s.fail("500 internal")
        assertEquals(SendState.Phase.Failed, s.phase)
        assertTrue(s.showError)
        assertEquals("hello", s.pendingText)         // 保留原文供重试
        assertEquals("500 internal", s.error)
    }

    @Test fun `失败文案为空时兜底`() {
        val s = SendState()
        s.fail("")
        assertTrue((s.error ?: "").isNotBlank())
    }

    @Test fun `重试复用同一 requestId 保证幂等`() {
        val s = SendState()
        s.begin("rid-1", "hello")
        s.fail("x")
        assertEquals("rid-1", s.retryRequestId())
    }

    @Test fun `reset 清空`() {
        val s = SendState()
        s.begin("rid", "x"); s.fail("e")
        s.reset()
        assertEquals(SendState.Phase.Idle, s.phase)
        assertNull(s.pendingRequestId)
    }
}

/** 渐进加载（用户问题 4：大会话加载卡死）。 */
class ProgressiveLoadStateTest {

    @Test fun `初始显示骨架屏`() {
        val p = ProgressiveLoadState(30)
        assertTrue(p.showSkeleton)
        assertFalse(p.firstScreenReady)
    }

    @Test fun `首屏只渲染最近 N 条 立即上屏`() {
        val p = ProgressiveLoadState(30)
        p.onFirstBatch(total = 100)
        assertTrue(p.firstScreenReady)
        assertFalse(p.showSkeleton)
        assertEquals("首屏只渲染 30 条，不一次渲染全部", 30, p.visibleCount)
    }

    @Test fun `总数小于 N 时全部渲染`() {
        val p = ProgressiveLoadState(30)
        p.onFirstBatch(total = 12)
        assertEquals(12, p.visibleCount)
        assertTrue(p.backgroundDone)
    }

    @Test fun `后台补齐窗口`() {
        val p = ProgressiveLoadState(30)
        p.onFirstBatch(100)
        p.onBackgroundFilled(100)
        assertEquals(100, p.visibleCount)
        assertTrue(p.backgroundDone)
    }

    @Test fun `实时追加不超过窗口`() {
        val p = ProgressiveLoadState(30)
        p.onFirstBatch(100)
        repeat(500) { p.onLiveAppend(300) }
        assertEquals(300, p.visibleCount)
    }
}

/** 乐观插入（点发送立刻上屏）。 */
class OptimisticMessageTest {

    private fun pager() = MessagePager(50)

    @Test fun `乐观消息立即出现且右对齐`() {
        val p = pager()
        p.appendOptimistic("你好", "rid-1", 1000L)
        val row = p.rows.last()
        assertEquals("user", row.role)
        assertEquals(MessageAlign.RIGHT, row.align)
        assertEquals("你好", row.text)
        assertEquals("rid-1", row.requestId)
        assertEquals("sending", row.status)
    }

    @Test fun `乐观行用负 seq 不与 durable 冲突`() {
        val p = pager()
        val row = p.appendOptimistic("hi", "rid", 5000L)
        assertTrue("乐观 seq 必须为负，避免与 durable seq 撞车", row.seq < 0)
    }

    @Test fun `发送成功后标记已送达`() {
        val p = pager()
        p.appendOptimistic("hi", "rid-1", 1L)
        p.markOptimisticSent("rid-1")
        assertEquals("final", p.optimisticRow("rid-1")!!.status)
    }

    @Test fun `发送失败保留内容并标记失败`() {
        val p = pager()
        p.appendOptimistic("hi", "rid-1", 1L)
        p.markOptimisticFailed("rid-1")
        val row = p.optimisticRow("rid-1")
        assertEquals("failed", row!!.status)
        assertEquals("内容必须保留供重试", "hi", row.text)
    }

    @Test fun `可丢弃失败的乐观行`() {
        val p = pager()
        p.appendOptimistic("hi", "rid-1", 1L)
        p.markOptimisticFailed("rid-1")
        p.dropOptimistic("rid-1")
        assertNull(p.optimisticRow("rid-1"))
    }

    @Test fun `失败只影响对应 requestId`() {
        val p = pager()
        p.appendOptimistic("a", "r1", 1L)
        p.appendOptimistic("b", "r2", 2L)
        p.markOptimisticFailed("r1")
        assertEquals("failed", p.optimisticRow("r1")!!.status)
        assertEquals("另一条不受影响", "sending", p.optimisticRow("r2")!!.status)
    }

    @Test fun `visible 只返回最后 N 条`() {
        val p = MessagePager(100)
        repeat(100) { p.appendLive(com.deepseek.dshremote.protocol.Message(it.toLong(), "user", "m$it", 0, "final", "x", null)) }
        assertEquals(100, p.retainedCount)
        assertEquals(30, p.visible(30).size)
        assertEquals("m99", p.visible(30).last().text)
    }
}
