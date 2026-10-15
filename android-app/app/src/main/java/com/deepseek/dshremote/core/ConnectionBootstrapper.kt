package com.deepseek.dshremote.core

import java.util.concurrent.atomic.AtomicLong

/**
 * 连接启动器（D1 / D2 / D3 的修复核心）。
 *
 * 缺陷背景（Android 14 模拟器 + 真实网关实测，WS 从未建立）：
 *  - **D1 竞态**：配对 `savePairing()` 是 suspend，落盘尚未完成服务就被启动 ⇒ 读到 null；
 *  - **D2 一次性标志**：`if (!started)` 让首次早退成为终局 ⇒ 之后再无人重试；
 *  - **D3 重启不恢复**：服务只在配对回调里启动，App 重启后无人恢复长连接。
 *
 * 本类把「读配对 → 建连」抽成**幂等且可重试**的一步，因此：
 *  - 未配对时进入 `WAITING_PAIRING`，**不是终局**；配对一落盘（[onPairingSaved]）即可建连；
 *  - 已配对时（下一次 App 启动的全新实例）**自动建连**，无需 UI 参与；
 *  - 重复调用是幂等的（已建连就不重复建）。
 *
 * 纯 Kotlin、无 Android 依赖 ⇒ 上述全部可在 JVM 单测里确定性复现。
 */
class ConnectionBootstrapper(
    private val loadPairing: suspend () -> PairingInfo?,
    private val onConnect: (PairingInfo) -> Unit,
    /** 未配对时最多重试几次仍读不到配对 ⇒ 认定"确实没配对"，停止打转。 */
    private val maxUnpairedRetries: Int = 3,
) {
    enum class Phase {
        /** 还没尝试过。 */
        IDLE,

        /** 已尝试但读不到配对，正在等配对落盘（D1）。 */
        WAITING_PAIRING,

        /** 反复尝试仍无配对 ⇒ 用户确实没配对，等 UI 引导。 */
        UNPAIRED,

        /** 已建立（或已开始建立）连接。 */
        CONNECTED,
    }

    private val attempts = AtomicLong(0)

    /**
     * 预算是**总尝试次数**（不是"额外重试次数"），且必须 ≤ [maxUnpairedRetries]。
     * （原写成 `attempts > max` 时会出现 `attempts = max+1`，与"封顶"语义不符。）
     */
    @Volatile private var retryBudgetExhausted = false

    val phase: Phase get() = when {
        isConnected -> Phase.CONNECTED
        retryBudgetExhausted -> Phase.UNPAIRED
        attempts.get() > 0 -> Phase.WAITING_PAIRING
        else -> Phase.IDLE
    }

    @Volatile private var isConnected = false

    /** 本实例是否已创建过 ConnectionManager（服务用它判断是否需要再建）。 */
    val managerCreated: Boolean get() = isConnected

    /** 已尝试次数。 */
    val attemptCount: Long get() = attempts.get()

    /** 未配对时是否已用尽重试预算（UI 可据此显示"请先配对"）。 */
    val retriesExhausted: Boolean get() = retryBudgetExhausted

    /**
     * 幂等地确保连接已建立。
     *
     * 注意：**用尽未配对预算后仍允许 [onPairingSaved] 再驱动一次**——
     * 那时 DataStore 里已经真正落了盘，属于新事实，必须能建连（这就是 D1/D2 的修复点）。
     *
     * @return true 表示本次调用建立了连接；false 表示无需建立（已连接）或暂时建不了（未配对）。
     */
    suspend fun ensureConnected(): Boolean {
        if (isConnected) return false                       // 幂等
        val n = attempts.incrementAndGet()
        if (n >= maxUnpairedRetries) retryBudgetExhausted = true
        val pairing = runCatching { loadPairing() }.getOrNull() ?: return false
        isConnected = true
        retryBudgetExhausted = false
        runCatching { onConnect(pairing) }
        return true
    }

    /** 配对保存完成后由 UI 调用（D1：确保落盘完成再建连）。 */
    suspend fun onPairingSaved() {
        ensureConnected()
    }

    /**
     * 服务被系统再次拉起（`START_STICKY`）或用户手动点"启动保活"时调用。
     * 关键：即使此前因未配对早退过，这里也会重新驱动一次（D2）。
     */
    suspend fun onStartCommandLike() {
        ensureConnected()
    }

    /** 配对被清除（用户点"解除配对"/rotate）后复位，允许下次重新建连。 */
    fun reset() {
        isConnected = false
        retryBudgetExhausted = false
        attempts.set(0)
    }
}

/**
 * 连接状态的全局广播（D4）。
 *
 * 缺陷：UI 只在详情页订阅 `onState`，列表页拿不到真实状态 ⇒ 恒显示「未连接」，
 * 既误导用户又掩盖真实故障。
 *
 * 修复：状态由服务统一 publish，**任意页面**都能订阅；UI 生命周期内订阅/退订。
 */
class ConnectionStateBus {
    private val lock = Any()
    private val subs = LinkedHashMap<Long, (ConnectionSnapshot) -> Unit>()
    private var seq = 0L
    private var latest = ConnectionSnapshot(ConnectionState.DISCONNECTED, 0, 0, null, 0)

    /** 订阅；返回退订句柄。 */
    fun subscribe(listener: (ConnectionSnapshot) -> Unit): Long = synchronized(lock) {
        val id = ++seq
        subs[id] = listener
        listener(latest)          // 立即回放当前状态，避免新页面显示默认值
        id
    }

    fun unsubscribe(token: Long) {
        synchronized(lock) { subs.remove(token) }
    }

    /** 当前快照。 */
    fun current(): ConnectionSnapshot = synchronized(lock) { latest }

    /** 服务侧发布状态（任何状态变化都会通知所有订阅者）。 */
    fun publish(snapshot: ConnectionSnapshot) {
        val listeners = synchronized(lock) {
            latest = snapshot
            subs.values.toList()
        }
        for (l in listeners) runCatching { l(snapshot) }
    }
}
