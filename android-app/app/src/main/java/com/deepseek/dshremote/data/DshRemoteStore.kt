package com.deepseek.dshremote.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deepseek.dshremote.core.PairingInfo
import com.deepseek.dshremote.core.PairingParseResult
import com.deepseek.dshremote.core.PairingParser
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "dsh_remote")

/**
 * 配对信息存储（DataStore）与**待发队列持久化**（契约 GAP-17）。
 *
 * **token 不明文落盘**（裁定 R-1）：先经 [TokenCipher]（AndroidKeyStore + AES-GCM）
 * 加密成 `v1:<iv>:<ct>` 才写入 DataStore。密钥由系统 Keystore 持有，App 拿不到明文密钥。
 * - 密钥失效（用户清除凭据 / 换机）⇒ [loadPairing] 返回 null，UI 回到配对页，**不崩溃**；
 * - 存量明文 token **不迁移**（避免明文长期滞留），直接视为未配对。
 */
class DshRemoteStore(private val context: Context) {

    private object Keys {
        val NAME = stringPreferencesKey("pair_name")
        val HOST = stringPreferencesKey("pair_host")
        val PORT = stringPreferencesKey("pair_port")
        val TOKEN = stringPreferencesKey("pair_token_enc")
        val FP = stringPreferencesKey("pair_fp")
        val QUEUE = stringPreferencesKey("pending_queue")
        val SIDEBAR = stringPreferencesKey("sidebar_collapsed")
        /** Cached workspace list (JSON array) -- see `ListCache`. */
        val WS_CACHE = stringPreferencesKey("workspace_cache")
        /** Cached per-workspace session lists (JSON object: path -> JSON array). */
        val WS_SESSIONS = stringPreferencesKey("workspace_sessions_cache")
        /** When the two caches above were written (ms). */
        val LIST_CACHE_AT = stringPreferencesKey("list_cache_at")
    }

    // ── 左栏折叠状态记忆（需求 §二.4） ─────────────────────
    suspend fun loadSidebarCollapsed(): String? = context.dataStore.data.first()[Keys.SIDEBAR]

    suspend fun saveSidebarCollapsed(v: String) {
        context.dataStore.edit { it[Keys.SIDEBAR] = v }
    }

    // ── 配对信息 ────────────────────────────────────
    suspend fun savePairing(info: PairingInfo) {
        val sealed = TokenCipher.encrypt(info.token)      // 只落密文
        context.dataStore.edit {
            it[Keys.NAME] = info.name
            it[Keys.HOST] = info.host
            it[Keys.PORT] = info.port.toString()
            it[Keys.TOKEN] = sealed
            it[Keys.FP] = info.fp
        }
    }

    /**
     * Why pairing could not be restored.
     *
     * Cold-start debugging (2026-10-12): a silent `null` here made the App look
     * frozen with zero network traffic, because every failure looked identical to
     * "never paired". `KeyLost` is the common real case -- installing over an older
     * build wipes the AndroidKeyStore entry while the ciphertext in DataStore
     * survives, so the token can no longer be decrypted.
     */
    enum class PairingLoss { NONE, KEY_LOST, MALFORMED, NOT_PAIRED }

    sealed interface PairingReadResult {
        data class Ok(val info: PairingInfo) : PairingReadResult
        /** Nothing stored: the user has simply never paired this install. */
        data object NotPaired : PairingReadResult
        /** Stored, but the key is gone/corrupt => re-pair required, data was NOT wiped. */
        data class NeedsRepair(val reason: PairingLoss, val detail: String?) : PairingReadResult
    }

    suspend fun loadPairing(): PairingInfo? = when (val r = loadPairingDetailed()) {
        is PairingReadResult.Ok -> r.info
        else -> null
    }

    /**
     * Same as [loadPairing] but reports WHY it failed.
     *
     * Note: on [PairingLoss.KEY_LOST] we deliberately do **not** wipe the stored
     * ciphertext. Wiping first and failing later leaves the App with an empty
     * store and no way to tell the user what happened.
     */
    suspend fun loadPairingDetailed(): PairingReadResult {
        val p: Preferences = context.dataStore.data.first()
        val host = p[Keys.HOST] ?: return PairingReadResult.NotPaired
        val sealed = p[Keys.TOKEN] ?: return PairingReadResult.NotPaired
        val port = p[Keys.PORT]?.toIntOrNull()
            ?: return PairingReadResult.NeedsRepair(PairingLoss.MALFORMED, "port missing")
        // Non-envelope data (e.g. legacy plaintext) is treated as never-paired; no migration.
        if (!TokenCipher.isCiphertext(sealed)) return PairingReadResult.NotPaired
        val token = try {
            TokenCipher.decrypt(sealed)
        } catch (e: TokenCipher.DecryptFailed) {
            // The Keystore key is gone (reinstall over the old build wipes it).
            // Keep the ciphertext: a fresh pairing simply overwrites it.
            return PairingReadResult.NeedsRepair(
                PairingLoss.KEY_LOST, e.message ?: e::class.java.simpleName
            )
        }
        // Reuse the same validation, so corrupted data never enters the connect path.
        val parsed = PairingParser.parse(
            """{"v":1,"name":${quote(p[Keys.NAME].orEmpty())},"host":"$host","port":$port,"token":"$token","fp":"${p[Keys.FP].orEmpty()}"}"""
        )
        return (parsed as? PairingParseResult.Ok)
            ?.let { PairingReadResult.Ok(it.info) }
            ?: PairingReadResult.NeedsRepair(PairingLoss.MALFORMED, "stored pairing failed validation")
    }

    suspend fun clearPairing() {
        context.dataStore.edit { it.clear() }
        TokenCipher.deleteKey()
    }

    // ── 待发队列（GAP-17） ───────────────────────────
    /** 用 org.json 序列化（Android 侧专用；纯逻辑测试走 core 层，不依赖这里）。 */
    fun encodeQueue(items: List<QueueRow>): String {
        val arr = JSONArray()
        for (i in items) {
            arr.put(JSONObject().apply {
                put("requestId", i.requestId)
                put("sessionId", i.sessionId)
                put("text", i.text)
                put("enqueuedAt", i.enqueuedAt)
                put("attempts", i.attempts)
                put("lastError", i.lastError ?: JSONObject.NULL)
                put("failed", i.failed)
            })
        }
        return arr.toString()
    }

    fun decodeQueue(raw: String?): List<QueueRow> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                QueueRow(
                    requestId = o.optString("requestId"),
                    sessionId = o.optString("sessionId"),
                    text = o.optString("text"),
                    enqueuedAt = o.optLong("enqueuedAt"),
                    attempts = o.optInt("attempts"),
                    lastError = if (o.isNull("lastError")) null else o.optString("lastError"),
                    failed = o.optBoolean("failed"),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun loadQueueRows(): List<QueueRow> = decodeQueue(context.dataStore.data.first()[Keys.QUEUE])

    suspend fun saveQueueRows(rows: List<QueueRow>) {
        context.dataStore.edit { it[Keys.QUEUE] = encodeQueue(rows) }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    // ── 列表缓存（用户问题 4：长时间不访问后回来仍要秒开） ──────────
    /**
     * 落盘「工作区列表 + 各工作区会话列表」。
     *
     * 为什么需要：App 冷启动原先必须等 `/workspaces` 与 `/sessions` 两个网络往返
     * 才能画出左栏；用户离开很久再回来，PC 端会话数早已变化，等一遍网络就是
     * 「点进去要转很久」。有了本地副本，左栏**立刻**有内容，网络回来后在后台静默刷新。
     *
     * 只存**列表元数据**（id/title/cwd/updatedAt/status 等），不存消息正文，
     * 体积有界；`clearPairing()`（it.clear()）会连带清掉，两份**分开写**：
     * 冷启动刷新工作区时 loadedSessionsByPath 还是空的，不能顺手覆写会话缓存。
     */
    suspend fun saveWorkspaceCache(workspacesJson: String) {
        context.dataStore.edit {
            it[Keys.WS_CACHE] = workspacesJson
            it[Keys.LIST_CACHE_AT] = System.currentTimeMillis().toString()
        }
    }

    suspend fun saveSessionsCache(sessionsJson: String) {
        context.dataStore.edit {
            it[Keys.WS_SESSIONS] = sessionsJson
            it[Keys.LIST_CACHE_AT] = System.currentTimeMillis().toString()
        }
    }

    suspend fun loadListCacheAt(): Long =
        context.dataStore.data.first()[Keys.LIST_CACHE_AT]?.toLongOrNull() ?: 0L

    suspend fun loadWorkspaceCache(): String? = context.dataStore.data.first()[Keys.WS_CACHE]

    suspend fun loadWorkspaceSessionsCache(): String? = context.dataStore.data.first()[Keys.WS_SESSIONS]
}

/** DataStore 与 core 层之间的行模型（避免 core 依赖 Android）。 */
data class QueueRow(
    val requestId: String,
    val sessionId: String,
    val text: String,
    val enqueuedAt: Long,
    val attempts: Int,
    val lastError: String?,
    val failed: Boolean,
)
