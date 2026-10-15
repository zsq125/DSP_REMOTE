package com.deepseek.dshremote.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.dshremote.DshRemoteApp
import com.deepseek.dshremote.data.DshRemoteStore
import com.deepseek.dshremote.data.DshRemoteStore.PairingReadResult
import com.deepseek.dshremote.core.PairingInfo
import com.deepseek.dshremote.core.PairingParseResult
import com.deepseek.dshremote.core.PairingParser
import com.deepseek.dshremote.core.SidebarState
import com.deepseek.dshremote.core.StopButtonPolicy
import com.deepseek.dshremote.core.DshTrace
import com.deepseek.dshremote.core.ListCache
import com.deepseek.dshremote.core.SessionSubscriptionManager
import com.deepseek.dshremote.core.SubagentTree
import com.deepseek.dshremote.core.Transport
import com.deepseek.dshremote.core.Workspace
import com.deepseek.dshremote.core.WorkspaceRepo
import com.deepseek.dshremote.core.WorkspaceSource
import com.deepseek.dshremote.core.WorkspaceTreeState
import com.deepseek.dshremote.core.workspaceIdOfSessions
import com.deepseek.dshremote.core.relativeTimeLabel
import com.deepseek.dshremote.net.GatewayApi
import com.deepseek.dshremote.net.OkHttpTransport
import com.deepseek.dshremote.protocol.RestResult
import com.deepseek.dshremote.protocol.ServerFrame
import com.deepseek.dshremote.protocol.SessionSummary
import com.deepseek.dshremote.protocol.parseServerFrame
import com.deepseek.dshremote.service.ConnectionService
import kotlinx.coroutines.launch

/**
 * Layout mirroring the PC harness:
 *
 * ```
 * +------------+----------------------------------+
 * | Sidebar    |  Detail pane                     |
 * | workspace  |  [main][sub 1][sub 2]           |  <- switcher ABOVE the chat
 * |  + main    |  messages (progressive+stream)  |
 * |    session |  [input............][send]       |
 * +------------+----------------------------------+
 * ```
 *
 * Sidebar holds **workspaces + main sessions only**; sub-agent conversations are
 * reachable exclusively through the switcher above the chat area (PC harness behaviour).
 */
class MainActivity : ComponentActivity() {
    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DshTheme {
                DshRemoteRoot(onRequestCamera = { requestCamera.launch(Manifest.permission.CAMERA) })
            }
        }
    }
}

@Composable
private fun colors(): DshColors = DshTheme.colors

@Composable
fun DshRemoteRoot(onRequestCamera: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as DshRemoteApp
    val scope = rememberCoroutineScope()
    val screenW = LocalConfiguration.current.screenWidthDp.dp

    var paired by remember { mutableStateOf<Boolean?>(null) }
    var baseUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var pairingError by remember { mutableStateOf<String?>(null) }
    // ── 2026-10-15 用户报告：网关轮换 token 后 App 无法重新配对 ──────────
    // 旧行为：配对页只在 paired == false 时出现，而 App 里存着旧 token（paired=true）
    // ⇒ 只会一直 401，用户进不去配对页，只能 `pm clear` 清数据（连带丢本地历史）。
    // 另：ScannerScreen（扫码页）**写好了却零引用**，配对页的「Open scanner」只发了
    // 一次相机权限、没人消费（点了像没反应）。
    // 现在：① 已配对时也能进配对页（repairing），可取消返回；
    //       ② 扫码页真正接上（相机权限由 ScannerScreen 内部按需申请）。
    var repairing by remember { mutableStateOf(false) }
    var scannerOpen by remember { mutableStateOf(false) }
    /** 配对成功后的统一落库 + 生效（手动输入 / 扫码共用一条路径）。 */
    val applyPairing: (PairingInfo) -> Unit = { info ->
        scope.launch {
            app.store.savePairing(info)
            baseUrl = info.baseUrl
            token = info.token
            pairingError = null
            repairing = false
            scannerOpen = false
            paired = true
            ConnectionService.start(context)
        }
    }

    /**
     * The API client and the workspace repo must be re-created once pairing is
     * known.
     *
     * BUG (2026-10-12, cold start produced a blank App with ZERO network calls):
     * `remember { GatewayApi() }` and `remember { WorkspaceRepo { api.workspaces(baseUrl, token) } }`
     * capture `baseUrl`/`token` **by value at first composition**, i.e. the empty
     * strings. Pairing is loaded asynchronously in a `LaunchedEffect`, so the
     * load effect ran with an empty host and every request failed before leaving
     * the device. Keying both on the loaded values fixes it.
     */
    val effectiveBaseUrl = baseUrl.ifEmpty { "" }
    val readyForIO = paired == true && baseUrl.isNotEmpty() && token.isNotEmpty()

    // Rebuilt once pairing is loaded -- see the BUG note above.
    val api = remember(effectiveBaseUrl) { GatewayApi() }

    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var allSessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }

    // 2026-10-14 用户需求 2：每个会话独立的未发送草稿。提升到 Activity 级，
    // DetailPane 随列表刷新 unmount 也不丢；key=会话 id（子代理各自独立）。
    val sessionDrafts = remember { mutableStateMapOf<String, String>() }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val tree = remember { WorkspaceTreeState() }
    var treeVersion by remember { mutableStateOf(0) }

    // 折叠状态记忆（需求 §二.4）
    // ⚠ 必须是**不可变值**放进 mutableStateOf：之前存可变的 SidebarState 对象，
    //   Compose 观察不到内部字段变化 ⇒ 点折叠按钮完全没反应。
    var sidebarCollapsed by remember { mutableStateOf(false) }
    val sidebar = remember { SidebarState() }

    val repo = remember(effectiveBaseUrl, token) {
        WorkspaceRepo(object : WorkspaceSource {
            override suspend fun loadWorkspaces() = api.workspaces(baseUrl, token)
            override suspend fun loadSessions(p: String) = api.sessionsOfWorkspace(baseUrl, token, p)
        })
    }

    LaunchedEffect(Unit) {
        val raw = runCatching { app.store.loadSidebarCollapsed() }.getOrNull()
        if (raw != null) sidebarCollapsed = SidebarState.deserialize(raw).collapsed
    }
    LaunchedEffect(sidebarCollapsed) {
        scope.launch { runCatching { app.store.saveSidebarCollapsed(if (sidebarCollapsed) "1" else "0") } }
    }

    LaunchedEffect(Unit) {
        // Cold-start diagnosis (2026-10-12): distinguish "never paired" from
        // "the stored key is gone", otherwise both look like a frozen App.
        val result = runCatching { app.store.loadPairingDetailed() }
        android.util.Log.e("DshBoot", "read pairing ok=${result.isSuccess} value=${result.getOrNull()}")
        when (val r = result.getOrNull()) {
            is PairingReadResult.Ok -> {
                baseUrl = r.info.baseUrl; token = r.info.token; paired = true
                ConnectionService.start(context)          // D3: restore keepalive + WS
            }
            is PairingReadResult.NotPaired -> {
                pairingError = null
                paired = false
            }
            is PairingReadResult.NeedsRepair -> {
                // Actionable: tell the user to re-pair instead of showing a blank shell.
                pairingError = when (r.reason) {
                    DshRemoteStore.PairingLoss.KEY_LOST ->
                        "Saved pairing key is no longer valid (the app was reinstalled). Please pair again."
                    else -> "Saved pairing data is damaged. Please pair again."
                }
                paired = false
            }
            else -> { pairingError = "Failed to read pairing data"; paired = false }
        }
    }

    // Load workspaces + ALL paged sessions once (both panes need them).
    // Gated on BOTH `paired` and a non-empty host: previously the effect could run
    // while `baseUrl` was still "", which produced a silent, request-less startup.
    //
    // 用户问题 4（长时间不访问后回来仍要秒开）：
    // 先**同步**渲染本地缓存的工作区列表（DataStore，无网络），再发网络请求刷新。
    // 以前冷启动必须等 `/workspaces` 一个往返才能画出左栏，PC 端会话数早已变化，
    // 用户看到的就是「进入 app 要等很久」。

    // 已经按需展开过的工作区（本次运行内网络拉取过 ⇒ 不重复拉）。
    val loadedWorkspacePaths = remember { mutableStateListOf<String>() }

    /**
     * 已展开工作区的会话，按 path 归档（问题 4 的落盘数据源）。
     *
     * 展开一个工作区就记一份；刷新工作区列表时整体写盘。只存列表元数据，
     * 所以「很久以前展开过的工作区」在下次冷启动时也能立刻显示会话条数。
     */
    val loadedSessionsByPath = remember { mutableStateMapOf<String, List<SessionSummary>>() }

    // 有序签名：**顺序也算变化**。2026-10-14 用户报告「PC 端工作区/会话顺序改了，
    // App 长时间不连后不更新」——只比成员集合会漏掉纯拖拽排序。
    fun sessionsSig(l: List<SessionSummary>): String =
        l.joinToString("|") { it.id + ':' + it.updatedAt }

    /**
     * 把某工作区拉到的最新会话列表并入 allSessions；仅在**有序签名**变化时
     * 重绑 + 落盘。展开时静默刷新、回前台补刷、WS 重连补刷共用这一条路径，
     * 保证"任何入口拿到的新顺序都能更新 UI"。返回是否真的重绑。
     */
    fun applyFreshSessions(path: String, fresh: List<SessionSummary>): Boolean {
        val old = loadedSessionsByPath[path]
        if (old != null && sessionsSig(old) == sessionsSig(fresh)) return false
        allSessions = allSessions.filter { it.cwd != path } + fresh
        loadedSessionsByPath[path] = fresh
        scope.launch {
            runCatching {
                app.store.saveSessionsCache(
                    ListCache.encodeSessionsByPath(loadedSessionsByPath.toMap()),
                )
            }
        }
        return true
    }

    /** 静默重拉所有本次已展开过的工作区（回前台 / WS 重连后补顺序与增删）。 */
    suspend fun refreshExpandedWorkspaces() {
        var changed = 0
        for (path in loadedWorkspacePaths.toList()) {
            val ws = workspaces.firstOrNull { it.path == path } ?: continue
            when (val r = repo.sessionsFor(ws)) {
                is RestResult.Ok -> if (applyFreshSessions(path, r.value)) changed++
                is RestResult.Err -> Unit
            }
        }
        DshTrace.log("refreshExpanded ws=${loadedWorkspacePaths.size} changed=$changed")
    }

    LaunchedEffect(paired, baseUrl, token) {
        android.util.Log.e("DshBoot", "load effect paired=$paired host=$baseUrl ready=$readyForIO")
        if (!readyForIO) return@LaunchedEffect
        loading = true
        // ① local cache first: instant left pane, zero network
        val cachedWs = ListCache.decodeWorkspaces(app.store.loadWorkspaceCache())
        if (cachedWs.isNotEmpty()) {
            workspaces = cachedWs
            DshTrace.log("cache workspaces=${cachedWs.size} at=${app.store.loadListCacheAt()}")
        }
        // ② network refresh; a failure keeps the cached list on screen
        when (val r = repo.refreshWorkspaces()) {
            is RestResult.Ok -> {
                workspaces = r.value
                scope.launch {
                    runCatching { app.store.saveWorkspaceCache(ListCache.encodeWorkspaces(r.value)) }
                }
            }
            is RestResult.Err -> Unit
        }
        // 用户要求（问题 1）：首屏先给左栏工作区名，会话按展开加载。
        // 因此冷启动**不再拉全量 /sessions**：旧实现在这里调 allSessions()，会页展开后再拉一遍，
        // 首屏就需要下载全部会话（实测 /sessions 被拉了两页）。
        // 会话列表现在由 Sidebar 展开工作区时按需拉取（onLoadWorkspace）。
        allSessions = emptyList()
        if (selectedId == null) {
            // 默认不自动选中：等用户展开某个工作区再选会话，避免首屏必须先拉会话。
            tree.focusWorkspace(null)
        }
        loading = false
    }

    // ── 2026-10-14 用户报告：PC 端工作区/会话顺序改了，App 长时间不连后
    // 列表不更新（DetailPane 已修会话内消息，这里修左栏列表）──────────
    // 覆盖场景：①长时间后台回前台；②网络抖动 WS 重连（onOpen 时调用同一函数）。
    // 刷新工作区顺序 + 本次已展开过的每个工作区的会话顺序/增删，全部走
    // applyFreshSessions（有序签名，无变化不重绑、不闪烁）。
    val listLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var listLeftApp by remember { mutableStateOf(false) }
    // nonce 模式：observer 只计数（不碰 repo/workspaces 等组合状态），真正刷新
    // 在 LaunchedEffect 里执行 ⇒ 每次拿到的都是**最新一帧**的 repo/列表，
    // 不会冻结在 observer 注册首帧（那时 paired 可能尚未就绪）。
    var listResumeNonce by remember { mutableStateOf(0) }
    DisposableEffect(listLifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            when (ev) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> listLeftApp = true
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if (listLeftApp) {
                    listLeftApp = false
                    listResumeNonce++
                }
                else -> Unit
            }
        }
        listLifecycleOwner.lifecycle.addObserver(obs)
        onDispose { listLifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(listResumeNonce) {
        if (listResumeNonce == 0) return@LaunchedEffect
        com.deepseek.dshremote.core.DshTrace.log(
            "list-resume ready=$readyForIO expanded=${loadedWorkspacePaths.size}"
        )
        if (!readyForIO) return@LaunchedEffect
        when (val r = repo.refreshWorkspaces()) {
            is RestResult.Ok -> {
                workspaces = r.value
                runCatching { app.store.saveWorkspaceCache(ListCache.encodeWorkspaces(r.value)) }
            }
            is RestResult.Err -> Unit
        }
        refreshExpandedWorkspaces()
    }

    // 问题 1：展开工作区时才拉【该工作区】的会话；结果并入 allSessions。
    // （冷启动只拉 /workspaces，见上面的 load effect。）
    // loadedWorkspacePaths / loadedSessionsByPath 在 load effect 之前声明（见上）。

    val loadWorkspaceSessions: (Workspace) -> Unit = loadWs@{ ws ->
        // ── 2026-10-13 用户报告「展开会话列表还是卡」根因修复 ──────────
        // 实测：磁盘缓存 80ms 上屏后，代码又强制走一次网络（Wi-Fi RTT 波动
        // 142-575ms），回来整体替换 allSessions ⇒ 全列表重组闪烁 = 看到的"卡"。
        // 修复：磁盘缓存命中 ⇒ 直接用，网络刷新降级为**静默后台**且只在
        // 确有变化时才更新（避免无谓重组）；缓存为空才同步等网络。
        fun mergeSilent(fresh: List<SessionSummary>) {
            if (!applyFreshSessions(ws.path, fresh)) {
                DshTrace.log("expand ws=${ws.path} network-same-as-cache n=${fresh.size} (no rebind)")
            } else {
                DshTrace.log("expand ws=${ws.path} refreshed n=${fresh.size}")
            }
        }
        // ① 磁盘缓存先上屏（stale-while-revalidate）：很久没打开的 App 展开
        //    工作区时不等网络就有列表。（DataStore 是 suspend ⇒ 借 scope 读。）
        suspend fun readDiskSessions(): List<SessionSummary> = runCatching {
            ListCache.decodeSessionsByPath(app.store.loadWorkspaceSessionsCache())[ws.path].orEmpty()
        }.getOrDefault(emptyList())

        fun showDisk(disk: List<SessionSummary>) {
            loadedSessionsByPath[ws.path] = disk
            allSessions = allSessions.filter { s -> s.cwd != ws.path } + disk
            DshTrace.log("expand ws=${ws.path} disk-cache n=${disk.size}")
        }

        if (loadedWorkspacePaths.contains(ws.path)) {
            DshTrace.log("expand ws=${ws.path} req=partial cached=true")
            return@loadWs
        }
        loadedWorkspacePaths += ws.path
        DshTrace.log("expand ws=${ws.path} req=partial cached=false")
        val disk = kotlinx.coroutines.runBlocking { readDiskSessions() }
        if (disk.isNotEmpty()) {
            showDisk(disk)
            // 缓存命中：网络刷新**静默后台**，绝不阻塞列表显示。
            scope.launch {
                when (val r = repo.sessionsFor(ws)) {
                    is RestResult.Ok -> mergeSilent(r.value)
                    is RestResult.Err -> Unit
                }
            }
        } else {
            // 缓存为空（首次展开该工作区）：同步等网络，然后正常上屏。
            scope.launch {
                when (val r = repo.sessionsFor(ws)) {
                    is RestResult.Ok -> {
                        val fresh = r.value
                        val others = allSessions.filter { s -> s.cwd != ws.path }
                        allSessions = others + fresh
                        loadedSessionsByPath[ws.path] = fresh
                        runCatching {
                            app.store.saveSessionsCache(
                                ListCache.encodeSessionsByPath(loadedSessionsByPath.toMap()),
                            )
                        }
                        DshTrace.log("expand ws=${ws.path} done added=${fresh.size} total=${allSessions.size}")
                        // 展开后自动选中该工作区的第一个主会话，避免右栏空白。
                        if (selectedId == null) {
                            val first = SubagentTree.mainSessionsOnly(fresh).firstOrNull()
                                ?: fresh.firstOrNull()
                            if (first != null) {
                                selectedId = first.id
                                tree.focusSession(
                                    first.id,
                                    workspaceIdOfSessions(workspaces, allSessions),
                                )
                                treeVersion++
                            }
                        }
                    }
                    is RestResult.Err -> Unit
                }
            }
        }
    }

    // ── Single app-wide WS: status dots AND the session subscription ──────────
    // C4 (2026-10-12): the subscription used to live inside DetailPane, which is
    // only composed once a session is selected -- so on a cold start no subscribe
    // frame was ever sent (visible in the gateway request counter as
    // "WS connect but subscribe=0"), and it vanished whenever the pane was torn
    // down. Owning it here means ONE socket for the whole app, subscribed to the
    // session actually on screen.
    var liveStatus by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Latest live events for the open session; DetailPane consumes this stream.
    var liveEvents by remember { mutableStateOf<List<ServerFrame.SessionEvent>>(emptyList()) }
    // Declared before [subscription] because its lambda captures the socket.
    var appWs by remember(baseUrl, token) { mutableStateOf<OkHttpTransport?>(null) }
    val subscription = remember(baseUrl, token) {
        SessionSubscriptionManager(send = { appWs?.send(it) ?: false })
    }

    // ★ Owned here, NOT inside DetailPane: the pane unmounts whenever `selected`
    // momentarily becomes null (allSessions is rebuilt on every refresh), and a
    // remember-scoped guard would then reload the SAME session repeatedly
    // (observed 14 identical GET /messages, each resetting the viewport).
    var loadedSessionId by remember { mutableStateOf<String?>(null) }

    // ── 全局搜索（2026-10-13 用户需求 4）────────────────────────
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchBusy by remember { mutableStateOf(false) }
    var searchHits by remember { mutableStateOf<List<GatewayApi.SearchHit>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    // One-shot "open session X at seq N" navigation request.
    var focusRequest by remember { mutableStateOf<Pair<String, Long>?>(null) }

    fun runSearch() {
        val q = searchQuery.trim()
        if (q.isEmpty()) return
        scope.launch {
            searchBusy = true; searchError = null
            when (val r = api.search(baseUrl, token, q)) {
                is RestResult.Ok -> { searchHits = r.value; searchBusy = false }
                is RestResult.Err -> {
                    searchHits = emptyList()
                    searchError = "${r.error.code}：${r.error.message}"
                    searchBusy = false
                }
            }
        }
    }

    fun openHit(hit: GatewayApi.SearchHit) {
        searchOpen = false
        // ⚠ Search spans ALL workspaces, but `allSessions` only holds sessions of
        // EXPANDED workspaces. A hit from an unexpanded workspace resolves to
        // `selected == null` and the pane never mounts (device-verified 22:34:
        // subscribe fired, zero GET /messages). Fetch that workspace's sessions
        // first, merge, THEN select + focus.
        fun doSelect() {
            selectedId = hit.sessionId
            tree.focusSession(hit.sessionId, workspaceIdOfSessions(workspaces, allSessions))
            treeVersion++
            if (hit.topSeq != null) focusRequest = hit.sessionId to hit.topSeq!!
        }
        if (allSessions.any { it.id == hit.sessionId }) {
            doSelect()
        } else {
            // 2026-10-13 bug2 修复：网络失败原来静默 Unit ⇒ 点了没反应（"点新会话
            // 加载不到内容"）。失败时选中会话（右侧可见加载错误+重试），不再空转。
            scope.launch {
                when (val r = api.sessionsOfWorkspace(baseUrl, token, hit.cwd)) {
                    is RestResult.Ok -> {
                        val existing = allSessions.mapTo(HashSet()) { it.id }
                        allSessions = allSessions + r.value.filter { it.id !in existing }
                        doSelect()
                    }
                    is RestResult.Err -> {
                        // 仍然选中（用 hit 自带的信息合成最小会话行），右侧会因
                        // /messages 失败显示「加载失败 + 重试」，用户有反馈而不是没反应。
                        DshTrace.log("openHit ws-fetch FAILED code=${r.error.code} sid=${hit.sessionId} -> select anyway")
                        selectedId = hit.sessionId
                        focusRequest = hit.topSeq?.let { hit.sessionId to it }
                    }
                }
            }
        }
    }

    // Point the subscription at whatever session is on screen (main or sub-agent).
    val subscribedId = remember(selectedId, allSessions) {
        selectedId   // sub-agent tab selection is resolved inside DetailPane
    }

    // ★ Ready-gate: pairing is read asynchronously, so on the first frame
    // baseUrl/token are still empty. Key the effect on an explicit boolean so
    // it is guaranteed to re-run once pairing lands.
    val readyForWs = baseUrl.isNotEmpty() && token.isNotEmpty()

    // Switching sessions re-subscribes on the SAME socket: no disconnect/connect pair.
    LaunchedEffect(subscribedId) {
        if (!readyForWs) return@LaunchedEffect
        val epoch = subscription.currentGeneration()
        DshTrace.log("switch-only subscribedId=$subscribedId wsReused=true epoch=$epoch")
        subscription.switchTo(subscribedId, epoch)
    }

    // The socket is keyed on READINESS only, not on the selected session: a switch
    // must swap the subscription target, not pay for a new WebSocket handshake.
    DisposableEffect(readyForWs) {
        if (!readyForWs) {
            DshTrace.log("effect SKIP not-ready baseUrlEmpty=${baseUrl.isEmpty()} tokenEmpty=${token.isEmpty()}")
            return@DisposableEffect onDispose { }
        }
        val epoch = subscription.beginConnection()
        DshTrace.log("ws open requested epoch=$epoch host=$baseUrl")
        val t = OkHttpTransport()
        appWs = t
        t.connect(streamUrl(baseUrl, token), object : Transport.Listener {
            // WS 重连 = 网络恢复：错过的工作区/会话顺序与增删在这里补齐
            // （onOpen 在每次重连都会触发；首次连接时已展开列表为空，no-op）。
            override fun onOpen() {
                subscription.onOpen(epoch)
                scope.launch { if (readyForIO) refreshExpandedWorkspaces() }
            }

            override fun onText(text: String) {
                val f = parseServerFrame(text)
                when (f) {
                    is ServerFrame.SessionStatus -> liveStatus = f.sessionId to f.status
                    // Ownership filtering happens inside the manager: events for any
                    // other session are dropped, never misapplied.
                    is ServerFrame.SessionEvent -> liveEvents = subscription.onText(text)
                    else -> Unit
                }
            }

            // Pass the generation so a LATE callback from this socket cannot
            // wipe frames queued for the next one.
            override fun onClosed(code: Int, reason: String) = subscription.onClosed(code, reason, epoch)
            override fun onError(t: Throwable) = subscription.onError(t, epoch)
        })
        onDispose { t.close(); appWs = null }
    }
    // 前台时 PC 端会话顺序/updatedAt 变化：WS 没有"列表变更"推送帧，但会话
    // 状态帧（尤其结束/完成）往往伴随排序变化。借它做**节流**轻量补刷
    // （≥4s 一次，只在 App 在前台时；无变化 applyFreshSessions 内部不重绑）。
    var lastListPullAt by remember { mutableStateOf(0L) }
    LaunchedEffect(liveStatus) {
        val u = liveStatus ?: return@LaunchedEffect
        allSessions = StopButtonPolicy.mergeStatus(allSessions, u.first, u.second)
        val now = System.currentTimeMillis()
        if (readyForIO && now - lastListPullAt > 4000L &&
            listLifecycleOwner.lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.RESUMED
            )
        ) {
            lastListPullAt = now
            refreshExpandedWorkspaces()
        }
    }

    // 2026-10-13 bug2 修复：命中未展开工作区的会话且其工作区列表拉取失败时，
    // selectedId 有值但 allSessions 无该行 ⇒ selected=null ⇒ 右侧"Pick a session"
    // 空白无反馈。合成一行最小 SessionSummary（含 cwd/title/updatedAt），保证
    // DetailPane 挂载 —— /messages 失败会显示「加载失败 + 重试」。
    val selected = allSessions.firstOrNull { it.id == selectedId }
        ?: focusRequest?.takeIf { it.first == selectedId }?.let {
            com.deepseek.dshremote.protocol.SessionSummary(
                id = it.first, title = "(来自搜索)", status = "unknown",
                createdAt = 0L, updatedAt = 0L, cwd = "", messageCount = 0,
                lastMessagePreview = "", agentAvailable = false,
            )
        }

    when {
        paired == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        // 未配对，或用户主动点了「重新配对」（paired 仍为 true，但允许换 token）。
        paired == false || repairing -> {
            if (scannerOpen) {
                // 扫码页：相机权限由它自己按需申请（旧代码只发权限、从不显示本页）。
                ScannerScreen(
                    onResult = { payload ->
                        when (val r = PairingParser.parse(payload)) {
                            is PairingParseResult.Ok -> applyPairing(r.info)
                            is PairingParseResult.Invalid -> {
                                pairingError = r.reason
                                scannerOpen = false
                            }
                        }
                    },
                    onClose = { scannerOpen = false },
                )
            } else {
                PairingView(
                    error = pairingError,
                    onRequestCamera = { scannerOpen = true },
                    onManual = { hostPort, tk ->
                        when (val r = PairingParser.parseManual(hostPort, tk)) {
                            is PairingParseResult.Ok -> applyPairing(r.info)
                            is PairingParseResult.Invalid -> pairingError = r.reason
                        }
                    },
                    // 已配对状态下进来换 token ⇒ 给一个「返回」，不把用户困在配对页。
                    onCancel = if (paired == true) {
                        { repairing = false; pairingError = null }
                    } else null,
                )
            }
        }
        else -> {
            val c = colors()
            Row(Modifier.fillMaxSize().background(c.chatBackground)) {
                // ---- Sidebar (workspaces + MAIN sessions only) ----
                if (!sidebarCollapsed) {
                    Column(
                        Modifier
                            .width((screenW * sidebar.widthFraction).coerceAtLeast(112.dp))
                            .fillMaxHeight()
                            .background(c.sidebarBackground)
                    ) {
                        // ── 全局搜索入口（2026-10-13 用户需求 3：DeepSeek 式放大镜）──
                        // 侧栏只留一个放大镜按钮；点开全屏搜索页（SearchOverlay）。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { searchOpen = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "搜索全部会话",
                                    tint = c.textSecondary)
                            }
                            // ── 2026-10-15：重新配对 / 更换 token ────────────────
                            // 网关轮换 token 后，App 存着旧 token 只会 401；旧代码只能
                            // `pm clear` 清数据（顺带丢本地历史）。这里给一个正经入口：
                            // 进配对页（可取消返回），扫码或手输新 host+token 即可换绑。
                            IconButton(onClick = { repairing = true; pairingError = null }) {
                                Icon(Icons.Filled.Settings, contentDescription = "重新配对 / 更换 token",
                                    tint = c.textSecondary)
                            }
                        }
                        Sidebar(
                            workspaces = workspaces,
                            sessions = allSessions,
                            selectedId = selectedId,
                            tree = tree,
                            treeVersion = treeVersion,
                            onToggleWs = { id ->
                                val nowExpanded = tree.toggle(id)
                                treeVersion++
                                // 只有【展开】时才去加载该工作区的会话。
                                if (nowExpanded) {
                                    workspaces.firstOrNull { it.id == id }?.let { loadWorkspaceSessions(it) }
                                }
                            },
                            onSelect = { s ->
                                selectedId = s.id
                                tree.focusSession(s.id, workspaceIdOfSessions(workspaces, allSessions))
                                treeVersion++
                            },
                        )
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider))
                }

                // ---- Detail pane ----
                Column(Modifier.weight(1f).fillMaxHeight().background(c.chatBackground)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { sidebarCollapsed = !sidebarCollapsed }) {
                            Icon(Icons.Filled.Menu, contentDescription = if (sidebarCollapsed) "expand" else "collapse")
                        }
                        Text(
                            selected?.title?.takeIf { it.isNotBlank() } ?: "No session selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = c.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )
                        if (selected != null) {
                            Box(
                                Modifier.size(8.dp).clip(CircleShape).background(
                                    if (StopButtonPolicy.isRunning(selected)) c.dotRunning else c.dotIdle
                                )
                            )
                        }
                        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    HorizontalDivider(color = c.divider, thickness = 1.dp)

                    if (selected == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Pick a session on the left", color = c.textSecondary)
                        }
                    } else {
                        // ★ Invalidate the guard when the pane really unmounts.
                        // The guard lives here so it survives recomposition, but it
                        // describes state that lives INSIDE the pane (pager, scroll).
                        // If `selected` is momentarily null -- `allSessions` is
                        // rebuilt on every refresh -- the pane is torn down and its
                        // messages are gone, while the guard would still claim
                        // "already loaded" and skip the reload => blank transcript.
                        // onDispose fires only on a REAL unmount, so plain
                        // recompositions keep the guard and do not re-fetch.
                        val paneSessionId = selected.id
                        DisposableEffect(paneSessionId) {
                            onDispose {
                                if (loadedSessionId == paneSessionId) {
                                    DshTrace.log("pane unmounted sid=$paneSessionId -> invalidate guard")
                                    loadedSessionId = null
                                }
                            }
                        }
                        DetailPane(
                            session = selected,
                            allSessions = allSessions,
                            baseUrl = baseUrl,
                            token = token,
                            liveEvents = liveEvents,
                            loadRequestKey = selected.id,
                            loadedFor = loadedSessionId,
                            onLoaded = { loadedSessionId = it },
                            focusSeq = focusRequest?.takeIf { it.first == selected.id }?.second,
                            onFocusConsumed = { focusRequest = null },
                            drafts = sessionDrafts,
                        )
                    }
                }
            }
        }
    }

    // ── 全屏搜索页（DeepSeek 式：点放大镜进入，输入即搜，点结果跳会话）──
    if (searchOpen) {
        SearchOverlay(
            baseUrl = baseUrl,
            token = token,
            onDismiss = { searchOpen = false },
            onOpenHit = { hit -> openHit(hit) },
        )
    }
}

/**
 * 全屏搜索页（用户需求 3：学 DeepSeek —— 放大镜进入、顶部一条圆角输入框、
 * 下方是全部相关结果列表；点结果跳回会话并定位）。
 *
 * 行为：
 *  - 进入即聚焦键盘；输入去抖 300ms 自动搜索（不用点"搜"按钮）。
 *  - 结果 = 会话级列表（标题 + 最优命中摘要 + 时间/命中数），点任意一条跳转。
 *  - 返回键 / 点左上箭头关闭。
 */
@Composable
private fun SearchOverlay(
    baseUrl: String,
    token: String,
    onDismiss: () -> Unit,
    onOpenHit: (GatewayApi.SearchHit) -> Unit,
) {
    val c = colors()
    val scope = rememberCoroutineScope()
    val api = remember { GatewayApi() }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<GatewayApi.SearchHit>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    // 输入去抖 300ms 自动搜索。
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { hits = emptyList(); error = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        busy = true; error = null
        when (val r = api.search(baseUrl, token, q)) {
            is RestResult.Ok -> { hits = r.value; busy = false }
            is RestResult.Err -> { hits = emptyList(); error = "${r.error.code}：${r.error.message}"; busy = false }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(Modifier.fillMaxSize().background(c.chatBackground)) {
        // ── 顶栏：返回箭头 + 圆角搜索框 ──────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "关闭搜索")
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索全部会话", style = MaterialTheme.typography.bodyLarge) },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = c.sidebarHeaderBackground,
                    focusedContainerColor = c.sidebarHeaderBackground,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                ),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = c.textSecondary) },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            )
            Spacer(Modifier.width(8.dp))
        }
        HorizontalDivider(color = c.divider, thickness = 1.dp)

        // ── 结果区 ────────────────────────────────────────────
        when {
            busy && hits.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            error != null -> Box(Modifier.fillMaxSize().padding(16.dp)) {
                Text(error.orEmpty(), color = c.errorText, style = MaterialTheme.typography.bodySmall)
            }
            query.isNotBlank() && hits.isEmpty() && !busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有匹配的内容", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(hits, key = { it.sessionId + ":" + (it.topSeq ?: -1L) }) { hit ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenHit(hit) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            hit.title.ifBlank { "(untitled)" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (hit.topSnippet.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                hit.topSnippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "${relativeTimeLabel(System.currentTimeMillis(), hit.updatedAt)}  ·  ${hit.hitCount} 处命中",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textSecondary,
                        )
                    }
                    HorizontalDivider(color = c.divider, thickness = 1.dp)
                }
            }
        }
    }
}

/** One flattened row of the sidebar tree. */
private sealed interface SidebarNode {    val key: String
    data class WsRow(val ws: Workspace, val expanded: Boolean) : SidebarNode {
        override val key get() = "w:" + ws.id
    }

    data class SessionRow(val session: SessionSummary) : SidebarNode {
        override val key get() = "s:" + session.id
    }
}

/**
 * Sidebar: workspace (collapsible) -> its **main** sessions.
 *
 * The tree is flattened into rows for a single [LazyColumn] because nested `items`
 * calls are not allowed inside LazyListScope; flattening keeps one scroll container.
 */
@Composable
private fun Sidebar(
    workspaces: List<Workspace>,
    sessions: List<SessionSummary>,
    selectedId: String?,
    tree: WorkspaceTreeState,
    treeVersion: Int,
    onToggleWs: (String) -> Unit,
    onSelect: (SessionSummary) -> Unit,
) {
    val byWs = remember(workspaces, sessions) {
        val map = HashMap<String, MutableList<SessionSummary>>()
        for (s in sessions) {
            val w = workspaces.firstOrNull { it.path == s.cwd } ?: continue
            map.getOrPut(w.id) { mutableStateListOf() }.add(s)
        }
        map
    }
    @Suppress("UNUSED_EXPRESSION") treeVersion

    val nodes: List<SidebarNode> = remember(workspaces, sessions, treeVersion) {
        val out = ArrayList<SidebarNode>(workspaces.size + sessions.size)
        for (ws in workspaces) {
            val expanded = tree.isExpanded(ws.id)
            out += SidebarNode.WsRow(ws, expanded)
            if (expanded) {
                // Sidebar lists MAIN sessions only; sub-agents live in the detail switcher.
                SubagentTree.mainSessionsOnly(byWs[ws.id].orEmpty())
                    .forEach { out += SidebarNode.SessionRow(it) }
            }
        }
        out
    }
    val c = colors()

    LazyColumn(Modifier.fillMaxSize()) {
        items(nodes, key = { it.key }) { node ->
            when (node) {
                is SidebarNode.WsRow -> {
                    val ws = node.ws
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(c.sidebarHeaderBackground)
                            .clickable { onToggleWs(ws.id) }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (node.expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = c.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            ws.display,
                            style = MaterialTheme.typography.labelLarge,
                            color = c.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 2.dp),
                        )
                        Text("${ws.sessionCount}", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    }
                }

                is SidebarNode.SessionRow -> {
                    val s = node.session
                    val isSel = s.id == selectedId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isSel) c.selectedBackground else Color.Transparent)
                            .clickable { onSelect(s) }
                            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape).background(
                                if (StopButtonPolicy.isRunning(s)) c.dotRunning else c.dotIdle
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.title.ifBlank { "(untitled)" },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSel) c.onSelectedText else c.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                // messageCount 恒为 0（网关列表不再 enrich，见 relativeTimeLabel
                                // 注释）；改成相对时间，来自会话行自带的 updatedAt。
                                relativeTimeLabel(System.currentTimeMillis(), s.updatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSel) c.onSelectedText else c.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingView(
    error: String?,
    onRequestCamera: () -> Unit,
    onManual: (String, String) -> Unit,
    /** 非空 = 已配对时进来换 token，给用户一个「返回」（不把他困在配对页）。 */
    onCancel: (() -> Unit)? = null,
) {
    var host by remember { mutableStateOf("") }
    var tk by remember { mutableStateOf("") }
    val c = colors()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Scan the QR code, or type the PC address", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRequestCamera) { Text("Open scanner") }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("Host (e.g. 192.168.2.27 or 100.64.1.5)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = tk, onValueChange = { tk = it },
            label = { Text("Token (32 hex chars)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { onManual(host.trim(), tk.trim()) }) { Text("Connect") }
        if (onCancel != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = c.errorText)
        }
    }
}

/**
 * Extracts the **hostname only** (no port, no scheme).
 *
 * ⚠ FORBIDDEN TO USE FOR BUILDING URLs.
 * This function deliberately drops the port, so `ws://<hostOf(baseUrl)>/...`
 * silently connects to port 80. That exact mistake cost us a long hunt for the
 * missing `subscribe` frame (2026-12: the WS URL was built without `:8765`).
 * Use [streamUrl] for the WebSocket address.
 */
internal fun hostOf(baseUrl: String): String =
    baseUrl.removePrefix("http://").substringBeforeLast(':')

/**
 * WebSocket endpoint for a paired base URL.
 *
 * `baseUrl` is `http://host:port` (see [com.deepseek.dshremote.core.PairingInfo.baseUrl]),
 * so swapping the scheme **preserves the port** -- which `hostOf` would have lost.
 */
internal fun streamUrl(baseUrl: String, token: String): String =
    baseUrl.replaceFirst("http://", "ws://") + "/rc/v1/stream?token=" + token
