package com.deepseek.dshremote.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deepseek.dshremote.protocol.Message

/**
 * 会话消息的**渲染模型**（需求 §4.4）。
 *
 * 契约里的 `Message` 是传输结构；这里转成 UI 直接消费的模型，把
 * "user 右 / assistant 左 / tool 折叠 / system 弱化" 的判断固化下来（可单测）。
 */
enum class MessageAlign { LEFT, RIGHT, FULL }

/** 渲染模型。 */
data class MessageRow(
    val seq: Long,
    val role: String,
    val text: String,
    val ts: Long,
    val status: String,
    val messageId: String?,
    val align: MessageAlign,
    /** tool 行是否折叠（`[tool] <name>` 一行摘要）。 */
    val collapsed: Boolean,
    /** system 行用弱化样式。 */
    val dimmed: Boolean,
    val isStreaming: Boolean,
    /** 乐观发送的本地消息携带其 requestId（用于发送成功后对账去重）。 */
    val requestId: String? = null,
    /** 流式帧的累积字符数（网关 `chunk.seq`），用于丢弃旧 attempt 的乱序 chunk。 */
    val streamedUpTo: Long = 0,
    // ---- 需求 3：reasoning / tool 的可展开内容（网关提供，可为空）----
    val kind: String = "message",
    val reasoning: String? = null,
    val hasReasoning: Boolean = false,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val collapsedSummary: String = "",
) {
    val isUser: Boolean get() = align == MessageAlign.RIGHT

    /** 内容是否被服务端截断（需要 expand=full 再取一次）。 */
    val needsFullContent: Boolean
        get() = reasoning?.contains("[truncated") == true || toolOutput?.contains("[truncated") == true

    /** 是否是可展开的内容块。 */
    val isExpandable: Boolean
        get() = kind == "tool-call" || kind == "tool-result" || hasReasoning

    /**
     * 该行内容被服务端/窗口截断。
     *
     * DSH 语义（`records.d.ts` 的 `ToolResultNode.call` 与 `CompactionSummaryNode`）：
     * 窗口截断后的 marker **不可展开**。App 照此不给截断行提供点击。
     */
    val truncated: Boolean
        get() = reasoning?.contains("[truncated") == true
            || toolOutput?.contains("[truncated") == true
            || toolInput?.contains("[truncated") == true
}

object MessageRenderer {

    /** 契约 role → 对齐方式。 */
    fun alignFor(role: String): MessageAlign = when (role) {
        "user" -> MessageAlign.RIGHT
        "assistant" -> MessageAlign.LEFT
        else -> MessageAlign.FULL      // tool / system 整行
    }

    /** tool 消息折叠为 `[tool] <name>` 一行摘要。 */
    fun toolSummary(text: String): String {
        val t = text.trim()
        if (t.startsWith("[tool]")) return t
        return "[tool] ${t.substringBefore('\n').take(80)}"
    }

    /** 单条转换。 */
    fun render(m: Message): MessageRow {
        val text = if (m.role == "tool") toolSummary(m.text) else m.text
        return MessageRow(
            seq = m.seq,
            role = m.role,
            text = text,
            ts = m.ts,
            status = m.status,
            messageId = m.messageId,
            align = alignFor(m.role),
            collapsed = m.role == "tool",
            dimmed = m.role == "system",
            isStreaming = m.status == "streaming",
            requestId = m.requestId,
            kind = m.kind,
            reasoning = m.reasoning,
            hasReasoning = m.hasReasoning,
            toolName = m.toolName,
            toolCallId = m.toolCallId,
            toolInput = m.toolInput,
            toolOutput = m.toolOutput,
            collapsedSummary = m.collapsedSummary,
        )
    }

    /** 批量转换。 */
    fun renderAll(list: List<Message>): List<MessageRow> = list.map { render(it) }
}

/**
 * 消息分页器（需求 §4.4 + §六.7：4654 条会话必须分页 + 懒加载、不得 OOM）。
 *
 * 设计要点：
 *  - **只保留一个窗口**：内存里最多 [windowSize] 条（默认 300），向上翻页时丢弃更旧的，
 *    因此 4654 条会话的内存占用是 **O(window)** 而不是 O(总数)；
 *  - 首屏取**最新一页**（`before = null`），向上加载用上一页返回的 `nextCursor`；
 *  - 合并时按 `seq` 去重（重连/重复请求不会产生重复行）。
 *
 * ## 为什么 [rows] 是 Compose State（2026-12 关键修复）
 * 本类**所有**变更都必须驱动 UI 重组。此前 `var rows` 是**普通 var**，
 * 于是 `appendOptimistic` / `appendStreaming` / `appendLive` 改了数据但 **Compose 观察不到**，
 * 造成用户报的三个问题：发送无反应、PC 端流式不同步、子代理切换后不刷新。
 *
 * 选**方案 A（把 `rows` 直接做成 `mutableStateOf`）**而不是"UI 层 `renderTick++`"，理由：
 *  1. **不可能漏**：`rows` 是唯一的渲染数据源，把它做成 State 后，**任何**赋值点都自动触发重组；
 *     而 `renderTick` 方案依赖"每处改动后都记得 ++"，漏一处就是又一个不刷新 bug（正是这次的教训）。
 *  2. 语义正确：`rows` 本来就是**UI 状态**，不是纯数据。
 *  3. `androidx.compose.runtime` 是**纯 JVM 可用**的，`Snapshot`/`mutableStateOf` 在单测中可直接断言
 *     （见 `MessagePagerStateTest`），不破坏 core 层可测性。
 */
class MessagePager(
    private val windowSize: Int = 300,
) {
    /** 当前窗口内的消息（按 seq 升序）。**Compose State**：任何赋值都会触发重组。 */
    var rows: List<MessageRow> by mutableStateOf(emptyList())
        private set

    /** 是否还有更早的消息可加载。 */
    var hasMore: Boolean by mutableStateOf(true)
        private set

    /** 已加载的页数（诊断用）。 */
    var pagesLoaded: Int by mutableStateOf(0)
        private set

    /** 当前最小的 seq（= 下一页的 before 游标）。 */
    val oldestSeq: Long? get() = rows.firstOrNull()?.seq

    /** 2026-10-13 增量浏览：向下增量游标（null = 窗口后没有更新的消息）。 */
    var nextAfter: Long? by mutableStateOf<Long?>(null)
        private set

    /** 首屏/最新一页。 */
    fun reset(page: List<Message>, nextCursor: Long?) {
        val rendered = MessageRenderer.renderAll(page).takeLast(windowSize)
        rows = rendered
        hasMore = nextCursor != null && rendered.isNotEmpty()
        nextAfter = null
        pagesLoaded = 1
    }

    /**
     * 搜索定位 / seq 跳转：以命中处为中心重置窗口。
     *
     * `hasNewer=false` 时窗口就是最新端，正常跟随；`hasNewer=true` 时窗口
     * 后面还有消息，必须**停止自动跟随尾巴**（用户在看历史位置）。
     */
    fun resetAround(page: List<Message>, prevCursor: Long?, nextAfter: Long?, hasNewer: Boolean?) {
        val rendered = MessageRenderer.renderAll(page).takeLast(windowSize)
        rows = rendered
        hasMore = prevCursor != null && rendered.isNotEmpty()
        this.nextAfter = nextAfter
        pagesLoaded = 1
    }

    /** 向下增量（seq > after 的最旧 limit 条）；返回实际新增行数。 */
    fun appendPage(page: List<Message>, nextAfter: Long?, hasNewer: Boolean?): Int {
        if (page.isEmpty()) { this.nextAfter = null; return 0 }
        val known = rows.mapTo(HashSet()) { it.seq }
        val fresh = MessageRenderer.renderAll(page).filter { it.seq !in known }
        this.nextAfter = nextAfter
        if (fresh.isEmpty()) return 0
        rows = (rows + fresh).takeLast(windowSize)
        pagesLoaded += 1
        return fresh.size
    }

    /**
     * 向上加载更早的一页。
     *
     * ⚠ 2026-10-14 用户优化 1 修正：这里**不能再 `take(windowSize)`**。
     * 旧实现 `(fresh + rows).take(windowSize)` 保留的是**最旧的** N 条，会把
     * 用户正在看的最新消息挤出窗口（实测：补完 4654 条后窗口只剩最旧的 1000 条，
     * 新消息全部消失）。
     *
     * 现在改为**只按剩余空间接收**：
     *  - 窗口 = 最新 N 条，**永不丢最新**；
     *  - 满了就不再接收更早的行（`hasMore=false`），把"窗口有界"这个不变量
     *    重新收回 pager 内部（单测 `窗口上限被严格遵守` / `4654 条会话…` 锁定它）。
     *
     * @return 实际新增的行数（0 = 没有更多 / 窗口已满 / 该页全部重复）。
     */
    fun prepend(page: List<Message>, nextCursor: Long?): Int {
        if (page.isEmpty()) { hasMore = false; return 0 }
        val room = windowSize - rows.size
        if (room <= 0) { hasMore = false; return 0 }        // 窗口已满：不再往更早扩
        val known = rows.mapTo(HashSet()) { it.seq }
        val fresh = MessageRenderer.renderAll(page).filter { it.seq !in known }
        if (fresh.isEmpty()) { hasMore = nextCursor != null; return 0 }
        val accept = if (fresh.size <= room) fresh else fresh.takeLast(room)
        rows = accept + rows
        // 只有这一页没被空间截断、且窗口还没满，才继续允许往更早翻。
        hasMore = nextCursor != null && accept.size == fresh.size && rows.size < windowSize
        pagesLoaded += 1
        return accept.size
    }

    /** 实时事件追加（去重 + 受窗口约束）。 */
    fun appendLive(m: Message): Boolean {
        if (m.seq >= 0 && rows.any { it.seq == m.seq }) return false
        // 2026-10-13 真机实测（"命中后只有 tool pwsh"根因）：网关在订阅时会
        // **回放积压的历史事件**（seq 155/222/266，全部 < 窗口尾 seq）。这些旧帧
        // 若直接 append，`takeLast(windowSize)` 会把定位窗口的真实内容挤出去，
        // 屏幕只剩最后一条 tool 行。
        // 守卫：非流式帧的 seq 不大于当前窗口最大 seq ⇒ 必然是旧回放/重复，丢弃。
        val maxSeq = rows.maxOfOrNull { it.seq } ?: Long.MIN_VALUE
        if (m.seq >= 0 && m.seq <= maxSeq) return false
        val row = MessageRenderer.render(m)
        rows = (rows + row).takeLast(windowSize)
        return true
    }

    /**
     * 追加一条**流式增量**（打字机效果）。
     *
     * 网关的流式帧（见 pc-gateway/README §6.2）：`chunk` 携带**本段文本**，
     * `seq` = 本次 attempt 的**累积字符数**（严格递增）。
     * 因此这里：
     *  - 按 attempt（用 seq 递增判定）**合并进同一条** streaming 行，文本持续增长；
     *  - seq 不大于已累积长度 ⇒ **旧 attempt 的乱序 chunk 直接丢弃**。
     *
     * @return 更新后的 streaming 行（无新增内容时返回 null）
     */
    fun appendStreamingChunk(text: String, seq: Long, ts: Long): MessageRow? {
        if (text.isEmpty()) return null
        val existing = rows.lastOrNull { it.isStreaming }
        // 旧 attempt / 乱序：seq 不大于已累积长度 ⇒ 丢弃
        if (existing != null && existing.streamedUpTo > 0 && seq <= existing.streamedUpTo) return null
        if (existing == null) {
            val row = MessageRow(
                seq = -ts, role = "assistant", text = text, ts = ts, status = "streaming",
                messageId = null, align = MessageAlign.LEFT, collapsed = false,
                dimmed = false, isStreaming = true, requestId = null, streamedUpTo = seq,
            )
            rows = (rows + row).takeLast(windowSize)
            return row
        }
        val merged = existing.copy(text = existing.text + text, ts = ts, streamedUpTo = seq)
        rows = rows.dropLast(1) + merged
        return merged
    }

    /**
     * 流式收尾（对应网关的 `end` 帧）。
     * @param finalText 完整文本（网关 end 帧会给；为空则用已拼接的）
     * @return 是否确实改动了状态
     */
    fun finishStreaming(finalText: String?): Boolean {
        val idx = rows.indexOfLast { it.isStreaming }
        if (idx < 0) return false
        val cur = rows[idx]
        val text = finalText?.takeIf { it.isNotBlank() } ?: cur.text
        rows = rows.toMutableList().also {
            it[idx] = cur.copy(text = text, status = "final", isStreaming = false, streamedUpTo = 0)
        }
        return true
    }

    /** 内存占用上界（用于自证「不 OOM」）。 */
    val retainedCount: Int get() = rows.size

    /** 窗口上限（= 本地历史一次最多同步多少条；补齐到它就停）。 */
    val historyCap: Int get() = windowSize

    /**
     * **乐观插入**一条本地用户消息（用户问题 1：点发送后要立刻看到）。
     *
     * 用**负 seq**（durable seq 非负），因此不会与随后 WS 推来的 durable user 行冲突；
     * 服务端真正落库后会另有一条 authoritative 行，由上层按 requestId 去重。
     */
    fun appendOptimistic(text: String, requestId: String, ts: Long): MessageRow {
        val row = MessageRow(
            seq = -ts, role = "user", text = text, ts = ts, status = "sending",
            messageId = null, align = MessageAlign.RIGHT, collapsed = false,
            dimmed = false, isStreaming = false, requestId = requestId,
        )
        rows = (rows + row).takeLast(windowSize)
        return row
    }

    /** 乐观行发送成功 ⇒ 标记已送达（否则用户以为没发出去）。 */
    fun markOptimisticSent(requestId: String) {
        rows = rows.map { if (it.requestId == requestId && it.status == "sending") it.copy(status = "final") else it }
    }

    /** 乐观行发送失败 ⇒ 保留内容并标记失败（UI 显示错误 + 可重试）。 */
    fun markOptimisticFailed(requestId: String) {
        rows = rows.map { if (it.requestId == requestId) it.copy(status = "failed") else it }
    }

    /** 丢弃失败的乐观行（用户放弃重试）。 */
    fun dropOptimistic(requestId: String) {
        rows = rows.filterNot { it.requestId == requestId && it.status == "failed" }
    }

    /** 只渲染最后 [n] 条（渐进首屏：先出后补，避免一次渲染过多）。 */
    fun visible(n: Int): List<MessageRow> = if (n >= rows.size) rows else rows.takeLast(n)

    // ── 2026-10-14 用户优化：本地历史缓存 ────────────────────────────────
    /**
     * 用**本地缓存**恢复窗口（不走网络，开屏即见）。
     *
     * @param cached 已按 seq 升序的缓存行
     * @param oldestSeq 缓存里最旧的 durable seq（= 下次向上翻页的 before 游标，
     *        这样重新打开时**不会重下已缓存的历史**，从本地最大处继续往更早走）
     * @param reachedStart 缓存是否已到会话开头（true ⇒ hasMore=false）
     */
    fun resetFromCache(cached: List<MessageRow>, oldestSeq: Long?, reachedStart: Boolean) {
        rows = cached.takeLast(windowSize)
        nextAfter = null
        pagesLoaded = 1
        hasMore = !reachedStart && oldestSeq != null
    }

    /**
     * 静默合并「比当前窗口更新」的消息（本地缓存 + 网关增量对账）。
     *
     * 只接 durable（seq ≥ 0）且 seq 未出现过的行；乐观发送的负 seq 行保持在末尾
     * （它们还没有 durable 身份，排序时不能混进正序里）。
     *
     * @return 实际新增行数
     */
    fun mergeNewer(page: List<Message>): Int {
        if (page.isEmpty()) return 0
        val known = rows.mapTo(HashSet()) { it.seq }
        val fresh = MessageRenderer.renderAll(page).filter { it.seq >= 0 && it.seq !in known }
        if (fresh.isEmpty()) return 0
        val durable = rows.filter { it.seq >= 0 }
        val optimistic = rows.filter { it.seq < 0 }
        rows = (durable + fresh).sortedBy { it.seq }.takeLast(windowSize) + optimistic
        return fresh.size
    }

    /** 当前窗口里最大的 durable seq（本地"最新索引"）。 */
    val newestDurableSeq: Long? get() = rows.filter { it.seq >= 0 }.maxOfOrNull { it.seq }

    /** 当前窗口里最小的 durable seq（本地"最深历史索引"）。 */
    val oldestDurableSeq: Long? get() = rows.filter { it.seq >= 0 }.minOfOrNull { it.seq }

    /** 流式行在内存中的累计条数（打字机效果：每帧一条，最后合成）。 */
    val streamingRows: List<MessageRow> get() = rows.filter { it.isStreaming }

    /** 找到某个 requestId 的乐观行。 */
    fun optimisticRow(requestId: String): MessageRow? = rows.firstOrNull { it.requestId == requestId }
}
