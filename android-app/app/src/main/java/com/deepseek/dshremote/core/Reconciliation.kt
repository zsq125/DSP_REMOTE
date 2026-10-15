package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.Message

/**
 * 崩溃恢复对账（规范 §9.7）。
 *
 * 三种 id 各司其职，务必分清：
 *  - 本地待发队列项的 `requestId` → 乐观上屏的锚点；
 *  - `/messages` 行上的 `requestId`（durable `source.rpcId`）→ **权威对账键**；
 *  - prompt 回执的 `messageId`（网关受理 id）→ **只用于同一次发送的幂等重试**，
 *    不能用来判断「是否真的落库」（受理时内层还没落库）。
 *
 * 因此进程被杀再重启后：把持久队列里的每条按 `requestId` 去 `/messages` 里找，
 * 找到 ⇒ 已落库，从队列移除；找不到 ⇒ 仍在路上或已丢失，重发（幂等保证不会重复）。
 */
object Reconciliation {

    /** 一条待发命令的对账结论。 */
    enum class Outcome {
        /** 已在 durable 历史中找到 ⇒ 可从队列移除。 */
        ALREADY_PERSISTED,

        /** durable 历史中还没有 ⇒ 保留在队列里等待（或重发）。 */
        STILL_PENDING,

        /** 同一条文本出现了多行 ⇒ 数据异常，需要人工介入（不自动丢弃）。 */
        DUPLICATED,
    }

    /** 该 requestId 在 durable 历史里出现了几次。 */
    fun countDurable(messages: List<Message>, requestId: String): Int =
        messages.count { it.role == "user" && it.requestId == requestId }

    /** 对账单条命令。 */
    fun reconcile(messages: List<Message>, cmd: PendingCommand): Outcome {
        val n = countDurable(messages, cmd.requestId)
        return when {
            n > 1 -> Outcome.DUPLICATED
            n == 1 -> Outcome.ALREADY_PERSISTED
            else -> Outcome.STILL_PENDING
        }
    }

    /**
     * 批量对账：返回应当从队列移除的条目下标集合。
     * [DUPLICATED] 的条目**不自动移除**（宁可让用户看到，也不静默丢数据）。
     */
    fun reconcileAll(messages: List<Message>, pending: List<PendingCommand>): Set<Int> {
        val drop = mutableSetOf<Int>()
        pending.forEachIndexed { i, cmd ->
            if (reconcile(messages, cmd) == Outcome.ALREADY_PERSISTED) drop += i
        }
        return drop
    }

    /**
     * 把 durable 消息并入本地视图：以 `requestId` 去重。
     * 用于 App 崩溃恢复后重建消息列表（乐观上屏的条目若已落库，应被 durable 行取代）。
     *
     * `requestId` 为 null 的条目**不参与去重**（无法判定身份，一律保留）。
     */
    fun mergeByRequestId(optimistic: List<Message>, durable: List<Message>): List<Message> {
        val seen = durable.mapNotNull { it.requestId }.toSet()
        // requestId 为 null ⇒ 无法判定身份 ⇒ 一律保留（宁可重复展示也不静默丢数据）
        val extra = optimistic.filter { it.role == "user" && (it.requestId == null || it.requestId !in seen) }
        return (durable + extra).sortedBy { it.seq }
    }
}
