package com.deepseek.dshremote.data

import android.content.Context
import com.deepseek.dshremote.core.MessagePager
import com.deepseek.dshremote.core.MessageRow
import com.deepseek.dshremote.protocol.Message
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会话消息的**本地历史缓存**（2026-10-14 用户优化 1）。
 *
 * 需求原文：
 *  「apk端没有历史数据。应该保存历史数据，并保留相关列表的最大索引值，下次同步
 *   网关的时候，用户无感知从网关最大索引值增往历史最大值倒序同步。就是先展示
 *   最新的，然后动态同步历史数据到手机本地最大值为止。」
 *
 * 实现：
 *  - 每个会话一个 JSON 文件 `files/history/<sessionId>.json`，**只存 durable 行**
 *    （seq ≥ 0），乐观/流式的负 seq 行不落盘（它们还没有权威身份）；
 *  - 文件头记录 `oldestSeq` / `newestSeq` / `reachedStart`（是否已到会话开头），
 *    这就是「最大/最小索引值」：下次打开**先从本地最大索引往后拉最新**，再**从
 *    本地最小索引继续往更早倒序补**，用户全程无感知；
 *  - 读失败一律当空缓存（缓存是加速手段，绝不能让 App 起不来）；
 *  - 单会话上限 [MAX_ROWS] 行（超出丢最旧的，保证磁盘有界）。
 *
 * 与 DataStore 的分工：DataStore 存**列表元数据**（小、要事务）；这里存**消息正文**
 * （大、可截断），所以用普通文件而不是 Preferences。
 */
object MessageCache {

    /**
     * 单会话最多保留多少行（超出丢最旧）。
     *
     * 2026-10-16 用户报告「跳新会话要卡半天才到最新页」：1000 行 + reasoning/tool 正文
     * 实测单文件 **5MB**，读/解析/写都贵（每次补齐与防抖落盘都要整窗序列化）。
     * 收敛到 400 行 + 字段截断（见 [cap]），体积降一个量级，秒开才成立。
     */
    const val MAX_ROWS = 400

    /** 单个大字段的落盘上限（超出截断）。整窗序列化的成本主要来自这些正文。 */
    private const val MAX_FIELD_CHARS = 4000

    /** 截断超大字段（`null` 原样返回）。 */
    private fun cap(s: String?): String? =
        if (s == null || s.length <= MAX_FIELD_CHARS) s else s.take(MAX_FIELD_CHARS) + "…[cache-truncated]"

    private const val DIR = "history"

    private fun fileFor(context: Context, sessionId: String): File {
        // sessionId 可能含 '-'（UUID）等安全字符，但仍做一次清洗避免路径穿越。
        val safe = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$safe.json")
    }

    /** 落盘快照（在 IO 线程调用）。 */
    fun save(
        context: Context,
        sessionId: String,
        rows: List<MessageRow>,
        oldestSeq: Long?,
        newestSeq: Long?,
        reachedStart: Boolean,
    ) {
        val durable = rows.filter { it.seq >= 0 }.takeLast(MAX_ROWS)
        if (durable.isEmpty()) return
        val arr = JSONArray()
        for (r in durable) arr.put(encodeRow(r))
        val root = JSONObject().apply {
            put("v", 1)
            put("oldestSeq", oldestSeq ?: JSONObject.NULL)
            put("newestSeq", newestSeq ?: JSONObject.NULL)
            put("reachedStart", reachedStart)
            put("rows", arr)
        }
        runCatching {
            val f = fileFor(context, sessionId)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            // 原子替换：先写临时文件再改名，避免写一半崩溃留下半截缓存。
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
    }

    /** 读取缓存；任何异常都退化为「空缓存」。 */
    fun load(context: Context, sessionId: String): Cached {
        val f = fileFor(context, sessionId)
        if (!f.exists()) return Cached(emptyList(), null, null, false)
        return runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("rows") ?: JSONArray()
            val rows = (0 until arr.length()).mapNotNull { i ->
                runCatching { decodeRow(arr.getJSONObject(i)) }.getOrNull()
            }
            Cached(
                rows = rows,
                oldestSeq = if (root.isNull("oldestSeq")) null else root.optLong("oldestSeq"),
                newestSeq = if (root.isNull("newestSeq")) null else root.optLong("newestSeq"),
                reachedStart = root.optBoolean("reachedStart", false),
            )
        }.getOrDefault(Cached(emptyList(), null, null, false))
    }

    /** 删除某会话缓存（配对解除 / 清缓存时用）。 */
    fun clear(context: Context, sessionId: String) {
        runCatching { fileFor(context, sessionId).delete() }
    }

    /** 缓存内容。 */
    data class Cached(
        val rows: List<MessageRow>,
        val oldestSeq: Long?,
        val newestSeq: Long?,
        val reachedStart: Boolean,
    ) {
        val isEmpty: Boolean get() = rows.isEmpty()
    }

    // ── 行编解码：只保留渲染必需的字段，尽量省体积 ──────────────
    private fun encodeRow(r: MessageRow): JSONObject = JSONObject().apply {
        put("seq", r.seq)
        put("role", r.role)
        put("text", cap(r.text))
        put("ts", r.ts)
        put("status", r.status)
        put("messageId", r.messageId ?: JSONObject.NULL)
        put("requestId", r.requestId ?: JSONObject.NULL)
        put("kind", r.kind)
        put("reasoning", cap(r.reasoning) ?: JSONObject.NULL)
        put("hasReasoning", r.hasReasoning)
        put("toolName", r.toolName ?: JSONObject.NULL)
        put("toolCallId", r.toolCallId ?: JSONObject.NULL)
        put("toolInput", cap(r.toolInput) ?: JSONObject.NULL)
        put("toolOutput", cap(r.toolOutput) ?: JSONObject.NULL)
        put("collapsedSummary", r.collapsedSummary)
    }

    private fun decodeRow(o: JSONObject): MessageRow = MessageRendererShim.render(
        Message(
            seq = o.optLong("seq"),
            role = o.optString("role"),
            text = o.optString("text"),
            ts = o.optLong("ts"),
            status = o.optString("status", "final"),
            messageId = if (o.isNull("messageId")) null else o.optString("messageId"),
            requestId = if (o.isNull("requestId")) null else o.optString("requestId"),
            kind = o.optString("kind", "message"),
            reasoning = if (o.isNull("reasoning")) null else o.optString("reasoning"),
            hasReasoning = o.optBoolean("hasReasoning", false),
            toolName = if (o.isNull("toolName")) null else o.optString("toolName"),
            toolCallId = if (o.isNull("toolCallId")) null else o.optString("toolCallId"),
            toolInput = if (o.isNull("toolInput")) null else o.optString("toolInput"),
            toolOutput = if (o.isNull("toolOutput")) null else o.optString("toolOutput"),
            collapsedSummary = o.optString("collapsedSummary"),
        )
    )

    /** 复用 [MessagePager] 里的渲染器，避免两处规则漂移。 */
    private object MessageRendererShim {
        fun render(m: Message): MessageRow =
            com.deepseek.dshremote.core.MessageRenderer.render(m)
    }
}
