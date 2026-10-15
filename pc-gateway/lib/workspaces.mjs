/**
 * pc-gateway/lib/workspaces.mjs —— 工作区分组（纯函数，可单测）
 *
 * 数据来源：PC 端 `~/.dsh/storages/workspace.json`
 *   global.workspaceIds            有序 uuid 数组
 *   tables.workspaces[uuid]         { path, title, sessionIds[], createdAt, updatedAt }
 *
 * 两条路径（需求 §3.2）：
 *  1. **权威**：按 workspace.json 的 workspaceIds 顺序输出；
 *  2. **兜底**：读不到文件时，按会话的 `cwd` 去重分组，保证 App 不白屏。
 *
 * 注意 `updatedAt` 在该文件里是 **ISO 字符串**（如 2026-10-10T16:47:12.325Z），
 * 而契约要求毫秒整数，故在此统一转成 ms。
 */
import { createHash } from 'node:crypto';

/** ISO 字符串 / 毫秒数 → 毫秒整数；无法解析时回退 0。 */
export function toMillis(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.length > 0) {
    const t = Date.parse(value);
    if (Number.isFinite(t)) return t;
  }
  return 0;
}

/**
 * 从已解析的 workspace.json 构造工作区列表。
 * @param {any} wsJson 解析后的 JSON
 * @returns {Array<{id,title,path,sessionCount,updatedAt}>}
 */
export function workspacesFromStorage(wsJson) {
  const ids = Array.isArray(wsJson?.global?.workspaceIds) ? wsJson.global.workspaceIds : [];
  const table = wsJson?.tables?.workspaces ?? {};
  const out = [];
  const seen = new Set();
  for (const id of ids) {
    const w = table[id];
    if (!w || seen.has(id)) continue;          // 跳过缺失项与重复项，保持幂等
    seen.add(id);
    out.push({
      id,
      title: typeof w.title === 'string' && w.title.length > 0 ? w.title : (w.path || id),
      path: typeof w.path === 'string' ? w.path : '',
      sessionCount: Array.isArray(w.sessionIds) ? w.sessionIds.length : 0,
      updatedAt: toMillis(w.updatedAt ?? w.createdAt),
    });
  }
  return out;
}

/**
 * 兜底：按会话 `cwd` 去重分组。
 * 仅包含**至少有一个会话**的分组（没有会话的工作区对用户无意义）。
 *
 * @param {Array<{id:string,cwd?:string}>} rows 会话列表项（契约 §3.2）
 * @returns {Array<{id,title,path,sessionCount,updatedAt}>}
 */
export function workspacesFromSessions(rows) {
  const byPath = new Map();
  for (const r of rows ?? []) {
    const path = typeof r?.cwd === 'string' ? r.cwd : '';
    if (!path) continue;                        // 没有 cwd 的会话无法归组，跳过
    let g = byPath.get(path);
    if (!g) {
      // id 用路径的稳定派生值（同一路径永远得到同一个 id，便于 App 侧选中态稳定）
      g = { id: deriveId(path), title: titleFromPath(path), path, sessionCount: 0, updatedAt: 0 };
      byPath.set(path, g);
    }
    g.sessionCount += 1;
    const u = toMillis(r.updatedAt);
    if (u > g.updatedAt) g.updatedAt = u;
  }
  // 按最近活跃排序，和 PC 端「最近使用的在前」直觉一致
  return [...byPath.values()].sort((a, b) => b.updatedAt - a.updatedAt || a.path.localeCompare(b.path));
}

/**
 * 按 `cwd` 实时统计每个工作区的会话数。
 *
 * PM 2026-10-11 实测发现：`workspace.json` 的 `sessionIds` 是**历史快照**，
 * 会明显滞后（12 个工作区里 11 个与实时值不符，tiga-survivor_v1 存储说 4、实际 47）。
 * 因此 `sessionCount` 必须实时计算，且必须**遍历全部分页**（实测 limit=100 就已 100 条 + nextCursor）。
 *
 * @param {Array<{cwd?:string}>} rows **全部**会话（调用方负责翻完 cursor）
 * @param {Array<{path:string}>} workspaces 需要被计数的路径列表
 * @returns {Map<string, number>} path -> 实时会话数
 */
export function countSessionsByPath(rows, workspaces) {
  /** @type {Map<string, number>} */
  const counts = new Map();
  // 先把目标路径置 0，保证「0 会话的工作区」也返回 0 而不是 undefined
  for (const w of workspaces) counts.set(w.path, 0);
  for (const r of rows ?? []) {
    const p = typeof r?.cwd === 'string' ? r.cwd : '';
    if (!p) continue;
    counts.set(p, (counts.get(p) ?? 0) + 1);
  }
  return counts;
}

/** 把实时计数覆盖到工作区列表上（返回新数组，不改入参）。 */
export function withLiveSessionCount(workspaces, counts) {
  return workspaces.map(w => ({ ...w, sessionCount: counts.get(w.path) ?? 0 }));
}

function titleFromPath(p) {
  const parts = String(p).split(/[\\/]/).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : p;
}

/** 路径 → 稳定 id（用 sha1 前 16 hex，加前缀避免与真实 uuid 混淆）。 */
export function deriveId(path) {
  return 'path-' + createHash('sha1').update(String(path)).digest('hex').slice(0, 16);
}
