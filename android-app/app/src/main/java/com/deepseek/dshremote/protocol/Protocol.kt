package com.deepseek.dshremote.protocol

/**
 * 契约 v1.1 的 DTO 与编解码（docs/01-架构与接口规范-v1.md 第 3 节 + 第 7 节裁定）。
 *
 * 本包**不依赖任何 Android API**，可纯 JVM 单测（规范验收 §5.1 要求 testDebugUnitTest 覆盖协议层）。
 * JSON 用 org.json（JVM 单测需要 Android stub，测试侧使用 Robolectric 或手写 stub；
 * 为保持零依赖可测性，这里所有解析都走 [JsonReader] 抽象，实现在 Android 侧用 org.json 提供）。
 */

/** 最小 JSON 抽象：Android 侧用 org.json 实现；单测可用内存实现或返回固定值。 */
interface JsonReader {
    fun optString(key: String, fallback: String? = null): String?
    fun optLong(key: String, fallback: Long = 0L): Long
    fun optInt(key: String, fallback: Int = 0): Int
    fun optBoolean(key: String, fallback: Boolean = false): Boolean
    fun optObject(key: String): JsonReader?
    fun optArray(key: String): List<JsonReader>
    fun has(key: String): Boolean
}

object Protocol {
    const val VERSION = 1
}

// ────────────────────────── REST DTO ──────────────────────────

data class PingResponse(
    val v: Int,
    val name: String,
    val version: String,
    val pairRequired: Boolean,
    val ts: Long,
) {
    companion object {
        fun parse(j: JsonReader): PingResponse = PingResponse(
            v = j.optInt("v"),
            name = j.optString("name").orEmpty(),
            version = j.optString("version").orEmpty(),
            pairRequired = j.optBoolean("pairRequired"),
            ts = j.optLong("ts"),
        )
    }
}

data class DshStatus(val ok: Boolean)

data class SessionCount(val total: Int, val running: Int)

data class StatusResponse(
    val dshOk: Boolean,
    val sessions: SessionCount,
    val uptimeMs: Long,
    val ts: Long,
) {
    companion object {
        fun parse(j: JsonReader): StatusResponse {
            val dsh = j.optObject("dsh")
            val s = j.optObject("sessions")
            return StatusResponse(
                dshOk = dsh?.optBoolean("ok") ?: false,
                sessions = SessionCount(s?.optInt("total") ?: 0, s?.optInt("running") ?: 0),
                uptimeMs = j.optLong("uptimeMs"),
                ts = j.optLong("ts"),
            )
        }
    }
}

/** 契约 §3.2 会话列表项 + 网关 v1.3 起透出的子代理归属字段（老 App 忽略即可，向后兼容） */
data class SessionSummary(
    val id: String,
    val title: String,
    val status: String,          // running | idle | unknown
    val createdAt: Long,
    val updatedAt: Long,
    val cwd: String,
    val messageCount: Int,
    val lastMessagePreview: String,
    val agentAvailable: Boolean,
    /** 父会话 id；子代理才有（照抄 PC 版的子代理切换器需要） */
    val parentSessionId: String? = null,
    /** 是否是子代理会话 */
    val isSubagent: Boolean = false,
    /** 子代理名称（来自 durable `subagent.identity.label`） */
    val subagentLabel: String? = null,
) {
    companion object {
        fun parse(j: JsonReader): SessionSummary = SessionSummary(
            id = j.optString("id").orEmpty(),
            title = j.optString("title").orEmpty(),
            status = j.optString("status", "unknown").orEmpty(),
            createdAt = j.optLong("createdAt"),
            updatedAt = j.optLong("updatedAt"),
            cwd = j.optString("cwd").orEmpty(),
            messageCount = j.optInt("messageCount"),
            lastMessagePreview = j.optString("lastMessagePreview").orEmpty(),
            agentAvailable = j.optBoolean("agentAvailable"),
            parentSessionId = if (j.has("parentSessionId")) j.optString("parentSessionId") else null,
            isSubagent = j.optBoolean("isSubagent", false),
            subagentLabel = if (j.has("subagentLabel")) j.optString("subagentLabel") else null,
        )
    }
}

data class SessionPage(val items: List<SessionSummary>, val nextCursor: String?)

/**
 * 契约 §3.2 消息行 + 规范 §9.7 的 `requestId`。
 *
 * 对账语义（三者务必分清）：
 *  - 本地待发队列项自带的 `requestId` → 用于**乐观上屏**；
 *  - `/messages` 行上的 `requestId`（取自 durable `source.rpcId`）→ **崩溃恢复对账的权威键**；
 *  - `messageId`（prompt 回执）= **网关受理 id**，只用于同一次发送的幂等重试，
 *    **不用于跨进程持久对账**（受理时内层尚未落库，不可能拿到 durable id）。
 */
data class Message(
    val seq: Long,
    val role: String,            // user | assistant | tool | system
    val text: String,
    val ts: Long,
    val status: String,          // final | streaming | failed
    val messageId: String?,
    val requestId: String? = null,
    // ---- 需求 3：reasoning / tool 的可展开内容（网关提供，可为空）----
    /** message | reasoning | tool-call | tool-result */
    val kind: String = "message",
    /** assistant 的完整思考内容（折叠时只显示 collapsedSummary） */
    val reasoning: String? = null,
    val hasReasoning: Boolean = false,
    val toolName: String? = null,
    /** 用于把 tool-call 与 tool-result 配对成一张可展开卡片 */
    val toolCallId: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val isError: Boolean = false,
    val collapsedSummary: String = "",
    /** Image references only (never bytes); empty when the message has none. */
    val images: List<ImageRef> = emptyList(),
) {
    val isStreaming: Boolean get() = status == "streaming"

    /** 该行是否是「可展开」的内容块（reasoning 或 tool）。 */
    val isExpandable: Boolean
        get() = (kind == "tool-call" || kind == "tool-result")
            || (role == "assistant" && hasReasoning && !reasoning.isNullOrEmpty())

    /**
     * One image attached to a message.
     *
     * Source: `dsh-attachment/lib/types/types.d.ts` -- `ImageAttachmentRef`
     * `{attachmentId, mediaType, bytes, width, height, name?, originalDimensions?}`.
     * `attachmentId` is documented as an OPAQUE storage id and is never parsed here.
     */
    data class ImageRef(
        val attachmentId: String,
        val mediaType: String,
        val bytes: Long,
        val width: Int,
        val height: Int,
        val name: String?,
    ) {
        /** DSH accepts only png/jpeg/webp/gif (ImageMediaType). */
        val supported: Boolean
            get() = mediaType == "image/png" || mediaType == "image/jpeg"
                || mediaType == "image/webp" || mediaType == "image/gif"
    }

    companion object {
        fun parse(j: JsonReader): Message = Message(
            seq = j.optLong("seq"),
            role = j.optString("role").orEmpty(),
            text = j.optString("text").orEmpty(),
            ts = j.optLong("ts"),
            status = j.optString("status", "final").orEmpty(),
            messageId = if (j.has("messageId")) j.optString("messageId") else null,
            requestId = if (j.has("requestId")) j.optString("requestId") else null,
            kind = j.optString("kind", "message").orEmpty(),
            reasoning = if (j.has("reasoning")) j.optString("reasoning") else null,
            hasReasoning = j.optBoolean("hasReasoning", false),
            toolName = if (j.has("toolName")) j.optString("toolName") else null,
            toolCallId = if (j.has("toolCallId")) j.optString("toolCallId") else null,
            toolInput = if (j.has("toolInput")) j.optString("toolInput") else null,
            toolOutput = if (j.has("toolOutput")) j.optString("toolOutput") else null,
            isError = j.optBoolean("isError", false),
            collapsedSummary = j.optString("collapsedSummary").orEmpty(),
            images = parseImages(j),
        )

        /**
         * Parses the `images` array.
         *
         * Contract (gateway F-5 rule "keys always present"):
         *  - key absent  -> empty list (older gateway)
         *  - `null`      -> empty list
         *  - `[]`        -> empty list
         * Rows without an `attachmentId` are dropped: without an opaque id the
         * App cannot fetch bytes, so showing a broken placeholder would be worse
         * than not rendering the image at all.
         */
        fun parseImages(j: JsonReader): List<ImageRef> {
            if (!j.has("images")) return emptyList()
            val arr = j.optArray("images")
            val out = ArrayList<ImageRef>(arr.size)
            for (o in arr) {
                val aid = o.optString("attachmentId")
                if (aid.isNullOrEmpty()) continue
                out += ImageRef(
                    attachmentId = aid,
                    mediaType = o.optString("mediaType").orEmpty(),
                    bytes = o.optLong("bytes"),
                    width = o.optInt("width"),
                    height = o.optInt("height"),
                    name = if (o.has("name")) o.optString("name") else null,
                )
            }
            return out
        }
    }
}

/**
 * One messages page.
 *
 * 2026-10-13 incremental browsing: the gateway may also return `prevCursor`
 * (older direction, = smallest seq in the window), `nextAfter` (downward
 * incremental cursor) and `hasNewer` when `aroundSeq`/`after` was used. All
 * three are optional so the old newest-page shape keeps parsing unchanged.
 */
data class MessagePage(
    val items: List<Message>,
    val nextCursor: Long?,
    val prevCursor: Long? = null,
    val nextAfter: Long? = null,
    val hasNewer: Boolean? = null,
)

data class PromptAck(
    val accepted: Boolean,
    val messageId: String,
    val sessionId: String,
)

data class RotateResult(val token: String, val fp: String, val ts: Long)

/** 契约 §3.1 错误体 */
data class ApiError(val code: String, val message: String) {
    companion object {
        val CODES = setOf(
            "unauthorized", "invalid_request", "not_found",
            "session_busy", "dsh_unavailable", "internal",
        )
    }
}

/** 统一 REST 结果 */
sealed class RestResult<out T> {
    data class Ok<T>(val value: T) : RestResult<T>()
    data class Err(val status: Int, val error: ApiError) : RestResult<Nothing>() {
        val code: String get() = error.code
    }
}

// ───────────────────── WebSocket 帧 ─────────────────────

/** 客户端 → 服务端 */
sealed class ClientFrame {
    abstract val id: String?

    data class Ping(override val id: String? = null, val ts: Long = 0L) : ClientFrame()
    data class Subscribe(override val id: String, val sessionId: String) : ClientFrame()
    data class Unsubscribe(override val id: String, val sessionId: String) : ClientFrame()
    data class Prompt(
        override val id: String,
        val sessionId: String,
        val text: String,
        val requestId: String,
    ) : ClientFrame()
    data class Cancel(override val id: String, val sessionId: String) : ClientFrame()

    /** 编码为契约要求的单行 JSON（v=1）。 */
    fun encode(): String = when (this) {
        is Ping -> buildString {
            append("""{"v":1,"type":"ping","ts":""").append(ts)
            id?.let { append(""","id":""").append(q(it)) }
            append('}')
        }
        is Subscribe -> """{"v":1,"type":"subscribe","id":${q(id)},"sessionId":${q(sessionId)}}"""
        is Unsubscribe -> """{"v":1,"type":"unsubscribe","id":${q(id)},"sessionId":${q(sessionId)}}"""
        is Prompt -> """{"v":1,"type":"prompt","id":${q(id)},"sessionId":${q(sessionId)},"text":${q(text)},"requestId":${q(requestId)}}"""
        is Cancel -> """{"v":1,"type":"cancel","id":${q(id)},"sessionId":${q(sessionId)}}"""
    }

    companion object {
        /** JSON 字符串转义（契约要求 UTF-8 JSON）。 */
        fun q(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '' -> sb.append("\\f")
                    else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}

/** 服务端 → 客户端 */
sealed class ServerFrame {
    data class Hello(val name: String, val version: String, val sessions: List<SessionSummary>) : ServerFrame()

    /** kind = message | status | turn */
    data class SessionEvent(
        val sessionId: String,
        val kind: String,
        val role: String?,
        val text: String?,
        val seq: Long?,
        val ts: Long?,
        val status: String?,
        val messageId: String?,
        /** 规范 §9.7：user 消息带 durable requestId，崩溃恢复对账用 */
        val requestId: String? = null,
    ) : ServerFrame()

    data class SessionStatus(val sessionId: String, val status: String, val ts: Long) : ServerFrame()

    /** GAP-10：prompt 的 ack 额外带 sessionId / requestId / messageId */
    data class Ack(
        val id: String?,
        val ok: Boolean,
        val sessionId: String? = null,
        val requestId: String? = null,
        val messageId: String? = null,
    ) : ServerFrame()

    data class Error(val id: String?, val code: String, val message: String) : ServerFrame()

    data class Pong(val ts: Long) : ServerFrame()

    /** 无法识别的帧（协议演进兜底，不断连） */
    data class Unknown(val type: String?) : ServerFrame()

    companion object {
        /**
         * 解析服务端帧。**永不抛异常**——坏帧返回 [Unknown]，由上层记日志后继续。
         */
        fun parse(j: JsonReader): ServerFrame {
            if (j.optInt("v") != Protocol.VERSION) return ServerFrame.Unknown(j.optString("type"))
            return when (j.optString("type")) {
                "hello" -> {
                    val s = j.optObject("server")
                    Hello(
                        name = s?.optString("name").orEmpty(),
                        version = s?.optString("version").orEmpty(),
                        sessions = j.optArray("sessions").map { SessionSummary.parse(it) },
                    )
                }
                "session.event" -> {
                    val e = j.optObject("event")
                    SessionEvent(
                        sessionId = j.optString("sessionId").orEmpty(),
                        kind = e?.optString("kind").orEmpty(),
                        role = e?.optString("role"),
                        text = e?.optString("text"),
                        seq = if (e != null && e.has("seq")) e.optLong("seq") else null,
                        ts = if (e != null && e.has("ts")) e.optLong("ts") else null,
                        status = e?.optString("status"),
                        messageId = e?.optString("messageId"),
                        requestId = e?.optString("requestId"),
                    )
                }
                "session.status" -> SessionStatus(
                    sessionId = j.optString("sessionId").orEmpty(),
                    status = j.optString("status").orEmpty(),
                    ts = j.optLong("ts"),
                )
                "ack" -> Ack(
                    id = j.optString("id"),
                    ok = j.optBoolean("ok"),
                    sessionId = j.optString("sessionId"),
                    requestId = j.optString("requestId"),
                    messageId = j.optString("messageId"),
                )
                "error" -> Error(
                    id = j.optString("id"),
                    code = j.optString("code").orEmpty(),
                    message = j.optString("message").orEmpty(),
                )
                "pong" -> Pong(j.optLong("ts"))
                else -> ServerFrame.Unknown(j.optString("type"))
            }
        }
    }
}
