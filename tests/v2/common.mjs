// tests/v2/common.mjs — v2 契约测试共用工具（只读优先；写仅限本文件 CREATED 登记的会话）
export const BASE = 'http://127.0.0.1:8765';
export const TOKEN = process.env.DSH_RC_TOKEN || '';
export const HEADERS = { Authorization: `Bearer ${TOKEN}` };

/** 本测试自己创建的会话（唯一允许写入的对象） */
export const CREATED = [];

export async function api(path, { method = 'GET', body, headers = {} } = {}) {
  const h = { ...HEADERS, ...headers };
  if (body !== undefined) h['Content-Type'] = 'application/json';
  const res = await fetch(BASE + path, {
    method, headers: h,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { }
  return { status: res.status, json, text };
}

/** 用 before 游标把整个会话历史**确定性取全**（避免两次请求间数据增长导致页漂移） */
export async function fetchAll(sid, expand = 'summary', pageSize = 100) {
  const out = [];
  let before = null, guard = 0;
  do {
    const q = `/rc/v1/sessions/${sid}/messages?limit=${pageSize}` +
      (expand ? `&expand=${expand}` : '') +
      (before ? `&before=${before}` : '');
    const r = await api(q);
    if (r.status !== 200) return { items: out, err: `HTTP ${r.status} ${r.text.slice(0, 120)}` };
    const items = r.json.items || [];
    out.push(...items);
    before = r.json.nextCursor;
    if (!before) break;
    if (++guard > 200) return { items: out, err: 'cursor did not converge' };
  } while (true);
  out.sort((a, b) => a.seq - b.seq);
  return { items: out, err: null };
}

export function mkSession(title) { return title; }
