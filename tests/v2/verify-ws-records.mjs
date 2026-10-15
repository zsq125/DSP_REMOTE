// tests/v2/verify-ws-records.mjs -- do WS control frames actually reach the counter?
// Drives a real WsHub over a real HTTP server and asserts subscribe/unsubscribe
// are recorded (C4 evidence).
import http from 'node:http';
import { createGateway } from '../../pc-gateway/lib/http.mjs';
import { DshAdapter } from '../../pc-gateway/lib/dsh.mjs';
// `ws` is a dependency of the gateway itself, so resolve it from there.
// (index.js is CommonJS: the class lives on `module.exports` / `.WebSocket`.)
const wsmod = await import(new URL('../../pc-gateway/node_modules/ws/index.js', import.meta.url));
const WebSocket = wsmod.WebSocket ?? wsmod.default?.WebSocket ?? wsmod.default;

let pass = 0, fail = 0;
const A = (c, m, x = '') => { if (c) { pass++; console.log('[PASS] ' + m); } else { fail++; console.log('[FAIL] ' + m + ' :: ' + x); } };

const dsh = new DshAdapter({
  list: async () => ({ items: [] }),
  inspect: async () => ({ meta: {}, events: [] }),
  projections: async () => ({ id: 's1' }),
  listPage: async () => ({ items: [{ sessionId: 's1', id: 's1' }], nextCursor: null }),
  history: async () => ({ meta: {}, messages: [] }),
  follow: () => ({ abort: { abort() {} } }),
});
const tokenStore = {
  equals: (t) => t === 'TOK', lookupFor: () => null, remember: () => {},
  consumeBusy: () => false, rotate: () => 'TOK2', armBusy: () => {},
};
const gw = createGateway({
  dsh, tokenStore, host: '127.0.0.1', port: 0, defaultCwd: '.',
  testHooks: false, log: () => {},
});
await gw.listen();
const port = gw.server.address().port;
const H = { Authorization: 'Bearer TOK' };
const rec = async () => (await (await fetch(`http://127.0.0.1:${port}/rc/v1/_debug/requests`, { headers: H })).json()).items;
await fetch(`http://127.0.0.1:${port}/rc/v1/_debug/requests`, { method: 'DELETE', headers: H });

const ws = new WebSocket(`ws://127.0.0.1:${port}/rc/v1/stream?token=TOK`);
await new Promise((r, j) => { ws.once('open', r); ws.once('error', j); });
await new Promise(r => setTimeout(r, 150));

const seen = [];
ws.on('message', (d) => { try { seen.push(JSON.parse(d.toString())); } catch { /* ignore */ } });

const send = (o) => ws.send(JSON.stringify(o));
const wait = (ms) => new Promise(r => setTimeout(r, ms));

// exactly what SessionSubscriptionManager does on a session switch
send({ v: 1, id: 'sub-s1', type: 'subscribe', sessionId: 's1' });
await wait(120);
send({ v: 1, id: 'unsub-s1', type: 'unsubscribe', sessionId: 's1' });
await wait(120);
send({ v: 1, id: 'sub-s2', type: 'subscribe', sessionId: 's2' });
await wait(200);

console.log('=== server-side counter ===');
const items = await rec();
const wsFrames = items.filter(i => i.method === 'WS');
for (const f of wsFrames) console.log('  ' + f.method + ' ' + f.path + '  sid=' + (f.sessionId ?? '-'));

A(wsFrames.some(f => f.path === 'connect'), 'WS connect is recorded');
A(wsFrames.some(f => f.path === 'subscribe' && f.sessionId === 's1'), 'subscribe(s1) is recorded', JSON.stringify(wsFrames));
A(wsFrames.some(f => f.path === 'unsubscribe' && f.sessionId === 's1'), 'unsubscribe(s1) is recorded (C4 evidence)', JSON.stringify(wsFrames));
A(wsFrames.some(f => f.path === 'subscribe' && f.sessionId === 's2'), 'subscribe(s2) is recorded');

// the switch order must be unsubscribe(old) BEFORE subscribe(new)
const idxUnsub = wsFrames.findIndex(f => f.path === 'unsubscribe' && f.sessionId === 's1');
const idxSub2 = wsFrames.findIndex(f => f.path === 'subscribe' && f.sessionId === 's2');
A(idxUnsub >= 0 && idxSub2 > idxUnsub, 'order is unsubscribe(old) then subscribe(new)', `unsub@${idxUnsub} sub2@${idxSub2}`);

// the gateway should ack both control frames
A(seen.some(m => m.type === 'ack' && m.id === 'unsub-s1'), 'unsubscribe is acknowledged');

ws.close();
await wait(150);
A((await rec()).some(f => f.method === 'WS' && f.path === 'disconnect'), 'WS disconnect is recorded');

await gw.close();
console.log('\n' + '='.repeat(56));
console.log('WS RECORD VERIFY  PASS=' + pass + '  FAIL=' + fail);
process.exit(fail > 0 ? 1 : 0);