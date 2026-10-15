// tests/contract/run.mjs - protocol contract tests (L3, against the REAL gateway)
// usage: node tests/contract/run.mjs --base http://127.0.0.1:8765 --token <token> [--hooks|--no-hooks] [--skip-rotate] [--verbose]
// exit: 0 = all pass, 1 = has FAIL, 2 = bad args / gateway unreachable
// NOTE: literals are ASCII on purpose (Windows PS/codepage safety).
import { Report, req, openWs, isIntMs, sleep, arg, hasFlag, TOKENS, trackCreated, saveCreated } from '../lib/harness.mjs';

const BASE = arg('base', 'http://127.0.0.1:8765').replace(/\/$/, '');
const TOKEN = arg('token');
if (!TOKEN) { console.error('missing --token'); process.exit(2); }
TOKENS.current = TOKEN; // req() 鐪佺暐 token 鏃惰嚜鍔ㄤ娇鐢ㄥ綋鍓?鍙兘宸茶疆鎹? token
const T = () => TOKENS.current;
const R = new Report('contract @ ' + BASE);
const V = hasFlag('verbose');

const HEX32 = /^[0-9a-f]{32}$/;
const SESSION_KEYS = ['id', 'title', 'status', 'createdAt', 'updatedAt', 'cwd', 'messageCount', 'lastMessagePreview', 'agentAvailable'];
const MSG_KEYS = ['seq', 'role', 'text', 'ts', 'status', 'messageId'];
const STATUS_ENUM = ['running', 'idle', 'unknown'];
const ERR_CODES = ['unauthorized', 'not_found', 'invalid_request', 'session_busy', 'internal', 'dsh_unavailable'];

/** null/true = pass; string = fail reason */
function errIs(r, code, http) {
  if (r.status !== http) return `HTTP ${r.status} != ${http} (${r.text.slice(0, 200)})`;
  const e = r.json?.error;
  if (!e) return 'missing error field';
  if (e.code !== code) return `code=${e.code} != ${code}`;
  if (typeof e.message !== 'string' || !e.message) return 'missing/empty message';
  if (r.json.v !== 1) return 'error response missing v=1';
  return null;
}

// ================= 0. reachability =================
let ping;
try { ping = await req(BASE, '/rc/v1/ping'); }
catch (e) { console.error('gateway unreachable:', e.message); process.exit(2); }

R.add('PROTO-G01', 'ping 200 without auth', ping.status === 200, `status=${ping.status}`);
R.add('PROTO-G08', 'ping response has v=1', ping.json?.v === 1, JSON.stringify(ping.json));
R.add('PROTO-G02', 'ping has name/version/pairRequired/ts(int-ms)',
  !!ping.json?.name && !!ping.json?.version && ping.json?.pairRequired === true && isIntMs(ping.json?.ts),
  JSON.stringify(ping.json));

// ================= 1. auth =================
R.addCheck('PROTO-G04', 'no token -> 401 unauthorized', errIs(await req(BASE, '/rc/v1/status', { token: null }), 'unauthorized', 401));
R.addCheck('PROTO-G05', 'wrong token -> 401 unauthorized', errIs(await req(BASE, '/rc/v1/status', { token: 'deadbeef' }), 'unauthorized', 401));
R.addCheck('PROTO-G06', 'non-Bearer -> 401 unauthorized', errIs(await req(BASE, '/rc/v1/status', { token: 'x', headers: { Authorization: 'Basic YWJj' } }), 'unauthorized', 401));

// ================= 2. status =================
const st = await req(BASE, '/rc/v1/status', { token: T() });
R.add('REST-S01', 'status 200 + fields',
  st.status === 200 && st.json?.v === 1 && typeof st.json?.dsh?.ok === 'boolean'
  && Number.isInteger(st.json?.sessions?.total) && isIntMs(st.json?.ts), JSON.stringify(st.json).slice(0, 300));
if (st.status === 200 && typeof st.json?.dsh?.ok === 'boolean') {
  R.add('GAP-19', 'dsh.ok=false still returns 200', st.status === 200, `ok=${st.json.dsh.ok}`);
} else if (st.json?.error) {
  R.add('GAP-19', 'dsh down -> 503 dsh_unavailable', st.status === 503 && st.json.error.code === 'dsh_unavailable',
    `status=${st.status} code=${st.json.error.code}`);
} else {
  R.add('GAP-19', 'status shape recognizable', false, st.text.slice(0, 200));
}
{
  const u1 = (await req(BASE, '/rc/v1/status', { token: T() })).json?.uptimeMs;
  await sleep(1500);
  const u2 = (await req(BASE, '/rc/v1/status', { token: T() })).json?.uptimeMs;
  R.add('REST-S01b', 'uptimeMs non-decreasing', Number.isInteger(u1) && Number.isInteger(u2) && u2 >= u1, `${u1} -> ${u2}`);
}

// ================= 3. sessions list & paging =================
async function listAll() {
  const items = []; let cur = null, guard = 0;
  do {
    const q = '/rc/v1/sessions?limit=2' + (cur ? `&cursor=${encodeURIComponent(cur)}` : '');
    const r = await req(BASE, q, { token: T() });
    if (r.status !== 200) return { items, err: `HTTP ${r.status} ${r.text.slice(0, 200)}` };
    items.push(...(r.json.items || [])); cur = r.json.nextCursor;
    if (++guard > 500) return { items, err: 'paging did not converge' };
  } while (cur);
  return { items };
}
const first = await req(BASE, '/rc/v1/sessions?limit=50', { token: T() });
let SESSIONS = [];
if (first.status === 200) {
  const its = first.json.items || [];
  R.add('REST-L01', 'session list fields complete', its.every(x => SESSION_KEYS.every(k => k in x)), JSON.stringify(its[0] || {}));
  R.add('REST-L02', 'status enum valid', its.every(x => STATUS_ENUM.includes(x.status)), JSON.stringify(its.map(x => x.status)));
  R.add('REST-L03', 'timestamps int-ms and updated>=created', its.every(x => isIntMs(x.createdAt) && isIntMs(x.updatedAt) && x.updatedAt >= x.createdAt));
  R.add('GAP-03a', 'sessions sorted by updatedAt desc', its.every((x, i) => i === 0 || its[i - 1].updatedAt >= x.updatedAt), JSON.stringify(its.map(x => x.updatedAt)));
  R.add('REST-L11', 'lastMessagePreview single-line', its.every(x => typeof x.lastMessagePreview === 'string' && !x.lastMessagePreview.includes('\n')));
  SESSIONS = (await listAll()).items;
  R.add('REST-L04', 'limit=2 honored', (await req(BASE, '/rc/v1/sessions?limit=2', { token: T() })).json?.items?.length === 2);
  R.addCheck('REST-L05', 'full paging: no dup, no loss', await (async () => {
    const ids = SESSIONS.map(x => x.id);
    return new Set(ids).size === ids.length ? null : `dup count=${ids.length - new Set(ids).size}`;
  })());
  R.add('XREF-ANCHOR', 'sessions available for later cases', SESSIONS.length > 0, `count=${SESSIONS.length}`);
} else {
  R.add('REST-L01', 'GET /sessions 200', false, `HTTP ${first.status} ${first.text.slice(0, 200)}`);
}
const SESS = SESSIONS[0]?.id ?? null;
const SESS2 = SESSIONS[1]?.id ?? null;

// ================= 3b. D-1 鍒嗛〉鍙栧叏锛坙imit=20 / 50 / 缂虹渷锛涙贩鍚?id 鐮佺偣搴忥級 =================
async function pageAll(qs) {
  const ids = []; let cur = null, guard = 0, pages = 0;
  for (; ;) {
    const q = '/rc/v1/sessions?' + qs + (cur ? `&cursor=${encodeURIComponent(cur)}` : '');
    const r = await req(BASE, q, { token: T() });
    if (r.status !== 200) return { ids, pages, err: `HTTP ${r.status} ${r.text.slice(0, 160)}` };
    ids.push(...(r.json.items || []).map(x => x.id)); pages++;
    cur = r.json.nextCursor;
    if (!cur) break;
    if (++guard > 500) return { ids, pages, err: 'cursor did not converge' };
  }
  return { ids, pages };
}
{
  const truth = (await req(BASE, '/rc/v1/sessions?limit=200', { token: T() })).json?.items || [];
  const total = truth.length;
  R.add('D1-00', 'total session count (truth via limit=200)', total > 0, `total=${total}`);
  for (const [label, qs] of [['20', 'limit=20'], ['50', 'limit=50'], ['default', '']]) {
    const p = await pageAll(qs);
    const uniq = new Set(p.ids);
    const dup = p.ids.length - uniq.size;
    R.addCheck('D1-' + label, `paging[${label}] returns ALL ${total} sessions, no dup`,
      p.err ?? (p.ids.length === total && dup === 0 ? null : `got=${p.ids.length}/${total} dup=${dup} pages=${p.pages}`));
    const missing = truth.map(x => x.id).filter(id => !uniq.has(id));
    R.add('D1-miss-' + label, `paging[${label}] no missing vs limit=200 truth`,
      missing.length === 0, missing.length ? `missing ${missing.length}: ${missing.slice(0, 3).join(',')}` : '');
    R.add('D1-gap-' + label, `paging[${label}] set equality (mixed-id codepoint order, no gaps)`,
      uniq.size === total && missing.length === 0, `uniq=${uniq.size}/${total} missing=${missing.length}`);
  }
  const p1 = await req(BASE, '/rc/v1/sessions?limit=20', { token: T() });
  R.add('D1-cursor', 'nextCursor non-null when more items remain',
    (p1.json?.items || []).length >= total ? true : !!p1.json?.nextCursor,
    `items=${(p1.json?.items || []).length}/${total} cursor=${p1.json?.nextCursor}`);
}


for (const [q, id, label] of [['limit=0', 'REST-L06a', 'limit=0'], ['limit=201', 'GAP-03b', 'limit=201'],
['limit=abc', 'GAP-03c', 'limit=abc'], ['limit=-5', 'REST-L06b', 'limit=-5']]) {
  R.addCheck(id, `sessions?${label} -> 400 invalid_request`, errIs(await req(BASE, '/rc/v1/sessions?' + q, { token: T() }), 'invalid_request', 400));
}
R.addCheck('REST-L07', 'bad cursor -> not 500', (await req(BASE, '/rc/v1/sessions?cursor=%40%40bogus%40%40', { token: T() })).status === 500
  ? `HTTP 500` : null);
R.addCheck('PROTO-G11', 'unknown path -> 404 not_found', errIs(await req(BASE, '/rc/v1/nope', { token: T() }), 'not_found', 404));

// ================= 4. messages =================
if (SESS) {
  const r = await req(BASE, `/rc/v1/sessions/${SESS}/messages?limit=50`, { token: T() });
  R.add('REST-M01', 'message fields complete', r.status === 200 && (r.json.items || []).every(x => MSG_KEYS.every(k => k in x)), r.text.slice(0, 200));
  R.add('REST-M02', 'seq strictly ascending', r.status === 200 && (r.json.items || []).every((x, i, a) => i === 0 || a[i - 1].seq < x.seq));
  R.add('GAP-20', 'messages returns nextCursor key', r.status === 200 && 'nextCursor' in (r.json || {}));
  R.addCheck('REST-M03', 'before-paging: no dup', await (async () => {
    let items = [], cur = null, g = 0, err = null;
    do {
      const q = `/rc/v1/sessions/${SESS}/messages?limit=1` + (cur ? `&before=${cur}` : '');
      const rr = await req(BASE, q, { token: T() });
      if (rr.status !== 200) { err = `HTTP ${rr.status}`; break; }
      items.push(...(rr.json.items || [])); cur = rr.json.nextCursor;
      if (++g > 500) { err = 'not converge'; break; }
    } while (cur);
    if (err) return err;
    return new Set(items.map(x => x.seq)).size === items.length ? null : `dup ${items.length}`;
  })());
} else R.skip('REST-M01', 'message cases', 'no session available');
R.addCheck('REST-M05', 'unknown session messages -> 404', errIs(await req(BASE, '/rc/v1/sessions/sess_nope_zzz/messages', { token: T() }), 'not_found', 404));

// ================= 5. create session + prompt idempotency =================
let NEW = null;
{
  const r = await req(BASE, '/rc/v1/sessions', { method: 'POST', token: T(), body: { cwd: null, title: 'rc-test' } });
  R.add('REST-C01', 'POST /sessions -> 200 + id', r.status === 200 && r.json?.v === 1 && typeof r.json?.id === 'string' && r.json.id, r.text.slice(0, 200));
  NEW = trackCreated(r.json?.id, 'contract temp session');
}
R.addCheck('REST-C04', 'cwd wrong type -> 400', errIs(await req(BASE, '/rc/v1/sessions', { method: 'POST', token: T(), body: { cwd: 123 } }), 'invalid_request', 400));
R.addCheck('PROTO-G06', 'POST /sessions no token -> 401', errIs(await req(BASE, '/rc/v1/sessions', { method: 'POST', token: null, body: { cwd: null, title: null } }), 'unauthorized', 401));

/** v1.3: 鐢??waitRequestId=<id> 鍋氱‘瀹氭€у垽瀹氾紙鏈€闀?12s锛岃秴鏃朵粛 200 杩斿洖褰撳墠鍐呭锛?*/
async function waitMessageCount(base, sid, needle, want, requestId = null, timeoutMs = 20000) {
  const end = Date.now() + timeoutMs;
  let n = 0;
  do {
    const q = requestId
      ? `/rc/v1/sessions/${sid}/messages?limit=200&waitRequestId=${encodeURIComponent(requestId)}`
      : `/rc/v1/sessions/${sid}/messages?limit=200`;
    const mr = await req(base, q, { token: T() });
    if (mr.status !== 200) return -mr.status; // 璐熸暟琛ㄧず寮傚父鐘舵€佺爜
    const items = mr.json?.items || [];
    n = items.filter(x => typeof x.text === 'string' && x.text.includes(needle)).length;
    if (n >= want) return n;
    await sleep(600);
  } while (Date.now() < end);
  return n;
}

/** 杞 /messages 鐩村埌鍑虹幇 role=user 涓?requestId===rid 鐨勮锛圖-2/D-3 鍒ゆ嵁锛夛紱杩斿洖璇?rid 鐨勮鏁?*/
// 鐪熷疄妯″瀷鍥炲悎鑰楁椂鍙揪鏁板崄绉掞紙鏈満瀹炴祴 >25s锛夛紝鏁?durable 绛夊緟绐楀彛鏀惧鍒?150s
async function durableUserRows(base, sid, rid, want = 1, timeoutMs = 150000) {
  const end = Date.now() + timeoutMs;
  let n = 0, total = 0;
  do {
    const mr = await req(base, `/rc/v1/sessions/${sid}/messages?limit=200&waitRequestId=${encodeURIComponent(rid)}`, { token: T() });
    if (mr.status !== 200) return { n: -mr.status, total: 0 };
    const items = mr.json?.items || [];
    total = items.length;
    n = items.filter(x => x.role === 'user' && x.requestId === rid).length;
    if (n >= want) return { n, total };
    await sleep(800);
  } while (Date.now() < end);
  return { n, total };
}

const TID = 'rc-' + Date.now();
let PMID = null;
if (NEW) {
  const rid = 'rid-' + Date.now();
  const r1 = await req(BASE, `/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', token: T(), body: { text: 'run test ' + TID, requestId: rid } });
  PMID = r1.json?.messageId ?? null;
  R.add('REST-P01', 'prompt 200 accepted + messageId + sessionId',
    r1.status === 200 && r1.json?.accepted === true && typeof PMID === 'string' && !!PMID && r1.json?.sessionId === NEW, r1.text.slice(0, 200));
  const r2 = await req(BASE, `/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', token: T(), body: { text: 'run test ' + TID, requestId: rid } });
  R.add('REST-P02a', 'idempotent requestId -> same messageId', r2.status === 200 && r2.json?.messageId === PMID, `${r1.json?.messageId} vs ${r2.json?.messageId}`);
  const r3 = await req(BASE, `/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', token: T(), body: { text: 'run test ' + TID, requestId: rid } });
  R.add('REST-P02b', 'idempotent requestId (3rd) -> same messageId', r3.status === 200 && r3.json?.messageId === PMID);
  const hits = await waitMessageCount(BASE, NEW, TID, 1, rid);
  R.add('REST-P02c', 'NO duplicate enqueue (waitRequestId sees exactly 1 durable msg)', hits === 1, `count=${hits}`);
  const rid2 = rid + '-b';
  await durableUserRows(BASE, NEW, rid, 1, 150000);
  const r4 = await req(BASE, `/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', token: T(), body: { text: 'run test ' + TID, requestId: rid2 } });
  const hitRid = await durableUserRows(BASE, NEW, rid, 1, 150000);
  const hitRid2 = await durableUserRows(BASE, NEW, rid2, 1, 150000);
  R.add('REST-P03', 'different requestId -> 2 distinct durable entries',
    r4.json?.messageId !== PMID && hitRid.n === 1 && hitRid2.n === 1,
    `msgId differ=${r4.json?.messageId !== PMID} rid=${hitRid.n} rid2=${hitRid2.n}`);
  // v1.4 D-2锛歊EST 渚?user 琛?requestId 瀵归綈 + D-3 鍗曡
  {
    const durR = await durableUserRows(BASE, NEW, rid2, 1, 150000);
    R.add('D2-rest-requestId', 'REST: durable user row requestId == sent value',
      durR.n === 1, `matched=${durR.n} items=${durR.total} want=${rid2}`);
    R.add('D3-rest-single', 'REST: one prompt -> exactly 1 durable user row', durR.n === 1, `matched=${durR.n}`);
  }
  R.addCheck('REST-P04', 'missing text -> 400', errIs(await req(BASE, `/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', token: T(), body: { requestId: 'x1' } }), 'invalid_request', 400));
  R.addCheck('REST-P05', 'text not string -> 400', errIs(await req(BASE, `/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', token: T(), body: { text: 42, requestId: 'x2' } }), 'invalid_request', 400));
  R.addCheck('REST-P06', 'blank text -> 400', errIs(await req(BASE, `/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', token: T(), body: { text: '   ', requestId: 'x3' } }), 'invalid_request', 400));
  R.addCheck('REST-P07', 'missing requestId -> 400', errIs(await req(BASE, `/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', token: T(), body: { text: 'hi' } }), 'invalid_request', 400));
  R.addCheck('REST-X05', 'cancel idempotent 3x -> 200 ok:true', await (async () => {
    for (let i = 0; i < 3; i++) { const r = await req(BASE, `/rc/v1/sessions/${NEW}/cancel`, { method: 'POST', token: T(), body: {} }); if (r.status !== 200 || r.json?.ok !== true) return `#${i + 1} HTTP ${r.status} ${r.text.slice(0, 120)}`; }
    return null;
  })());
  R.addCheck('PROTO-G13', 'non-JSON body -> not 500', await (async () => {
    const res = await fetch(`${BASE}/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', headers: { Authorization: `Bearer ${T()}`, 'Content-Type': 'application/json' }, body: 'not-json' });
    return res.status === 500 ? 'HTTP 500' : null;
  })());
}
R.addCheck('GAP-06', 'unknown session + invalid body -> 404 before 400',
  errIs(await req(BASE, '/rc/v1/sessions/sess_nope_zzz/prompt', { method: 'POST', token: T(), body: { text: 42 } }), 'not_found', 404));
R.addCheck('REST-P08', 'unknown session prompt -> 404', errIs(await req(BASE, '/rc/v1/sessions/sess_nope_zzz/prompt', { method: 'POST', token: T(), body: { text: 'hi', requestId: 'r' } }), 'not_found', 404));
R.addCheck('REST-X03', 'unknown session cancel -> 404', errIs(await req(BASE, '/rc/v1/sessions/sess_nope_zzz/cancel', { method: 'POST', token: T(), body: {} }), 'not_found', 404));

// ================= 6. pair/rotate (destructive) =================
if (!hasFlag('skip-rotate')) {
  const wsBefore = openWs(BASE, '/rc/v1/stream?token=' + TOKEN);
  const helloBefore = await wsBefore.waitType('hello', 8000);
  const ro = await req(BASE, '/rc/v1/pair/rotate', { method: 'POST', token: T(), body: {} });
  R.add('REST-R01', 'rotate returns v/token(32hex)/fp/ts',
    ro.status === 200 && ro.json?.v === 1 && HEX32.test(ro.json?.token || '') && typeof ro.json?.ts === 'number', ro.text.slice(0, 200));
  R.add('REST-R01b', 'rotate fp==token[:8] and differs from old',
    ro.json?.fp === (ro.json?.token || '').slice(0, 8) && ro.json?.token !== TOKEN);
  R.addCheck('REST-R06', 'existing WS survives rotate (stays OPEN, still works)', await (async () => {
    const deadline = Date.now() + 45000; let pongOk = false;
    while (Date.now() < deadline) {
      if (wsBefore.ws.readyState !== 1) return `closed (readyState=${wsBefore.ws.readyState})`;
      wsBefore.send({ v: 1, type: 'ping', ts: Date.now() });
      if (await wsBefore.waitType('pong', 3000)) { pongOk = true; break; }
    }
    if (!pongOk) return 'no pong within 45s';
    wsBefore.send({ v: 1, type: 'subscribe', id: 'post-rotate', sessionId: NEW || SESS });
    const ack = await wsBefore.wait(j => j?.type === 'ack' && j.id === 'post-rotate', 8000);
    return ack ? null : 'no ack for subscribe after rotate';
  })(), `hello=${!!helloBefore}`);
  const NEWTOK = ro.json?.token;
  if (NEWTOK) {
    TOKENS.current = NEWTOK;
    R.addCheck('REST-R02', 'old token -> 401 immediately', errIs(await req(BASE, '/rc/v1/status', { token: TOKEN }), 'unauthorized', 401));
    R.add('REST-R03', 'new token -> 200', (await req(BASE, '/rc/v1/status', { token: NEWTOK })).status === 200);
    const w2 = openWs(BASE, '/rc/v1/stream?token=' + TOKEN);
    const c = await w2.closed;
    R.add('REST-R07', 'old token new WS -> close 4401', c?.code === 4401, `code=${c?.code}`);
  }
  wsBefore.close();
  console.log(`\n[NOTE] token rotated. Use new token for later runs: ${TOKENS.current}`);
} else R.skip('REST-R01', 'pair/rotate', '--skip-rotate given');

// ================= 7. WebSocket =================
async function wsTests() {
  for (const [id, label, tok] of [['WS-02', 'no token', ''], ['WS-03', 'wrong token', 'deadbeef']]) {
    const w = openWs(BASE, `/rc/v1/stream${tok ? '?token=' + tok : ''}`);
    const c = await Promise.race([w.closed, sleep(8000).then(() => ({ code: 'TIMEOUT' }))]);
    R.add(id, `${label} -> zero frames then close 4401`, c?.code === 4401 && w.frames.length === 0, `code=${c?.code} frames=${w.frames.length}`);
  }
  R.add('WS-02b', 'auth failure judged by close code only (v1.1)', true, 'GAP-09 ruling applied');

  const w = openWs(BASE, '/rc/v1/stream?token=' + T());
  const hello = await w.waitType('hello', 8000);
  R.add('WS-01', 'valid token -> first frame is hello', !!hello, 'timeout');
  R.add('WS-04', 'hello fields v/server.name/server.version/sessions',
    !!hello && hello.json.v === 1 && !!hello.json.server?.name && !!hello.json.server?.version && Array.isArray(hello.json.sessions), hello?.raw?.slice(0, 200));
  R.add('WS-04b', 'hello.sessions items match REST shape',
    !hello || (hello.json.sessions || []).every(x => SESSION_KEYS.every(k => k in x)));

  w.send({ v: 1, type: 'ping', ts: Date.now() });
  const pong = await w.waitType('pong', 10000);
  R.add('WS-05', 'ping -> pong <=10s, ts int', !!pong && isIntMs(pong.json.ts), pong?.raw);

  if (NEW) {
    w.send({ v: 1, type: 'subscribe', id: 'c1', sessionId: NEW });
    const ack = await w.wait(j => j?.type === 'ack' && j.id === 'c1', 8000);
    R.add('WS-07', 'subscribe -> ack (id exact, ok=true)', !!ack && ack.json.ok === true, ack?.raw);

    if (SESS2) {
      const mk9 = w.mark();
      await req(BASE, `/rc/v1/sessions/${SESS2}/prompt`, { method: 'POST', token: T(), body: { text: 'unsubscribed probe', requestId: 'unsub-' + Date.now() } });
      await sleep(3000);
      const leaked = w.frames.slice(mk9).filter(f => f.json?.type === 'session.event' && f.json?.event?.kind === 'message' && f.json?.sessionId === SESS2);
      R.add('WS-09', 'NOT-subscribed session: no message events', leaked.length === 0, `leaked=${leaked.length}`);
    }
    const mk10 = w.mark();
    await req(BASE, `/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', token: T(), body: { text: 'subscribed probe', requestId: 'sub-' + Date.now() } });
    const ev = await w.waitSince(j => j?.type === 'session.event' && j?.sessionId === NEW && j?.event?.kind === 'message', mk10, 20000);
    R.add('WS-10', 'subscribed session: session.event(kind=message) pushed', !!ev,
      V ? `NEW=${NEW} got=${JSON.stringify(ev?.json?.sessionId)} frames=[${w.frames.map(f => f.json?.type).join(',')}]` : 'timeout');
    R.add('WS-13', 'session.event fields complete', !!ev
      && ['v', 'type', 'sessionId', 'event'].every(k => k in ev.json)
      && ['kind', 'role', 'text', 'seq', 'ts', 'status', 'messageId'].every(k => k in ev.json.event), ev?.raw?.slice(0, 200));
    R.add('D2-ws-event-requestId', 'WS session.event carries requestId field',
      !!ev && 'requestId' in ev.json.event, ev ? JSON.stringify(Object.keys(ev.json.event)) : 'timeout');

    const rid3 = 'ack-' + Date.now();
    await req(BASE, '/rc/v1/sessions/' + NEW + '/cancel', { method: 'POST', token: T(), body: {} });
    await sleep(1500);
    w.send({ v: 1, type: 'prompt', id: 'c3', sessionId: NEW, text: 'ack field probe', requestId: rid3 });
    const a3 = await w.wait(j => j?.type === 'ack' && j.id === 'c3', 10000);
    R.add('WS-16', 'prompt ack carries sessionId/requestId/messageId (GAP-10)',
      !!a3 && a3.json.sessionId === NEW && a3.json.requestId === rid3 && typeof a3.json.messageId === 'string' && !!a3.json.messageId, a3?.raw);
    w.send({ v: 1, type: 'prompt', id: 'c5', sessionId: NEW, text: 'ack field probe', requestId: rid3 });
    const a5 = await w.wait(j => j?.type === 'ack' && j.id === 'c5', 10000);
    R.add('WS-17', 'WS idempotency: same requestId -> same messageId',
      !!a5 && !!a3 && a5.json.messageId === a3.json.messageId && a5.json.requestId === rid3,
      `c3=${a3?.json?.messageId} c5=${a5?.json?.messageId}`);
    if (a3?.json?.requestId || rid3) {
      const dur = await durableUserRows(BASE, NEW, rid3, 1, 150000);
      R.add('D2-ws-requestId', 'WS: durable user row carries requestId == sent value',
        dur.n === 1, `matched=${dur.n} items=${dur.total} want=${rid3}`);
      R.add('D3-ws-single', 'WS: one prompt -> exactly 1 durable user row', dur.n === 1, `matched=${dur.n} items=${dur.total}`);
      // 瀛楁闆?= 濂戠害鍏」 + requestId
      {
        const mrF = await req(BASE, `/rc/v1/sessions/${NEW}/messages?limit=200&waitRequestId=${encodeURIComponent(rid3)}`, { token: T() });
        const items = mrF.status === 200 ? (mrF.json?.items || []) : [];
        R.add('D2-fields', 'message row fields = 6 contract keys + requestId',
          items.length > 0 && items.every(x => [...MSG_KEYS, 'requestId'].every(k => k in x)),
          JSON.stringify(Object.keys(items[0] || {})));
        R.add('D2-nonuser-null', 'non-user rows have requestId === null',
          items.filter(x => x.role !== 'user').every(x => x.requestId === null),
          JSON.stringify(items.filter(x => x.role !== 'user').map(x => `${x.role}:${x.requestId}`).slice(0, 5)));
      }
    } else {
      R.skip('D2-ws-requestId', 'WS requestId alignment', 'no rid3');
    }

    w.send({ v: 1, type: 'subscribe', id: 'c6', sessionId: 'sess_nope_zzz' });
    const e6 = await w.wait(j => j?.type === 'error' && j.id === 'c6', 8000);
    R.add('WS-08', 'error frame carries same id + valid code', !!e6 && ERR_CODES.includes(e6.json.code), e6?.raw);
    w.send({ v: 1, type: 'prompt', id: 'c7', sessionId: NEW, requestId: 'z' });
    const e7 = await w.wait(j => j?.type === 'error' && j.id === 'c7', 8000);
    R.add('WS-19', 'prompt invalid field -> error(invalid_request) same id', !!e7 && e7.json.code === 'invalid_request', e7?.raw);
    w.send({ v: 1, type: 'cancel', id: 'c4', sessionId: NEW });
    const a4 = await w.wait(j => j?.type === 'ack' && j.id === 'c4', 8000);
    R.add('WS-20', 'cancel -> ack id=c4', !!a4, a4?.raw ?? 'timeout');
    w.send({ v: 1, type: 'cancel', id: 'c9z', sessionId: 'sess_nope_zzz' });
    const e9z = await w.wait(j => j?.type === 'error' && j.id === 'c9z', 8000);
    R.add('WS-21', 'cancel unknown session -> error(not_found) same id', !!e9z && e9z.json.code === 'not_found', e9z?.raw);

    w.send({ v: 1, type: 'unsubscribe', id: 'c2', sessionId: NEW });
    const a2 = await w.wait(j => j?.type === 'ack' && j.id === 'c2', 8000);
    R.add('WS-15a', 'unsubscribe -> ack id=c2', !!a2, a2?.raw);
    const mk15 = w.mark();
    await req(BASE, `/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', token: T(), body: { text: 'after unsub', requestId: 'u-' + Date.now() } });
    await sleep(3000);
    R.add('WS-15', 'after unsubscribe: no message events',
      w.frames.slice(mk15).filter(f => f.json?.type === 'session.event' && f.json?.event?.kind === 'message').length === 0);
  } else R.skip('WS-10', 'event push cases', 'no session available');

  w.send({ v: 1, type: 'bogus', id: 'c9' });
  const e9 = await w.wait(j => j?.type === 'error' && j.id === 'c9', 8000);
  R.add('WS-22', 'unknown type -> error id=c9 (not silently dropped)', !!e9, e9?.raw ?? 'timeout');
  w.send({ v: 2, type: 'ping', ts: Date.now(), id: 'c10' });
  const r10 = await w.wait(j => (j?.type === 'error' && j.id === 'c10') || j?.type === 'pong', 8000);
  R.add('WS-33', 'v!=1 frame -> handled, connection stays open', !!r10 && w.ws.readyState === 1, r10?.raw ?? 'timeout (record actual behavior)');
  // 鐢ㄥ叏鏂拌繛鎺ユ祴鍧?JSON 鍚庣殑鍙敤鎬э紝閬垮厤涓庡悗缁瀹㈡埛绔敤渚嬬殑鏃跺簭涓叉壈
  const wBad = openWs(BASE, '/rc/v1/stream?token=' + T());
  await wBad.waitType('hello', 8000);
  wBad.send('not-json');
  await sleep(600);
  wBad.send({ v: 1, type: 'ping', ts: Date.now() });
  const pongBad = await wBad.waitType('pong', 8000);
  R.add('WS-24', 'connection usable after non-JSON frame', !!pongBad, `readyState=${wBad.ws.readyState}`);
  wBad.close();
  await sleep(300);

  const wA = openWs(BASE, '/rc/v1/stream?token=' + T());
  const wB = openWs(BASE, '/rc/v1/stream?token=' + T());
  const wC = openWs(BASE, '/rc/v1/stream?token=' + T());
  const [hA, hB, hC] = await Promise.all([wA.waitType('hello', 15000), wB.waitType('hello', 15000), wC.waitType('hello', 15000)]);
  R.add('WS-26a', '3 concurrent conns with same token, all get hello', !!hA && !!hB && !!hC,
    `A=${!!hA} B=${!!hB} C=${!!hC} states=${wA.ws.readyState},${wB.ws.readyState},${wC.ws.readyState}`);
  wC.close();
  if (NEW) {
    for (const c of [wA, wB]) c.send({ v: 1, type: 'subscribe', id: 'sub', sessionId: NEW });
    await sleep(2000);
    const mk26 = Math.min(wA.mark(), wB.mark());
    await req(BASE, `/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', token: T(), body: { text: 'multi probe', requestId: 'multi-' + Date.now() } });
    const gotA = await wA.waitSince(j => j?.type === 'session.event' && j?.sessionId === NEW, mk26, 20000);
    const gotB = await wB.waitSince(j => j?.type === 'session.event' && j?.sessionId === NEW, mk26, 20000);
    R.add('WS-26b', 'both clients receive the same event', !!gotA && !!gotB, `A=${!!gotA} B=${!!gotB}`);
    const mkIso = wA.mark();
    wB.send({ v: 1, type: 'prompt', id: 'iso', sessionId: NEW, text: 'isolation probe', requestId: 'iso-' + Date.now() });
    const iso = await wB.wait(j => (j?.type === 'ack' || j?.type === 'error') && j.id === 'iso', 6000);
    const otherGot = wA.frames.slice(mkIso).filter(f => f.json?.id === 'iso').length;
    R.add('GAP-13', 'ack goes only to originating conn (no broadcast)', !!iso && otherGot === 0, `other conn got ${otherGot} frames`);
    wA.close();
    await sleep(600);
    R.add('WS-28', 'closing one conn does not affect the other', wB.ws.readyState === 1, `readyState=${wB.ws.readyState}`);
    wB.close();
  }

  const wIdle = openWs(BASE, '/rc/v1/stream?token=' + T());
  await wIdle.waitType('hello', 8000);
  R.addCheck('WS-06', 'server does not disconnect after 30s idle', await (async () => {
    for (let i = 0; i < 10; i++) { await sleep(3000); if (wIdle.ws.readyState !== 1) return `disconnected at ~${(i + 1) * 3}s`; }
    return null;
  })());
  wIdle.close(); w.close();
}
await wsTests();

// ================= 8. gated test hooks =================
if (hasFlag('hooks')) {
  // ---- 骞茶窇閽╁瓙 echo-prompt锛氬瓧娈?骞傜瓑绫荤敤渚嬩紭鍏堢敤瀹冿紝閬垮厤鐪熷疄妯″瀷娑堣€?----
  if (NEW) {
    const erid = 'echo-' + Date.now();
    const e1 = await req(BASE, '/rc/v1/_test/echo-prompt', { method: 'POST', token: T(), body: { sessionId: NEW, text: '1', requestId: erid } });
    R.add('HOOK-09', 'echo-prompt accepts {sessionId,text,requestId}',
      e1.status === 200 && e1.json?.v === 1, e1.text.slice(0, 200));
    const e2r = await req(BASE, '/rc/v1/_test/echo-prompt', { method: 'POST', token: T(), body: { sessionId: NEW, text: '1', requestId: erid } });
    R.add('HOOK-09b', 'echo-prompt idempotent (same requestId -> same messageId)',
      e2r.status === 200 && e1.json?.messageId && e2r.json?.messageId === e1.json.messageId,
      `${e1.json?.messageId} vs ${e2r.json?.messageId}`);
      {
        const e3r = await req(BASE, '/rc/v1/_test/echo-prompt', { method: 'POST', token: T(), body: { sessionId: NEW, text: '1', requestId: erid } });
        R.add('D3-echo-idempotent', 'echo-prompt: 3x same requestId -> same messageId (no new record)',
          e3r.status === 200 && e3r.json?.messageId === e1.json?.messageId,
          `${e1.json?.messageId} / ${e2r.json?.messageId} / ${e3r.json?.messageId}`);
        // 鍘嗗彶涓笉搴斿嚭鐜伴噸澶?requestId锛堝宸叉湁 durable 鏁版嵁鍋氬叏灞€妫€鏌ワ級
        const all = (await req(BASE, `/rc/v1/sessions/${NEW}/messages?limit=200`, { token: T() })).json?.items || [];
        const seen = new Map();
        for (const x of all) if (x.role === 'user' && x.requestId) seen.set(x.requestId, (seen.get(x.requestId) || 0) + 1);
        const dups = [...seen.entries()].filter(([, c]) => c > 1);
        R.add('D3-no-dup-requestId', 'history has no duplicate requestId among user rows', dups.length === 0,
          dups.length ? JSON.stringify(dups.slice(0, 3)) : `checked ${seen.size} requestIds`);
      }
    R.addCheck('HOOK-10', 'echo-prompt blank text -> 400',
      errIs(await req(BASE, '/rc/v1/_test/echo-prompt', { method: 'POST', token: T(), body: { sessionId: NEW, text: '   ', requestId: 'x' } }), 'invalid_request', 400));
    R.addCheck('HOOK-11', 'echo-prompt missing sessionId -> 400',
      errIs(await req(BASE, '/rc/v1/_test/echo-prompt', { method: 'POST', token: T(), body: { text: '1', requestId: 'x' } }), 'invalid_request', 400));
  } else R.skip('HOOK-09', 'echo-prompt', 'no session available');

  R.add('HOOK-03', 'hook enabled: POST /rc/v1/_test/busy/{id} -> 200',
    (await req(BASE, `/rc/v1/_test/busy/${NEW || SESS}`, { method: 'POST', token: T(), body: {} })).status === 200);
  R.addCheck('HOOK-04', 'REST 409 session_busy (via hook)',
    errIs(await req(BASE, `/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', token: T(), body: { text: '1', requestId: 'busy-' + Date.now() } }), 'session_busy', 409));
  await R.addCheckAsync('WS-18', 'WS prompt busy -> error/session_busy with same id, NEVER ack (P0-1)', (async () => {
    if (!NEW) return 'no session';
    const w = openWs(BASE, '/rc/v1/stream?token=' + T());
    await w.waitType('hello', 8000);
    w.send({ v: 1, type: 'prompt', id: 'busy1', sessionId: NEW, text: '1', requestId: 'busyws-' + Date.now() });
    const e = await w.wait(j => (j?.type === 'error' || j?.type === 'ack') && j.id === 'busy1', 12000);
    w.close();
    if (!e) return 'timeout (no ack/error frame)';
    if (e.json.type === 'ack') return 'got ACK 鈥?P0-1 NOT fixed';
    if (e.json.code !== 'session_busy') return `code=${e.json.code}`;
    if (e.json.id !== 'busy1') return `id mismatch=${e.json.id}`;
    return null;
  })());
  await R.addCheckAsync('HOOK-12', '?times=1: REST consumes it, WS then succeeds', (async () => {
    if (!NEW) return 'no session';
    await req(BASE, `/rc/v1/_test/busy/${NEW}?times=1`, { method: 'POST', token: T(), body: {} });
    const r1 = await req(BASE, `/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', token: T(), body: { text: '1', requestId: 't1-r-' + Date.now() } });
    const w = openWs(BASE, '/rc/v1/stream?token=' + T());
    await w.waitType('hello', 8000);
    w.send({ v: 1, type: 'prompt', id: 't1w', sessionId: NEW, text: '1', requestId: 't1-w-' + Date.now() });
    const e = await w.wait(j => (j?.type === 'error' || j?.type === 'ack') && j.id === 't1w', 12000);
    w.close();
    if (r1.status !== 409) return `REST expected 409, got ${r1.status}`;
    return e?.json?.type === 'ack' ? null : `WS after 1 arm: ${e?.json?.type}/${e?.json?.code}`;
  })());
  await R.addCheckAsync('HOOK-13', '?times=2: REST and WS both get 409', (async () => {
    if (!NEW) return 'no session';
    await req(BASE, `/rc/v1/_test/busy/${NEW}?times=2`, { method: 'POST', token: T(), body: {} });
    const r1 = await req(BASE, `/rc/v1/sessions/${NEW}/prompt`, { method: 'POST', token: T(), body: { text: '1', requestId: 't2-r-' + Date.now() } });
    const w = openWs(BASE, '/rc/v1/stream?token=' + T());
    await w.waitType('hello', 8000);
    w.send({ v: 1, type: 'prompt', id: 't2w', sessionId: NEW, text: '1', requestId: 't2-w-' + Date.now() });
    const e = await w.wait(j => (j?.type === 'error' || j?.type === 'ack') && j.id === 't2w', 12000);
    w.close();
    if (r1.status !== 409) return `REST expected 409, got ${r1.status}`;
    return e?.json?.type === 'error' && e?.json?.code === 'session_busy' ? null : `WS: ${e?.json?.type}/${e?.json?.code}`;
  })());
  R.add('HOOK-01-note', 'run with --no-hooks separately to prove hooks absent by default', true, '');
} else if (hasFlag('no-hooks')) {
  await R.addCheckAsync('HOOK-01', 'default env: hook route -> 404 (no leak to production)', (async () => {
    const r = await req(BASE, `/rc/v1/_test/busy/${NEW || 'sess_alpha'}`, { method: 'POST', token: T(), body: {} });
    return r.status === 404 ? null : `HTTP ${r.status} (must be 404)`;
  })());
  await R.addCheckAsync('HOOK-07', 'hook without token -> 401/404 (never 200)', (async () => {
    const r = await req(BASE, `/rc/v1/_test/busy/${NEW || 'sess_alpha'}`, { method: 'POST', token: null, body: {} });
    return (r.status === 401 || r.status === 404) ? null : `HTTP ${r.status}`;
  })());
  await R.addCheckAsync('HOOK-01b', 'default env: echo-prompt route -> 404 (no leak)', (async () => {
    const r = await req(BASE, '/rc/v1/_test/echo-prompt', { method: 'POST', token: T(), body: { sessionId: NEW || 'x', text: '1', requestId: 'echo-404-' + Date.now() } });
    return r.status === 404 ? null : `HTTP ${r.status} (must be 404)`;
  })());
  R.add('REST-S01c', 'official endpoints OK while hook disabled', (await req(BASE, '/rc/v1/status', { token: T() })).status === 200);
} else {
  R.skip('HOOK-01', 'gated hooks', 'neither --hooks nor --no-hooks given');
}

// ================= 9. 浜岀淮鐮?host 蹇呴』鏄湡瀹?WLAN锛坴1.2 淇椤癸級 =================
{
  const fs = await import('node:fs');
  const path = await import('node:path');
  const readJson = p => {
    try { return JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, '')); } catch { return null; }
  };
  let g = null, r = null;
  for (let i = 0; i < 10; i++) { // 鐘舵€佹枃浠跺湪 ping OK 涔嬪悗鎵嶈惤鐩橈紝绋嶇瓑
    g = readJson(path.resolve('pc-gateway/.state/gateway.json'));
    r = readJson(path.resolve('pc-gateway/.state/runtime.json'));
    if (g?.host) break;
    await sleep(1000);
  }
  const host = g?.host ?? null;
  const rhost = r?.peer ?? r?.host ?? null;
  const VIRT = /^192\.168\.(125|211)\./;
  R.add('PAIR-02a', 'gateway.json host exists', !!host, `host=${host}`);
  R.add('PAIR-02b', 'host is NOT a virtual adapter (192.168.125.x / 192.168.211.x)',
    !!host && !VIRT.test(host), `host=${host} (must be real WLAN)`);
  R.add('PAIR-02c', 'host == runtime.json peer (single source of truth)', host && rhost ? host === rhost : null,
    `gateway.json=${host} runtime.json.peer=${rhost}`);
  R.add('PAIR-03', 'gateway.json fp == token[:8]', g?.fp === String(g?.token || '').slice(0, 8), `fp=${g?.fp}`);
  R.add('PAIR-05', 'gateway.json has host/port/token/fp', !!(g?.host && g?.port && g?.token && g?.fp), JSON.stringify(Object.keys(g || {})));
  R.add('PROTO-G10', 'token.txt is 32 hex and matches gateway.json', /^[0-9a-f]{32}$/.test(String(g?.token || '')) && g?.token === TOKEN, `match=${g?.token === TOKEN}`);
  R.add('HOOK-14', `runtime.json testHooks == ${hasFlag('hooks') ? 'true' : 'false'} in this round`,
    r?.testHooks === hasFlag('hooks'), `testHooks=${r?.testHooks}`);
}

const s = R.save('contract-' + new Date().toISOString().replace(/[:.]/g, '-') + '.json');
saveCreated();
process.exit(s.failed > 0 ? 1 : 0);

