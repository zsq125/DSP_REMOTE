package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.SessionSummary

/**
 * 子代理归属与「右栏切换器」数据（用户要求照抄 PC 版 harness）。
 *
 * ## 父子关系的确切来源（已查清）
 * DSH 内层 `SessionSummary`（`dsh-api-session-controller/lib/types/types.d.ts:166-167`）**本身就带**：
 *   - `parentSessionId?: SessionId`
 *   - `origin?: 'subagent'`
 * 之前**网关的 `#toItem()` 没有映射这两个字段**，所以 App 拿不到父子关系，
 * 只能把子代理平铺进左栏（这正是用户否决的那版）。
 * 现网关已透出 `parentSessionId` / `isSubagent` / `subagentLabel`，父子关系**是精确的**，无需降级方案。
 *
 * 结构（照抄 PC 版）：
 *   左栏  = 工作区 → **主会话**（`isSubagent == false`）
 *   右栏上方 = 子代理切换器（主会话 tab + 各子代理 tab）
 */
object SubagentTree {

    /** 主会话：没有父，或父不在已知集合内。 */
    fun isMainSession(s: SessionSummary): Boolean = !s.isSubagentFlag

    /** 某主会话下的直接子代理（按创建时间升序，切换器里的顺序稳定）。 */
    fun childrenOf(parentId: String, all: List<SessionSummary>): List<SessionSummary> =
        all.filter { it.parentSessionId == parentId }
            .sortedBy { it.createdAt }

    /** 左栏只放主会话（子代理一律不进左栏）。 */
    fun mainSessionsOnly(all: List<SessionSummary>): List<SessionSummary> =
        all.filter { isMainSession(it) }
            .sortedByDescending { it.updatedAt }

    /**
     * 右栏切换器的 tab 列表：第 0 个永远是主会话本身，其后是各子代理。
     * @param main 主会话
     * @param all 全部会话（用于找子代理）
     */
    fun tabsFor(main: SessionSummary, all: List<SessionSummary>): List<Tab> {
        val out = mutableListOf(Tab(main.id, "主会话", isMain = true))
        for (c in childrenOf(main.id, all)) {
            val label = c.subagentLabel?.takeIf { it.isNotBlank() } ?: deriveLabel(c.title)
            out += Tab(c.id, label, isMain = false)
        }
        return out
    }

    /** 从标题里提取子代理名（兜底：`你是本项目的**开发工程师**（…` → `开发工程师`）。 */
    fun deriveLabel(title: String): String {
        val bold = Regex("\\*\\*(.+?)\\*\\*").find(title)?.groupValues?.get(1)
        if (!bold.isNullOrBlank()) return bold
        val m = Regex("你是[^「\\n]{0,20}?([\\u4e00-\\u9fa5A-Za-z]{2,12})(?:（|\\(|专业|专属|$)").find(title)
        if (m != null) return m.groupValues[1]
        val short = title.trim().take(12)
        return if (short.isNotEmpty()) short else "子代理"
    }

    /** 切换器的一个 tab。 */
    data class Tab(val sessionId: String, val label: String, val isMain: Boolean)
}

/**
 * 乐观发送 + 失败可见（用户问题 1：点发送毫无反应）。
 *
 * 点发送后：
 *  1. **立即**把用户消息插入消息流（本地先显示，不等网络）；
 *  2. 记录 pending 状态（可显示"发送中"）；
 *  3. 请求失败 ⇒ 转 [SendState.Failed] 并**保留原文**，UI 必须显示错误 + 可重试；
 *  4. 成功 ⇒ 标记已送达；后续 WS 推来的 durable user 消息按 requestId 去重。
 */
class SendState {

    enum class Phase { Idle, Sending, Sent, Failed }

    var phase: Phase = Phase.Idle
        private set
    var error: String? = null
        private set
    /** 当前 pending 的文本（失败时保留，供重试）。 */
    var pendingText: String? = null
        private set
    var pendingRequestId: String? = null
        private set

    val isSending: Boolean get() = phase == Phase.Sending
    val showError: Boolean get() = phase == Phase.Failed && error != null

    /** 点击发送 ⇒ 返回待用的 requestId（契约幂等键）。 */
    fun begin(requestId: String, text: String) {
        phase = Phase.Sending
        error = null
        pendingText = text
        pendingRequestId = requestId
    }

    fun success() {
        phase = Phase.Sent
        error = null
        failure = null            // 成功后清掉失败分类（否则 UI 会一直显示旧的错误条）
        pendingText = null
    }

    /** 失败必须**可见**且保留原文以便重试。 */
    fun fail(message: String) {
        phase = Phase.Failed
        error = message.ifBlank { "发送失败" }
    }

    /**
     * 带语义分类的失败（[SendFailure]）：
     * 子代理只读 / PC 端占用 / 其它真失败，三者 UI 表现不同。
     */
    fun fail(f: SendFailure) {
        phase = Phase.Failed
        error = f.userMessage
        failure = f
    }

    /** 最近的失败分类（null = 无失败）。 */
    var failure: SendFailure? = null
        private set

    /** 子代理只读：输入框应禁用，**不提供重试**。 */
    val inputDisabled: Boolean get() = failure == SendFailure.SubagentReadOnly

    /** 是否提供「重试」按钮（子代理只读时不提供）。 */
    val canRetry: Boolean get() = failure == null || failure!!.retryable

    /** 重试：复用同一个 requestId（服务端幂等 ⇒ 不会重复入队）。 */
    fun retryRequestId(): String? = pendingRequestId

    fun reset() {
        phase = Phase.Idle
        error = null
        pendingText = null
        pendingRequestId = null
        failure = null
    }
}

/**
 * 发送失败的**语义分类**（依据网关响应体的 `error.code`，不靠猜）。
 *
 * 网关已把 DSH 的 `session/writer-held` / `session/agent-busy` 统一映射为 `session_busy`，
 * 因此 App 必须结合「目标会话是不是子代理」才能区分这两种完全不同的含义。
 */
enum class SendFailure(
    /** 展示给用户的中文提示。 */
    val userMessage: String,
    /** 是否值得提供「重试」。 */
    val retryable: Boolean,
    /** 是否应禁用输入框。 */
    val disablesInput: Boolean = false,
) {
    /** 子代理会话只读（409 + 目标是子代理）——重试无用，直接引导去主会话。 */
    SubagentReadOnly("子代理会话只读，请在主会话中操作", retryable = false, disablesInput = true),

    /** 主会话正被 PC 端占用（DSH 单写者保护）——稍候可重试，**不是网络故障**。 */
    SessionBusyOnPc("该会话正在 PC 端运行，请稍候再发", retryable = true),

    /** 其它真失败：网络 / 5xx / dsh_unavailable / 超时。 */
    Other("发送失败，请检查网络后重试", retryable = true);

    companion object {
        /**
         * 依据**响应体的 error.code** 与**目标会话是否为子代理**判定。
         *
         * @param errorCode 网关 `error.code`
         * @param session   目标会话（复用已有的 isSubagent / parentSessionId 字段）
         */
        fun classify(errorCode: String?, session: SessionSummary?): SendFailure = when {
            errorCode == "session_busy" && session != null && session.isSubagentFlag -> SubagentReadOnly
            errorCode == "session_busy" -> SessionBusyOnPc
            else -> Other
        }
    }
}

/**
 * 渐进式首屏加载（用户问题 4：大会话加载卡死界面）。
 *
 * 目标：**先出后补** —— 先渲染最近 [firstBatch] 条（立即上屏），再在后台把窗口补满，
 * 全程 UI 可交互；**绝不在主线程一次性解析全量**。
 */
class ProgressiveLoadState(
    private val firstBatch: Int = 30,
) {
    /** 首屏已渲染条数（验收：≤1.5s 内可见）。 */
    var visibleCount: Int = 0
        private set
    var firstScreenReady: Boolean = false
        private set
    var backgroundDone: Boolean = false
        private set

    /** 首屏返回后：只渲染最近 N 条，立即上屏。 */
    fun onFirstBatch(total: Int) {
        visibleCount = minOf(firstBatch, total)
        firstScreenReady = true
        backgroundDone = total <= visibleCount
    }

    /** 后台补齐窗口（不阻塞 UI）。 */
    fun onBackgroundFilled(windowSize: Int) {
        visibleCount = windowSize
        backgroundDone = true
    }

    /** 追加实时消息后递增可见数（上限=窗口）。 */
    fun onLiveAppend(windowSize: Int) {
        visibleCount = minOf(windowSize, visibleCount + 1)
    }

    /**
     * 定位模式（搜索命中跳转）直接以窗口实长为准重置渐进计数。
     * 2026-10-13 真机实测：focus 分支只改了 DetailPane.visibleCount，而
     * progress 自己的 visibleCount 还是 0 ⇒ 第一条实时帧 onLiveAppend 把
     * 它顶成 1，LazyColumn 瞬间只剩 1 行（"命中后只有 tool pwsh"的半边根因）。
     */
    fun resync(count: Int) {
        visibleCount = count
        firstScreenReady = true
        backgroundDone = true
    }

    val showSkeleton: Boolean get() = !firstScreenReady
}

/** 扩展：SessionSummary 的子代理字段（网关 v1.3 起透出）。 */
val SessionSummary.isSubagentFlag: Boolean
    get() = isSubagent || parentSessionId != null
