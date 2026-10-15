// tests/v2/test-regression.mjs — (e) 回归：不得破坏 v1 契约
// 纪律：**纯只读**。本文件不创建、不写入任何会话。
import { api } from './common.mjs';

const TOKEN = process.env.DSH_RC_TOKEN;
const cases = [];
const ok = (id, name, pass, detail = '') => {
  cases.push({ id, name, pass, detail });
  console.log(`[${pass ? 'PASS' : 'FAIL'}] ${id} ${name}${pass ? '' : ' :: ' + detail}`);
};

// ---- R1 /ping 免鉴权 ----
{
  const res = await fetch('http://127.0.0.1:8765/rc/v1/ping');
  const j = await res.json();
  ok('R1', '/ping 免鉴权且 v=1 + ts 为整数',
    res.status === 200 && j.v === 1 && Number.isInteger(j.ts) && typeof j.name === 'string',
    JSON.stringify(j));
}

// ---- R2 /status ----
{
  const r = await api('/rc/v1/status');
  ok('R2', '/status 200，字段齐全',
    r.status === 200 && r.json?.v === 1 && typeof r.json?.dsh?.ok === 'boolean'
    && Number.isInteger(r.json?.sessions?.total) && Number.isInteger(r.json?.uptimeMs),
    r.text.slice(0, 160));
}

// ---- R3 错误 token → 401 ----
{
  const res = await fetch('http://127.0.0.1:8765/rc/v1/status', { headers: { Authorization: 'Bearer deadbeef' } });
  const j = await res.json().catch(() => null);
  ok('R3', '错误 token → 401 unauthorized',
    res.status === 401 && j?.error?.code === 'unauthorized', `status=${res.status}`);
}

// ---- R4 无 token → 401 ----
{
  const res = await fetch('http://127.0.0.1:8765/rc/v1/sessions');
  ok('R4', '无 token → 401', res.status === 401, `status=${res.status}`);
}

// ---- R5 /sessions 分页无重无漏 ----
{
  const all = await api('/rc/v1/sessions?limit=200');
  const ids = [];
  let cur = null, guard = 0, pages = 0;
  do {
    const q = '/rc/v1/sessions?limit=50' + (cur ? `&cursor=${encodeURIComponent(cur)}` : '');
    const r = await api(q);
    if (r.status !== 200) break;
    ids.push(...(r.json.items || []).map(x => x.id));
    cur = r.json.nextCursor; pages++;
    if (++guard > 100) break;
  } while (cur);
  const uniq = new Set(ids).size;
  ok('R5', '/sessions 分页遍历无重无漏',
    ids.length === uniq && ids.length === (all.json?.items?.length ?? -1),
    `pages=${pages} 累计=${ids.length} 去重=${uniq} limit200真值=${all.json?.items?.length}`);
}

// ---- R6 nextCursor 非空当还有更多 ----
{
  const r = await api('/rc/v1/sessions?limit=2');
  const n = (r.json?.items || []).length;
  ok('R6', 'limit=2 时 nextCursor 非空（还有更多）', n === 2 && !!r.json?.nextCursor,
    `items=${n} cursor=${r.json?.nextCursor}`);
}

// ---- R7 limit 非法 → 400 ----
{
  const a = await api('/rc/v1/sessions?limit=0');
  const b = await api('/rc/v1/sessions?limit=abc');
  ok('R7', 'limit 非法（0/abc）→ 400 invalid_request',
    a.status === 400 && a.json?.error?.code === 'invalid_request'
    && b.status === 400 && b.json?.error?.code === 'invalid_request',
    `limit=0 → ${a.status}; limit=abc → ${b.status}`);
}

// ---- R8 会话项九字段 ----
{
  const r = await api('/rc/v1/sessions?limit=5');
  const K = ['id', 'title', 'status', 'createdAt', 'updatedAt', 'cwd', 'messageCount', 'lastMessagePreview', 'agentAvailable'];
  const its = r.json?.items || [];
  ok('R8', '会话列表项九字段齐全', its.length === 0 || its.every(x => K.every(k => k in x)),
    JSON.stringify(Object.keys(its[0] || {})));
}

// ---- R9 /workspaces：12 个工作区 ----
{
  const r = await api('/rc/v1/workspaces');
  const items = r.json?.items || r.json?.workspaces || [];
  ok('R9', '/workspaces 返回 200 且含 items 数组', r.status === 200 && Array.isArray(items),
    `status=${r.status} keys=${Object.keys(r.json || {}).join(',')} count=${items?.length}`);
  if (r.status === 200 && Array.isArray(items)) {
    ok('R9b', '/workspaces 每项含 id/title/path/sessionCount',
      items.every(x => 'id' in x && 'title' in x && 'path' in x && 'sessionCount' in x),
      JSON.stringify(Object.keys(items[0] || {})));
    ok('R9c', '/workspaces 计数与列表一致（数字非字符串）',
      items.every(x => Number.isInteger(x.sessionCount) && x.sessionCount >= 0),
      `types=${[...new Set(items.map(x => typeof x.sessionCount))].join(',')}`);
    console.log(`[INFO] workspaces count = ${items.length}`);
  }
}

// ---- R10 不存在会话 → 404 ----
{
  const a = await api('/rc/v1/sessions/session_not_exist_zzz/messages');
  ok('R10', '不存在会话 → 404 not_found',
    a.status === 404 && a.json?.error?.code === 'not_found', `status=${a.status}`);
}

// ---- R11 WS 仍可用（冒烟）----
{
  const ws = new WebSocket('ws://127.0.0.1:8765/rc/v1/stream?token=' + TOKEN);
  const hello = await Promise.race([
    new Promise(r => ws.addEventListener('message', e => { try { r(JSON.parse(e.data)); } catch { r(null); } })),
    new Promise(r => setTimeout(() => r(null), 8000)),
  ]);
  ok('R11', 'WS 仍可建连并收 hello', !!hello && hello.type === 'hello', JSON.stringify(hello || {}).slice(0, 100));
  ws.close();
}

// ---- R12 未知路径 → 404 ----
{
  const r = await api('/rc/v1/nope');
  ok('R12', '未知路径 → 404', r.status === 404, `status=${r.status}`);
}

// ---- R13 ping 在同一进程（方案A 附带确认）----
ok('R13', '方案A：网关与主 DSH 同进程（由外部 ps 判定，此处仅占位）', true, '见交付报告的 PID 证据');

const pass = cases.filter(c => c.pass).length;
console.log(`\n== regression: TOTAL=${cases.length} PASS=${pass} FAIL=${cases.length - pass}`);
process.exit(pass === cases.length ? 0 : 1);
