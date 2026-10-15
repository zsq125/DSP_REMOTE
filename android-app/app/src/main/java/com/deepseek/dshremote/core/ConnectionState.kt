package com.deepseek.dshremote.core

/**
 * 连接状态机（规范 §4 第 3 条）：
 * `DISCONNECTED / CONNECTING / CONNECTED / WAITING_FOR_RETRY / BACKING_OFF / DRAINING_QUEUE`
 * 必须在 UI 可见，故定义为一等公民枚举。
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    WAITING_FOR_RETRY,
    BACKING_OFF,
    DRAINING_QUEUE;

    /** UI 文案（中文）。 */
    val label: String
        get() = when (this) {
            DISCONNECTED -> "未连接"
            CONNECTING -> "连接中"
            CONNECTED -> "已连接"
            WAITING_FOR_RETRY -> "等待重连"
            BACKING_OFF -> "退避中"
            DRAINING_QUEUE -> "正在补发队列"
        }

    val isActive: Boolean get() = this != DISCONNECTED
}

/** 指数退避（规范 §4：初始 500ms、上限 30s、抖动 0–50%）。 */
object Backoff {
    const val INITIAL_MS = 500L
    const val MAX_MS = 30_000L
    const val JITTER_RATIO = 0.5

    /**
     * 第 [attempt] 次（从 0 开始）的退避时长。
     * 基础值 = min(500 * 2^attempt, 30000)；实际值 = 基础值 × (1 + rand × 0.5)，
     * 即抖动区间 **[0%, +50%]**（规范原文「抖动 0–50%」按此解读：至少为基础值，最多 +50%）。
     */
    fun baseDelayMs(attempt: Int): Long {
        require(attempt >= 0) { "attempt must be >= 0" }
        var d = INITIAL_MS
        repeat(attempt) {
            if (d >= MAX_MS) return MAX_MS
            d *= 2
        }
        return if (d > MAX_MS) MAX_MS else d
    }

    /** @param random01 注入的 [0,1) 随机数，便于单测 */
    fun delayMs(attempt: Int, random01: Double): Long {
        require(random01 in 0.0..1.0) { "random01 must be in [0,1]" }
        val base = baseDelayMs(attempt)
        val jitter = (base * JITTER_RATIO * random01).toLong()
        return (base + jitter).coerceAtMost(MAX_MS)
    }
}

/** 唯一时钟来源，测试注入虚拟时钟。 */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** 唯一调度来源，测试注入手动推进器。 */
interface Scheduler {
    /** 延时 [ms] 后执行 [task]；返回一个可取消的句柄。 */
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

fun interface Cancellable {
    fun cancel()
}

/** 随机来源，测试注入固定序列。 */
interface RandomSource {
    fun nextDouble(): Double

    companion object {
        val SYSTEM: RandomSource = object : RandomSource {
            override fun nextDouble(): Double = Math.random()
        }
    }
}
