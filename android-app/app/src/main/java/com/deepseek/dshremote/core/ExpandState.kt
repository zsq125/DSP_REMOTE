package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.Message

/**
 * reasoning / tool 的折叠-展开状态（需求 3）。
 *
 * 规则：
 *  - **默认全部折叠**（用户明确要求"折叠起来，点击动态加载"）；
 *  - 展开状态**按 (sessionId, seq)** 记住，滚动/重载后保持；
 *  - 只有用户**主动点开**时，UI 才去请求 `expand=full` 拿全文（动态加载），
 *    默认列表用 `expand=summary`，避免大会话一次性拉全文爆内存。
 */
class ExpandStateStore(
    private val maxTracked: Int = 500,
) {
    private val expanded = LinkedHashSet<String>()
    private fun key(sessionId: String, seq: Long) = "$sessionId#$seq"

    fun isExpanded(sessionId: String, seq: Long): Boolean = key(sessionId, seq) in expanded

    /** 切换；返回切换后的展开状态。 */
    fun toggle(sessionId: String, seq: Long): Boolean {
        val k = key(sessionId, seq)
        val now = if (expanded.contains(k)) { expanded.remove(k); false } else { expanded.add(k); true }
        if (expanded.size > maxTracked) {
            // drop the oldest entries so the set cannot grow without bound
            val snapshot = expanded.toList()
            val dropCount = expanded.size - maxTracked
            repeat(dropCount) { expanded.remove(snapshot[it]) }
        }
        return now
    }

    fun collapse(sessionId: String, seq: Long) { expanded -= key(sessionId, seq) }

    fun collapseAll() { expanded.clear() }

    val expandedCount: Int get() = expanded.size

    /** 该行在当前状态下是否需要向服务端请求全文。 */
    fun needsFullContent(m: Message): Boolean =
        m.isExpandable && !m.textIsAlreadyFull()

    private fun Message.textIsAlreadyFull(): Boolean =
        // summary 级别下服务端会截断并追加 …[truncated N]；有该标记说明还没拿到全文
        !(reasoning?.contains("[truncated") == true || (toolOutput?.contains("[truncated") == true))
}

/**
 * 把 tool-call 与 tool-result 配对成一张可展开卡片（需求 3）。
 *
 * 网关用 `toolCallId` 关联两者；配对后 App 只需渲染一张卡片：
 *   折叠行 = `[tool] <name>`，展开体 = 入参 + 输出（失败标红）。
 */
object ToolCardBuilder {

    /** 一张工具卡片。 */
    data class Card(
        val callSeq: Long,
        val callId: String?,
        val name: String,
        val input: String?,
        val output: String?,
        val isError: Boolean,
        val summary: String,
    )

    /** 按 toolCallId 配对；没有 result 的 call 也会成卡（输出为 null）。 */
    fun build(messages: List<Message>): List<Card> {
        val byCall = LinkedHashMap<String?, MutableList<Message>>()
        for (m in messages) {
            if (m.kind == "tool-call" || m.kind == "tool-result") {
                byCall.getOrPut(m.toolCallId) { mutableListOf() }.add(m)
            }
        }
        val cards = mutableListOf<Card>()
        for ((callId, group) in byCall) {
            val call = group.firstOrNull { it.kind == "tool-call" }
            val result = group.firstOrNull { it.kind == "tool-result" }
            cards += Card(
                callSeq = call?.seq ?: result?.seq ?: 0L,
                callId = callId,
                name = call?.toolName ?: result?.toolName ?: "tool",
                input = call?.toolInput,
                output = result?.toolOutput,
                isError = (call?.isError == true) || (result?.isError == true),
                summary = call?.collapsedSummary ?: result?.collapsedSummary ?: "",
            )
        }
        return cards.sortedBy { it.callSeq }
    }

    /** 卡片是否需要展开后才看得见内容。 */
    fun hasExpandableContent(c: Card): Boolean = !c.input.isNullOrEmpty() || !c.output.isNullOrEmpty()
}
