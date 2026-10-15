package com.deepseek.dshremote.net

import com.deepseek.dshremote.core.PairingInfo
import com.deepseek.dshremote.core.PromptMode
import com.deepseek.dshremote.protocol.ApiError
import com.deepseek.dshremote.protocol.Json
import com.deepseek.dshremote.protocol.JsonObjectReader
import com.deepseek.dshremote.protocol.Message
import com.deepseek.dshremote.protocol.MessagePage
import com.deepseek.dshremote.protocol.PingResponse
import com.deepseek.dshremote.protocol.RestResult
import com.deepseek.dshremote.protocol.RotateResult
import com.deepseek.dshremote.protocol.SessionPage
import com.deepseek.dshremote.protocol.StatusResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * REST 客户端（契约 §3.2）。
 *
 * 所有错误统一成 [RestResult]：HTTP 非 2xx → `Err(status, ApiError)`，
 * 由 UI/队列按错误码决策（GAP-15：`session_busy` 重试，其余 4xx 直接失败）。
 */
class GatewayApi(private val client: OkHttpClient = defaultClient()) {

    companion object {
        /** Page size when listing one workspace's sessions (see `sessionsOfWorkspace`). */
        const val WORKSPACE_PAGE_LIMIT = 200

        /**
         * Debug-only HTTP tracing (`Log.i("DshHttp", …)`), enabled solely in debug builds.
         *
         * This is the App-side counterpart of the gateway's `_debug/requests`
         * counter: the gateway proves what actually arrived, this proves what the
         * App believes it sent, so a mismatch localises the fault precisely.
         *
         * Never logs headers or bodies -- only method, path, status and duration.
         */
        private val TRACE: Boolean = try {
            Class.forName("com.deepseek.dshremote.BuildConfig")
                .getField("DEBUG").getBoolean(null)
        } catch (e: Throwable) {
            false          // unit tests run without generated BuildConfig
        }

        private const val TAG = "DshHttp"

        private fun trace(msg: String) {
            if (TRACE) android.util.Log.e(TAG, msg)
        }

        /**
         * Logs one finished exchange. Called from [call] where the response (and
         * therefore the status code) is actually in hand.
         *
         * `method`/`path` only -- never headers, bodies or the bearer token.
         */
        fun report(method: String, url: String?, code: Int, ms: Long, failed: String? = null) {
            if (!TRACE) return
            val pathOnly = runCatching { java.net.URI(url ?: "").path ?: "?" }.getOrDefault("?")
            if (failed != null) {
                trace("$method $pathOnly FAILED $failed ${ms}ms")
            } else {
                trace("$method $pathOnly $code ${ms}ms")
            }
        }

        private fun listener(): EventListener? = if (!TRACE) null else object : EventListener() {
            private var startNs = 0L
            private var path = "?"

            override fun callStart(call: Call) { startNs = System.nanoTime() }

            override fun requestHeadersStart(call: Call) {
                // Only the path is logged: no headers, no body, no token.
                path = runCatching { call.request().url.encodedPath }.getOrDefault("?")
            }

            override fun callEnd(call: Call) {
                // OkHttp 4's Call has no response accessor, so the status is reported
                // by `report()` below from the place that actually sees the response.
            }

            override fun callFailed(call: Call, ioe: IOException) {
                val ms = (System.nanoTime() - startNs) / 1_000_000
                trace("${call.request().method} $path FAILED ${ioe.javaClass.simpleName} ${ms}ms")
            }
        }

        fun defaultClient(): OkHttpClient {
            val b = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
            listener()?.let { b.eventListener(it) }
            return b.build()
        }

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private fun auth(token: String) = Request.Builder().header("Authorization", "Bearer $token")

    private suspend inline fun <T> call(
        crossinline build: (Request.Builder) -> Request.Builder,
        crossinline parse: (JsonObjectReader) -> T,
    ): RestResult<T> = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        var method = "?"
        var url: String? = null
        try {
            val req = build(Request.Builder()).build()
            method = req.method
            url = req.url.toString()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                report(method, url, resp.code, (System.nanoTime() - startedAt) / 1_000_000)
                if (!resp.isSuccessful) {
                    val err = runCatching {
                        JsonObjectReader(Json.parseObject(text)).optObject("error")
                    }.getOrNull()
                    return@withContext RestResult.Err(
                        resp.code,
                        ApiError(err?.optString("code") ?: "internal", err?.optString("message") ?: "HTTP ${resp.code}"),
                    )
                }
                RestResult.Ok(parse(JsonObjectReader(Json.parseObject(text))))
            }
        } catch (e: Exception) {
            report(
                method, url, 0,
                (System.nanoTime() - startedAt) / 1_000_000,
                failed = e.javaClass.simpleName + ": " + (e.message ?: ""),
            )
            RestResult.Err(0, ApiError("network", e.message ?: e::class.java.simpleName))
        }
    }

    private fun Request.Builder.post(body: String) = post(body.toRequestBody(JSON))

    // ── GET /rc/v1/ping（免鉴权） ───────────────────
    suspend fun ping(baseUrl: String): RestResult<PingResponse> = call(
        { it.url("$baseUrl/rc/v1/ping") },
        { PingResponse.parse(it) },
    )

    // ── GET /rc/v1/status ───────────────────────────
    suspend fun status(baseUrl: String, token: String): RestResult<StatusResponse> = call(
        { auth(token).url("$baseUrl/rc/v1/status") },
        { StatusResponse.parse(it) },
    )

    // ── GET /rc/v1/sessions ─────────────────────────
    suspend fun sessions(
        baseUrl: String,
        token: String,
        limit: Int = 50,
        cursor: String? = null,
        /**
         * Optional cwd filter (`?workspace=`). Expanding a workspace in the sidebar
         * used to pull every session on the machine; this narrows the request to the
         * sessions that workspace actually owns. Null => unchanged full list.
         */
        workspace: String? = null,
    ): RestResult<SessionPage> = call(
        { auth(token).url("$baseUrl/rc/v1/sessions?limit=$limit" + (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "") + (workspace?.let { "&workspace=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")) },
        { j ->
            SessionPage(
                items = j.optArray("items").map { com.deepseek.dshremote.protocol.SessionSummary.parse(it) },
                nextCursor = j.optString("nextCursor"),
            )
        },
    )

    // ── GET /rc/v1/sessions/{id}/messages ───────────
    suspend fun messages(
        baseUrl: String,
        token: String,
        sessionId: String,
        limit: Int = 50,
        before: Long? = null,
        // Requirement 3: none | summary | full. The UI fetches `summary` for the
        // list and only asks for `full` when the user expands a reasoning/tool
        // block -- that is the "click to load dynamically" behaviour.
        expand: String = "summary",
        // 2026-10-13 incremental browsing: jump-to-seq window (search locate)
        // and downward incremental pages (after=). Null => unchanged behaviour.
        aroundSeq: Long? = null,
        after: Long? = null,
    ): RestResult<MessagePage> = call(
        {
            auth(token).url(
                "$baseUrl/rc/v1/sessions/${enc(sessionId)}/messages?limit=$limit" +
                    (before?.let { "&before=$it" } ?: "") +
                    (aroundSeq?.let { "&aroundSeq=$it" } ?: "") +
                    (after?.let { "&after=$it" } ?: "") +
                    "&expand=$expand"
            )
        },
        { j ->
            MessagePage(
                items = j.optArray("items").map { Message.parse(it) },
                nextCursor = if (j.has("nextCursor")) j.optLong("nextCursor") else null,
                prevCursor = if (j.has("prevCursor")) j.optLong("prevCursor") else null,
                nextAfter = if (j.has("nextAfter")) j.optLong("nextAfter") else null,
                hasNewer = if (j.has("hasNewer")) j.optBoolean("hasNewer") else null,
            )
        },
    )

    // ── GET /rc/v1/search?q=<keywords> ──────────────
    suspend fun search(
        baseUrl: String,
        token: String,
        q: String,
        limit: Int = 30,
    ): RestResult<List<SearchHit>> = call(
        {
            auth(token).url(
                "$baseUrl/rc/v1/search?limit=$limit&q=" +
                    java.net.URLEncoder.encode(q, "UTF-8")
            )
        },
        { j ->
            j.optArray("items").map { o ->
                SearchHit(
                    sessionId = o.optString("sessionId").orEmpty(),
                    title = o.optString("title").orEmpty(),
                    cwd = o.optString("cwd").orEmpty(),
                    updatedAt = o.optLong("updatedAt"),
                    hitCount = o.optInt("hitCount"),
                    hits = o.optArray("hits").map { h ->
                        SearchHit.Hit(
                            seq = if (h.has("seq")) h.optLong("seq") else null,
                            role = h.optString("role").orEmpty(),
                            ts = h.optLong("ts"),
                            snippet = h.optString("snippet").orEmpty(),
                        )
                    },
                )
            }
        },
    )

    /** One search result: a session plus up to 3 in-body hits. */
    data class SearchHit(
        val sessionId: String,
        val title: String,
        val cwd: String,
        val updatedAt: Long,
        val hitCount: Int,
        val hits: List<Hit>,
    ) {
        data class Hit(val seq: Long?, val role: String, val ts: Long, val snippet: String)
        /** Top snippet for list rendering; title-only matches show the title. */
        val topSnippet: String get() = hits.firstOrNull()?.snippet.orEmpty()
        val topSeq: Long? get() = hits.firstOrNull()?.seq
    }

    // ── POST /rc/v1/sessions ────────────────────────
    suspend fun createSession(baseUrl: String, token: String, cwd: String?): RestResult<String> = call(
        { auth(token).url("$baseUrl/rc/v1/sessions").post("""{"cwd":${cwd?.let { "\"" + it + "\"" } ?: "null"}}""") },
        { it.optString("id").orEmpty() },
    )

    // ── POST /rc/v1/sessions/{id}/prompt ───────────
    suspend fun prompt(
        baseUrl: String,
        token: String,
        sessionId: String,
        text: String,
        requestId: String,
        // 'steer' interrupts the running turn AND wakes an idle driver;
        // 'queue' merely appends (an idle session would never wake).
        // User requirement: the message from the phone has top priority -> default steer.
        mode: String = PromptMode.STEER,
    ): RestResult<com.deepseek.dshremote.protocol.PromptAck> = call(
        {
            auth(token).url("$baseUrl/rc/v1/sessions/${enc(sessionId)}/prompt")
                .post("""{"text":${q(text)},"requestId":${q(requestId)},"mode":${q(mode)}}""")
        },
        { j ->
            com.deepseek.dshremote.protocol.PromptAck(
                accepted = j.optBoolean("accepted"),
                messageId = j.optString("messageId").orEmpty(),
                sessionId = j.optString("sessionId").orEmpty(),
            )
        },
    )

    // ── POST /rc/v1/sessions/{id}/cancel ───────────
    suspend fun cancel(baseUrl: String, token: String, sessionId: String): RestResult<Boolean> = call(
        { auth(token).url("$baseUrl/rc/v1/sessions/${enc(sessionId)}/cancel").post("{}") },
        { it.optBoolean("ok") },
    )

    // ── POST /rc/v1/pair/rotate ─────────────────────
    suspend fun rotate(baseUrl: String, token: String): RestResult<RotateResult> = call(
        { auth(token).url("$baseUrl/rc/v1/pair/rotate").post("{}") },
        { RotateResult(it.optString("token").orEmpty(), it.optString("fp").orEmpty(), it.optLong("ts")) },
    )

    /**
     * Read one durable image attachment (requirement: images).
     *
     * Endpoint: `GET /rc/v1/sessions/{sessionId}/attachments/{attachmentId}`
     * Response: `{ v:1, attachment: ImageAttachmentRef, data: "<base64>" }`
     * (mirrors DSH's `SessionAttachmentValue`).
     *
     * Bytes are fetched **on demand**, never pushed over the WS socket.
     */
    suspend fun attachment(
        baseUrl: String,
        token: String,
        sessionId: String,
        attachmentId: String,
    ): RestResult<AttachmentPayload> = call(
        { auth(token).url("$baseUrl/rc/v1/sessions/${enc(sessionId)}/attachments/${enc(attachmentId)}") },
        { j ->
            val a = j.optObject("attachment")
            AttachmentPayload(
                data = j.optString("data").orEmpty(),
                mediaType = a?.optString("mediaType").orEmpty(),
                bytes = a?.optLong("bytes") ?: 0L,
                width = a?.optInt("width") ?: 0,
                height = a?.optInt("height") ?: 0,
            )
        },
    )

    /** Raw attachment bytes plus the metadata needed to decode them safely. */
    data class AttachmentPayload(
        val data: String,
        val mediaType: String,
        val bytes: Long,
        val width: Int,
        val height: Int,
    )

    // ── GET /rc/v1/workspaces（需求 §3.2） ──────────────────
    suspend fun workspaces(baseUrl: String, token: String): RestResult<List<com.deepseek.dshremote.core.Workspace>> = call(
        { auth(token).url("$baseUrl/rc/v1/workspaces") },
        { j ->
            j.optArray("items").map { o ->
                com.deepseek.dshremote.core.Workspace(
                    id = o.optString("id").orEmpty(),
                    title = o.optString("title").orEmpty(),
                    path = o.optString("path").orEmpty(),
                    sessionCount = o.optInt("sessionCount"),
                    updatedAt = o.optLong("updatedAt"),
                )
            }
        },
    )

    /**
     * 翻完**所有**分页取回会话。
     *
     * 必须翻完 —— 一次 `limit=100` 只覆盖前 100 条（实测总会话 112 = 2 页，
     * tiga-survivor_v1 一个工作区就有 47 条）。
     * 「标题数字 == 列表条数」正是靠这里与网关 `/workspaces` 的实时统计**同源**保证。
     */
    suspend fun allSessions(
        baseUrl: String,
        token: String,
        onProgress: (loaded: Int) -> Unit = {},
    ): RestResult<List<com.deepseek.dshremote.protocol.SessionSummary>> {
        val all = mutableListOf<com.deepseek.dshremote.protocol.SessionSummary>()
        var cursor: String? = null
        var pages = 0
        do {
            when (val r = sessions(baseUrl, token, limit = 100, cursor = cursor)) {
                is RestResult.Err -> return r
                is RestResult.Ok -> {
                    all += r.value.items
                    onProgress(all.size)
                    cursor = r.value.nextCursor
                    if (++pages > 200) break                    // 防御：异常数据不打死循环
                }
            }
        } while (cursor != null)
        return RestResult.Ok(all)
    }

    /**
     * 某工作区的会话（按 cwd 精确过滤，与网关统计同源）。
     *
     * 2026-12: switched from "fetch every session, then filter locally" to the
     * gateway's `?workspace=` filter. Expanding a workspace used to download the
     * whole session list (measured 132 rows across two pages) just to keep a
     * handful; the server-side filter makes the response proportional to the
     * workspace. Still pages, because a busy workspace can exceed one page.
     */
    suspend fun sessionsOfWorkspace(
        baseUrl: String,
        token: String,
        workspacePath: String,
        onProgress: (loaded: Int) -> Unit = {},
    ): RestResult<List<com.deepseek.dshremote.protocol.SessionSummary>> {
        val out = mutableListOf<com.deepseek.dshremote.protocol.SessionSummary>()
        var cursor: String? = null
        do {
            when (val page = sessions(baseUrl, token, limit = WORKSPACE_PAGE_LIMIT, cursor = cursor, workspace = workspacePath)) {
                is RestResult.Err -> return page
                is RestResult.Ok -> {
                    out += page.value.items
                    cursor = page.value.nextCursor
                }
            }
            onProgress(out.size)
        } while (cursor != null)
        return RestResult.Ok(out)
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun q(s: String) = Json.quote(s)
}
