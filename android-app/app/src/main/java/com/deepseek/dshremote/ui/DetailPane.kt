package com.deepseek.dshremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dshremote.core.DshTrace
import com.deepseek.dshremote.core.ExpandStateStore
import com.deepseek.dshremote.core.MessagePager
import com.deepseek.dshremote.core.MessageRow
import com.deepseek.dshremote.core.ProgressiveLoadState
import com.deepseek.dshremote.core.SendFailure
import com.deepseek.dshremote.core.SessionSubscriptionManager
import com.deepseek.dshremote.core.SendState
import com.deepseek.dshremote.core.StopButtonPolicy
import com.deepseek.dshremote.core.SubagentTree
import com.deepseek.dshremote.data.MessageCache
import com.deepseek.dshremote.core.Transport
import com.deepseek.dshremote.net.GatewayApi
import com.deepseek.dshremote.net.OkHttpTransport
import com.deepseek.dshremote.protocol.Message
import com.deepseek.dshremote.protocol.RestResult
import com.deepseek.dshremote.protocol.ServerFrame
import com.deepseek.dshremote.protocol.SessionSummary
import com.deepseek.dshremote.protocol.parseServerFrame
import kotlinx.coroutines.launch
import java.util.UUID

/** 详情页首屏加载阶段。 */
private enum class DetailPhase { Idle, Loading, Failed }

/**
 * 右栏（照抄 PC 版 harness）：
 * ```
 *  ┌ 子代理切换器：[主会话][子代理1][子代理2] ┐   ← 对话框上方
 *  ├ 消息流（渐进加载 + 流式渲染）
 *  └ 输入框 + 发送（running 时显示停止）
 * ```
 *
 * 关键行为：
 *  - 选中会话 ⇒ **自动**加载并渐进上屏（先最近 30 条，再后台补窗口）；
 *  - 点发送 ⇒ **立即乐观插入**本地消息 ⇒ 调 `POST /prompt` ⇒ 失败**可见 + 可重试**；
 *  - assistant 流式输出逐帧追加（打字机效果）；
 *  - 切换子代理**复用同一条渲染管线**。
 */
@Composable
internal fun DetailPane(
    session: SessionSummary,
    allSessions: List<SessionSummary>,
    baseUrl: String,
    token: String,
    /** Live events for the open session, published by MainActivity's single WS. */
    liveEvents: List<ServerFrame.SessionEvent> = emptyList(),
    /** Session id the caller wants loaded, or null to load nothing yet. */
    loadRequestKey: String? = null,
    /** Caller-owned "already loaded" guard; survives unmounts. */
    loadedFor: String? = null,
    /** Receives the new guard value after a load starts. */
    onLoaded: (String?) -> Unit = {},
    /**
     * 2026-10-13 search-locate mode: open this seq as the window centre instead
     * of the newest page. Non-null disables tail-following until the user
     * scrolls back to the live end.
     */
    focusSeq: Long? = null,
    /** Clears [focusSeq] after it has been consumed (one-shot navigation). */
    onFocusConsumed: () -> Unit = {},
    /**
     * 2026-10-14 用户需求 2：每个会话（含子代理 tab）独立的未发送草稿。
     * key = 当前展示的会话 id；由 MainActivity 持有，DetailPane 因列表刷新
     * unmount 也不丢。切换会话/tab ⇒ 各自恢复自己的草稿，互不串台。
     */
    drafts: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    /**
     * 2026-10-16「点击会话不是每次都能显示到最新页」：用户**再点一次当前会话**时
     * 调用方把该值 +1 ⇒ 这里强制重新加载最新页（顺带退出搜索定位窗口）。
     */
    newestNonce: Int = 0,
    /**
     * 2026-10-16：发送前回调 —— 让宿主**重发一次 subscribe**，确保网关的 follow
     * 循环活着，否则这次发送触发的新回合事件一个都推不回来（用户报告：发完消息
     * 内容一直不更新，切走再回来才有）。
     */
    onAfterSend: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val api = remember { GatewayApi() }
    val c = DshTheme.colors
    val s = session
    // 本地历史缓存需要的 ApplicationContext（文件 IO 用，不参与重组）。
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext

    // ── 子代理切换器（PC 版：位于对话框上方） ─────────────────
    val tabs = remember(s.id, allSessions) { SubagentTree.tabsFor(s, allSessions) }
    var activeTabId by remember(s.id) { mutableStateOf(s.id) }
    val active = tabs.firstOrNull { it.sessionId == activeTabId } ?: tabs.first()
    val activeSession = allSessions.firstOrNull { it.id == active.sessionId } ?: s
    // 当前真正展示的会话（主会话或某个子代理 tab）。下面所有"按会话隔离"的
    // 状态都必须 key 在它上面，而不是主会话 s.id —— 否则切子代理 tab 时
    // 既不换消息窗口也不加载历史（2026-10-14「部分会话无法加载历史」根因）。
    val displayId = activeSession.id

    // ── 消息窗口（内存有界） ────────────────────────────────
    // 2026-10-14 用户优化 1：300 → 1000。历史要"同步到本地并在横杠里体现"，
    // 300 条装不下几百个回合；1000 条 LazyColumn 仍是懒渲染，内存可控。
    val pager = remember(displayId) { MessagePager(windowSize = 1000) }
    var nextCursor by remember(displayId) { mutableStateOf<Long?>(null) }
    var hasMore by remember(displayId) { mutableStateOf(true) }
    // 本地缓存已覆盖到的最旧 seq（= 后台倒序补齐的终点：补到它就停）
    // 与「缓存是否已到会话开头」。用户优化 1 的"本地最大/最小索引"。
    var cacheOldestSeq by remember(displayId) { mutableStateOf<Long?>(null) }
    var cacheReachedStart by remember(displayId) { mutableStateOf(false) }
    /** 缓存已覆盖的最新 seq（自愈判断用：新端必须与网关最新页相接）。 */
    var cacheNewestSeq by remember(displayId) { mutableStateOf<Long?>(null) }
    /** 本次打开是否真的命中过本地缓存（决定后台补齐的停止条件）。 */
    var restoredFromCacheState by remember(displayId) { mutableStateOf(false) }
    /**
     * 缓存新端与网关最新页之间还有"断档"待补（离线期间落下的消息）。
     * 2026-10-16：断档补齐从 `loadFirst`（同步、阻塞首屏）挪到后台执行，本标志是开关。
     */
    var needGapFill by remember(displayId) { mutableStateOf(false) }
    var loadPhase by remember(displayId) { mutableStateOf(DetailPhase.Idle) }
    var loadError by remember(displayId) { mutableStateOf<String?>(null) }
    val progress = remember(displayId) { ProgressiveLoadState(firstBatch = 30) }
    var visibleCount by remember(displayId) { mutableStateOf(0) }
    // 输入框绑定当前 tab（主会话/子代理各自独立）的草稿：读=草稿表，写=回写。
    // 切走再切回内容仍在；发送成功后清空该会话草稿（见发送按钮处）。
    // 直接读取 SnapshotStateMap（组合内会订阅），写回即触发重组。
    val input = drafts[activeSession.id].orEmpty()
    val onInputChange: (String) -> Unit = { v -> drafts[activeSession.id] = v }
    val send = remember { SendState() }
    var sendPhase by remember(displayId) { mutableStateOf(SendState.Phase.Idle) }
    var sendError by remember(displayId) { mutableStateOf<String?>(null) }
    // listState 也按当前 tab 会话隔离（否则切 tab 保留上个 tab 的滚动位置）。
    val listState = key(displayId) { rememberLazyListState() }

    // ══ UNIFIED SCROLL MECHANISM ══════════════════════════════════════════
    // One rule: ONLY A USER GESTURE stops following the newest message.
    // Data changes (open, switch, live append) can never stop it.
    // Deriving the answer from `layoutInfo` was the structural bug: for one frame
    // after any data change the layout is stale, so it read `false` and silently
    // disabled following on many sessions.
    var userScrolledAway by remember(displayId) { mutableStateOf(false) }
    /**
     * 2026-10-16 用户报告「经常需要手动滑到最新 / 有时候还不更新」：
     * 用户上划看历史后，实时帧其实**一直在追加**（只是不跟随），屏幕看着像"不更新"，
     * 而且没有任何提示。本标志 = "离开底部期间又来了新消息"，用来显示「有新消息 ↓」。
     */
    var pendingNew by remember(displayId) { mutableStateOf(false) }

    /** Bumped after every load so the viewport lands on the newest row. */
    var followNonce by remember(displayId) { mutableStateOf(0) }

    /**
     * Jumps to the newest row. No guards: this is the "open == newest" guarantee.
     *
     * ## Why this retries (user report: "the dialog never shows the newest, I have
     * to scroll down myself")
     * `LazyColumn.scrollToItem(n)` is a **no-op when the list has not laid out yet**:
     * it needs at least one measured frame with the new items. The previous version
     * called it once, right after `followNonce++`, i.e. while `totalItemsCount` was
     * still 0 (or the previous session's count) -- the request was silently dropped
     * and the viewport stayed at the TOP of the transcript.
     *
     * So the target is derived from what the list ACTUALLY contains, and we retry
     * until the layout agrees (bounded: a settled list cannot need more than a
     * couple of frames, and the loop yields between attempts so it never blocks).
     */
    val scrollToNewest: suspend () -> Unit = scrollNew@{
        val target = pager.visible(visibleCount).size - 1
        if (target < 0) {
            // A brand-new session has zero messages. `scrollToItem(-1)` would throw,
            // so this guard is the reason an empty conversation can be opened at all.
            DshTrace.log("  scrollToNewest skipped: empty list")
            return@scrollNew
        }
        DshTrace.log(
            "  scrollToNewest called target=$target layoutItems=${listState.layoutInfo.totalItemsCount} " +
                "visible=$visibleCount retained=${pager.retainedCount} away=$userScrolledAway"
        )
        // Wait for the list to actually contain the target row. Without this the
        // first call lands before composition and is dropped (the reported bug).
        var guard = 0
        while (listState.layoutInfo.totalItemsCount <= target && guard < 60) {
            withFrameNanos { }
            guard++
        }
        // ★ scrollToItem(target) 默认把最新行顶部对齐视口顶部 ⇒ 流式打字机时
        // 最新气泡在视口上沿、气泡内新增长的文字全在视口外（真机：实时更新
        // 屏幕不往下走）。给 Int.MAX_VALUE offset：clamp 后最新行底部贴近视口
        // 底部，始终显示最新内容。
        listState.scrollToItem(target, Int.MAX_VALUE)
        // Second pass: a frame after the scroll the layout may still be settling
        // (items measured lazily / the last bubble grew taller this frame).
        // Re-issue once when the newest row is not actually at the bottom.
        withFrameNanos { }
        val laid = listState.layoutInfo
        val lastVisible = laid.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible < laid.totalItemsCount - 1 && laid.totalItemsCount > target) {
            listState.scrollToItem(target, Int.MAX_VALUE)
        }
        // ★ Read back the REAL result: "we called scrollToItem" is not evidence that
        //   the viewport moved, which is exactly the gap in the previous verdicts.
        DshTrace.log(
            "  scrollToNewest result firstVisible=${listState.firstVisibleItemIndex} " +
                "total=${listState.layoutInfo.totalItemsCount}"
        )
    }

    // Opening / switching: ALWAYS land on the newest row and re-arm following.
    LaunchedEffect(followNonce) {
        if (followNonce == 0) return@LaunchedEffect
        userScrolledAway = false
        scrollToNewest()
    }

    // Switching sub-agent tabs is a PROGRAMMATIC switch: it must land on the newest
    // row and re-arm following, exactly like opening a conversation. It used to do
    // neither, which is the "switching tabs is not at the bottom" report.
    // ⚠ 定位模式例外：从搜索点进来的显示切换（displayId == 命中会话）必须停在
    //   命中处，不能 scrollToNewest。判断依据与上面一致（nextAfter 活着）。
    LaunchedEffect(displayId) {
        DshTrace.log("  switch sid=$displayId away-before=$userScrolledAway")
        userScrolledAway = false
        if (pager.nextAfter == null) {
            followNonce++
            scrollToNewest()
        }
        DshTrace.log("  switch sid=$displayId away-after=$userScrolledAway focusWindow=${pager.nextAfter != null}")
    }

    // Live append / page fill: follow only while the user has not opted out.
    // ⚠ 定位模式（focusSeq 刚消费掉）不算"用户在底部"：不能因为窗口加载完成
    //   就 scrollToNewest 把视口拽到最新端——那会直接跳过命中位置。
    //   pager.nextAfter != null（= 窗口后面还有消息）⇒ 这是定位窗口，禁止跟随。
    // liveFollowTick：打字机 chunk 不改行数，靠它每帧触发跟随（见帧处理处）。
    var liveFollowTick by remember { mutableStateOf(0) }
    LaunchedEffect(visibleCount, pager.retainedCount, liveFollowTick) {
        if (userScrolledAway) return@LaunchedEffect
        if (pager.nextAfter != null) return@LaunchedEffect   // focus window: no tail-follow
        scrollToNewest()
    }

    // The ONLY way to re-enable following is returning to the bottom.
    // Measured one frame later so a stale layout cannot produce a false positive.
    LaunchedEffect(listState, s.id) {
        var wasAtBottom = false
        while (true) {
            withFrameNanos {}
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val atBottom = last >= 0 && last >= info.totalItemsCount - 1
            if (atBottom && !wasAtBottom) {
                if (userScrolledAway) {
                    userScrolledAway = false
                    pendingNew = false          // 已回到最新 ⇒ 未读提示清零
                    DshTrace.log("  back-at-bottom: following resumed")
                }
            }
            wasAtBottom = atBottom
        }
    }




    /**
     * Older history, fetched ONLY when the user scrolls up (P-A).
     *
     * `before` means "older than this seq" in the gateway contract; it is never
     * used to refresh, which is what once made the transcript look stale.
     */
    // ── 首屏：自动加载 + 渐进上屏（先出后补） ─────────────────
    // 2026-10-13 用户改版：加载互斥用**栈语义（LWW）**——
    //   每个 loadFirst 启动时领一个递增代号 loadGen；**只有代号最大的返回**才允许
    //   写 pager/viewport。后启动的请求 = 栈顶，先返回的直接丢弃其结果（数据不丢、
    //   不取消在途请求，谁后来谁先显示）。
    // ⚠ 代号必须**跨会话全局递增**（不能 remember(displayId)）：切 A→B 时若代号
    //   重置，A 的慢响应返回时 r_gen==loadGen==1，会把 A 的数据写进 B 的新 pager
    //   （真机实测：先点"需求:deep"后点"分析项目"，最终内容被前者覆盖）。
    //   DetailPane 卸载时 rememberCoroutineScope 一并取消在途协程，无残留写入。
    var loadGen by remember { mutableStateOf(0L) }
    /**
     * 本地缓存是否**可信**（用户优化 1 的自愈判据）。
     *
     * 可信 = 缓存的新端与网关最新页之间没有"大断档"：正常离线只是落下若干条，
     * 用 `after` 补一下即可；若相差几千条，说明这份缓存是坏的/错位的
     * （例如旧版本留下的"只存了最旧 1000 条"），丢弃重载才不会在窗口里留空洞。
     */
    val isCacheUsable: (List<Message>) -> Boolean = { newestPage ->
        val cacheNewest = cacheNewestSeq
        if (cacheNewest == null || newestPage.isEmpty()) true
        else (newestPage.first().seq - cacheNewest) <= CACHE_GAP_RESET_LIMIT
    }
    val loadFirst: suspend (SessionSummary, Long) -> Unit = load@{ target, r_gen ->
        DshTrace.log("  loadFirst enter sid=${target.id} gen=$r_gen")
        loadPhase = DetailPhase.Loading
        loadError = null
        pendingNew = false          // 重新加载 ⇒ 未读提示清零（否则会残留一个假"有新消息"）
        // ⚠ `before` is deliberately NOT passed: it means "older history".
        // The first screen must always be the NEWEST page (contract verified:
        // limit-only => latest N). Passing a cursor here is what made the UI show
        // older turns (user report #4).
        // P-A: open the session with ONLY the newest page. A 2499-message
        // session must never be pulled whole.
        DshTrace.log("loadFirst limit=$FIRST_PAGE_LIMIT sid=${target.id} focusSeq=$focusSeq")
        // ── 2026-10-14 用户优化 1：本地历史缓存 ─────────────────────────
        // 开屏**先上本地缓存**（无网络、无感知），再让网络只补最新增量；
        // 之后由后台补齐协程从本地最小索引继续往更早倒序同步。
        // 定位模式（focusSeq）不做缓存恢复：那一屏必须精确停在命中处。
        var restoredFromCache = false
        restoredFromCacheState = false          // 重载时先清，避免沿用上次的停止条件
        if (focusSeq == null) {
            val cached = withContext(Dispatchers.IO) {
                MessageCache.load(appContext, target.id)
            }
            if (!cached.isEmpty && r_gen == loadGen) {
                pager.resetFromCache(cached.rows, cached.oldestSeq, cached.reachedStart)
                cacheOldestSeq = cached.oldestSeq
                cacheNewestSeq = cached.newestSeq
                cacheReachedStart = cached.reachedStart
                restoredFromCache = true
                restoredFromCacheState = true
                progress.onFirstBatch(pager.retainedCount)
                visibleCount = progress.visibleCount
                loadPhase = DetailPhase.Idle
                followNonce++
                DshTrace.log(
                    "loadFirst CACHE sid=${target.id} n=${cached.rows.size} " +
                        "oldest=${cached.oldestSeq} newest=${cached.newestSeq} " +
                        "reachedStart=${cached.reachedStart}"
                )
            }
        }
        // ── 搜索定位模式：以命中 seq 为中心开窗（用户需求 4）─────────────
        if (focusSeq != null) {
            when (val r = api.messages(baseUrl, token, target.id, limit = FIRST_PAGE_LIMIT, aroundSeq = focusSeq)) {
                is RestResult.Ok -> {
                    if (r_gen != loadGen) {   // 栈顶易主：本响应作废
                        DshTrace.log("loadFirst STALE (gen=$r_gen < loadGen=$loadGen) aroundSeq branch — dropped")
                        // ⚠ 2026-10-16 必须**照样消费**定位请求：否则 focusRequest 会
                        //   永久留在调用方，之后每一次重载都又走定位分支 ⇒ 视口永远停在
                        //   命中处、实时帧永远被丢弃（真机现象："跳不到最新页，一直在从
                        //   旧的加载"）。
                        onFocusConsumed()
                        return@load
                    }
                    pager.resetAround(r.value.items, r.value.prevCursor, r.value.nextAfter, r.value.hasNewer)
                    nextCursor = r.value.prevCursor
                    hasMore = r.value.prevCursor != null
                    loadPhase = DetailPhase.Idle
                    visibleCount = pager.retainedCount   // 定位模式一次给全窗口，无需渐进
                    // ★ 同步渐进计数器：否则 progress.visibleCount 停在 0，第一条
                    //   实时帧 onLiveAppend 会把它顶成 1，LazyColumn 瞬间只剩 1 行。
                    progress.resync(pager.retainedCount)
                    // 2026-10-13 修复「命中后右侧只剩一条 tool」：这里**不能** bump
                    // followNonce —— 它会触发 scrollToNewest 把视口拽离命中位置；
                    // 定位窗口的正确视口由下面的 scrollToItem(idx) 负责。
                    scope.launch {
                        val idx = (pager.visible(visibleCount).size / 2) - 1
                        if (idx > 0) listState.scrollToItem(idx)
                        DshTrace.log("focusSeq land seq=$focusSeq idx=$idx retained=${pager.retainedCount} hasNewer=${r.value.hasNewer}")
                    }
                    onFocusConsumed()
                }
                is RestResult.Err -> {
                    if (r_gen == loadGen) {
                        loadPhase = DetailPhase.Failed
                        loadError = "${r.error.code}：${r.error.message}"
                    }
                    onFocusConsumed()
                }
            }
            return@load
        }
        when (val r = api.messages(baseUrl, token, target.id, limit = FIRST_PAGE_LIMIT)) {
            is RestResult.Ok -> {
                if (r_gen != loadGen) {       // 栈顶易主：本响应作废（后来者先显示）
                    DshTrace.log("loadFirst STALE (gen=$r_gen < loadGen=$loadGen) normal branch — dropped")
                    return@load
                }
                // 定位模式：normal 路径的结果也允许带窗口语义（竞态兜底）。
                if (focusSeq != null && r.value.nextAfter != null) {
                    pager.resetAround(r.value.items, r.value.prevCursor, r.value.nextAfter, r.value.hasNewer)
                    nextCursor = r.value.prevCursor
                    hasMore = r.value.prevCursor != null
                } else if (restoredFromCache && isCacheUsable(r.value.items)) {
                    // ★ 用户优化 1：已有本地缓存 ⇒ 网络只做**增量对账**（并入最新页），
                    //   绝不清空/重排已显示的历史。
                    // ⚠ 2026-10-16 用户报告「跳新会话要卡半天才到最新页」根因之一：
                    //   断档补齐（`after=` 最多 12 页 × 200 条）原本**串行跑在这里**，
                    //   把首屏活活拖住（真机见过 gapFilled=429）。现在只并最新页，
                    //   断档交给后台补齐（见 backfill effect 的 gap-fill 段）。
                    val added = pager.mergeNewer(r.value.items)
                    needGapFill = cacheNewestSeq != null
                    if (cacheReachedStart) {
                        hasMore = false
                    } else {
                        nextCursor = cacheOldestSeq
                        hasMore = cacheOldestSeq != null
                    }
                    DshTrace.log(
                        "loadFirst MERGE-NEWER sid=${target.id} added=$added " +
                            "oldest=$cacheOldestSeq reachedStart=$cacheReachedStart gapPending=$needGapFill"
                    )
                } else {
                    pager.reset(r.value.items, r.value.nextCursor)
                    nextCursor = r.value.nextCursor
                    hasMore = r.value.nextCursor != null
                    cacheOldestSeq = pager.oldestDurableSeq
                    cacheNewestSeq = pager.newestDurableSeq
                    cacheReachedStart = r.value.nextCursor == null
                }
                // Progressive: 有缓存时**保持整窗可见**（缓存早已上屏，缩回 30 条
                // 纯属倒退，还会造成一次可感知的重排）；只有"纯网络首屏"才先渲染 30 条。
                if (restoredFromCache) progress.resync(pager.retainedCount)
                else progress.onFirstBatch(pager.retainedCount)
                visibleCount = progress.visibleCount
                loadPhase = DetailPhase.Idle
                // Jump to the very bottom AFTER the items exist. LazyColumn needs a
                // frame to measure, so the scroll is requested via the effect below
                // (scrolling to item 29 here would leave the viewport parked on the
                // OLDEST 30 of the newest 100 — exactly "showing a few turns back").
                // Opening a session ALWAYS shows the newest page: bump the nonce
                // and let the follow effect jump there without any guard.
                followNonce++
                val vis = pager.visible(visibleCount)
                DshTrace.log("loadFirst done firstSeq=${vis.firstOrNull()?.seq} lastSeq=${vis.lastOrNull()?.seq} count=${vis.size} retained=${pager.retainedCount}")
            }
            is RestResult.Err -> {
                if (r_gen == loadGen) {
                    loadPhase = DetailPhase.Failed
                    loadError = "${r.error.code}：${r.error.message}"
                }
            }
        }
    }

    // ⚠ A3 (2026-10-12): exactly ONE load per session.
    //
    // The guard used to live in `remember(s.id)`, i.e. INSIDE this composable. That
    // state is destroyed whenever DetailPane leaves composition -- and it does,
    // because MainActivity renders it only while `selected != null`, and `selected`
    // is derived from `allSessions`, which is rebuilt on every session-list refresh.
    // The effect then ran again for the SAME session: observed 14 identical
    // `GET /messages` calls, each resetting the viewport, so the transcript stayed
    // parked on an old checkpoint instead of the newest message.
    //
    // The guard therefore lives in the caller (MainActivity) and survives
    // unmounts; the pane only reacts to an explicit request (e.g. "retry").
    // 加载入口（栈语义）：启动即领新代号，成为栈顶；不做取消 —— 慢的旧响应
    // 返回时因代号过期被丢弃。谁后来谁先显示。
    fun launchLoad(target: SessionSummary) {
        loadGen++
        val gen = loadGen
        DshTrace.log("launchLoad gen=$gen sid=${target.id}")
        scope.launch { loadFirst(target, gen) }
    }

    LaunchedEffect(loadRequestKey) {
        if (loadRequestKey == null || loadRequestKey == loadedFor) return@LaunchedEffect
        onLoaded(loadRequestKey)
        com.deepseek.dshremote.core.DshTrace.log("loadFirst for=$loadRequestKey")
        // ★ The target must be the session actually on screen. The old code fell back
        //   to the OUTER session `s` whenever the ids disagreed for one composition
        //   tick, which rendered the PREVIOUS conversation under the new title
        //   (user report: "shows the previous conversation").
        val target = allSessions.firstOrNull { it.id == loadRequestKey }
            ?: activeSession.takeIf { it.id == loadRequestKey }
            ?: s
        DshTrace.log(
            "loadFirst for=$loadRequestKey target=${target.id} " +
                "session=${s.id} active=${activeSession.id}"
        )
        launchLoad(target)
    }

    // ── 2026-10-14「部分会话无法加载历史」根因修复 ──────────────────
    // 切到子代理 tab 时 activeSession 变了，但旧代码 pager 按主会话 s.id 记忆、
    // loadRequestKey 也只传主会话 id ⇒ 子代理窗口永远不加载自己的历史。
    // 现 pager 已按 displayId 隔离；这里在 displayId 变化（点 tab / 切回）时
    // 加载该会话历史。首帧主会话由上面的 loadRequestKey 负责，跳过避免重复。
    val tabFirst = remember(s.id) { mutableStateOf(true) }
    LaunchedEffect(displayId) {
        if (tabFirst.value) { tabFirst.value = false; return@LaunchedEffect }
        com.deepseek.dshremote.core.DshTrace.log("tab-switch load sid=$displayId")
        launchLoad(activeSession)
    }

    // A fresh nonce forces a reload (used by the explicit "retry" button).
    var reloadNonce by remember { mutableStateOf(0) }
    val reload = { reloadNonce++ }
    LaunchedEffect(reloadNonce) {
        if (reloadNonce > 0) {
            onLoaded(null)          // clear the caller-owned guard
            com.deepseek.dshremote.core.DshTrace.log("loadFirst retry sid=${activeSession.id}")
            launchLoad(activeSession)
        }
    }

    // ── 2026-10-16「点击会话不是每次都能显示到最新页」修复 ────────────────
    // 用户**再点一次当前会话**（selectedId 不变 ⇒ 正常路径不会重载）时，
    // 强制重新加载最新页：既回到最新，又顺带退出搜索定位窗口（nextAfter 归 null，
    // 实时帧才会重新被接收）。
    LaunchedEffect(newestNonce) {
        if (newestNonce == 0) return@LaunchedEffect
        userScrolledAway = false
        pendingNew = false
        DshTrace.log("newest-nonce -> reload newest sid=${activeSession.id}")
        onLoaded(null)
        launchLoad(activeSession)
    }

    /**
     * 点「有新消息 ↓」时**不直接在点击回调里重载**：那一帧读到的 `focusSeq` 还是旧值
     * （状态更新尚未重组），会再次开成定位窗口 ⇒ 帧继续被丢弃（真机实测踩到）。
     * 改成 nonce：本 effect 在重组之后运行，读到的参数才是最新的（focusSeq 已为 null）。
     */
    var forceNewestNonce by remember(displayId) { mutableStateOf(0) }
    LaunchedEffect(forceNewestNonce) {
        if (forceNewestNonce == 0) return@LaunchedEffect
        userScrolledAway = false
        pendingNew = false
        onLoaded(null)
        DshTrace.log("force-newest reload focusSeq=$focusSeq sid=${activeSession.id}")
        launchLoad(activeSession)
    }

    // ── 2026-10-14 用户报告：长时间不进应用，回来后当前会话不显示最新，必须
    // 切走再切回才刷新 ──────────────────────────────────────────────
    // 根因：后台期间 WS 断开，错过的实时帧在重连后是"旧 seq"，被 replay 守卫
    // 丢弃；而当前会话未变 ⇒ loadRequestKey/focusSeq/reloadNonce 三个触发键都
    // 不动 ⇒ 不重拉首屏。修：应用从后台回前台（ON_RESUME 且此前 ON_STOP 过）
    // 时，对当前会话静默补拉一次最新页（走栈语义，成为栈顶）。冷启动首次
    // resume 不触发（首屏 loadRequestKey 已负责）。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumeRefreshNonce by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        var backgrounded = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                // 用 ON_PAUSE 而非 ON_STOP：部分机型/窗口模式只到 PAUSE，长时间
                // 离开应用一定会先 PAUSE；冷启动首次 resume 前没有 pause，安全。
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> backgrounded = true
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    // 只在"真的离开过"后计数；冷启动首次 resume 不触发。
                    if (backgrounded) {
                        backgrounded = false
                        resumeRefreshNonce++
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumeRefreshNonce) {
        if (resumeRefreshNonce > 0) {
            com.deepseek.dshremote.core.DshTrace.log(
                "resume-refresh sid=${activeSession.id}"
            )
            onLoaded(null)
            launchLoad(activeSession)
        }
    }

    // ── 2026-10-13 用户报告：「后续命中会话后右侧无法展示」根因修复 ─────────
    // 场景：已在会话 A 内（loadRequestKey == loadedFor），再从搜索点 A 的另一条
    // 命中 ⇒ focusSeq 变了，但 LaunchedEffect(loadRequestKey) 因 guard 相等不重跑
    // ⇒ 窗口不刷新，画面停在旧位置（真机 23:29 截图证实停留在旧内容）。
    // 修复：显式盯着 focusSeq —— 只要它非 null 就强制走一遍定位加载
    // （绕过 loadedFor guard，用完清掉）。
    LaunchedEffect(focusSeq) {
        if (focusSeq == null) return@LaunchedEffect
        com.deepseek.dshremote.core.DshTrace.log("focusSeq effect fired seq=$focusSeq sid=${activeSession.id}")
        onLoaded(null)          // force the caller guard open so later loads still work
        launchLoad(activeSession)
    }


    /**
     * Keep the viewport pinned to the newest message.
     *
     * Runs after both the first batch AND the background fill, so the final resting
     * position is the true end of the transcript (problem #4).
     */
    // DSH behaviour: follow the tail while the reader is at the bottom, and stop
    // following the moment they scroll up to read history. A one-shot flag cannot
    // express this -- it is consumed by the first fill and then new messages
    // would either snap the viewport or leave it stale.
    val loadMore: suspend () -> Unit = load@{
        val cursor = nextCursor
        if (cursor == null || !hasMore) return@load
        // 本地历史已达上限 ⇒ 不再往更早拉（窗口 = 最新 N 条，内存有界）。
        if (pager.retainedCount >= pager.historyCap) return@load
        when (val r = api.messages(baseUrl, token, activeSession.id, limit = FIRST_PAGE_LIMIT, before = cursor)) {
            is RestResult.Ok -> {
                val added = pager.prepend(r.value.items, r.value.nextCursor)
                nextCursor = r.value.nextCursor
                hasMore = r.value.nextCursor != null
                // ★★ 2026-10-14 Bug1 真正根因修复：「右侧消息窗无法上拉」──
                // pager.visible(n) = rows.takeLast(n)，补进来的**更早**的行在
                // takeLast 之外 ⇒ 不把 visibleCount 提到新窗口长度，用户上拉
                // 拉回来的历史**永远不会渲染**（视口什么都不变，看着就是"拉不动"）。
                visibleCount = pager.retainedCount
                DshTrace.log("loadMore before=$cursor added=$added vis=$visibleCount sid=${activeSession.id}")
            }
            is RestResult.Err -> loadError = "${r.error.code}：${r.error.message}"
        }
    }

    // 2026-10-13 用户要求（v2）：首屏 80 条 ⇒ 视口静态时 firstVisibleItemIndex
    // 常驻 0：**一进会话就会触发 loadMore**，必须节流。in-flight 互斥 + 完成后
    // 350ms 冷却；预取在距顶 3 条（上划 3 条）时启动。
    var loadMoreBusy by remember(displayId) { mutableStateOf(false) }
    val loadMoreThrottled: () -> Unit = loadMoreRun@{
        if (loadMoreBusy) return@loadMoreRun
        // 首屏数据未到（cursor=null）时不占锁：否则会白白锁住 350ms，用户
        // 上划到顶的首个真实预取请求可能正好撞上锁。
        if (nextCursor == null) return@loadMoreRun
        loadMoreBusy = true
        scope.launch {
            try { loadMore() } finally {
                kotlinx.coroutines.delay(350)
                loadMoreBusy = false
            }
        }
    }

    // Trigger the older page when the viewport reaches the top of the loaded window.
    // 2026-10-13 (user request v2): first page is 80 rows; prefetch starts when the
    // user has scrolled up just 3 items from the top -- by the time they reach the
    // top the older page is usually already on screen.
    LaunchedEffect(listState.firstVisibleItemIndex) {
        // 距顶 ≤3 行 ⇒ 立即预取上一页（含静止在顶部的常驻态）。
        if (hasMore && listState.firstVisibleItemIndex <= 3) loadMoreThrottled()
        // 定位模式（focusSeq）：滚动到窗口顶部时同样向上补页；到达窗口底部且
        // 还有更新的消息时向下增量补页。
        if (pager.nextAfter != null) {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (last >= info.totalItemsCount - 3) {
                val na = pager.nextAfter
                if (na != null) {
                    when (val r = api.messages(baseUrl, token, activeSession.id, limit = FIRST_PAGE_LIMIT, after = na)) {
                        is RestResult.Ok -> {
                            pager.appendPage(r.value.items, r.value.nextAfter, r.value.hasNewer)
                            DshTrace.log("appendPage after=$na got=${r.value.items.size} nextAfter=${r.value.nextAfter}")
                        }
                        is RestResult.Err -> Unit
                    }
                }
            }
        }
    }

    // 后台补齐窗口（不阻塞 UI）
    LaunchedEffect(s.id, progress.firstScreenReady) {
        if (progress.firstScreenReady && !progress.backgroundDone) {
            progress.onBackgroundFilled(pager.retainedCount)
            visibleCount = progress.visibleCount
        }
    }

    // ── 2026-10-14 用户优化 1：后台**倒序**把历史同步到本地 ──────────────
    // 需求：「先展示最新的，然后动态同步历史数据到手机本地最大值为止」。
    // 首屏上屏后，静默地一页页往更早拉：直到（a）追上本地缓存已覆盖的最旧
    // seq（说明这次只是"补最新增量"，历史早就在本地了），或（b）真的拉到会话
    // 开头。全程不打断用户阅读（不动视口、不显示 loading）。
    // 每拉一页就落盘一次，所以下次冷启动能直接命中更多历史。
    /** 把当前窗口落盘（IO 线程）。oldest/newest 索引一起存，供下次续传。 */
    val persistCache: suspend () -> Unit = {
        val rows = pager.rows
        if (rows.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                MessageCache.save(
                    appContext, activeSession.id, rows,
                    pager.oldestDurableSeq, pager.newestDurableSeq, cacheReachedStart,
                )
            }
        }
    }
    // 每补一页 tick 一次 ⇒ 让下面的补齐协程能跨轮继续（不能只靠 retainedCount，
    // 窗口满 1000 后它就不变了，补齐会中途停住）。
    var backfillTick by remember(displayId) { mutableStateOf(0) }
    // ⚠ key 里必须带 cacheReachedStart：缓存自愈会把它 true→false，若不在 key 里，
    //   补齐协程不会重跑 ⇒ 历史永远补不下来（实测踩到）。
    LaunchedEffect(displayId, loadPhase, backfillTick, cacheReachedStart) {
        if (loadPhase != DetailPhase.Idle) return@LaunchedEffect
        if (focusSeq != null) return@LaunchedEffect          // 定位窗口不做补齐
        if (cacheReachedStart) return@LaunchedEffect
        // ── ① 断档补齐（**后台**，2026-10-16 从 loadFirst 挪出来）────────────
        // 缓存新端 → 网关最新之间落下的消息。原先它串行跑在首屏路径里，把"跳到
        // 最新页"活活拖慢；现在放在首屏之后静默补，用户无感。
        if (needGapFill) {
            needGapFill = false
            var cur: Long? = cacheNewestSeq
            var guard = 0
            while (cur != null && guard++ < 6) {
                val g = api.messages(
                    baseUrl, token, activeSession.id,
                    limit = BACKFILL_PAGE_LIMIT, after = cur,
                )
                if (g is RestResult.Err) break
                val gp = (g as RestResult.Ok).value
                if (gp.items.isEmpty()) break
                val added = pager.mergeNewer(gp.items)
                DshTrace.log("gap-fill after=$cur added=$added nextAfter=${gp.nextAfter}")
                if (added > 0 && !userScrolledAway) followNonce++
                cur = gp.nextAfter
                if (gp.hasNewer != true) break
                if (added == 0) break
            }
            cacheNewestSeq = pager.newestDurableSeq
        }
        // 每次进入最多补 8 页（limit=200 ⇒ 最多 1600 条），补完 tick 再触发下一轮，
        // 避免一口气把网关和磁盘打满，同时保证「动态同步」持续推进。
        var rounds = 0
        while (rounds++ < 8) {
            // 本地历史已达上限 ⇒ 停止倒序同步（内存/磁盘都有界）。
            if (pager.retainedCount >= pager.historyCap) {
                DshTrace.log("backfill STOP cap=${pager.historyCap}")
                break
            }
            val cursor = nextCursor ?: break
            // 已追平「上次落盘的最深处」⇒ 缺口已补完（历史都在本地），停止。
            // ⚠ 只在**真的从缓存恢复过**时才有意义；首次打开 cacheOldestSeq 为 null，
            //   此时应一路补到会话开头。
            val stopAt = if (restoredFromCacheState) cacheOldestSeq else null
            if (stopAt != null && pager.oldestDurableSeq != null &&
                pager.oldestDurableSeq!! <= stopAt
            ) {
                DshTrace.log("backfill STOP reached persisted oldest=$stopAt")
                break
            }
            val r = api.messages(baseUrl, token, activeSession.id, limit = BACKFILL_PAGE_LIMIT, before = cursor)
            if (r is RestResult.Err) break
            val page = (r as RestResult.Ok).value
            if (page.items.isEmpty()) { cacheReachedStart = page.nextCursor == null; break }
            val added = pager.prepend(page.items, page.nextCursor)
            nextCursor = page.nextCursor
            hasMore = page.nextCursor != null
            visibleCount = pager.retainedCount          // ★ 让补进来的历史真的渲染
            if (page.nextCursor == null) cacheReachedStart = true
            DshTrace.log(
                "backfill before=$cursor added=$added next=${page.nextCursor} " +
                    "oldest=${pager.oldestDurableSeq} vis=$visibleCount"
            )
            // ⚠ 不再每页落盘：整窗序列化（可达数 MB）会把 IO 打满、拖慢后续补页。
            //   落盘交给下面那个 1.5s 防抖 effect，本批补完再落一次。
            backfillTick++
            // 补齐期间保持"贴底"：窗口在**上方**变长，视口若不重新贴底就可能停在
            // 中途（表现为"点开会话不是最新页，要手动滑"）。用户主动离开时不打扰。
            if (!userScrolledAway) followNonce++
            if (added == 0 && page.nextCursor == null) break
        }
        // 本批补齐结束：落盘一次（批内不再逐页写）。
        persistCache()
    }

    // 缓存落盘（**防抖**）：key 一变就重启本协程 ⇒ 流式打字机期间不断被取消，
    // 只有静下来 1.5s 才真正写一次盘。否则每帧重写整个文件（几 MB）会拖垮 IO。
    LaunchedEffect(displayId, pager.retainedCount, cacheReachedStart, cacheOldestSeq) {
        if (pager.retainedCount == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        persistCache()
    }

    // Re-subscribe when the user switches to a different sub-agent tab.
    LaunchedEffect(activeSession.id) {
        // The transport effect below keys on `activeSession.id`, so switching tabs
        // reconnects and re-subscribes to the newly selected session.
    }

    // ---- Live stream (typewriter effect) -----------------------------
    // C4 (2026-10-12): this pane no longer owns a WebSocket. MainActivity keeps
    // ONE app-wide socket subscribed to the session on screen and republishes the
    // frames here, so the subscription exists from cold start (previously the
    // subscribe frame only existed while this pane was composed, which is why the
    // gateway counter showed "connect but subscribe=0").
    LaunchedEffect(liveEvents) {
        for (f in liveEvents) {
            if (f.kind != "message") continue
            // 定位模式：窗口后面还有未加载的消息（nextAfter != null）时，实时帧
            // 不能直接 appendLive——那会把 seq 2000+ 的新消息接到 seq 4900 窗口
            // 尾上（真机 22:41 实测乱序）。丢帧；等用户滑到底触发 appendPage
            // 把缺口补齐后，nextAfter 归 null，实时流自然恢复。
            if (pager.nextAfter != null) {
                DshTrace.log("  appendLive DROPPED (focus window) sid=${f.sessionId} seq=${f.seq}")
                // ★ 2026-10-16「实时输出的会话更跟不上、跳不到最新页」：
                //   搜索定位窗口下帧被**按设计丢弃**，但 `userScrolledAway` 是 false
                //   ⇒ 连「有新消息 ↓」都不显示，看着就是"一直在从旧的加载"。
                //   这里补上未读标记：让用户一眼看到并且一键回到最新页。
                pendingNew = true
                continue
            }
            val status = f.status ?: "final"
            val seq = f.seq ?: 0L
            // 2026-10-13 真机实测：网关订阅回放的历史事件（seq < 窗口尾）会被
            // pager.appendLive 的序守卫丢弃；此时**不能**再调 onLiveAppend 顶
            // visibleCount（否则 LazyColumn 从 8 行缩成 1 行）。
            val accepted: Boolean
            when (status) {
                "streaming" -> {
                    val streamed = pager.appendStreamingChunk(f.text.orEmpty(), seq, f.ts ?: System.currentTimeMillis())
                    accepted = streamed != null
                }
                "final" -> {
                    if (f.role == "assistant" && pager.finishStreaming(f.text)) {
                        accepted = true
                    } else {
                        accepted = pager.appendLive(
                            Message(
                                seq = seq, role = f.role ?: "assistant",
                                text = f.text.orEmpty(),
                                ts = f.ts ?: System.currentTimeMillis(),
                                status = status, messageId = f.messageId,
                            )
                        )
                    }
                }
                else -> accepted = false
            }
            DshTrace.log("  appendLive ${if (accepted) "sid=${f.sessionId} seq=${f.seq} pos=last" else "REJECTED (replay) seq=${f.seq} sid=${f.sessionId}"}")
            if (accepted) {
                progress.onLiveAppend(pager.retainedCount)
                visibleCount = progress.visibleCount
                // ★ 打字机修复：streaming chunk 更新的是**同一个气泡行**，行数
                // （retained/visibleCount）不变 ⇒ 下面那个跟随 effect 不会重跑 ⇒
                // 气泡逐字长高时屏幕不往下跟。用独立 tick 每帧触发一次跟随
                // （同帧多次自增被 Compose 合并，天然每帧最多滚一次）。
                liveFollowTick++
                // 用户已离开底部 ⇒ 记一笔"有新消息"，让界面能显示「有新消息 ↓」。
                if (userScrolledAway) pendingNew = true
            }
        }
    }


    val running = StopButtonPolicy.isRunning(activeSession)

    // ── 阶段跳转锚点（用户优化 1）：每个 assistant 回合行一个锚点 ─────
    // 2026-10-14 Bug 修复：「横杠只有 2 条，实际几百条」——锚点原先只从
    // `visible(visibleCount)`（渐进首屏 30 条）里取，窗口一满就只有一两个。
    // 改为从 **pager.rows 全量窗口**（最多 300 条）取，配合本地历史缓存，
    // 横杠就能覆盖整段会话。
    // currentStageIndex：视口最后可见行之前最近的一个锚点（高亮用）。
    // 索引空间统一用 pager.rows 的下标；跳转时再换算到 visible 列表下标。
    val allRows = pager.rows
    val stageAnchors: List<Int> = remember(allRows) {
        allRows.withIndex().filter { it.value.role == "assistant" && !it.value.isStreaming }
            .map { it.index }
    }
    /** pager.rows 下标 → 当前 visible 列表下标（visible 是 rows 的后缀）。 */
    fun toVisibleIndex(rowsIndex: Int): Int = rowsIndex - (allRows.size - pager.visible(visibleCount).size)
    val currentStageIndex: Int by remember { derivedStateOf {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val lastVisibleRowsIdx = lastVisible + (allRows.size - pager.visible(visibleCount).size)
        stageAnchors.lastOrNull { it <= lastVisibleRowsIdx } ?: (stageAnchors.firstOrNull() ?: 0)
    } }

    // ---- requirement 3: collapse/expand state for reasoning & tool blocks ----
    val sessionKey = activeSession.id
    val expandStore = remember(sessionKey) { ExpandStateStore() }
    /** Called when the user opens a block whose content was truncated. */
    var onRequestFull by remember(sessionKey) { mutableStateOf<(Long) -> Unit>({}) }

    Column(Modifier.fillMaxSize()) {
        // ── 子代理切换器（PC 版：对话框上方） ───────────────────
        if (tabs.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.sidebarBackground)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (t in tabs) {
                    val sel = t.sessionId == active.sessionId
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (sel) c.onSelectedText else c.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(DshTheme.shapes.cornerMedium.dp))
                            .background(if (sel) c.selectedBackground else Color.Transparent)
                            .clickable { activeTabId = t.sessionId }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        maxLines = 1,
                    )
                }
            }
            HorizontalDivider(color = c.divider, thickness = 1.dp)
        }

        // ── 消息流（渐进渲染，先出后补） ──────────────────────
        // A DRAG is the only opt-out from following the newest message.
        // Data changes never disable it.
        // 2026-10-13 修复「会话窗口只显示一半」：阶段小横线曾是 LazyColumn 的
        // 同级节点、固定占 252dp 高，把消息流压到半屏。现在二者同处一个
        // weight(1f) Box，消息流铺满，小横线以 overlay 悬浮右缘，不占布局。
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                // ── 2026-10-14 Bug 修复：「右侧消息窗无法上拉」────────────────
                // 根因：旧实现用 `changed.positionChanged()` 判断位移，而该方法在
                // 事件**已被消费**时返回 false —— LazyColumn 自己的滚动手势在
                // Main 阶段就把拖动消费掉了 ⇒ 我们的标记永远不触发 ⇒
                // userScrolledAway 一直 false ⇒ 会话在跑时每个流式帧都
                // scrollToNewest 把视口拽回底部，用户上划被弹回，看起来"拉不动"。
                // 修法：在 **Initial 阶段**（消费之前）读原始坐标并自己累计位移，
                // 完全绕开 isConsumed 的影响。
                .pointerInput(displayId) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        var lastY = down.position.y
                        var dyAccum = 0f
                        var awayMarked = false
                        // 手势开始时的首个可见行：只有**列表真的动了**才算用户翻历史。
                        // （否则在内容不足一屏/只是手指抖动时，一过阈值就静默停止跟随，
                        //   表现为"点开会话后经常停在半路、要手动滑到最新"。）
                        val startIdx = listState.firstVisibleItemIndex
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull { it.pressed }
                                ?: break
                            if (!change.pressed) break
                            dyAccum += change.position.y - lastY
                            lastY = change.position.y
                            // 上划累计超过阈值 ⇒ 立刻标记"用户在翻历史"，滚动期间
                            // 不再被实时帧拽回底部（不必等抬手，抬手时视口已被拽走）。
                            if (!awayMarked && dyAccum < -28f) {
                                val moved = listState.firstVisibleItemIndex != startIdx ||
                                    listState.canScrollBackward
                                if (moved) {
                                    awayMarked = true
                                    userScrolledAway = true
                                    DshTrace.log(
                                        "  userScrolledAway -> true reason=drag-up " +
                                            "dy=$dyAccum first=${listState.firstVisibleItemIndex}"
                                    )
                                }
                            }
                        }
                        // 抬手兜底：只有**列表真的被滚动了**才算用户离开底部。
                        // ⚠ 旧条件 `firstVisibleItemIndex < last` 是错的：内容不足一屏时
                        //   首行恒为 0、last 是"最后一行下标"(如 29) ⇒ **随便点一下消息区**
                        //   就会静默关闭跟随（真机现象：经常要手动滑到最新 / 看着不更新）。
                        if (!awayMarked && listState.firstVisibleItemIndex != startIdx) {
                            userScrolledAway = true
                            DshTrace.log(
                                "  userScrolledAway -> true reason=gesture " +
                                    "from=$startIdx to=${listState.firstVisibleItemIndex}"
                            )
                        }
                    }
                },
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pager.visible(visibleCount), key = { "${it.role}-${it.seq}" }) { row ->
                MessageBubble(row, c, sessionKey, expandStore, onRequestFull)
            }
        }

        // ── 阶段跳转小横线（2026-10-13 用户优化 1，DeepSeek 式）──────────
        // 每条 assistant 回合行 = 一个阶段锚点；右缘**悬浮**竖排（overlay，不占
        // 消息流布局），点击跳转到该回合。当前视口所在阶段高亮。锚点多于一屏时
        // 横线列自身可上下滚动。
        if (stageAnchors.isNotEmpty()) {
            StageJumper(
                anchors = stageAnchors,
                currentIndex = currentStageIndex,
                onJump = { anchorIdx ->
                    scope.launch {
                        // anchorIdx 是 pager.rows 的下标；LazyColumn 只渲染 visible 后缀。
                        // 若目标还没进入可见窗口，先滚到窗口顶触发上滑预取，再逐帧逼近。
                        var guard = 0
                        while (guard++ < 40) {
                            val vis = pager.visible(visibleCount)
                            val target = toVisibleIndex(anchorIdx)
                            if (target in 0 until vis.size) {
                                listState.scrollToItem(target)
                                DshTrace.log("stage jump -> rowsIdx=$anchorIdx visIdx=$target")
                                break
                            }
                            // 目标在可见窗口之外（更早）：滚到顶部触发 loadMore，等窗口变大
                            listState.scrollToItem(0)
                            withFrameNanos { }
                            if (hasMore) {
                                val cursor = nextCursor ?: break
                                val r = api.messages(
                                    baseUrl, token, activeSession.id,
                                    limit = FIRST_PAGE_LIMIT, before = cursor,
                                )
                                if (r is RestResult.Ok) {
                                    pager.prepend(r.value.items, r.value.nextCursor)
                                    nextCursor = r.value.nextCursor
                                    hasMore = r.value.nextCursor != null
                                    visibleCount = pager.retainedCount
                                } else break
                            } else break
                        }
                    }
                },
            )
        }
        // ── 2026-10-16「有时候还不更新」的可见化 + 一键修复 ───────────────
        // 两种情形都要提示：①用户上划看历史；②搜索定位窗口（帧被按设计丢弃）。
        // 此前后者没有任何提示，实时输出的会话看起来就是"永远停在旧内容"。
        if (pendingNew) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.selectedBackground)
                    .clickable {
                        pendingNew = false
                        userScrolledAway = false
                        // 定位窗口要真正回到最新页：**先清掉调用方的定位请求**，再用 nonce
                        // 触发重载（点击这一帧读到的 focusSeq 还是旧值，直接 launchLoad 会
                        // 又开成定位窗口 —— 真机实测踩到）。
                        if (pager.nextAfter != null) {
                            onFocusConsumed()
                            forceNewestNonce++
                        } else {
                            followNonce++
                        }
                        DshTrace.log("  jump-to-newest tapped focus=${pager.nextAfter != null}")
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    "有新消息 ↓",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.onSelectedText,
                )
            }
        }
        } // ── 消息区 Box（LazyColumn + 小横线 overlay）──

        // ── 反馈区：加载中 / 加载失败 / 发送失败 ───────────────
        if (loadPhase == DetailPhase.Loading) {
            Box(Modifier.fillMaxWidth().padding(6.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        if (loadPhase == DetailPhase.Failed) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    loadError ?: "加载失败", color = c.errorText,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { reload() }) { Text("重试") }
            }
        }
        if (sendPhase == SendState.Phase.Failed) {
            // 三种失败分别提示（依据 error.code + 目标会话是否为子代理）：
            //  1) 子代理只读  → 禁用输入框，**不提供重试**
            //  2) PC 端占用    → 明确不是网络故障，保留重试
            //  3) 其它真失败   → 红条 + 重试（复用同一 requestId）
            val f = send.failure ?: SendFailure.Other
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    f.userMessage,
                    color = if (f == SendFailure.SubagentReadOnly) c.textSecondary else c.errorText,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                if (f.retryable) {
                    TextButton(onClick = {
                        val rid = send.retryRequestId() ?: UUID.randomUUID().toString()
                        scope.launch {
                            when (val r = api.prompt(baseUrl, token, activeSession.id, send.pendingText.orEmpty(), rid)) {
                                is RestResult.Ok -> {
                                    send.success(); pager.markOptimisticSent(rid)
                                    sendPhase = SendState.Phase.Sent; sendError = null
                                }
                                is RestResult.Err -> {
                                    val nf = SendFailure.classify(r.error.code, activeSession)
                                    send.fail(nf); sendPhase = SendState.Phase.Failed; sendError = nf.userMessage
                                }
                            }
                        }
                    }) { Text("重试") }
                }
            }
        }

        // ── 输入区（2026-10-13 用户要求 4：DeepSeek 式）────────────────
        // 一个圆角容器装输入框 + 圆形发送按钮；"停止"作为容器内左侧小按钮。
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(c.sidebarHeaderBackground)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    TextButton(onClick = { scope.launch { api.cancel(baseUrl, token, activeSession.id) } }) {
                        Text("停止", style = MaterialTheme.typography.labelMedium)
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (!send.inputDisabled) onInputChange(it) },
                    enabled = !send.inputDisabled,          // 子代理只读 ⇒ 禁用输入
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (send.inputDisabled) "子代理会话只读" else "发送消息",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                    ),
                    maxLines = 4,
                )
                // 圆形发送按钮（禁用/发送中变灰）。
                val sendEnabled = !send.inputDisabled && input.isNotBlank() && sendPhase != SendState.Phase.Sending
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                sendEnabled -> c.selectedBackground
                                else -> c.dotIdle.copy(alpha = 0.24f)
                            }
                        )
                        .clickable(enabled = sendEnabled) {
                            val text = input.trim()
                            if (text.isEmpty() || sendPhase == SendState.Phase.Sending) return@clickable
                            val rid = UUID.randomUUID().toString()
                            // ① optimistic insert: show it right away, before the network answers
                            pager.appendOptimistic(text, rid, System.currentTimeMillis())
                            progress.onLiveAppend(pager.retainedCount)
                            visibleCount = progress.visibleCount
                            send.begin(rid, text)
                            sendPhase = SendState.Phase.Sending
                            sendError = null
                            drafts.remove(activeSession.id)   // 清该会话草稿
                            scope.launch { listState.scrollToItem(maxOf(0, pager.visible(visibleCount).size - 1)) }
                            // ★ 发送前先确保订阅活着（网关 follow 死掉就收不到本回合事件）。
                            onAfterSend()
                            // ② fire the request
                            scope.launch {
                                when (val r = api.prompt(baseUrl, token, activeSession.id, text, rid)) {
                                    is RestResult.Ok -> {
                                        send.success(); pager.markOptimisticSent(rid)
                                        sendPhase = SendState.Phase.Sent; sendError = null
                                    }
                                    is RestResult.Err -> {
                                        // classify by the gateway's error.code (+ whether the target
                                        // is a sub-agent), never by guessing
                                        val f = SendFailure.classify(r.error.code, activeSession)
                                        send.fail(f)
                                        sendPhase = SendState.Phase.Failed
                                        sendError = f.userMessage
                                        // keep the text visible; mark failed so the user can retry
                                        pager.markOptimisticFailed(rid)
                                    }
                                }
                                visibleCount = progress.visibleCount
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = if (sendPhase == SendState.Phase.Sending) "Sending..." else "Send",
                        tint = if (sendEnabled) c.onSelectedText else c.textSecondary,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

/**
 * Messages fetched for the FIRST screen.
 *
 * 2026-10-13 用户要求（v3）：首屏只要最近 **8 条**（80 条实测首屏过重）；上划约
 * 3 条就预取更早一页。定位模式（搜索跳转）仍用同一 limit 开窗。
 */
internal const val FIRST_PAGE_LIMIT = 8

/**
 * 后台**倒序补齐历史**用的页大小（2026-10-14 用户优化 1）。
 *
 * 首屏仍然只拉 8 条（要快）；但"把历史同步到本地"是后台批量活，用 200/页
 * 才能把 4654 条的会话压到 ~24 次请求，而不是 580 次。
 */
internal const val BACKFILL_PAGE_LIMIT = 200

/**
 * 本地缓存"可信"的最大断档（2026-10-14 用户优化 1 的自愈阈值）。
 *
 * 缓存新端与网关最新页相差 ≤ 该值 ⇒ 只当作"离线期间落后了几条"，用 `after`
 * 补齐即可；超过 ⇒ 这份缓存不可信（错位/损坏），丢弃重载，避免窗口留空洞。
 */
internal const val CACHE_GAP_RESET_LIMIT = 2000L

/** Message bubble: user right / assistant left / tool collapsed / system dimmed. */
@Composable
internal fun MessageBubble(
    row: MessageRow,
    c: DshColors,
    sessionKey: String = "",
    expandStore: ExpandStateStore = ExpandStateStore(),
    onRequestFull: (Long) -> Unit = {},
) {
    when {
        row.dimmed -> Text(
            row.text, style = DshTokens.markdownSmall,
            color = c.textSecondary, modifier = Modifier.fillMaxWidth(),
        )
        // P-C: a tool row is NOT a dead summary line -- it is a disclosure row.
        // DSH shows the tool name in a 24dp row with a 16dp chevron; tapping it
        // reveals the arguments (tool-call) and/or the result (tool-result).
        row.collapsed -> ExpandableBlock(
            label = row.toolName?.let { "tool $it" } ?: "tool",
            summary = row.collapsedSummary.ifEmpty { row.text.removePrefix("[tool] ").trim() },
            body = buildString {
                row.toolInput?.let { appendLine("input:"); appendLine(it) }
                row.toolOutput?.let { appendLine("output:"); appendLine(it) }
            }.trim().ifEmpty { "(no detail yet)" },
            expanded = expandStore.isExpanded(sessionKey, row.seq),
            expandable = !row.truncated,
            c = c,
            contentDescription = "tool ${row.toolName ?: ""} details",
            onToggle = {
                com.deepseek.dshremote.core.DshTrace.log("  expand tapped sid=$sessionKey seq=${row.seq}")
                expandStore.toggle(sessionKey, row.seq)
                if (row.needsFullContent) onRequestFull(row.seq)
            },
        )
        row.status == "failed" -> Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
        ) {
            Column(
                Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(DshTokens.radiusLg))
                    .background(c.bubbleHighlight)
                    .padding(10.dp)
            ) {
                Text(row.text, color = c.textPrimary)
                Text("send failed", style = MaterialTheme.typography.labelSmall, color = c.errorText)
            }
        }
        row.status == "sending" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(DshTokens.radiusLg))
                    .background(c.bubbleHighlight)
                    .padding(10.dp)
            ) {
                Text(row.text, color = c.textPrimary)
                Text("sending...", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            }
        }
        row.isUser -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(DshTokens.radiusLg),
                color = c.bubbleHighlight,
                modifier = Modifier.fillMaxWidth(0.85f),
            ) { Text(row.text, color = c.textPrimary, modifier = Modifier.padding(10.dp)) }
        }
        else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Surface(
                shape = RoundedCornerShape(DshTokens.radiusLg),
                color = c.bubbleBackground,
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        row.text,
                        style = DshTokens.markdownBase,   // 14sp / 24sp (DSH body)
                        color = c.textPrimary,
                    )
                    // DSH renders a live caret while the turn streams; the shimmering
                    // bar stands in for "the model is still thinking".
                    if (row.isStreaming) {
                        if (row.text.isEmpty()) ThinkingIndicator(c) else StreamingCursor(c)
                    }
                    // Requirement 3: reasoning block -- collapsed by default,
                    // tap to expand (the full text is fetched on demand).
                    if (row.hasReasoning) {
                        ExpandableBlock(
                            label = "reasoning",
                            summary = row.collapsedSummary.ifEmpty { "reasoning" },
                            body = row.reasoning.orEmpty(),
                            expanded = expandStore.isExpanded(sessionKey, row.seq),
                            // DSH marks a truncated node as NOT expandable (window cut).
                            expandable = !row.truncated,
                            c = c,
                            onToggle = {
                                val now = expandStore.toggle(sessionKey, row.seq)
                                // dynamic load: only fetch full content when opening
                                if (now && row.needsFullContent) onRequestFull(row.seq)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Disclosure row (requirement 3), calibrated to DSH's real metrics.
 *
 * Source: `dsh-client-ui-primitives/lib/DisclosureRow.module.css`, whose own
 * comment states: `[16px leading] gap 6 [title 13/24] at the default size`.
 *  - row height            : 24px          (`height: calc(24px + delta)`)
 *  - chevron box           : 16x16px, glyph 14px inside it
 *  - chevron-to-title gap  : 6px           (`margin-right: 6px`)
 *  - title                 : 13px / 20px   (`--dsw-font-xs-13`)
 *  - pointer cursor        : only when the row is actually expandable
 *  - colour                : `--dsw-alias-label-secondary` for the chevron,
 *                            `--dsw-alias-label-tertiary` for the title
 *
 * Collapse state lives in [ExpandStateStore] so it survives scroll and reload.
 */
@Composable
internal fun ExpandableBlock(
    label: String,
    summary: String,
    body: String,
    expanded: Boolean,
    c: DshColors,
    /** Announced in the UI dump so tests can find the control. */
    contentDescription: String = "",
    /** Spoken/state description of the chevron, e.g. "expand". */
    chevronDescription: String = if (expanded) "collapse" else "expand",
    /** DSH marks non-expandable rows with no pointer cursor; mirror that here. */
    expandable: Boolean = true,
    onToggle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(DshTokens.disclosureRowHeight)
                .clip(RoundedCornerShape(DshTokens.radiusSm))
                // Only expandable rows take the tap (matches DSH's [data-expandable]).
                .then(if (expandable) Modifier.clickable { onToggle() } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (expandable) {
                Box(
                    Modifier
                        .size(DshTokens.disclosureIconBox)
                        .padding(start = 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (expanded) "▾" else "▸",
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        color = c.textSecondary,           // --dsw-alias-label-secondary
                    )
                }
                Spacer(Modifier.width(DshTokens.disclosureIconGap))   // 6px
            }
            Text(
                "[$label] " + summary,
                style = DshTokens.disclosureTitle,                  // 13sp / 20sp
                color = c.textTertiary,                              // --dsw-alias-label-tertiary
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (expanded) {
            Text(
                body,
                style = DshTokens.markdownCode,      // 12sp / 19sp monospace
                color = c.textPrimary,
                modifier = Modifier.padding(start = DshTokens.disclosureIconBox + DshTokens.disclosureIconGap, top = 2.dp),
            )
        }
    }
}

/**
 * 阶段跳转小横线（2026-10-13 用户优化 1，DeepSeek 式 minimap）。
 *
 * 右缘悬浮一列浅色小横线，每条 = 一个 assistant 回合（会话阶段）。
 *  - 当前视口所在阶段：accent 色 + 更宽；
 *  - 其余：divider 色；
 *  - 锚点多于一屏时可上下滚动（内部 LazyColumn，竖向滑动手势不穿透到消息流）；
 *  - 点击横线 → 跳到该回合（消息流窗口不足时逐帧逼近）。
 *
 * 布局：作为消息区 Box 内的 **overlay**（fillMaxSize + CenterEnd）悬浮右缘。
 * 根层不注册 pointerInput ⇒ 除 34dp 宽锚点列外的触摸全部穿透给消息 LazyColumn，
 * 消息流滚动不受影响；锚点少时锚点列按内容收缩（heightIn max），不多占触摸区。
 */
@Composable
internal fun StageJumper(
    anchors: List<Int>,
    currentIndex: Int,
    onJump: (Int) -> Unit,
) {
    val c = DshTheme.colors
    val listScroll = rememberLazyListState()
    // 锚点多时自动把当前阶段的横线滚进可视区（首帧 + currentIndex 变化时）。
    LaunchedEffect(currentIndex, anchors.size) {
        val pos = anchors.indexOf(currentIndex)
        if (pos >= 0) listScroll.animateScrollToItem(pos.coerceAtMost((anchors.size - 1).coerceAtLeast(0)))
    }
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        LazyColumn(
            state = listScroll,
            // 最多 ~14 条横线高；锚点少时按内容收缩。超过后内部滚动。
            modifier = Modifier
                .heightIn(max = (14 * 18).dp)
                .width(34.dp)
                .padding(end = 3.dp, top = 4.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.Center,
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(anchors.size, key = { anchors[it] }) { pos ->
                val active = anchors[pos] == currentIndex
                Box(
                    Modifier
                        .padding(vertical = 5.dp)
                        .size(width = if (active) 22.dp else 14.dp, height = 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (active) c.accent else c.divider)
                        .clickable { onJump(anchors[pos]) }
                )
            }
        }
    }
}
