package com.deepseek.dshremote.core

/**
 * 对话区的**记录节点流**（需求 2：照抄 DSH）。
 *
 * 参考（实际读过的 DSH 前端源码，不是凭印象）：
 *  - `dsh-client-ui-conversation/lib/types/client/contract/records.d.ts`
 *    · `AssistantBlock = {kind:'text'} | {kind:'reasoning'} | {kind:'image'}
 *                        | {kind:'tool-call', callId,name,argsRaw} | {kind:'other'}`
 *    · `ConversationNode = User | Assistant | Steering | Context | ModelRetry | TurnError
 *                        | TurnMaxTokens | ToolResult | Command | CompactionSummary | Unknown`
 *    · `ToolResultNode { callId, call: {name,argsRaw} | null, callTime, content, isError, subCalls }`
 *      —— 其中 `call` 注释明确：**窗口截断导致 call 落在窗口外时为 null，卡片头改显示 callId**；
 *         这就是"不可展开 marker"的语义来源。
 *  - `dsh-client-ui-chat/lib/types/client/chat/use-scroll-follow.d.ts`
 *    · `ScrollFollow(following, threshold)`，`nearBottom(metrics)` 用**自身阈值**判定；
 *    · `sample(metrics, movedByReader)` 只有**读者实际移动**才改变跟随意图；
 *    · 动画进行中不接受读者输入（`interrupt` 先取消原生动画）。
 *  - `.../chat/use-chat-reading.d.ts`：`followingTail` / `followTail()` /
 *    「显式导航会释放底部跟随」——即"用户上滚后停止跟随"。
 *
 * 抄到 App 的具体行为：
 *  1. 消息不是"一坨文本"，而是**节点流**：assistant 的 reasoning / text / tool-call
 *     是**独立块**，可分别折叠；
 *  2. tool call ↔ result 用 `callId` 合成**一张卡片**（不是两行）；
 *  3. 窗口外的节点降级为**不可展开的 marker**（`ToolCard.headFromCallId`）；
 *  4. 自动跟随：仅当用户**本来就在底部**时跟随；上滚即停止并可"回到底部"；
 *  5. 同回合/同角色的连续块合并成组，组内不加间距、组间加。
 */
enum class NodeKind {
    USER, ASSISTANT, TEXT, REASONING, TOOL_CALL, TOOL_RESULT,
    STEERING, CONTEXT, TURN_ERROR, COMPACTION, UNKNOWN;

    /** DSH 的 `kind` 字符串（用于与网关字段对齐）。 */
    val wire: String
        get() = when (this) {
            USER -> "user"
            ASSISTANT -> "assistant"
            TEXT -> "message"
            REASONING -> "reasoning"
            TOOL_CALL -> "tool-call"
            TOOL_RESULT -> "tool-result"
            STEERING -> "steering"
            CONTEXT -> "context"
            TURN_ERROR -> "turn-error"
            COMPACTION -> "compaction"
            UNKNOWN -> "unknown"
        }
}

/** 一个可渲染的节点（对齐 DSH `ConversationNode` 的可用子集）。 */
data class ConversationNode(
    val kind: NodeKind,
    val seq: Long,
    val time: Long,
    val text: String = "",
    val reasoning: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val isError: Boolean = false,
    /** 折叠行摘要（DSH 的 collapsedSummary 同义）。 */
    val summary: String = "",
    /** 该节点是否被窗口截断（DSH：截断后 marker 不可展开）。 */
    val truncated: Boolean = false,
    /** 所属回合（用于分组）。 */
    val turn: Int = 0,
    /** 同一消息内的块序（DSH 用 blocks 数组保序，这里用显式序号）。 */
    val order: Int = 0,
    /** 是否是流式中的临时节点。 */
    val streaming: Boolean = false,
) {
    /** DSH 语义：可展开 = 有正文 且 未被窗口截断。 */
    val expandable: Boolean
        get() = when (kind) {
            NodeKind.REASONING -> !reasoning.isNullOrEmpty() && !truncated
            NodeKind.TOOL_CALL -> !truncated && (!toolInput.isNullOrEmpty() || !toolName.isNullOrEmpty())
            NodeKind.TOOL_RESULT -> !truncated && !toolOutput.isNullOrEmpty()
            else -> false
        }
}

/** 工具卡片（对齐 DSH `ToolResultNode` + `ToolCallHead`）。 */
data class ToolCardNode(
    val callId: String,
    val name: String?,
    val argsRaw: String?,
    val output: String?,
    val isError: Boolean,
    val callTime: Long?,
    val resultTime: Long?,
    /** call 落在窗口外时为 null（DSH：卡片头改显示 callId）。 */
    val call: ToolCallHead? = null,
    val truncated: Boolean = false,
) {
    /** DSH `ToolCallHead`：`{name, argsRaw}`。 */
    data class ToolCallHead(val name: String?, val argsRaw: String?)
    /** 折叠行标题：DSH 显示工具名；call 缺失时显示 callId。 */
    val head: String
        get() = when {
            !name.isNullOrEmpty() -> name
            call != null -> call.name.orEmpty().ifEmpty { callId }
            else -> callId
        }

    /** call-row duration（DSH `callTime` → result 的耗时；缺任一端为 null）。 */
    val durationMs: Long?
        get() = if (callTime != null && resultTime != null && resultTime >= callTime) resultTime - callTime else null

    val expandable: Boolean
        get() = !truncated && ((!argsRaw.isNullOrEmpty()) || (!output.isNullOrEmpty()))
}

/**
 * 底部跟随控制器（对齐 DSH `ScrollFollow`）。
 *
 * 关键语义（照抄）：
 *  - 只有**读者实际滚动**才改变跟随意图（`sample(movedByReader=true)`）；
 *  - 「在底部」用**阈值**判定，不是必须精确等于 0；
 *  - 内容增长时若 `following` 才滚到底；
 *  - 用户上滚 ⇒ 停止跟随（上滚到底部以外即视为读者离开了底部）。
 */
class ScrollFollowController(
    initial: Boolean = true,
    /** 距底部多少像素内仍算"在底部"（DSH 用像素阈值，这里用行数等价）。 */
    val threshold: Float = 2f,
) {
    var following: Boolean = initial
        private set

    /** 是否处于"跟随底部"的意图（与当前偏移解耦，DSH 的 `active`）。 */
    val active: Boolean get() = following

    /** 当前是否在底部（阈值内）。 */
    fun nearBottom(distanceFromBottom: Float): Boolean = distanceFromBottom <= threshold

    /**
     * 采样一次滚动位置。
     * @param movedByReader 用户是否主动滚动（程序滚动不算）
     * @return 更新后的跟随意图
     */
    fun sample(distanceFromBottom: Float, movedByReader: Boolean): Boolean {
        if (movedByReader) following = nearBottom(distanceFromBottom)
        return following
    }

    /** 内容增长后调用：仅在跟随时返回 true（UI 据此滚到底）。 */
    fun shouldFollowGrowth(distanceFromBottom: Float): Boolean {
        if (movedByReaderAway(distanceFromBottom)) return false
        return following
    }

    private fun movedByReaderAway(distanceFromBottom: Float): Boolean =
        !nearBottom(distanceFromBottom)

    /** 显式「回到底部」：恢复跟随。 */
    fun followTail() { following = true }

    /** 显式导航（如点 Turn 导航器）：释放跟随。 */
    fun releaseFollow() { following = false }

    /** 重置（新会话/新窗口）。 */
    fun reset(initial: Boolean = true) { following = initial }
}

/**
 * 记录节点流构建器（需求 2 的核心）。
 *
 * 输入是网关 `/messages` 的行，输出是**对齐 DSH 的节点流**：
 *  - assistant 行拆成 `reasoning` + `text` 两个独立块；
 *  - tool-call 与 tool-result 合成**一张卡片**；
 *  - 窗口外的内容降级为不可展开 marker；
 *  - 同回合/同角色连续块归为一组（组内无间距、组间有间距）。
 */
object ConversationNodeBuilder {

    /** 把消息行转成节点流。 */
    fun build(rows: List<com.deepseek.dshremote.protocol.Message>): List<ConversationNode> {
        val out = mutableListOf<ConversationNode>()
        var order = 0
        for (m in rows) {
            val truncated = m.reasoning?.contains("[truncated") == true
                || m.toolOutput?.contains("[truncated") == true
                || m.toolInput?.contains("[truncated") == true
            when {
                m.kind == "tool-call" -> out += ConversationNode(
                    kind = NodeKind.TOOL_CALL, seq = m.seq, time = m.ts, order = order++,
                    toolName = m.toolName, toolCallId = m.toolCallId,
                    toolInput = m.toolInput, summary = m.collapsedSummary,
                    truncated = truncated,
                )

                m.kind == "tool-result" -> out += ConversationNode(
                    kind = NodeKind.TOOL_RESULT, seq = m.seq, time = m.ts, order = order++,
                    toolName = m.toolName, toolCallId = m.toolCallId,
                    toolOutput = m.toolOutput, isError = m.isError,
                    summary = m.collapsedSummary, truncated = truncated,
                )

                // DSH `AssistantMessageNode.blocks` 是一条**块数组**：reasoning 与
                // text 是**两个独立块**，块内顺序即渲染顺序。
                // 两者保留**同一个真实 seq**（对外契约不变），另用 order 维护块序。
                m.role == "assistant" && m.hasReasoning -> {
                    out += ConversationNode(
                        kind = NodeKind.REASONING, seq = m.seq, time = m.ts, order = order++,
                        reasoning = m.reasoning, summary = m.collapsedSummary,
                        truncated = truncated,
                        streaming = m.status == "streaming",
                    )
                    if (m.text.isNotEmpty()) {
                        out += ConversationNode(
                            kind = NodeKind.TEXT, seq = m.seq, time = m.ts, order = order++,
                            text = m.text, summary = m.collapsedSummary,
                            streaming = m.status == "streaming",
                        )
                    }
                }

                m.role == "assistant" -> out += ConversationNode(
                    kind = NodeKind.TEXT, seq = m.seq, time = m.ts,
                    text = m.text, summary = m.collapsedSummary,
                    streaming = m.status == "streaming",
                )

                m.role == "user" -> out += ConversationNode(
                    kind = NodeKind.USER, seq = m.seq, time = m.ts,
                    text = m.text, summary = m.collapsedSummary,
                )

                m.role == "system" -> out += ConversationNode(
                    kind = NodeKind.CONTEXT, seq = m.seq, time = m.ts,
                    text = m.text,
                )

                else -> out += ConversationNode(
                    kind = NodeKind.UNKNOWN, seq = m.seq, time = m.ts,
                    text = m.text,
                )
            }
        }
        // 先按 seq 排（网关已升序，稳定排序保持块内顺序），
        // 再按 order 排（同 seq 的 reasoning/text 块必须保持相邻且有序）。
        return out.sortedWith(compareBy({ it.seq }, { it.order }))
    }

    /** 按 `callId` 把 tool-call 与 tool-result 合成卡片（DSH `ToolResultNode`）。 */
    fun toolCards(nodes: List<ConversationNode>): List<ToolCardNode> {
        val calls = nodes.filter { it.kind == NodeKind.TOOL_CALL }
        val results = nodes.filter { it.kind == NodeKind.TOOL_RESULT }
        val byCallId = LinkedHashMap<String?, ToolCardNode>()
        for (c in calls) {
            byCallId[c.toolCallId] = ToolCardNode(
                callId = c.toolCallId.orEmpty(),
                name = c.toolName,
                argsRaw = c.toolInput,
                output = null,
                isError = false,
                callTime = c.time,
                resultTime = null,
                call = if (c.truncated || c.toolName == null) null
                else ToolCardNode.ToolCallHead(c.toolName, c.toolInput),
                truncated = c.truncated,
            )
        }
        for (r in results) {
            val id = r.toolCallId
            val existing = byCallId[id]
            byCallId[id] = if (existing == null) {
                // call 落在窗口外 ⇒ DSH：call=null，卡片头显示 callId
                ToolCardNode(
                    callId = id.orEmpty(), name = r.toolName, argsRaw = null,
                    output = r.toolOutput, isError = r.isError,
                    callTime = null, resultTime = r.time, call = null, truncated = r.truncated,
                )
            } else {
                existing.copy(
                    output = r.toolOutput, isError = existing.isError || r.isError,
                    resultTime = r.time, truncated = existing.truncated || r.truncated,
                )
            }
        }
        return byCallId.values.sortedBy { it.callId }
    }

    /**
     * 分组：连续的同 `kind` 节点归一组（组内不加间距、组间加）。
     * DSH 按 Turn/角色分组（`conversation-nodes/common.d.ts` 的 "Assistant, its
     * follow-up Nodes, then follow-ups to an ordinary final"），这里用可测的近似规则。
     */
    fun group(nodes: List<ConversationNode>): List<List<ConversationNode>> {
        fun family(k: NodeKind): Int = when (k) {
            // assistant 的各类块同属一条消息（组内零间距）
            NodeKind.REASONING, NodeKind.TEXT, NodeKind.TOOL_CALL, NodeKind.TOOL_RESULT, NodeKind.ASSISTANT -> 1
            NodeKind.USER -> 2
            NodeKind.STEERING -> 3
            NodeKind.CONTEXT -> 4
            NodeKind.TURN_ERROR -> 5
            NodeKind.COMPACTION -> 6
            NodeKind.UNKNOWN -> 7
        }
        val out = mutableListOf<List<ConversationNode>>()
        var cur = mutableListOf<ConversationNode>()
        var last: Int? = null
        for (n in nodes) {
            val f = family(n.kind)
            if (last != null && f != last) { out += cur; cur = mutableListOf() }
            cur.add(n)
            last = f
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }
}
