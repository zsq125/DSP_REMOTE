// tests/v2/verify-ws-app-protocol.mjs -- Is an explicit `subscribe` frame required?
//
// Uses the REAL WsHub over a REAL server and the EXACT frame format the App emits
// (SessionSubscription.encode()). Three scenarios:
//   1) connect, never subscribe      -> do events arrive?
//   2) connect + subscribe (App flow)-> do events arrive?
//   3) subscribe then unsubscribe   -> does the stream stop?
import { createGateway } from '../../pc-gateway/lib/http.mjs';
import { DshAdapter } from '../../pc-gateway/lib/dsh.mjs';

const wsmod = await import(new URL('../../pc-gateway/node_modules/ws/index.js', import.meta.url));
const WebSocket = wsmod.WebSocket ?? wsmod.default?.WebSocket ?? wsmod.default;
const SID = 's1';

let pass = 0, fail = 0;
const A = (c, m, x = '') => { if (c) { pass++; console.log('[PASS] ' + m); } else { fail++; console.log('[FAIL] ' + m + ' :: ' + x); } };
const sleep = ms => new Promise(r => setTimeout(r, ms));

// A DSH adapter whose follow yields exactly two events for session S1.
// NOTE: the real API is `follow(request, signal): AsyncIterable<SessionFollowFrame>`
// (dsh-api-session-controller/lib/types/index.d.ts:196), NOT a callback object --
// an earlier version of this stub used the wrong shape and produced a false 0.
function makeDsh() {
  return new DshAdapter({
    list: async () => ({ items: [] }),
    inspect: async () => ({ meta: {}, events: [] }),
    projections: async () => ({ id: SID }),
    listPage: async () => ({ items: [{ sessionId: SID, id: SID }], nextCursor: null }),
    history: async () => ({ meta: {}, messages: [] }),
    follow: (req, signal) => ({
      async *[Symbol.asyncIterator]() {
        const ev = (seq, type, data) => ({ type: 'event', event: { seq, type, time: Date.now(), data } });
        yield { type: 'snapshot', header: { id: SID }, cursor: 0, records: [], hasMore: false, projections: {} };
        await new Promise(r => setTimeout(r, 200));
        yield ev(1, 'user/message', { id: 'm1', content: [{ type: 'text', text: 'EVT-A' }] });
        await new Promise(r => setTimeout(r, 150));
        yield ev(2, 'assistant/message', { message: { id: 'a1', content: [{ type: 'text', text: 'EVT-B' }] } });
        // stay open until aborted, like the real follow loop
        await new Promise(r => {
          if (signal?.aborted) return r();
          signal?.addEventListener?.('abort', r, { once: true });
        });
      },
    }),
  });
}

const tokenStore = {
  equals: t => t === 'TOK', lookupFor: () => null, remember: () => {},
  consumeBusy: () => false, rotate: () => 'TOK2', armBusy: () => {},
};

async function withServer(fn) {
  const gw = createGateway({ dsh: makeDsh(), tokenStore, host: '127.0.0.1', port: 0, defaultCwd: '.', testHooks: false, log: () => {} });
  await gw.listen();
  const port = gw.server.address().port;
  try { await fn(port); } finally { await gw.close(); }
}

// Collect every session.event for SID.
async function run(port, doSubscribe, doUnsubscribe) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/rc/v1/stream?token=TOK`);
  const events = [];
  const acks = [];
  await new Promise((r, j) => { ws.once('open', r); ws.once('error', j); });
  ws.on('message', d => {
    let f; try { f = JSON.parse(d.toString()); } catch { return; }
    if (f.type === 'ack') acks.push(f);
    if (f.type === 'session.event') events.push(f);
  });
  await sleep(120);

  if (doSubscribe) {
    // EXACT format from SessionSubscription.encode()
    ws.send(JSON.stringify({ v: 1, type: 'subscribe', id: 'sub-' + SID, sessionId: SID }));
    await sleep(500);
  }
  if (doUnsubscribe) {
    ws.send(JSON.stringify({ v: 1, type: 'unsubscribe', id: 'unsub-' + SID, sessionId: SID }));
    await sleep(300);
  }
  await sleep(500);
  ws.close();
  return { events, acks };
}

console.log('=== 场景 1：连接后【不发 subscribe】 ===');
await withServer(async (port) => {
  const { events, acks } = await run(port, false, false);
  console.log('  events=' + events.length + '  acks=' + acks.length);
  A(events.length === 0, '不发 subscribe 就【收不到】任何事件 ⇒ 显式订阅是必需的');
});

console.log('\n=== 场景 2：连接 + subscribe（App 的正常流程）===');
await withServer(async (port) => {
  const { events, acks } = await run(port, true, false);
  console.log('  events=' + events.length + '  acks=' + acks.length + '  ackIds=' + JSON.stringify(acks.map(a => a.id)));
  A(events.length > 0, 'subscribe 后能收到事件（协议成立）');
  A(acks.some(a => a.id === 'sub-' + SID && a.ok), 'subscribe 有 ack，id 与 App 发的一致');
});

console.log('\n=== 场景 3：subscribe 后 unsubscribe（切换会话的收尾）===');
await withServer(async (port) => {
  const { events, acks } = await run(port, true, true);
  const unsubAck = acks.some(a => a.id === 'unsub-' + SID && a.ok);
  console.log('  events=' + events.length + '  unsubAck=' + unsubAck);
  A(unsubAck, 'unsubscribe 也有 ack（App 的 unsub 帧格式正确）');
});

console.log('\n' + '='.repeat(56));
console.log('WS APP PROTOCOL VERIFY  PASS=' + pass + '  FAIL=' + fail);