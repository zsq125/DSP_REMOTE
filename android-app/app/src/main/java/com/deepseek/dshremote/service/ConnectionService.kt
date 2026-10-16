package com.deepseek.dshremote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.deepseek.dshremote.DshRemoteApp
import com.deepseek.dshremote.R
import com.deepseek.dshremote.core.Clock
import com.deepseek.dshremote.core.ConnectionManager
import com.deepseek.dshremote.core.ConnectionState
import com.deepseek.dshremote.core.Cancellable
import com.deepseek.dshremote.core.ConnectionBootstrapper
import com.deepseek.dshremote.core.ConnectionStateBus
import com.deepseek.dshremote.core.PendingCommand
import com.deepseek.dshremote.core.PendingQueue
import com.deepseek.dshremote.core.QueueFullException
import com.deepseek.dshremote.core.RandomSource
import com.deepseek.dshremote.core.Scheduler
import com.deepseek.dshremote.net.OkHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * 前台服务：持有 WS 长连接（规范 §4）。
 *
 * 关键约束：
 *  - `startForeground()` 必须在 `onStartCommand` 后 **5s 内**调用；
 *  - 通知 `setOngoing(true)` + 高优先级；
 *  - Android 14 需 `foregroundServiceType="dataSync"`（manifest 已声明）。
 */
class ConnectionService : Service() {

    companion object {
        private const val TAG = "DshConnectionService"
        const val CHANNEL_ID = "dsh_connection"
        const val NOTIF_ID = 1001
        /** 换绑 / 轮换 token：要求服务用新配对重建连接（见 [pairingChanged]）。 */
        const val ACTION_PAIRING_CHANGED = "com.deepseek.dshremote.action.PAIRING_CHANGED"
        /** startForeground 的硬性时限（规范：5s） */
        const val FOREGROUND_DEADLINE_MS = 5_000L

        @Volatile var instance: ConnectionService? = null
            private set

        fun start(context: Context) {
            val i = Intent(context, ConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        /**
         * 配对信息发生变化（重新配对 / 网关轮换 token）时调用。
         *
         * 与 [start] 的区别：这会让服务**丢弃旧连接并用新配对重建**，而不是走幂等的
         * `ensureConnected()`（后者在已连接时直接返回，连接会一直停在旧 URL / 旧 token 上）。
         */
        fun pairingChanged(context: Context) {
            val i = Intent(context, ConnectionService::class.java)
                .setAction(ACTION_PAIRING_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private val scheduler = HandlerScheduler(Handler(Looper.getMainLooper()))
    private var queue: PendingQueue? = null
    private var manager: ConnectionManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var fgDeadline: Cancellable? = null

    /** D1/D2/D3：幂等的连接启动器（取代原先的一次性 `started` 标志）。 */
    private var bootstrapper: ConnectionBootstrapper? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startUp()                 // D3：服务一创建就驱动一次，已配对即自动恢复长连接
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ① 5s 内必须 startForeground —— 用「兜底定时器」保证即使后续初始化卡住也不会被系统 ANR/Kill
        armForegroundDeadline()
        ensureForeground()

        // ── 2026-10-16 换绑 / 轮换 token：必须**断开旧连接**并用新配对重建 ──────
        // 根因：`ensureConnected()` 是幂等的（isConnected 后直接 return），而
        // ConnectionManager 把 URL 固定在 connect() 那一刻（`url` 字段，重连复用它）。
        // 所以轮换 token 后服务仍拿**旧 token** 反复重连 → 网关 4401 踢（真机日志实证），
        // 保活连接永远建不起来。这里给一条显式的"配对变了"路径。
        if (intent?.action == ACTION_PAIRING_CHANGED) {
            scope.launch {
                runCatching { manager?.disconnect() }
                manager = null
                bootstrapper?.reset()
                bootstrapper = null
                startUp()
            }
            return START_STICKY
        }

        // D2 修复：不再用一次性 `started` 标志。原先首次早退（未配对）后该标志已置 true，
        // 之后任何 start() 都不会再驱动建连 ⇒ ConnectionManager 永不创建（WS 永连不上）。
        // 现在每次 onStartCommand 都会驱动一次幂等的 ensureConnected()。
        scope.launch { bootstrapper?.onStartCommandLike() ?: startUp() }
        return START_STICKY
    }

    private fun armForegroundDeadline() {
        val runnable = Runnable {
            Log.w(TAG, "startForeground deadline (${FOREGROUND_DEADLINE_MS}ms) reached; calling it now")
            ensureForeground()
        }
        handler.postDelayed(runnable, FOREGROUND_DEADLINE_MS)
    }

    private fun ensureForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH,   // 高优先级
            ).apply { description = getString(R.string.notif_channel_desc) }
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)                 // 不可清除
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startUp() {
        val app = application as DshRemoteApp
        val store = app.store

        // 网络回调必须在主线程注册（框架要求），先注册再做异步初始化，
        // 这样"未配对"等早退分支也不会漏掉监听。
        registerNetworkCallback()

        // D1/D2/D3 修复：把「读配对 → 建连」交给幂等的 ConnectionBootstrapper。
        //  - D1：UI 在 savePairing 落盘**完成后**才调 onPairingSaved()，消除竞态；
        //  - D2：未配对早退不再是终局，配对一到即可建连；
        //  - D3：服务（及 App 启动路径）总会驱动一次，已配对即自动恢复长连接。
        val bs = ConnectionBootstrapper(
            loadPairing = { store.loadPairing() },
            onConnect = { pairing ->
                // onConnect 是普通回调，不能直接调 suspend 函数 —— 另起协程
                scope.launch { onPairingReady(store, pairing) }
            },
        )
        bootstrapper = bs
        scope.launch { bs.ensureConnected() }
    }

    /** 配对可用：恢复待发队列并建立 WS 长连接。 */
    private suspend fun onPairingReady(
        store: com.deepseek.dshremote.data.DshRemoteStore,
        pairing: com.deepseek.dshremote.core.PairingInfo,
    ) {
        // 队列从 DataStore 恢复（GAP-17：进程被杀后仍能排空）
        if (queue == null) {
            val rows = runCatching { store.loadQueueRows() }.getOrDefault(emptyList())
            queue = PendingQueue(
                QueueStoreBridge(
                    store,
                    rows.map {
                        PendingCommand(
                            requestId = it.requestId,
                            sessionId = it.sessionId,
                            text = it.text,
                            enqueuedAt = it.enqueuedAt,
                            attempts = it.attempts,
                            lastError = it.lastError,
                            failed = it.failed,
                        )
                    },
                )
            )
        }
        val m = ConnectionManager(
            clock = Clock.SYSTEM,
            scheduler = scheduler,
            random = RandomSource.SYSTEM,
            transportFactory = { OkHttpTransport() },
            queue = queue!!,
            // D4：状态统一广播到总线，任意页面都能订阅（原先只有详情页订阅）
            onSnapshot = { snap ->
                persist(store)
                updateNotification(snap.state)
                ConnectionStateBusHolder.bus.publish(snap)
            },
        )
        manager = m
        m.connect(pairing.streamUrl())
    }

    /**
     * 配对刚保存完成时由 UI 调用（**必须在 savePairing 落盘之后**）——D1 的关键：
     * 原实现是「保存与启动服务并发」，服务很可能先读到 null。
     */
    fun notifyPairingSaved() {
        scope.launch { bootstrapper?.onPairingSaved() ?: startUp() }
    }

    private fun persist(store: com.deepseek.dshremote.data.DshRemoteStore) {
        val q = queue ?: return
        scope.launch {
            runCatching {
                store.saveQueueRows(
                    q.snapshot().map {
                        com.deepseek.dshremote.data.QueueRow(
                            it.requestId, it.sessionId, it.text, it.enqueuedAt, it.attempts, it.lastError, it.failed,
                        )
                    }
                )
            }
        }
    }

    private fun updateNotification(state: ConnectionState) {
        val nm = getSystemService(NotificationManager::class.java)
        val text = when (state) {
            ConnectionState.CONNECTED -> getString(R.string.notif_text)
            ConnectionState.DRAINING_QUEUE -> "正在补发队列…"
            ConnectionState.BACKING_OFF -> "连接中断，退避重连中…"
            ConnectionState.CONNECTING -> "正在连接…"
            ConnectionState.WAITING_FOR_RETRY -> "等待重连…"
            ConnectionState.DISCONNECTED -> "未连接"
        }
        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build(),
        )
    }

    /** 网络变化监听（规范 §4）：Wi-Fi/蜂窝切换时触发重连。 */
    private fun registerNetworkCallback() {
        // registerNetworkCallback 必须在主线程调用（否则框架抛
        // IllegalStateException / PAUSED mode 错误），这里统一切回主线程。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { registerNetworkCallback() }
            return
        }
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                manager?.onNetworkAvailable()
            }

            override fun onLost(network: Network) {
                // 连接丢失时不动状态：由心跳超时（10s）驱动，避免误判
            }
        }
        networkCallback = cb
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        }.onFailure { Log.w(TAG, "failed to register network callback: ${it.message}") }
    }

    override fun onDestroy() {
        fgDeadline?.cancel()
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        }
        manager?.disconnect()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    /** 把 core 的 [PendingQueue] 接到 DataStore（写盘失败不影响内存队列）。 */
    private class QueueStoreBridge(
        private val store: com.deepseek.dshremote.data.DshRemoteStore,
        initial: List<PendingCommand>,
    ) : com.deepseek.dshremote.core.QueueStore {
        @Volatile private var rows: List<PendingCommand> = initial
        override fun load(): List<PendingCommand> = rows
        override fun save(items: List<PendingCommand>) {
            rows = items
            // 同步写盘在 IO 线程；这里用单线程执行器避免阻塞调用方
            io.execute {
                runBlocking {
                    runCatching {
                        store.saveQueueRows(
                            items.map {
                                com.deepseek.dshremote.data.QueueRow(
                                    it.requestId, it.sessionId, it.text, it.enqueuedAt, it.attempts, it.lastError, it.failed,
                                )
                            }
                        )
                    }
                }
            }
        }

        private val io = Executors.newSingleThreadExecutor()
    }
}

/**
 * 进程级连接状态总线（D4）。
 *
 * 缺陷：UI 只在详情页订阅 `onState`，列表页拿不到真实状态 ⇒ 恒显示「未连接」，
 * 既误导用户又掩盖真实故障。
 *
 * 修复：服务是唯一的状态生产者，UI 任意页面订阅本总线即可；
 * `subscribe()` 会**立即回放当前快照**，避免新页面先闪一下默认值。
 */
object ConnectionStateBusHolder {
    val bus = ConnectionStateBus()
}

/** 主线程 Handler 实现的 [Scheduler]。 */
class HandlerScheduler(private val handler: Handler) : Scheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val r = Runnable { task() }
        handler.postDelayed(r, delayMs)
        return Cancellable { handler.removeCallbacks(r) }
    }
}
