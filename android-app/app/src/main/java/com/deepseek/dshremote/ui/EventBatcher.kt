package com.deepseek.dshremote.ui

import com.deepseek.dshremote.protocol.Message
import com.deepseek.dshremote.protocol.ServerFrame

/**
 * 事件风暴的客户端合批（规范 §9.3）。
 *
 * 服务端**不节流**（一次 prompt 实测可达 180 帧），因此 App 必须自己做：
 *  1. **≤100ms 批次合并**：窗口内累积的事件一次性刷新 UI，避免每帧重组；
 *  2. **虚拟滚动**：由 Compose `LazyColumn` 承担（不在本类）；
 *  3. **reasoning 帧默认折叠**：见 [isCollapsedByDefault]。
 *
 * 本类**纯逻辑、无 Android 依赖**，可 JVM 单测。
 */
class EventBatcher(
    private val windowMs: Long = 100L,
    private val clock: () -> Long,
    private val onFlush: (List<ServerFrame.SessionEvent>) -> Unit,
) {
    private val buffer = ArrayList<ServerFrame.SessionEvent>()
    private var windowStart = -1L
    private var flushed = 0

    /** 累计刷新次数（测试断言"合批确实降低了渲染次数"）。 */
    val flushCount: Int get() = flushed

    /** 当前缓冲的帧数。 */
    val pending: Int get() = buffer.size

    /** 把一帧事件放入缓冲区。 */
    fun offer(event: ServerFrame.SessionEvent) {
        val now = clock()
        if (windowStart < 0) windowStart = now
        buffer += event
    }

    /**
     * 驱动刷新：若距窗口起点已达 [windowMs]，则把缓冲一次性刷出。
     * UI 层由一个 ≤100ms 的定时器调用本方法。
     */
    fun tick() {
        if (buffer.isEmpty()) return
        val now = clock()
        if (now - windowStart < windowMs) return
        flush()
    }

    /** 立即刷出（连接断开、切后台等场景）。 */
    fun flush() {
        if (buffer.isEmpty()) return
        val out = ArrayList(buffer)
        buffer.clear()
        windowStart = -1L
        flushed++
        onFlush(out)
    }

    fun reset() {
        buffer.clear()
        windowStart = -1L
    }

    companion object {
        /**
         * reasoning 类内容默认折叠（规范 §9.3）。
         *
         * 实测 DSH 的 assistant 消息里 reasoning 以 `[reasoning]` 标记出现在
         * 同一段文本中；v1 不做富结构，故按「整条折叠」处理。
         */
        fun isCollapsedByDefault(text: String): Boolean = text.contains("[reasoning]")

        /** 折叠时给用户看的一行摘要：跳过 reasoning 行，取首个实质内容行。 */
        fun collapsedSummary(text: String, max: Int = 60): String {
            val line = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("[reasoning]") }
                ?: text.lineSequence().firstOrNull { it.isNotBlank() }
                ?: ""
            return if (line.length > max) line.take(max) + "…" else line
        }
    }
}

/**
 * 把一帧消息并入本地消息列表（按 seq 去重、按 seq 升序）。
 *
 * 纯逻辑、可单测；UI 层直接消费结果交给 LazyColumn。
 */
object MessageListReducer {

    /**
     * 把一帧消息并入本地消息列表。
     *
     * - durable 行：按 `seq` 去重（同 seq 后到者覆盖），按 seq 升序；
     * - **流式帧**（`status == "streaming"`）：其 `seq` 是「本次 attempt 的字符位置」，
     *   与 durable 的事件 seq **不是同一坐标轴**（见 pc-gateway/README §6.2），
     *   因此单独放在列表末尾，保证打字机输出始终追加在最后，不与 durable 行抢位置。
     */
    fun apply(current: List<Message>, incoming: List<Message>): List<Message> {        if (incoming.isEmpty()) return current
        val streaming = (current + incoming).filter { it.isStreaming }
        val durable = LinkedHashMap<Long, Message>()
        (current + incoming).filterNot { it.isStreaming }.forEach { durable[it.seq] = it }
        return durable.values.sortedBy { it.seq } + streaming
    }

    /** 只保留最后 [keep] 条，防止长会话把内存吃满。 */
    fun trim(messages: List<Message>, keep: Int = 500): List<Message> =
        if (messages.size <= keep) messages else messages.takeLast(keep)
}
