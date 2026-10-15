package com.deepseek.dshremote.core

/**
 * 详情页消息加载状态机（用户问题 1：点会话后必须**自动**加载，不该让用户手动下拉）。
 *
 * 之前把「加载更早」做成一个需要**点击**的按钮，且首屏没有明确的 loading / error 反馈，
 * 用户看到的是一片空白 + 一个「上拉加载更早的消息」。
 *
 * 现在：
 *  - 选中会话 ⇒ [onSessionSelected] ⇒ 自动进入 [LoadingFirstPage]；
 *  - 成功 ⇒ [Loaded]（**立即渲染最新一屏**）；
 *  - 失败 ⇒ [Failed]（**显示错误文案**，不静默空白）；
 *  - 向上滚到顶 ⇒ [onScrolledToTop] ⇒ 返回 true（应触发加载更早）。
 */
class DetailLoadState {

    enum class Phase {
        /** 空闲（未选中会话）。 */
        Idle,

        /** 首屏加载中（显示 loading 指示）。 */
        LoadingFirstPage,

        /** 已加载，右栏应有消息内容。 */
        Loaded,

        /** 首屏加载失败（显示错误 + 重试入口）。 */
        Failed,
    }

    var phase: Phase = Phase.Idle
        private set

    /** 错误文案（Failed 时非空）。 */
    var error: String? = null
        private set

    /** 是否应显示首屏 loading 指示。 */
    val showLoading: Boolean get() = phase == Phase.LoadingFirstPage

    /** 是否应显示错误提示。 */
    val showError: Boolean get() = phase == Phase.Failed && error != null

    /** 是否已渲染出内容（验收：点会话后右栏直接有消息）。 */
    val hasContent: Boolean get() = phase == Phase.Loaded

    /** 选中/切换会话 ⇒ 自动开始首屏加载。 */
    fun onSessionSelected(): Boolean {
        phase = Phase.LoadingFirstPage
        error = null
        return true                       // 需要发起首屏请求
    }

    /** 首屏成功。 */
    fun onFirstPageLoaded(hasRows: Boolean) {
        phase = Phase.Loaded
        error = null
    }

    /** 首屏失败（必须给出可见文案，不能静默空白）。 */
    fun onFirstPageFailed(message: String) {
        phase = Phase.Failed
        error = message.ifBlank { "加载消息失败" }
    }

    /**
     * 用户向上滚动到顶 ⇒ 应自动加载更早的一页（无限滚动入口）。
     * @return 是否需要发起"加载更早"请求
     */
    fun onScrolledToTop(hasMore: Boolean): Boolean {
        if (phase != Phase.Loaded) return false
        return hasMore
    }

    /** 重试。 */
    fun retry(): Boolean {
        phase = Phase.LoadingFirstPage
        error = null
        return true
    }

    /** 切到别的会话 ⇒ 回到 Loading（重新自动加载）。 */
    fun onSwitchedAway(): Boolean {
        if (phase == Phase.Loaded) return false
        return onSessionSelected()
    }
}
