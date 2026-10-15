package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.ApiError
import com.deepseek.dshremote.protocol.RestResult
import com.deepseek.dshremote.protocol.SessionSummary

/** 工作区（网关 `GET /rc/v1/workspaces`）。 */
data class Workspace(
    val id: String,
    val title: String,
    val path: String,
    /** **实时**会话数（网关按 cwd 统计，与二级页列表条数同源）。 */
    val sessionCount: Int,
    val updatedAt: Long,
) {
    /** 列表副标题用的显示名。 */
    val display: String get() = title.ifBlank { path }
}

/** 工作区数据源（网关 REST），便于单测注入。 */
interface WorkspaceSource {
    suspend fun loadWorkspaces(): RestResult<List<Workspace>>
    /** 翻完**所有**分页取回该工作区的会话（按 cwd 过滤）。 */
    suspend fun loadSessions(workspacePath: String): RestResult<List<SessionSummary>>
}

/**
 * 工作区仓储：本地缓存 + 同源过滤（需求 §4.2 / §4.3）。
 *
 * 两条关键性质：
 *  1. **同源**：二级页的会话列表与一级页标题上的数字来自**同一份数据**
 *     （都是「按 `cwd == workspace.path` 过滤」）⇒ 数字与条数必然一致；
 *  2. **缓存**：工作区列表本地缓存，二次进入瞬时（PM 2026-10-11 确认）。
 */
class WorkspaceRepo(
    private val source: WorkspaceSource,
    private val cache: MutableList<Workspace> = mutableListOf(),
) {
    /** 内存缓存的工作区（可能为空 = 尚未加载）。 */
    fun cached(): List<Workspace> = cache.toList()

    fun hasCache(): Boolean = cache.isNotEmpty()

    /** 加载工作区；成功则写入缓存。 */
    suspend fun refreshWorkspaces(): RestResult<List<Workspace>> {
        return when (val r = source.loadWorkspaces()) {
            is RestResult.Ok -> {
                cache.clear(); cache.addAll(r.value); r
            }
            is RestResult.Err -> r
        }
    }

    /** 取工作区：优先用缓存（避免白屏），缓存为空才走网络。 */
    suspend fun workspaces(): RestResult<List<Workspace>> {
        if (cache.isNotEmpty()) return RestResult.Ok(cache.toList())
        return refreshWorkspaces()
    }

    /**
     * 某工作区的会话列表（按 cwd 过滤）。
     * 过滤规则与 [sessionsForWorkspace] 完全一致 ⇒ 保证「标题数字 == 列表条数」。
     */
    suspend fun sessionsFor(ws: Workspace): RestResult<List<SessionSummary>> =
        source.loadSessions(ws.path)
}

/**
 * 按 `cwd` 精确过滤会话（**纯函数，单测覆盖**）。
 *
 * ⚠ 不做大小写/尾斜杠归一化 —— 网关的 `sessionCount` 也是用**完全相等**的 `cwd` 统计的；
 * 若这里归一化而网关不归一化，两边就会对不上（PM 2026-10-11 已确认本机无此类变体）。
 */
fun sessionsForWorkspace(
    sessions: List<SessionSummary>,
    workspacePath: String,
): List<SessionSummary> = sessions.filter { it.cwd == workspacePath }

/**
 * subagent 会话识别（需求 §4.5）。
 *
 * subagent 会话的标题形如「你是本项目的**开发工程师**…」，且其会话本身也是普通会话，
 * 网关暂未提供父子关系字段 ⇒ 这里**只做列表层面的标记**，不阻塞任何流程
 * （拿不到父子关系就平铺，符合需求 §4.5「不得因此阻塞」）。
 */
object SubagentTagger {

    /** 标题特征：中文「你是」开头（DSH 生成 subagent 人设 prompt 的固定开头）。 */
    private val SUBAGENT_TITLE = Regex("^你是")

    /** 是否是 subagent 会话（依据标题特征，纯启发式，允许漏判但不误伤普通会话）。 */
    fun isSubagent(session: SessionSummary): Boolean =
        SUBAGENT_TITLE.containsMatchIn(session.title.trimStart())

    /** 列表展示用的标题：subagent 加前缀标记，便于用户区分。 */
    fun displayTitle(session: SessionSummary): String =
        if (isSubagent(session)) "【子代理】${session.title}" else session.title.ifBlank { "(无标题)" }

    /** 给一组会话打上标记。 */
    fun tagAll(sessions: List<SessionSummary>): List<TaggedSession> =
        sessions.map { TaggedSession(it, isSubagent(it)) }
}

/** 带 subagent 标记的会话（平铺展示，不做嵌套）。 */
data class TaggedSession(val session: SessionSummary, val isSubagent: Boolean)

/** 网络错误 → 人类可读文案。 */
fun apiErrorText(e: ApiError): String = "${e.code}: ${e.message}"

/**
 * 侧栏会话行的副标题：相对时间（"刚刚"/"5分钟前"/"3小时前"/"2天前"/"10月5日"）。
 *
 * 为什么用它而不是 messageCount：网关列表页默认**不再 enrich**（每行一次
 * `inspect()` 全量历史是「展开工作区卡住」的根因），messageCount 恒为 0；
 * 而 `updatedAt` 来自会话行本身，零成本且对用户更有信息量。
 */
fun relativeTimeLabel(now: Long, then: Long): String {
    if (then <= 0) return ""
    val d = now - then
    return when {
        d < 60_000L -> "刚刚"
        d < 3_600_000L -> "${d / 60_000L}分钟前"
        d < 86_400_000L -> "${d / 3_600_000L}小时前"
        d < 604_800_000L -> "${d / 86_400_000L}天前"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = then }
            "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日"
        }
    }
}
