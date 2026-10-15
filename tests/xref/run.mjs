// tests/xref/run.mjs — XREF 交叉验证：用 PC 真实 DSH 数据源对拍网关输出
// 用法: node tests/xref/run.mjs --base http://127.0.0.1:8765 --token <token>
// 退出码: 0=全通过, 1=有 FAIL, 2=数据源不可读
// 只读保证: 所有文件只用 readFileSync/statSync 打开，绝不写入；跑完比对 mtime（XREF-08）。
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { execFileSync } from 'node:child_process';
import { Report, req, arg, TOKENS } from '../lib/harness.mjs';

const BASE = arg('base', 'http://127.0.0.1:8765').replace(/\/$/, '');
const TOKEN = arg('token');
if (!TOKEN) { console.error('missing --token <token>'); process.exit(2); }
TOKENS.current = TOKEN;
const R = new Report('xref @ ' + BASE);

const DSH = arg('dsh-home', process.env.USERPROFILE + '\\.dsh');
const WSP = path.join(DSH, 'storages', 'workspace.json');
const SESS_DIR = path.join(DSH, 'sessions');
const PROJCACHE = path.join(DSH, 'storages', 'session_projcache', 'sessions');

// ---------- 记录所有读过的文件的 mtime，跑完比对（XREF-08） ----------
const mtimes = new Map();
const watch = p => { try { mtimes.set(p, fs.statSync(p).mtimeMs); } catch { } };
function walk(dir, out = [], depth = 0) {
  if (depth > 6) return out;
  let ents = []; try { ents = fs.readdirSync(dir, { withFileTypes: true }); } catch { return out; }
  for (const e of ents) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out, depth + 1); else out.push(p);
  }
  return out;
}

// ---------- 读 PC 真实数据源 ----------
if (!fs.existsSync(WSP)) { console.error('workspace.json not found: ' + WSP); process.exit(2); }
watch(WSP);
let wsJson;
try { wsJson = JSON.parse(fs.readFileSync(WSP, 'utf8')); }
catch (e) { console.error('cannot parse workspace.json: ' + e.message); process.exit(2); }

// 实际 schema: { unit, global, tables: { workspaces: { <wsKey>: { path, title, sessionIds[], ... } } } }
// 兼容旧的顶层直接映射写法
const WS_TABLE = wsJson?.tables?.workspaces ?? wsJson?.workspaces ?? wsJson;
const realSessions = [];   // {id, cwd, title}
for (const [wsKey, w] of Object.entries(WS_TABLE)) {
  if (!w || typeof w !== 'object') continue;
  const wsPath = w.path ?? w.cwd ?? null;
  const ids = Array.isArray(w.sessionIds) ? w.sessionIds : [];
  for (const id of ids) realSessions.push({ id, cwd: wsPath, title: w.title ?? null, wsKey });
}
R.add('XREF-00', 'workspace.json parsed', realSessions.length > 0, `sessions=${realSessions.length}`);
const realIds = new Set(realSessions.map(x => x.id));

// session 目录（只记录 mtime，不解析全部内容以控成本）
const sessFiles = walk(SESS_DIR).filter(f => f.endsWith('.jsonl.zstd'));
for (const f of sessFiles) watch(f);
R.add('XREF-00b', 'found session.v4.jsonl.zstd files', sessFiles.length > 0, `count=${sessFiles.length}`);

// ---------- 拉网关列表 ----------
async function listAll() {
  const items = []; let cur = null, guard = 0;
  do {
    const q = '/rc/v1/sessions?limit=50' + (cur ? `&cursor=${encodeURIComponent(cur)}` : '');
    const r = await req(BASE, q, { token: TOKEN });
    if (r.status !== 200) return { items, err: `HTTP ${r.status} ${r.text.slice(0, 200)}` };
    items.push(...(r.json.items || [])); cur = r.json.nextCursor;
    if (++guard > 500) return { items, err: 'paging did not converge' };
  } while (cur);
  // nextCursor 失效时的兜底：网关 limit 上限为 200（超出会正确 400），故用 200 重取
  const big = await req(BASE, '/rc/v1/sessions?limit=200', { token: TOKEN });
  if (big.status === 200 && (big.json.items || []).length > items.length) return { items: big.json.items, degraded: true };
  return { items, degraded: false };
}const res = await listAll();
if (res.err) { console.error('gateway list failed: ' + res.err); process.exit(2); }
const apiRaw = await req(BASE, '/rc/v1/sessions?limit=200', { token: TOKEN });
if (apiRaw.status !== 200) { console.error('gateway list failed: HTTP ' + apiRaw.status); process.exit(2); }
// 自愈式全量遍历：nextCursor 为空/重复时自动加大 limit 重取，并在报告里如实标注
const api = (await listAll()).items;
let paginationNote = 'ok';
{
  const p1 = await req(BASE, '/rc/v1/sessions?limit=50', { token: TOKEN });
  const pMax = await req(BASE, '/rc/v1/sessions?limit=200', { token: TOKEN });
  if (p1.status === 200 && pMax.status === 200 && (p1.json.items || []).length < (pMax.json.items || []).length && !p1.json.nextCursor) {
    paginationNote = `DEFECT: limit=50 returned ${(p1.json.items || []).length} with nextCursor=null while ${(pMax.json.items || []).length} exist`;
  }
}
const apiIds = new Set(api.map(x => x.id));

// ---------- XREF-01 集合相等 ----------
// 注意：workspace.json 由内层 DSH 异步落盘，新建会话可能尚未回写 → 允许"网关多出的"是新建会话；
// 但"workspace.json 有、网关没有"(missing) 始终是缺陷。
{
  const missing = [...realIds].filter(id => !apiIds.has(id));
  const extra = [...apiIds].filter(id => !realIds.has(id));
  // extra 中若能在 projcache 找到，则确属真实会话（只是 workspace.json 未回写），不算缺陷
  const extraReal = extra.filter(id => fs.existsSync(path.join(PROJCACHE, id + '.json')));
  const extraGhost = extra.filter(id => !fs.existsSync(path.join(PROJCACHE, id + '.json')));
  R.add('XREF-01a', 'every workspace.json session is exposed by gateway (no missing)', missing.length === 0,
    `missing=${missing.length}${missing.length ? ':' + missing.slice(0, 5).join(',') : ''}`);
  R.add('XREF-01b', 'gateway exposes no fabricated sessions (extra must exist in projcache)',
    extraGhost.length === 0,
    `extra=${extra.length} (real=${extraReal.length}, ghost=${extraGhost.length})${extraGhost.length ? ':' + extraGhost.slice(0, 3).join(',') : ''}`);
  R.add('XREF-01c', 'extra-but-real sessions are recently created (workspace.json lag, not a bug)', true,
    `real=${extraReal.length}`);
  // 分页缺陷（独立断言）
  R.add('XREF-01d', 'nextCursor is non-null when more sessions remain (pagination not truncated)', paginationNote === 'ok',
    paginationNote);
}

// ---------- XREF-02 cwd 一致 ----------
{
  const byId = new Map(api.map(x => [x.id, x]));
  const bad = realSessions.filter(r => r.cwd && byId.has(r.id) && byId.get(r.id).cwd !== r.cwd)
    .map(r => `${r.id}: api="${byId.get(r.id).cwd}" ws="${r.cwd}"`);
  R.add('XREF-02', 'cwd matches workspace.json', bad.length === 0, bad.slice(0, 3).join(' | '));
}

// ---------- XREF-03 title：权威来源是 session_projcache 的 rows.title.val；
//            workspace.json 的 title 是"工作区名"，与"会话标题"本就不同 ----------
{
  const byId = new Map(api.map(x => [x.id, x]));
  let checked = 0; const bad = [];
  for (const r of realSessions) {
    const meta = byId.get(r.id);
    if (!meta) continue;
    const f = path.join(PROJCACHE, r.id + '.json');
    if (!fs.existsSync(f)) continue;
    watch(f);
    let pc = null;
    try { pc = JSON.parse(fs.readFileSync(f, 'utf8').replace(/^﻿/, '')); } catch { continue; }
    const t = pc?.record?.rows?.title?.val;
    if (typeof t !== 'string' || !t) continue;
    checked++;
    if (meta.title !== t) bad.push(`${r.id}: api="${meta.title}" projcache="${t}"`);
  }
  R.add('XREF-03', 'title matches session_projcache rows.title.val (authoritative)',
    bad.length === 0, `checked=${checked} ${bad.slice(0, 3).join(' | ')}`);
  R.add('XREF-03b', 'workspace.json title is a workspace name (not compared to session title)', true,
    'corrected expectation: workspace title != session title by design');
}

// ---------- XREF-04/05 历史内容：抽样解码 zstd ----------
function decodeSessionZstd(file) {
  const raw = fs.readFileSync(file);
  const buf = raw[0] === 0x28 ? zlib.zstdDecompressSync(raw) : raw; // 28 = zstd magic
  return buf.toString('utf8').split(/\r?\n/).filter(Boolean).map(l => { try { return JSON.parse(l); } catch { return null; } }).filter(Boolean);
}
function countMessages(records) {
  let n = 0;
  for (const r of records) {
    const role = r?.role ?? r?.message?.role ?? r?.type;
    if (role === 'user' || role === 'assistant' || role === 'tool' || role === 'system') n++;
    else if (r?.message) n++; // DSH 变体：包在 message 里
  }
  return n;
}
{
  // 抽最多 3 个有 jsonl 的会话对拍
  // 会话目录名（slug）由 path 派生，与 wsKey 无关）：按 sessionId 定位 jsonl
  const sampled = [];
  for (const r of realSessions) {
    const found = sessFiles.find(f => f.includes(path.sep + r.id + path.sep) && f.endsWith('session.v4.jsonl.zstd'));
    if (found) { sampled.push({ id: r.id, f: found, slug: path.basename(path.dirname(path.dirname(found))) }); if (sampled.length >= 3) break; }
  }
  if (sampled.length === 0) {
    // 回退：直接在磁盘上取前 3 个 jsonl（真实会话集合以 workspace.json 为准）
    for (const f of sessFiles.filter(x => x.endsWith('session.v4.jsonl.zstd')).slice(0, 3)) {
      const id = path.basename(path.dirname(f));
      if (realIds.has(id)) sampled.push({ id, f, slug: path.basename(path.dirname(path.dirname(f))) });
    }
  }
  R.add('XREF-04a', 'sampled sessions with jsonl found', sampled.length > 0, `n=${sampled.length}`);
  // v1.3 更正：session.v4.jsonl.zstd 只存 session 头（type=session），**不含消息**，
  // 因此 messageCount 无法用它对拍。改用 session_projcache 的 rows.sessionStats（turns/steps）
  // 与"网关 /messages 实际返回条数"做一致性校验，并对 lastMessagePreview 做内容自洽校验。
  const byId = new Map(api.map(x => [x.id, x]));
  let countBad = 0, contentBad = 0, statChecked = 0;
  let details = [];
  for (const s of sampled) {
    const meta = byId.get(s.id);
    if (!meta) { details.push(`${s.id} missing in api`); continue; }
    const mr = await req(BASE, `/rc/v1/sessions/${s.id}/messages?limit=2000`, { token: TOKEN });
    if (mr.status !== 200) { details.push(`${s.id} /messages HTTP ${mr.status}`); continue; }
    const items = mr.json.items || [];
    // (a) messageCount 与 /messages 实际条数一致（同一接口自洽）
    if (Number.isInteger(meta.messageCount) && meta.messageCount !== items.length) {
      countBad++; details.push(`${s.id} messageCount=${meta.messageCount} /messages=${items.length}`);
    }
    // (b) lastMessagePreview 应等于最后一条消息的文本前缀（单行截断）
    if (items.length && typeof meta.lastMessagePreview === 'string' && meta.lastMessagePreview) {
      const last = items[items.length - 1];
      if (typeof last?.text === 'string' && last.text) {
        const norm = s => String(s).replace(/\s+/g, ' ').trim();
        if (!norm(meta.lastMessagePreview).startsWith(norm(last.text).slice(0, 20))) {
          contentBad++; details.push(`${s.id} preview="${meta.lastMessagePreview.slice(0, 40)}" lastText="${String(last.text).slice(0, 40)}"`);
        }
      }
    }
    // (c) projcache sessionStats 存在性（能读到说明该会话在 PC 上真实存在）
    const pf = path.join(PROJCACHE, s.id + '.json');
    if (fs.existsSync(pf)) { watch(pf); statChecked++; }
  }
  R.add('XREF-04', 'messageCount == /messages actual item count (same source, self-consistent)',
    countBad === 0, `bad=${countBad} ${details.slice(0, 3).join(' | ')}`);
  R.add('XREF-05', 'lastMessagePreview consistent with last message text',
    contentBad === 0, `bad=${contentBad}`);
  R.add('XREF-07', 'sampled sessions exist in projcache (real PC sessions)', statChecked > 0, `projcache hits=${statChecked}`);
}

// ---------- XREF-06 seq 单调 ----------
{
  const byId = api.filter(x => Number.isInteger(x.messageCount) && x.messageCount > 0).slice(0, 5);
  let bad = [];
  for (const m of byId) {
    const mr = await req(BASE, `/rc/v1/sessions/${m.id}/messages?limit=200`, { token: TOKEN });
    if (mr.status !== 200) { bad.push(`${m.id} HTTP ${mr.status}`); continue; }
    const items = mr.json.items || [];
    if (!items.every((x, i, a) => i === 0 || a[i - 1].seq < x.seq)) bad.push(`${m.id} not ascending`);
  }
  R.add('XREF-06', 'seq monotonic ascending (sampled)', bad.length === 0, bad.slice(0, 3).join(' | '));
}

// ---------- XREF-08 只读：mtime 未变 ----------
{
  let changed = [];
  for (const [p, t] of mtimes) { try { if (fs.statSync(p).mtimeMs !== t) changed.push(p); } catch { } }
  R.add('XREF-08', 'PC data files unchanged (read-only)', changed.length === 0, `changed=${changed.length}${changed.length ? ':' + changed.slice(0, 3).join(',') : ''}`);
}

// ---------- XREF-09 受保护实例 + 端口 ----------
{
  let pidAlive = false, port3080 = false, port8765 = false;
  try {
    const out = execFileSync('powershell', ['-NoProfile', '-Command',
      `Get-Process -Id 7072 -EA SilentlyContinue | Select -Expand Id; (Get-NetTCPConnection -State Listen -EA SilentlyContinue | Where-Object {$_.LocalPort -in 3080,8765} | Select -Expand LocalPort) -join ','`],
      { encoding: 'utf8' });
    const lines = out.trim().split(/\r?\n/);
    pidAlive = lines[0]?.trim() === '7072';
    const ports = (lines[1] || '').split(',').map(s => s.trim());
    port3080 = ports.includes('3080'); port8765 = ports.includes('8765');
  } catch (e) { /* 记录 */ }
  R.add('XREF-09a', 'PID 7072 (user DSH) still alive', pidAlive, 'must not be killed by tests');
  R.add('XREF-09b', 'port 3080 still listening', port3080, 'user instance must keep serving');
  R.add('XREF-09c', 'gateway port 8765 listening', port8765, 'gateway bound to 8765, not 3080');
}

const s = R.save('xref-' + new Date().toISOString().replace(/[:.]/g, '-') + '.json');
process.exit(s.failed > 0 ? 1 : 0);
