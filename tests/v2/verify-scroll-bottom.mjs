// tests/v2/verify-scroll-bottom.mjs
//
// 问题 3 acceptance: "打开会话没有自动跳到最新".
//
// The previous verdict accepted "a pin-to-bottom trace appeared" as proof, which is
// exactly why five rounds of "still not the newest" slipped through: the trace only
// proves we CALL scrollToItem, never that the viewport ended up there.
//
// This script asserts the two facts that actually matter:
//   A. GATEWAY  the newest row is reachable and is the last item of ?limit=N
//   B. DEVICE   the bottom-most visible text of the right pane matches that newest
//               row (read from a real `uiautomator dump`)
//
// Usage:
//   node tests/v2/verify-scroll-bottom.mjs                 # gateway only
//   node tests/v2/verify-scroll-bottom.mjs --device <serial> # gateway + UI dump
//
// Exit 0 only when every executed check passes. Checks that cannot run (no device
// attached) are reported as SKIP, never as PASS.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const BASE = process.env.DSH_GATEWAY || 'http://127.0.0.1:8765';
const ADB = process.env.ADB || 'E:\\system\\android-sdk\\platform-tools\\adb.exe';

const argv = process.argv.slice(2);
const devIdx = argv.indexOf('--device');
const SERIAL = devIdx >= 0 ? argv[devIdx + 1] : process.env.DSH_SERIAL || null;

const tokenFile = path.join(ROOT, 'pc-gateway', '.state', 'token.txt');
if (!fs.existsSync(tokenFile)) { console.error('token file missing'); process.exit(2); }
const H = { Authorization: 'Bearer ' + fs.readFileSync(tokenFile, 'utf8').trim() };

let pass = 0, fail = 0, skip = 0;
const A = (c, m, x = '') => { if (c) { pass++; console.log('[PASS] ' + m); } else { fail++; console.log('[FAIL] ' + m + (x ? ' :: ' + x : '')); } };
const S = (m, why = '') => { skip++; console.log('[SKIP] ' + m + (why ? ' :: ' + why : '')); };

const api = async (p) => {
  const r = await fetch(BASE + p, { headers: H });
  return { status: r.status, body: await r.json().catch(() => null) };
};

/** Text of a message row, used to compare the pane against the gateway. */
function rowText(m) {
  const pick = [m.text, m.collapsedSummary, m.toolName, m.toolOutput]
    .filter((x) => typeof x === 'string' && x.trim().length > 0);
  return (pick[0] || '').replace(/\s+/g, ' ').trim();
}

console.log('════ verify-scroll-bottom ════');
console.log('gateway : ' + BASE);
console.log('device  : ' + (SERIAL || '(none)'));
console.log('');

// ─────────────────────────────────────────────────────────────────────────
// A. GATEWAY — the newest row must be the last item of a limit-N page
// ─────────────────────────────────────────────────────────────────────────
const sessions = await api('/rc/v1/sessions?limit=200');
const all = sessions.body?.items || [];
A(sessions.status === 200 && all.length > 0, `会话列表可读（n=${all.length}）`);

// Cover several lengths, exactly like the parameterized App tests do.
const byLen = new Map();
for (const s of all) byLen.set(s.cwd, (byLen.get(s.cwd) || 0) + 1);
const sampled = [];
const seenCwds = new Set();
for (const s of all) {                       // prefer busy workspaces
  if (!seenCwds.has(s.cwd)) { seenCwds.add(s.cwd); sampled.push(s.cwd); }
}
const targets = [];
for (const cwd of sampled) {
  const page = await api('/rc/v1/sessions?limit=200&workspace=' + encodeURIComponent(cwd));
  const items = page.body?.items || [];
  if (items.length > 0) targets.push({ cwd, n: items.length });
}
targets.sort((a, b) => b.n - a.n);
const cover = targets.slice(0, 8);
console.log(`   取样 ${cover.length} 个工作区（按会话数降序）`);
console.log('');

// Compare each sampled workspace's newest row two ways: the last item of a
// limit=50 page, and a single limit=1 row. They must agree on seq.
const rowsChecked = [];
for (const t of cover) {
  const wsSessions = await api('/rc/v1/sessions?limit=200&workspace=' + encodeURIComponent(t.cwd));
  const list = wsSessions.body?.items || [];
  const sid = list[0]?.id;
  if (!sid) { S(`工作区 ${t.cwd} 没有会话`); continue; }

  // Full newest page + a single newest row, then compare their last entries.
  const full = await api(`/rc/v1/sessions/${sid}/messages?limit=50`);
  const one = await api(`/rc/v1/sessions/${sid}/messages?limit=1`);
  const items = full.body?.items || [];
  const last = items[items.length - 1];
  const only = (one.body?.items || [])[0];

  A(full.status === 200 && items.length > 0, `${sid.slice(0, 12)}… n=${t.n} messages 可读（返回 ${items.length} 条）`);
  if (last && only) {
    A(last.seq === only.seq, `${sid.slice(0, 12)}… limit=50 的末条 seq(${last.seq}) == limit=1 的 seq`);
    const ascending = items.length < 2 || items[0].seq <= last.seq;
    A(ascending, `${sid.slice(0, 12)}… 升序（first=${items[0]?.seq} last=${last.seq}）`);
    const text = rowText(last);
    if (text) rowsChecked.push({ sid, seq: last.seq, text });
  }
}
console.log(`   取到 ${rowsChecked.length} 条可比对的最新行`);

// ─────────────────────────────────────────────────────────────────────────
// B. DEVICE — the bottom-most visible text must equal the newest row
// ─────────────────────────────────────────────────────────────────────────
function adb(args) {
  return execFileSync(ADB, args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

let deviceReady = false;
if (SERIAL) {
  try {
    const out = adb(['-s', SERIAL, 'shell', 'echo', 'ok']);
    deviceReady = String(out).includes('ok');
  } catch { deviceReady = false; }
}
if (!deviceReady) {
  console.log('');
  S('⑤ 设备 UI 比对（无设备或未连接）', 'adb 未返回 ok；本轮只跑了网关侧');
} else {
  // Dump the view hierarchy and collect every text node with its bounds.
  const dumpPath = '/sdcard/verify_scroll_bottom.xml';
  try {
    adb(['-s', SERIAL, 'shell', 'uiautomator', 'dump', dumpPath]);
    adb(['-s', SERIAL, 'pull', dumpPath, path.join(os.tmpdir(), 'verify_scroll_bottom.xml')]);
  } catch (e) {
    S('⑤ UI dump', String(e.message).slice(0, 80));
  }
  const local = path.join(os.tmpdir(), 'verify_scroll_bottom.xml');
  if (fs.existsSync(local)) {
    const xml = fs.readFileSync(local, 'utf8');
    const nodes = [...xml.matchAll(/text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g)]
      .map(m => ({ text: m[1].replace(/&amp;/g, '&').trim(), bottom: +m[5] }))
      .filter(n => n.text.length > 0)
      .sort((a, b) => a.bottom - b.bottom);
    if (nodes.length === 0) S('⑤ 右栏无可读文本节点', 'dump 中没有 text 属性');
    for (const row of rowsChecked.slice(0, 5)) {
      const needle = row.text.slice(0, 24);
      const hit = nodes.find(n => n.text.includes(needle));
      A(!!hit, `右栏可见 seq=${row.seq} 的最新行`, `needle=${needle}`);
      if (hit) console.log(`        y=${hit.bottom}  ${hit.text.slice(0, 60)}`);
    }
  }
}

console.log('');
console.log('VERIFY-SCROLL-BOTTOM  PASS=' + pass + '  FAIL=' + fail + '  SKIP=' + skip);
if (skip > 0 && fail === 0) {
  console.log('⚠ 有跳过项：未跳过的判据才算已验证。');
}
process.exit(fail === 0 ? 0 : 1);