package com.deepseek.dshremote.core

import java.util.concurrent.atomic.AtomicLong

/** 虚拟时钟。 */
class FakeClock(start: Long = 1_000_000L) : Clock {
    private val t = AtomicLong(start)
    override fun nowMs(): Long = t.get()
    fun advance(ms: Long) { t.addAndGet(ms) }
}

/**
 * 手动推进的虚拟调度器：把所有延时行为变成可确定性触发的回调。
 *
 * 这是「25s ping / 10s pong 超时 / 500ms→30s 退避 / 2s busy 重试」能在
 * **纯 JVM 单测**里精确验证的关键（不 sleep、不依赖真实时间）。
 */
class FakeScheduler : Scheduler {

    private data class Task(val id: Long, val dueAt: Long, val action: () -> Unit)

    private val tasks = ArrayList<Task>()
    private var now = 0L
    private var seq = 0L

    /** 每次 schedule 的延时（供断言"确实排了 25s 的 ping"）。 */
    val scheduledDelays = mutableListOf<Long>()

    val pendingCount: Int get() = tasks.size
    val currentTimeMs: Long get() = now

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val id = ++seq
        tasks += Task(id, now + delayMs, task)
        scheduledDelays += delayMs
        return Cancellable { tasks.removeAll { it.id == id } }
    }

    /** 推进虚拟时间；按到期先后执行所有任务（同一时刻按排入顺序）。 */
    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val due = tasks.filter { it.dueAt <= target }.minByOrNull { it.dueAt }
                ?: break
            tasks.remove(due)
            now = due.dueAt
            due.action()
        }
        now = target
    }

    /** 只推进到下一个任务（用于"下一步会发生什么"的断言）。 */
    fun runNext(): Long {
        val due = tasks.minByOrNull { it.dueAt } ?: return -1
        tasks.remove(due)
        now = due.dueAt
        due.action()
        return due.dueAt
    }

    fun reset() {
        tasks.clear(); scheduledDelays.clear(); now = 0L; seq = 0L
    }
}

/** 固定序列的随机源，便于精确断言抖动边界。 */
class FakeRandom(private val values: List<Double>) : RandomSource {
    private var i = 0
    override fun nextDouble(): Double = values.getOrElse(i) { values.last() }.also { i++ }
}

/**
 * 假传输：记录发出的帧，可手动触发 onOpen / onText / onClosed。
 */
class FakeTransport : Transport {
    var url: String? = null
    val sent = mutableListOf<String>()
    var closed = false
    var listener: Transport.Listener? = null
    var connectCalls = 0
    var lastSendReturned: Boolean = true

    override fun connect(url: String, listener: Transport.Listener) {
        this.url = url
        this.listener = listener
        connectCalls++
    }

    override fun send(text: String): Boolean {
        if (lastSendReturned) sent += text
        return lastSendReturned
    }

    override fun close() {
        closed = true
    }

    // —— 测试用触发器 ——
    fun open() = listener?.onOpen()
    fun text(json: String) = listener?.onText(json)
    fun closedByServer(code: Int = 1006, reason: String = "") = listener?.onClosed(code, reason)
    fun fail(msg: String) = listener?.onError(RuntimeException(msg))

    /** 已发出的 prompt 帧的 requestId 列表。 */
    fun sentPromptRequestIds(): List<String> = sent.mapNotNull { frame ->
        val m = Regex("\"requestId\":\"([^\"]+)\"").find(frame)
        if (frame.contains("\"type\":\"prompt\"")) m?.groupValues?.get(1) else null
    }
}
