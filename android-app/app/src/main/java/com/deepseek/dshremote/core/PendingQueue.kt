package com.deepseek.dshremote.core

import java.util.UUID

/** 一条待发指令。 */
data class PendingCommand(
    val requestId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val text: String,
    /** 入队时间（毫秒），用于持久化后恢复与排序。 */
    val enqueuedAt: Long = 0L,
    /** 已重试次数（session_busy 专用，契约 GAP-15：每 2s 一次、最多 3 次）。 */
    val attempts: Int = 0,
    /** 最近一次失败原因（供 UI 展示）。 */
    val lastError: String? = null,
    /** 终态失败标记：重试用尽或不可重试的 4xx。 */
    val failed: Boolean = false,
) {
    fun withAttempt(error: String?): PendingCommand = copy(attempts = attempts + 1, lastError = error)
    fun markFailed(error: String?): PendingCommand = copy(failed = true, lastError = error)
}

/** 队列条目总数上限（契约 GAP-16）。 */
const val QUEUE_MAX_SIZE = 200

/** `session_busy` 的重试间隔与次数（契约 GAP-15）。 */
const val BUSY_RETRY_INTERVAL_MS = 2_000L
const val BUSY_MAX_RETRIES = 3

/** 不可重试的 4xx（除 session_busy 外）——契约 GAP-15：直接标记失败。 */
val NON_RETRYABLE_CODES = setOf("unauthorized", "invalid_request", "not_found", "dsh_unavailable")

/**
 * 持久化后端（契约 GAP-17：待发队列**必须持久化**，进程被杀后重启仍能排空）。
 *
 * Android 侧用 DataStore/文件实现；单测用内存实现。
 */
interface QueueStore {
    fun load(): List<PendingCommand>
    fun save(items: List<PendingCommand>)
}

/** 内存实现（单测用，也作为 Android 侧写盘失败时的降级）。 */
class InMemoryQueueStore(initial: List<PendingCommand> = emptyList()) : QueueStore {
    private var items: List<PendingCommand> = initial
    var saveCount: Int = 0
        private set

    override fun load(): List<PendingCommand> = items
    override fun save(items: List<PendingCommand>) {
        this.items = items
        saveCount++
    }
}

/** 队列被拒绝（已达上限）时的结果。 */
class QueueFullException(val size: Int) : IllegalStateException("pending queue is full ($size >= $QUEUE_MAX_SIZE)")

/**
 * 待发指令队列：严格 FIFO、逐条等 ack、GAP-15/16/17 全部落地。
 *
 * 本类**不碰网络**，只负责「入队 / 取出待发 / 按 ack 或错误结算」，
 * 因此可完全用单测覆盖（断网 n 次不丢消息、上限拒绝、busy 重试用尽等）。
 */
class PendingQueue(private val store: QueueStore) {

    private val items = ArrayDeque(store.load())

    /** 当前排队条数（不含正在等待 ack 的那一条）。 */
    val size: Int get() = items.size

    /** 当前是否有条目。 */
    val isEmpty: Boolean get() = items.isEmpty()

    /** 只读快照（UI / 测试用）。 */
    fun snapshot(): List<PendingCommand> = items.toList()

    /**
     * 入队。
     * @throws QueueFullException 达上限 200（GAP-16：不静默丢弃，由 UI 明确提示）
     */
    fun enqueue(sessionId: String, text: String, nowMs: Long): PendingCommand {
        if (items.size >= QUEUE_MAX_SIZE) throw QueueFullException(items.size)
        val cmd = PendingCommand(sessionId = sessionId, text = text, enqueuedAt = nowMs)
        items.addLast(cmd)
        persist()
        return cmd
    }

    /** 取出队首（待发送）。不弹出，等 ack/error 才结算。 */
    fun peek(): PendingCommand? = items.firstOrNull()

    /** 队首的 requestId（用于给 ack 对账）。 */
    fun headRequestId(): String? = items.firstOrNull()?.requestId

    /** ack 成功 → 弹出队首。返回被弹出的条目。 */
    fun ack(requestId: String): PendingCommand? {
        val head = items.firstOrNull() ?: return null
        if (head.requestId != requestId) return null
        items.removeFirst()
        persist()
        return head
    }

    /**
     * 处理一次失败。
     * - `session_busy`：每 2s 重试，最多 3 次；用尽后标记失败并弹出（GAP-15）。
     * - 其他 4xx / 5xx：直接标记失败并弹出，**不重试**（GAP-15）。
     * - 成功由 [ack] 处理。
     */
    fun fail(requestId: String, errorCode: String, message: String): PendingCommand? {
        val head = items.firstOrNull() ?: return null
        if (head.requestId != requestId) return null
        val retryable = errorCode == "session_busy" && head.attempts < BUSY_MAX_RETRIES
        if (retryable) {
            val updated = head.withAttempt(message)
            items.removeFirst()
            items.addFirst(updated)     // 保持队首位置 ⇒ 仍按 FIFO
            persist()
            return updated
        }
        val dead = head.markFailed(message)
        items.removeFirst()
        persist()
        return dead
    }

    /** 清理已标记失败的条目（UI 确认后调用）。 */
    fun clearFailed(): Int {
        val before = items.size
        items.removeAll { it.failed }
        persist()
        return before - items.size
    }

    /** 清空。 */
    fun clear() {
        items.clear()
        persist()
    }

    private fun persist() {
        store.save(items.toList())
    }
}
