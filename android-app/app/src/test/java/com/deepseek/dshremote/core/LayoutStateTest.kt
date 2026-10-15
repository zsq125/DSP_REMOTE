package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「停止」按钮的显示策略（缺陷根因修复）。
 *
 * 缺陷：详情页**无条件**渲染「停止」，并把「WS 是否连上」当成「会话是否在跑」，
 * 导致 idle 会话也出现该按钮（用户：「为何有停止了任务」）。
 */
class StopButtonPolicyTest {

    private fun s(status: String) = SessionSummary("s1", "t", status, 0L, 0L, "F:\\p", 1, "", false)

    @Test fun `running 会话显示停止`() {
        assertTrue(StopButtonPolicy.shouldShowStop("running"))
    }

    @Test fun `idle 会话不显示停止`() {
        assertEquals(false, StopButtonPolicy.shouldShowStop("idle"))
    }

    @Test fun `缺陷回归 idle 绝不能显示停止`() {
        // 用户实测的那个会话：untitled / 152 条 / idle
        val idleSession = SessionSummary("x", "(untitled)", "idle", 0L, 0L, "F:\\p", 152, "", false)
        assertFalse("idle 会话不得显示停止", StopButtonPolicy.shouldShowStop(idleSession.status))
    }

    @Test fun `状态缺失视为 idle 不显示停止`() {
        assertFalse(StopButtonPolicy.shouldShowStop(null))
        assertFalse(StopButtonPolicy.shouldShowStop(""))
        assertFalse(StopButtonPolicy.shouldShowStop("unknown"))
    }

    @Test fun `状态大小写与别名归一为 idle`() {
        assertFalse(StopButtonPolicy.shouldShowStop("Running"))
        assertFalse(StopButtonPolicy.shouldShowStop("busy"))
    }

    @Test fun `isRunning 与 shouldShowStop 同源`() {
        for (st in listOf("running", "idle", null, "", "unknown")) {
            val session = SessionSummary("s1", "t", st ?: "idle", 0L, 0L, "F:\\p", 1, "", false)
            assertEquals(
                "status=$st",
                StopButtonPolicy.shouldShowStop(st),
                StopButtonPolicy.isRunning(session),
            )
        }
    }

    @Test fun `isRunning 对 null 会话为 false`() {
        assertFalse(StopButtonPolicy.isRunning(null))
    }

    @Test fun `displayStatus 归一`() {
        assertEquals("running", StopButtonPolicy.displayStatus("running"))
        assertEquals("idle", StopButtonPolicy.displayStatus("idle"))
        assertEquals("idle", StopButtonPolicy.displayStatus(null))
    }

    @Test fun `WS 状态合并只改目标会话`() {
        val list = listOf(
            SessionSummary("a", "", "idle", 0, 0, "p", 0, "", false),
            SessionSummary("b", "", "idle", 0, 0, "p", 0, "", false),
        )
        val merged = StopButtonPolicy.mergeStatus(list, "a", "running")
        assertEquals("running", merged[0].status)
        assertEquals("idle", merged[1].status)
    }

    @Test fun `WS 合并把 running 归回 idle`() {
        val list = listOf(SessionSummary("a", "", "running", 0, 0, "p", 0, "", false))
        assertEquals("idle", StopButtonPolicy.mergeStatus(list, "a", "idle")[0].status)
    }

    @Test fun `合并不存在的会话不改变列表`() {
        val list = listOf(SessionSummary("a", "", "idle", 0, 0, "p", 0, "", false))
        assertEquals(1, StopButtonPolicy.mergeStatus(list, "zzz", "running").size)
        assertEquals("idle", StopButtonPolicy.mergeStatus(list, "zzz", "running")[0].status)
    }
}

/** 左栏折叠状态（需求 §二.4：宽度 ≤38%、可折叠、状态记忆）。 */
class SidebarStateTest {

    @Test fun `默认展开且宽度约三分之一`() {
        val s = SidebarState()
        assertTrue(s.visible)
        assertEquals(SidebarState.DEFAULT_FRACTION, s.widthFraction, 1e-6f)
    }

    @Test fun `toggle 折叠与展开`() {
        val s = SidebarState()
        s.toggle()
        assertFalse(s.visible)
        assertEquals("折叠后右栏全宽", 0f, s.effectiveFraction, 1e-6f)
        s.toggle()
        assertTrue(s.visible)
    }

    @Test fun `宽度不得超过 38 percent`() {
        val s = SidebarState()
        s.setFraction(0.9f)
        assertEquals(SidebarState.MAX_FRACTION, s.widthFraction, 1e-6f)
    }

    @Test fun `宽度不得低于下限`() {
        val s = SidebarState()
        s.setFraction(0.01f)
        assertEquals(SidebarState.MIN_FRACTION, s.widthFraction, 1e-6f)
    }

    @Test fun `折叠状态可序列化与恢复`() {
        val s = SidebarState()
        s.toggle()
        assertEquals("1", s.serialize())
        assertTrue(SidebarState.deserialize(s.serialize()).collapsed)
        assertFalse(SidebarState.deserialize("0").collapsed)
    }

    @Test fun `deserialize null 视为展开`() {
        assertTrue(SidebarState.deserialize(null).visible)
    }

    @Test fun `初始值可指定`() {
        assertFalse(SidebarState(initialCollapsed = true).visible)
    }
}

/** 左栏两级树的展开态（需求 §二.2）。 */
class WorkspaceTreeStateTest {

    private val ws = listOf(
        Workspace("w1", "A", "F:\\a", 1, 0),
        Workspace("w2", "B", "F:\\b", 1, 0),
        Workspace("w3", "C", "F:\\c", 1, 0),
    )
    private val sessions = listOf(
        SessionSummary("s1", "", "idle", 0, 0, "F:\\a", 0, "", false),
        SessionSummary("s2", "", "idle", 0, 0, "F:\\b", 0, "", false),
        SessionSummary("s3", "", "idle", 0, 0, "F:\\c", 0, "", false),
    )

    @Test fun `初始全部收起`() {
        assertFalse(WorkspaceTreeState().isExpanded("w1"))
    }

    @Test fun `toggle 切换展开态`() {
        val t = WorkspaceTreeState()
        assertTrue(t.toggle("w1"))
        assertTrue(t.isExpanded("w1"))
        assertFalse(t.toggle("w1"))
        assertFalse(t.isExpanded("w1"))
    }

    @Test fun `可同时展开多个`() {
        val t = WorkspaceTreeState()
        t.toggle("w1"); t.toggle("w2")
        assertEquals(setOf("w1", "w2"), t.expandedIds())
    }

    @Test fun `选中会话时只展开其所在工作区`() {
        val t = WorkspaceTreeState()
        t.toggle("w1"); t.toggle("w2")
        val map = workspaceIdOfSessions(ws, sessions)
        val focused = t.focusSession("s3", map)
        assertEquals("w3", focused)
        assertEquals(setOf("w3"), t.expandedIds())
    }

    @Test fun `会话不存在时不展开任何工作区`() {
        val t = WorkspaceTreeState()
        t.toggle("w1")
        assertEquals(null, t.focusSession("nope", workspaceIdOfSessions(ws, sessions)))
        assertTrue(t.expandedIds().isEmpty())
    }

    @Test fun `会话 id 为 null 时不展开`() {
        val t = WorkspaceTreeState()
        assertEquals(null, t.focusSession(null, workspaceIdOfSessions(ws, sessions)))
        assertTrue(t.expandedIds().isEmpty())
    }

    @Test fun `会话按 cwd 映射到工作区`() {
        val map = workspaceIdOfSessions(ws, sessions)
        assertEquals("w1", map["s1"])
        assertEquals("w3", map["s3"])
        assertEquals(3, map.size)
    }

    @Test fun `cwd 不匹配任何工作区的会话被忽略`() {
        val orphan = SessionSummary("s9", "", "idle", 0, 0, "F:\\unknown", 0, "", false)
        assertFalse(workspaceIdOfSessions(ws, sessions + orphan).containsKey("s9"))
    }
}
