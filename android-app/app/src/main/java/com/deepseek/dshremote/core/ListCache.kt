package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.SessionSummary
import org.json.JSONArray
import org.json.JSONObject

/**
 * 列表缓存的编解码（用户问题 4：长时间不访问后回来仍要秒开）。
 *
 * 存的是**列表元数据**：工作区（id/title/path/sessionCount/updatedAt）与
 * 会话摘要（id/title/status/cwd/时间戳/子代理归属）。**不含消息正文**，
 * 因此体积与「会话有多少条消息」无关，只与「有多少个工作区/会话」成正比。
 *
 * 约定：
 *  - 工作区数组：`[{id,title,path,sessionCount,updatedAt}, …]`
 *  - 会话映射：`{"<workspace path>": [{…SessionSummary…}, …], …}`
 *  - 解析失败一律返回空（缓存是**加速**手段，绝不能反过来让 App 起不来）。
 */
object ListCache {

    fun encodeWorkspaces(list: List<Workspace>): String {
        val arr = JSONArray()
        for (w in list) {
            arr.put(JSONObject().apply {
                put("id", w.id)
                put("title", w.title)
                put("path", w.path)
                put("sessionCount", w.sessionCount)
                put("updatedAt", w.updatedAt)
            })
        }
        return arr.toString()
    }

    fun decodeWorkspaces(raw: String?): List<Workspace> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Workspace(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    path = o.optString("path"),
                    sessionCount = o.optInt("sessionCount"),
                    updatedAt = o.optLong("updatedAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 编码「工作区 path -> 该工作区的会话列表」。 */
    fun encodeSessionsByPath(map: Map<String, List<SessionSummary>>): String {
        val root = JSONObject()
        for ((path, list) in map) {
            val arr = JSONArray()
            for (s in list) arr.put(encodeSession(s))
            root.put(path, arr)
        }
        return root.toString()
    }

    fun decodeSessionsByPath(raw: String?): Map<String, List<SessionSummary>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val out = HashMap<String, List<SessionSummary>>()
            for (k in root.keys()) {
                val arr = root.optJSONArray(k) ?: continue
                out[k] = (0 until arr.length()).map { i -> decodeSession(arr.getJSONObject(i)) }
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun encodeSession(s: SessionSummary): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("title", s.title)
        put("status", s.status)
        put("createdAt", s.createdAt)
        put("updatedAt", s.updatedAt)
        put("cwd", s.cwd)
        put("messageCount", s.messageCount)
        put("lastMessagePreview", s.lastMessagePreview)
        put("agentAvailable", s.agentAvailable)
        // null 用 JSONObject.NULL 占位，解析端用 isNull() 区分「没有这个键」
        put("parentSessionId", s.parentSessionId ?: JSONObject.NULL)
        put("isSubagent", s.isSubagent)
        put("subagentLabel", s.subagentLabel ?: JSONObject.NULL)
    }

    /** 与 [com.deepseek.dshremote.protocol.SessionSummary.parse] 同一套字段名。 */
    private fun decodeSession(o: JSONObject): SessionSummary = SessionSummary(
        id = o.optString("id"),
        title = o.optString("title"),
        status = o.optString("status", "unknown"),
        createdAt = o.optLong("createdAt"),
        updatedAt = o.optLong("updatedAt"),
        cwd = o.optString("cwd"),
        messageCount = o.optInt("messageCount"),
        lastMessagePreview = o.optString("lastMessagePreview"),
        agentAvailable = o.optBoolean("agentAvailable"),
        parentSessionId = if (o.isNull("parentSessionId")) null else o.optString("parentSessionId"),
        isSubagent = o.optBoolean("isSubagent", false),
        subagentLabel = if (o.isNull("subagentLabel")) null else o.optString("subagentLabel"),
    )
}
