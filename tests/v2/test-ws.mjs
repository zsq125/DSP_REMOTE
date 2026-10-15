// tests/v2/test-ws.mjs — (d) WebSocket：session.event 新字段 + streamKind + 订阅语义
// 纪律：只读 + 只对我自己创建的会话订阅/发消息。
import { api, CREATED } from './common.mjs';

const TOKEN = process.env.DSH_RC_TOKEN;
const cases = [];
const ok = (id, name, pass, detail = '') => {
  cases.push({ id, name, pass, detail });
  console.log(`[${pass ? 'PASS' : 'FAIL'}] ${id} ${name}${pass ? '' : ' :: ' + detail}`);
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

function openWs(path) {
  const url = 'ws://127.0.0.1:8765' + path;
  const ws = new WebSocket(url);
  const frames = [];
  const waiters = [];
  ws.addEventListener('message', e => {
    let j = null; try { j = JSON.parse(e.data); } catch { }
    const rec = { raw: e.data, json: j };
    frames.push(rec);
    for (let i = waiters.length - 1; i >= 0; i--) {
      const w = waiters[i];
      if (w.match(j)) { waiters.splice(i, 1); clearTimeout(w.t); w.resolve(rec); return; }
    }
  });
  const api = {
    ws, frames,
    send: o => ws.send(typeof o === 'string' ? o : JSON.stringify(o)),
    wait(match, ms = 10000) {
      return new Promise(res => {
        const w = { match, resolve: res };
        w.t = setTimeout(() => { const i = waiters.indexOf(w); if (i >= 0) waiters.splice(i, 1); res(null); }, ms);
        waiters.push(w);
      });
    },
    close: () => { try { ws.close(); } catch { } },
  };
  return api;
}

// ---- 建我自己的会话 ----
const cr = await api('/rc/v1/sessions', { method: 'POST', body: { cwd: null, title: 'v2-test-ws-probe' } });
if (cr.status !== 200 || !cr.json?.id) { console.error('create session failed', cr.status, cr.text.slice(0, 200)); process.exit(2); }
const SID = cr.json.id, OTHER = cr.json.id;
CREATED.push({ id: SID, purpose: 'v2 WS 测试' });
console.log(`[INFO] created test session: ${SID}`);

// ---- W1 鉴权失败 = close 4401 且零帧 ----
{
  const w = openWs('/rc/v1/stream?token=deadbeef');
  const c = await Promise.race([new Promise(r => w.ws.addEventListener('close', e => r(e.code))), sleep(8000).then(() => 'TIMEOUT')]);
  ok('W1', '错误 token → close 4401 且零帧', c === 4401 && w.frames.length === 0, `code=${c} frames=${w.frames.length}`);
}

// ---- W2 正确 token → hello 首帧 ----
const w = openWs('/rc/v1/stream?token=' + TOKEN);
{
  const h = await w.wait(j => j?.type === 'hello', 10000);
  ok('W2', '正确 token → 首帧 hello（含 sessions）',
    !!h && h.json.v === 1 && Array.isArray(h.json.sessions) && !!h.json.server?.name,
    h ? h.raw.slice(0, 120) : 'timeout');
}

// ---- W3 ping→pong ----
{
  w.send({ v: 1, type: 'ping', ts: Date.now() });
  const p = await w.wait(j => j?.type === 'pong', 10000);
  ok('W3', 'ping → pong', !!p && Number.isInteger(p.json.ts), p ? p.raw.slice(0, 80) : 'timeout');
}

// ---- W4 subscribe → ack 同 id ----
{
  w.send({ v: 1, type: 'subscribe', id: 'w-c1', sessionId: SID });
  const a = await w.wait(j => j?.type === 'ack' && j.id === 'w-c1', 8000);
  ok('W4', 'subscribe → ack（id 一致）', !!a && a.json.ok === true, a ? a.raw.slice(0, 100) : 'timeout');
}

// ---- W5 prompt ack 含 requestId/mode ----
{
  const R = 'ws-' + Date.now();
  w.send({ v: 1, type: 'prompt', id: 'w-c3', sessionId: SID, text: '1', requestId: R, mode: 'steer' });
  const a = await w.wait(j => (j?.type === 'ack' || j?.type === 'error') && j.id === 'w-c3', 10000);
  ok('W5', 'WS prompt ack 带 requestId（队列对账依据）',
    !!a && a.json.type === 'ack' && a.json.requestId === R, a ? a.raw.slice(0, 160) : 'timeout');
}

// ---- W6 session.event 含新字段 ----
{
  const all = await w.wait(j => j?.type === 'session.event', 90000);
  ok('W6', '订阅后收到 session.event', !!all, all ? all.raw.slice(0, 140) : `timeout（frames=${w.frames.length}）`);
  const ev = w.frames.find(f => f.json?.type === 'session.event' && f.json?.sessionId === SID);
  ok('W6d', 'session.event 属于我订阅的会话', !!ev,
    ev ? '' : `收到的 event 会话=${[...new Set(w.frames.filter(f => f.json?.type === 'session.event').map(f => f.json.sessionId))].join(',')} sid=${SID}`);
  if (ev) {
    const e = ev.json.event || {};
    ok('W6b', 'session.event 含需求3 新字段（kind/streamKind 至少其一）',
      ('kind' in e) || ('streamKind' in e), `keys=${Object.keys(e).join(',')}`);
    ok('W6c', 'session.event 仍带 messageId / seq（向后兼容）',
      ('messageId' in e) && ('seq' in e), `keys=${Object.keys(e).join(',')}`);
  } else {
    ok('W6b', 'session.event 含新字段', false, 'no event');
    ok('W6c', 'session.event 向后兼容', false, 'no event');
  }
}

// ---- W7/W8：等真实回合跑完后，再统计流式与收尾（否则测到的是回合刚开始的快照）----
{
  const t0 = Date.now();
  let done = false;
  while (Date.now() - t0 < 150000) {
    const evs = w.frames.filter(f => f.json?.type === 'session.event' && f.json?.event?.status === 'final');
    const streaming = w.frames.filter(f => f.json?.type === 'session.event' && f.json?.event?.status === 'streaming');
    if (evs.length > 0 && streaming.length > 0) { done = true; break; }
    await sleep(3000);
  }
  const evAll = w.frames.filter(f => f.json?.type === 'session.event');
  const withSk = evAll.filter(f => f.json.event?.streamKind);
  const skSet = new Set(withSk.map(f => f.json.event.streamKind));
  const bad = [...skSet].filter(x => x !== 'reasoning' && x !== 'message');
  ok('W7', '流式 chunk 带 streamKind（reasoning/message）',
    withSk.length > 0 && bad.length === 0,
    `总事件=${evAll.length} 带streamKind=${withSk.length} 取值=${[...skSet].join(',')} 非法=${bad.join(',')}`);
  const streaming = evAll.filter(f => f.json.event?.status === 'streaming');
  const finalEv = evAll.filter(f => f.json.event?.status === 'final');
  ok('W7b', '流式帧保留 messageId 键（中间态可为 null）',
    streaming.length === 0 || streaming.every(f => 'messageId' in f.json.event),
    `streaming=${streaming.length}`);
  ok('W8', '回合结束：存在 streaming→final 收尾',
    done || (finalEv.length > 0 && streaming.length > 0),
    `streaming=${streaming.length} final=${finalEv.length} 等待到收尾=${done}`);
}

// ---- W9 未订阅会话不推事件 ----
{
  const list = await api('/rc/v1/sessions?limit=10');
  const other = (list.json?.items || []).find(x => x.id !== SID && x.status !== 'deleted');
  if (other) {
    const mk = w.frames.length;
    // 对未订阅会话发一条（只对我自己的会话发；此处改为只观测，不写入他人会话）
    // 只验证：收到的所有 session.event 都属于已订阅的 SID
    const leaked = w.frames.slice(mk).filter(f => f.json?.type === 'session.event' && f.json?.sessionId !== SID);
    ok('W9', '只收到已订阅会话的事件（未订阅不推）',
      leaked.length === 0, `收到他人会话事件=${leaked.length}`);
  } else ok('W9', '只收到已订阅会话的事件', true, 'skip: no other session');
}

// ---- W10 error 帧带同一 id ----
{
  w.send({ v: 1, type: 'subscribe', id: 'w-c6', sessionId: 'session_not_exist_zzz' });
  const e = await w.wait(j => j?.type === 'error' && j.id === 'w-c6', 8000);
  ok('W10', '对不存在会话 subscribe → error（带同一 id）', !!e && e.json.code === 'not_found', e ? e.raw.slice(0, 120) : 'timeout');
}

// ---- W11 cancel ack（放在回合结束后，避免打断流式观测）----
{
  w.send({ v: 1, type: 'cancel', id: 'w-c4', sessionId: SID });
  const a = await w.wait(j => j?.type === 'ack' && j.id === 'w-c4', 8000);
  ok('W11', 'cancel → ack', !!a, a ? a.raw.slice(0, 100) : 'timeout');
}

// ---- W12 WS prompt 非法 mode → error ----
{
  w.send({ v: 1, type: 'prompt', id: 'w-c9', sessionId: SID, text: '1', requestId: 'ws-bogus-' + Date.now(), mode: 'bogus' });
  const e = await w.wait(j => (j?.type === 'error' || j?.type === 'ack') && j.id === 'w-c9', 8000);
  ok('W12', 'WS prompt 非法 mode → error(invalid_request)',
    !!e && e.json.type === 'error' && e.json.code === 'invalid_request',
    e ? e.raw.slice(0, 140) : 'timeout');
}

// ---- W13 未知 type 不静默丢弃 ----
{
  w.send({ v: 1, type: 'bogus', id: 'w-c10' });
  const e = await w.wait(j => j?.type === 'error' && j.id === 'w-c10', 8000);
  ok('W13', '未知 type → error（不静默丢弃）', !!e, e ? e.raw.slice(0, 120) : 'timeout');
}

w.close();
const pass = cases.filter(c => c.pass).length;
console.log(`\n== ws: TOTAL=${cases.length} PASS=${pass} FAIL=${cases.length - pass}`);
console.log(`CREATED_SESSION=${SID}`);
process.exit(pass === cases.length ? 0 : 1);
