package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.SessionSummary

/**
 * 会话运行状态 → 详情页操作条（纯逻辑，可单测）。
 *
 * ## 缺陷根因（2026-10-12 用户实机发现）
 * 详情页此前**无条件**渲染「停止」按钮，且把「WS 是否连上」当成了「会话是否在跑」：
 *  - 点进一个 `idle` 会话（152 条）仍然显示「停止」⇒ 用户问「为何有停止了任务」；
 *  - 根因是渲染时没有用 `session.status` 做判断，WS 没同步时的**默认值也被当成了 running**。
 *
 * 现在判定只看**会话自身状态**（`status == "running"`），与连接状态完全解耦：
 *  - WS 没同步 / 未连接 ⇒ 视为 idle ⇒ **不显示**停止（宁可少显示，不可误显示）；
 *  - 只有网关明确说这个会话在跑，才显示停止。
 */
object StopButtonPolicy {

    /** 是否应显示「停止」按钮。 */
    fun shouldShowStop(sessionStatus: String?): Boolean = sessionStatus == "running"

    /** 会话级展示状态：running / idle（用于标题与状态圆点）。 */
    fun displayStatus(sessionStatus: String?): String =
        if (sessionStatus == "running") "running" else "idle"

    /** 是否在运行（与 [shouldShowStop] 同源，避免两处判断不一致）。 */
    fun isRunning(session: SessionSummary?): Boolean = shouldShowStop(session?.status)

    /** 批量：把 WS 推来的状态合并进会话列表（未出现的会话保持原值）。 */
    fun mergeStatus(
        sessions: List<SessionSummary>,
        sessionId: String,
        newStatus: String,
    ): List<SessionSummary> = sessions.map {
        if (it.id == sessionId) it.copy(status = displayStatus(newStatus)) else it
    }
}

/**
 * 左栏折叠状态（需求 §二.4：折叠状态需记忆）。
 *
 * 抽出为纯逻辑以便单测；实际持久化用 DataStore（见 [rememberSidebarState] 的调用方）。
 */
class SidebarState(
    initialCollapsed: Boolean = false,
) {
    /** 窄屏下左栏宽度占比（需求：约 1/3 且 ≤ 38%）。 */
    var widthFraction: Float = DEFAULT_FRACTION
        private set

    /** 是否折叠（折叠后右栏全宽）。 */
    var collapsed: Boolean = initialCollapsed
        private set

    /** 当前是否应显示左栏。 */
    val visible: Boolean get() = !collapsed

    /** 左栏宽度占比：折叠后右栏全宽 ⇒ 0。 */
    val effectiveFraction: Float get() = if (collapsed) 0f else widthFraction

    fun toggle() { collapsed = !collapsed }

    fun setCollapsed(v: Boolean) { collapsed = v }

    /** 宽度必须夹在 (0, 0.38] 之间（需求硬要求）。 */
    fun setFraction(f: Float) {
        widthFraction = f.coerceIn(MIN_FRACTION, MAX_FRACTION)
    }

    /** 序列化为可持久化的字符串。 */
    fun serialize(): String = if (collapsed) "1" else "0"

    companion object {
        const val DEFAULT_FRACTION = 0.34f
        const val MAX_FRACTION = 0.38f
        const val MIN_FRACTION = 0.20f

        fun deserialize(raw: String?): SidebarState = SidebarState(raw == "1")
    }
}

/**
 * 左栏两级树的展开态（需求 §二.2：工作区可折叠/展开，默认展开当前会话所在工作区）。
 */
class WorkspaceTreeState {

    private val expanded = linkedSetOf<String>()

    /** 已展开的工作区 id。 */
    fun expandedIds(): Set<String> = expanded.toSet()

    fun isExpanded(workspaceId: String): Boolean = workspaceId in expanded

    /** 切换展开态，返回切换后的状态。 */
    fun toggle(workspaceId: String): Boolean {
        val now = if (workspaceId in expanded) { expanded -= workspaceId; false } else { expanded += workspaceId; true }
        return now
    }

    fun expand(workspaceId: String) { expanded += workspaceId }

    fun collapse(workspaceId: String) { expanded -= workspaceId }

    /**
     * 只展开当前会话所在的工作区（其余全部收起）——需求「默认展开当前会话所在工作区」。
     * @return 被展开的工作区 id（找不到会话时返回 null）
     */
    fun focusSession(
        sessionId: String?,
        sessionToWorkspace: Map<String, String>,
    ): String? {
        val wsId = sessionId?.let { sessionToWorkspace[it] }
        expanded.clear()
        if (wsId != null) expanded += wsId
        return wsId
    }

    /**
     * 只展开指定的工作区（其余收起）。
     *
     * 问题 1（首屏只加载工作区名）：冷启动时用 [focusWorkspace] 的 `null` 版本把左栏
     * 收到全折叠，这样用户必须**点开**某个工作区才会触发该工作区的会话加载。
     */
    fun focusWorkspace(workspaceId: String?): String? {
        expanded.clear()
        if (workspaceId != null) expanded += workspaceId
        return workspaceId
    }
}

/** 会话 → 所属工作区 id 的映射（按 cwd 匹配）。 */
fun workspaceIdOfSessions(
    workspaces: List<Workspace>,
    sessions: List<SessionSummary>,
): Map<String, String> {
    val byPath = workspaces.associate { it.path to it.id }
    val out = HashMap<String, String>()
    for (s in sessions) {
        val wsId = byPath[s.cwd]
        if (wsId != null) out[s.id] = wsId
    }
    return out
}
