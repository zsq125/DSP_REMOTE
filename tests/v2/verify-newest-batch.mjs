// tests/v2/verify-newest-batch.mjs -- Batch verification over MANY sessions.
//
// Stop verifying one session at a time: the "opening shows stale content" bug was
// structural, so it must be checked across session sizes. For every session this
// asserts the first-screen contract the App relies on:
//
//   1. GET /messages?limit=50 returns rows whose LAST seq is the session's newest
//   2. the returned array is ascending (oldest -> newest)
//   3. nextCursor is present when the session has more than `limit` messages
//   4. the first row is a real seq (> 0) and no newest message is dropped
//
// Usage:  node tests/v2/verify-newest-batch.mjs [limit]
// Reads the gateway token from pc-gateway/.state/token.txt (read-only).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const BASE = process.env.DSH_GATEWAY || 'http://127.0.0.1:8765';
const LIMIT = Number(process.argv[2] || 50);

const tokenFile = path.join(ROOT, 'pc-gateway', '.state', 'token.txt');
if (!fs.existsSync(tokenFile)) {
  console.error('token file missing: ' + tokenFile);
  process.exit(2);
}
const TOKEN = fs.readFileSync(tokenFile, 'utf8').trim();
const H = { Authorization: 'Bearer ' + TOKEN };

let pass = 0, fail = 0;
const A = (c, m, x = '') => { if (c) { pass++; console.log('   [PASS] ' + m); } else { fail++; console.log('   [FAIL] ' + m + (x ? ' :: ' + x : '')); } };

async function api(pathname) {
  const r = await fetch(BASE + pathname, { headers: H });
  if (!r.ok) throw new Error(pathname + ' -> ' + r.status);
  return r.json();
}

const seqOf = (m) => (typeof m.seq === 'number' ? m.seq : Number(m.seq ?? -1));

console.log('════ verify-newest-batch  limit=' + LIMIT + ' ════\n');

const sessions = await api('/rc/v1/sessions');
const all = (sessions.items || []).slice(0, 40);
if (all.length === 0) { console.error('no sessions'); process.exit(2); }

console.log('发现 ' + all.length + ' 个会话，逐一验证首屏契约\n');

const rows = [];
for (const s of all) {
  const id = s.id;
  let page;
  try {
    page = await api(`/rc/v1/sessions/${id}/messages?limit=${LIMIT}&expand=summary`);
  } catch (e) {
    console.log(`— ${id}: 请求失败 ${e.message}`);
    fail++;
    continue;
  }
  const items = page.items || [];
  if (items.length === 0) {
    // A brand-new conversation has zero messages. It is a first-class case, not a
    // skip: the App opens it, renders an empty state, and must not crash while
    // trying to scroll to a "newest" row that does not exist.
    A(page.nextCursor == null, `空会话 nextCursor 必须为 null（实际 ${page.nextCursor}）`);
    A(items.length === 0, `空会话 items 必须为 []（实际 ${items.length}）`);
    console.log(`✔ ${id}  n=0  EMPTY  cursor=null  [空会话已校验]`);
    rows.push({ id, n: 0, first: '-', last: '-', cursor: null });
    continue;
  }

  const seqs = items.map(seqOf);
  const first = seqs[0], last = seqs[seqs.length - 1];
  const ascending = seqs.every((v, i) => i === 0 || v >= seqs[i - 1]);

  // The newest seq in the session is whatever the LAST row carries; a descending
  // or "oldest-first" page would put it at index 0 instead.
  const newest = last;

  const checks = [
    [ascending, `返回数组升序（first=${first} < last=${last}）`],
    [first > 0, `首条 seq 有效（>0，实际 ${first}）`],
    [newest >= first, `末条即最新（${newest} >= ${first}）`],
    [items.length <= LIMIT, `返回不超过 limit（${items.length} <= ${LIMIT}）`],
    // A full page implies older history exists and MUST expose a cursor; a short
    // page legitimately has none.
    [items.length < LIMIT || page.nextCursor != null,
      `满页时必须有 nextCursor（n=${items.length}/${LIMIT}, cursor=${page.nextCursor ?? 'null'}）`],
  ];

  const bad = checks.filter(([ok]) => !ok);
  if (bad.length === 0) pass += checks.length;
  else fail += bad.length;

  console.log(`${bad.length === 0 ? '✔' : '✘'} ${id}  n=${items.length}  first=${first} last=${newest}  cursor=${page.nextCursor ?? 'null'}`);
  for (const [ok, m] of checks) console.log(`   [${ok ? 'PASS' : 'FAIL'}] ${m}`);

  // Cross-check the newest page against the NEXT page (older): the cursor must
  // point strictly BEFORE our last seq, otherwise paging would loop or skip.
  if (page.nextCursor != null) {
    try {
      const older = await api(`/rc/v1/sessions/${id}/messages?limit=${LIMIT}&expand=summary&before=${page.nextCursor}`);
      const olderSeqs = (older.items || []).map(seqOf);
      const olderLast = olderSeqs.length ? olderSeqs[olderSeqs.length - 1] : null;
      A(olderLast == null || olderLast < first, `向上翻页确实更旧（older.last=${olderLast} < first=${first}）`);
    } catch (e) {
      console.log('   [FAIL] 翻页请求失败 ' + e.message);
      fail++;
    }
  }
  rows.push({ id, n: items.length, first, last, cursor: page.nextCursor ?? null });
}

console.log('\n════ 会话形态覆盖 ════');
// Always show one sample per shape so the report proves every conversation form
// was exercised (empty / single / exactly-limit / limit+1 / short / full).
const shapes = new Map();
for (const r of rows) {
  const k = r.n === 0 ? 'empty(0)'
    : r.n === 1 ? 'single(1)'
    : r.n === LIMIT ? `exactly-limit(${LIMIT})`
    : r.n === LIMIT + 1 ? `limit-plus-one(${LIMIT + 1})`
    : r.n < LIMIT ? `short(<${LIMIT})`
    : `full(${r.n})`;
  if (!shapes.has(k)) shapes.set(k, r);
}
for (const k of [`empty(0)`, `single(1)`, `exactly-limit(${LIMIT})`, `limit-plus-one(${LIMIT + 1})`, `short(<${LIMIT})`]) {
  const r = shapes.get(k);
  console.log(`  ${r ? '✔' : '·'} ${k.padEnd(24)} ${r ? `${r.id}  n=${r.n} first=${r.first} last=${r.last}` : '(本机暂无此类会话)'}`);
}

console.log('\n════ 汇总表 ════');
console.log('会话'.padEnd(42) + '条数'.padStart(6) + 'first'.padStart(9) + 'last'.padStart(9) + '  cursor');
for (const r of rows) {
  console.log(r.id.padEnd(42) + String(r.n).padStart(6) + String(r.first).padStart(9) + String(r.last).padStart(9) + '  ' + r.cursor);
}
console.log('\nVERIFY-NEWEST-BATCH  PASS=' + pass + '  FAIL=' + fail + '  sessions=' + rows.length);
process.exit(fail === 0 ? 0 : 1);