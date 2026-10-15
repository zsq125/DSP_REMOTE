// tests/lib/mock-gateway.mjs — 仅用于**自测测试脚本本身**的假网关。
// 绝不用于验收判定：验收必须指向真实网关（DSH_RC_TEST_HOOKS 由真网关提供）。
// 用法：node tests/lib/mock-gateway.mjs --port 8799 [--hooks] [--hooks-off]
import http from 'node:http';
import crypto from 'node:crypto';
import { arg, hasFlag } from './harness.mjs';
const require = (await import('node:module')).createRequire(import.meta.url);

const PORT = Number(arg('port', '8799'));
let TOKEN = 'a'.repeat(32);
const V1 = { v: 1 };
const now = () => Date.now();
const SESSIONS = [
  { id: 'sess_alpha', title: '修复登录超时', status: 'running', createdAt: now() - 90000, updatedAt: now(), cwd: 'F:\\project\\x', messageCount: 2, lastMessagePreview: '跑一下测试', agentAvailable: true },
  { id: 'sess_beta', title: '写文档', status: 'idle', createdAt: now() - 80000, updatedAt: now(), cwd: 'F:\\project\\y', messageCount: 1, lastMessagePreview: '你好', agentAvailable: true },
  { id: 'sess_gamma', title: '', status: 'unknown', createdAt: now() - 70000, updatedAt: now(), cwd: '', messageCount: 0, lastMessagePreview: '', agentAvailable: false },
];
// seq 单调的消息池
let SEQ = 100;
const msgs = new Map();
function addMsg(sid, role, text, status = 'final') {
  const m = { seq: ++SEQ, role, text, ts: now(), status, messageId: 'm' + SEQ };
  (msgs.get(sid) ?? msgs.set(sid, []).get(sid)).push(m);
  return m;
}
msgs.set('sess_alpha', []);
msgs.set('sess_beta', []);
msgs.set('sess_gamma', []);
addMsg('sess_alpha', 'user', '跑一下测试');
addMsg('sess_alpha', 'assistant', '[tool] shell', 'final');
addMsg('sess_beta', 'user', '你好');
const busy = new Set();
const byRequestId = new Map();
const HOOKS = hasFlag('hooks');

const openSockets = [];
const broadcast = (sid, ev) => { for (const s of openSockets) if (s.subs.has(sid)) { try { s.sock.write(enc(JSON.stringify({ v: 1, ...ev }))); } catch { } } };

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const enc = (str) => { const p = Buffer.from(str, 'utf8'); const n = p.length; let h; if (n < 126) h = Buffer.from([0x81, n]); else if (n < 65536) { h = Buffer.alloc(4); h[0] = 0x81; h[1] = 126; h.writeUInt16BE(n, 2); } else { h = Buffer.alloc(10); h[0] = 0x81; h[1] = 127; h.writeBigUInt64BE(BigInt(n), 2); } return Buffer.concat([h, p]); };

const send = (res, code, obj) => {
  const b = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(b);
};
const ok = (res, o = {}) => send(res, 200, { ...V1, ...o });
const err = (res, code, msg, http = code) => send(res, http, { ...V1, error: { code, message: msg } });
const authed = req => req.headers.authorization === `Bearer ${TOKEN}`;

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const p = u.pathname;
  const chunks = []; for await (const c of req) chunks.push(c);
  const raw = Buffer.concat(chunks).toString('utf8');
  let body = null; try { body = raw ? JSON.parse(raw) : null; } catch { }
  const q = u.searchParams;

  if (p === '/rc/v1/ping') return ok(res, { name: 'MOCKPC', version: '1.0.0', pairRequired: true, ts: now() });

  if (HOOKS && p.startsWith('/rc/v1/_test/busy/')) {
    const id = p.split('/').pop();
    if (!authed(req)) return err(res, 'unauthorized', 'bad token', 401);
    busy.add(id); return ok(res, { ok: true });
  }
  if (p === '/rc/v1/pair/rotate') {
    if (!authed(req)) return err(res, 'unauthorized', 'bad token', 401);
    const t = require('node:crypto').randomBytes(16).toString('hex');
    TOKEN = t; // 旧 token 立即失效；已建立的 WS **不断开**
    return ok(res, { token: t, fp: t.slice(0, 8), ts: now() });
  }

  if (!authed(req)) return err(res, 'unauthorized', 'bad token', 401);
  const a = req.headers.authorization;
  if (!a.startsWith('Bearer ')) return err(res, 'unauthorized', 'bad token', 401);

  if (p === '/rc/v1/status') {
    if (hasFlag('dsh-down')) return ok(res, { dsh: { ok: false }, sessions: { total: 3, running: 0 }, uptimeMs: 1234, ts: now() });
    if (hasFlag('dsh-down-status503')) return err(res, 'dsh_unavailable', 'dsh down', 503);
    return ok(res, { dsh: { ok: true }, sessions: { total: 3, running: 1 }, uptimeMs: 1234, ts: now() });
  }
  if (p === '/rc/v1/sessions' && req.method === 'GET') {
    const lim = q.get('limit');
    if (lim !== null && (!/^\d+$/.test(lim) || Number(lim) < 1 || Number(lim) > 200)) return err(res, 'invalid_request', 'limit out of range', 400);
    let items = SESSIONS;
    const cur = q.get('cursor');
    if (cur !== null) { if (cur === '@@bad@@') return err(res, 'invalid_request', 'bad cursor', 400); const i = items.findIndex(x => x.id === cur); items = i >= 0 ? items.slice(i + 1) : items; }
    const n = lim === null ? items.length : Math.min(Number(lim), items.length);
    const page = items.slice(0, n);
    const rest = items.slice(n);
    return ok(res, { items: page, nextCursor: rest.length ? rest[0].id : null });
  }
  if (p === '/rc/v1/sessions' && req.method === 'POST') {
    if (body && body.cwd !== undefined && body.cwd !== null && typeof body.cwd !== 'string') return err(res, 'invalid_request', 'cwd must be string|null', 400);
    if (body && body.title !== undefined && body.title !== null && typeof body.title !== 'string') return err(res, 'invalid_request', 'title must be string|null', 400);
    const id = 'sess_new_' + Date.now();
    SESSIONS.push({ id, title: body?.title ?? '', status: 'idle', createdAt: now(), updatedAt: now(), cwd: body?.cwd ?? '', messageCount: 0, lastMessagePreview: '', agentAvailable: true });
    msgs.set(id, []);
    return ok(res, { id });
  }
  let m;
  if ((m = /^\/rc\/v1\/sessions\/([^/]+)\/messages$/.exec(p))) {
    const id = decodeURIComponent(m[1]);
    if (!SESSIONS.some(s => s.id === id)) return err(res, 'not_found', 'no session', 404);
    const lim = q.get('limit');
    if (lim !== null && (!/^\d+$/.test(lim) || Number(lim) < 1 || Number(lim) > 200)) return err(res, 'invalid_request', 'limit', 400);
    const before = q.get('before');
    if (before !== null && !/^\d+$/.test(before)) return err(res, 'invalid_request', 'before', 400);
    let all = (msgs.get(id) || []).slice().sort((a, b) => a.seq - b.seq);
    if (before !== null) all = all.filter(x => x.seq < Number(before));
    const n = lim === null ? all.length : Math.min(Number(lim), all.length);
    const page = all.slice(Math.max(0, all.length - n));
    const older = all.slice(0, Math.max(0, all.length - n));
    return ok(res, { items: page, nextCursor: older.length ? older[older.length - 1].seq : null });
  }
  if ((m = /^\/rc\/v1\/sessions\/([^/]+)\/prompt$/.exec(p))) {
    const id = decodeURIComponent(m[1]);
    // GAP-06：先 404 后 400
    if (!SESSIONS.some(s => s.id === id)) return err(res, 'not_found', 'no session', 404);
    if (!body || typeof body.text !== 'string' || body.text.trim() === '') return err(res, 'invalid_request', 'text required', 400);
    if (typeof body.requestId !== 'string' || !body.requestId) return err(res, 'invalid_request', 'requestId required', 400);
    if (busy.has(id)) return err(res, 'session_busy', 'locked', 409);
    const k = id + '|' + body.requestId;
    if (byRequestId.has(k)) return ok(res, { accepted: true, messageId: byRequestId.get(k), sessionId: id });
    const msg = addMsg(id, 'user', body.text);
    byRequestId.set(k, msg.messageId);
    broadcast(id, { type: 'session.event', sessionId: id, event: { kind: 'message', role: 'user', text: body.text, seq: msg.seq, ts: now(), status: 'final', messageId: msg.messageId } });
    return ok(res, { accepted: true, messageId: msg.messageId, sessionId: id });
  }
  if ((m = /^\/rc\/v1\/sessions\/([^/]+)\/cancel$/.exec(p))) {
    const id = decodeURIComponent(m[1]);
    if (!SESSIONS.some(s => s.id === id)) return err(res, 'not_found', 'no session', 404);
    return ok(res, { ok: true }); // GAP-08 幂等
  }
  if (p === '/rc/v1/nope') return err(res, 'not_found', 'no route', 404);
  send(res, 404, { ...V1, error: { code: 'not_found', message: 'no route' } });
});

// ---- WebSocket（自实现最小 server 端，无外部依赖）----
function decodeFrames(buf) { // → { msgs:[], rest:Buffer }
  const msgs = []; let o = 0;
  for (; ;) {
    if (buf.length - o < 2) break;
    const op = buf[o] & 0x0f; let len = buf[o + 1] & 0x7f; let p = o + 2;
    if (len === 126) { if (buf.length < p + 2) break; len = buf.readUInt16BE(p); p += 2; }
    else if (len === 127) { if (buf.length < p + 8) break; len = Number(buf.readBigUInt64BE(p)); p += 8; }
    const masked = (buf[o + 1] & 0x80) !== 0;
    let key = null;
    if (masked) { if (buf.length < p + 4) break; key = buf.subarray(p, p + 4); p += 4; }
    if (buf.length < p + len) break;
    let pl = Buffer.from(buf.subarray(p, p + len));
    if (key) for (let i = 0; i < pl.length; i++) pl[i] ^= key[i % 4];
    if (op === 1) msgs.push(pl.toString('utf8'));
    else if (op === 8) msgs.push(null); // close
    o = p + len;
  }
  return { msgs, rest: buf.subarray(o) };
}

const close4401 = (sock) => {
  try {
    const p = Buffer.alloc(2); p.writeUInt16BE(4401, 0);
    sock.write(Buffer.concat([Buffer.from([0x88, 0x02]), p]));
    setTimeout(() => sock.destroy(), 60);
  } catch { try { sock.destroy(); } catch { } }
};

const openSockets2 = [];
server.on('upgrade', (req, sock, head) => {
  const u = new URL(req.url, 'http://x');
  if (u.pathname !== '/rc/v1/stream') { sock.destroy(); return; }
  const tok = u.searchParams.get('token');
  const key = req.headers['sec-websocket-key'] || '';
  const accept = crypto.createHash('sha1').update(key + GUID).digest('base64');
  sock.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + accept + '\r\n\r\n');
  // GAP-09：upgrade 成功后不发任何帧，立即 close(4401)
  if (tok !== TOKEN) return close4401(sock);

  const entry = { sock, subs: new Set() };
  openSockets.push(entry);
  const send = o => { try { sock.write(enc(JSON.stringify({ ...V1, ...o }))); } catch { } };
  const sendRaw = o => { try { sock.write(enc(JSON.stringify({ v: 1, ...o }))); } catch { } };
  sendRaw({ type: 'hello', server: { name: 'MOCKPC', version: '1.0.0' }, sessions: SESSIONS });

  let buf = head && head.length ? Buffer.from(head) : Buffer.alloc(0);
  sock.on('data', chunk => {
    buf = Buffer.concat([buf, chunk]);
    const { msgs: decoded, rest } = decodeFrames(buf); buf = rest;
    for (const raw of decoded) {
      if (raw === null) { try { sock.destroy(); } catch { } return; }
      let j; try { j = JSON.parse(raw); } catch { return sendRaw({ type: 'error', id: null, code: 'invalid_request', message: 'bad json' }); }
      if (j.v !== 1) return sendRaw({ type: 'error', id: j.id ?? null, code: 'invalid_request', message: 'bad v' });
      const id = j.id;
      if (j.type === 'ping') return sendRaw({ type: 'pong', ts: now() });
      const known = ['subscribe', 'unsubscribe', 'prompt', 'cancel'];
      if (!known.includes(j.type)) return sendRaw({ type: 'error', id: id ?? null, code: 'invalid_request', message: 'unknown type' });
      if (!SESSIONS.some(s => s.id === j.sessionId)) return sendRaw({ type: 'error', id: id ?? null, code: 'not_found', message: 'no session' });
      if (j.type === 'prompt') {
        if (typeof j.text !== 'string' || !j.text.trim()) return sendRaw({ type: 'error', id, code: 'invalid_request', message: 'text' });
        if (!j.requestId) return sendRaw({ type: 'error', id, code: 'invalid_request', message: 'requestId' });
        if (busy.has(j.sessionId)) return sendRaw({ type: 'error', id, code: 'session_busy', message: 'locked' });
        const k = j.sessionId + '|' + j.requestId;
        let mid = byRequestId.get(k);
        let m2;
        if (!mid) { m2 = addMsg(j.sessionId, 'user', j.text); mid = m2.messageId; byRequestId.set(k, mid); }
        sendRaw({ type: 'ack', id, ok: true, sessionId: j.sessionId, requestId: j.requestId, messageId: mid });
        const last = (msgs.get(j.sessionId) || []).slice(-1)[0];
        broadcast(j.sessionId, { type: 'session.event', sessionId: j.sessionId, event: { kind: 'message', role: 'user', text: j.text, seq: last?.seq, ts: now(), status: 'final', messageId: mid } });
        return;
      }
      if (j.type === 'subscribe') entry.subs.add(j.sessionId); else entry.subs.delete(j.sessionId);
      sendRaw({ type: 'ack', id, ok: true });
    }
  });
  sock.on('close', () => { const i = openSockets.indexOf(entry); if (i >= 0) openSockets.splice(i, 1); });
  sock.on('error', () => { });
});


server.listen(PORT, '127.0.0.1', () => console.log(`MOCK gateway on http://127.0.0.1:${PORT} hooks=${HOOKS} token=${TOKEN}`));
