// tests/v2/probe-fork-push.mjs — 分叉判定：网关侧到底推不推事件？
// 纪律：只对我自己创建的会话写；订阅/发 prompt 都只针对该会话。
// 目的：区分「网关没推」 vs 「网关推了但 App 没应用」—— 这决定开发改哪里。
import { api } from './common.mjs';

const TOKEN = process.env.DSH_RC_TOKEN;
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ---- 1. 建我自己的会话（唯一允许写入的对象）----
const cr = await api('/rc/v1/sessions', { method: 'POST', body: { cwd: null, title: 'v2-fork-probe' } });
if (cr.status !== 200 || !cr.json?.id) { console.error('create failed', cr.status, cr.text.slice(0, 200)); process.exit(2); }
const SID = cr.json.id;
console.log(`[INFO] CREATED_SESSION=${SID}`);

// ---- 2. 连 WS，订阅，等事件 ----
const frames = [];
const ws = new WebSocket('ws://127.0.0.1:8765/rc/v1/stream?token=' + TOKEN);
ws.addEventListener('message', e => { try { frames.push(JSON.parse(e.data)); } catch { frames.push({ raw: e.data }); } });
await sleep(2500);
console.log(`[INFO] hello received = ${frames.some(f => f.type === 'hello')}`);

ws.send(JSON.stringify({ v: 1, type: 'subscribe', id: 's1', sessionId: SID }));
await sleep(1500);
console.log(`[INFO] subscribe ack = ${frames.some(f => f.type === 'ack' && f.id === 's1')}`);

// ---- 3. 发 prompt（mode=queue，避免打断；本会话是新建的，本来就 idle）----
const R = 'fork-' + Date.now();
ws.send(JSON.stringify({ v: 1, type: 'prompt', id: 'p1', sessionId: SID, text: '1', requestId: R, mode: 'queue' }));
await sleep(2000);
console.log(`[INFO] prompt ack = ${JSON.stringify(frames.find(f => f.id === 'p1') || {}).slice(0, 140)}`);

// ---- 4. 观察 60s：网关是否推送 session.event / session.status ----
const t0 = Date.now();
let firstEventAt = null, firstFinalAt = null, statusSeen = [];
while (Date.now() - t0 < 60000) {
  const evs = frames.filter(f => f.type === 'session.event');
  if (firstEventAt === null && evs.length) firstEventAt = Date.now() - t0;
  const fin = evs.filter(f => f.event?.status === 'final');
  if (firstFinalAt === null && fin.length) { firstFinalAt = Date.now() - t0; break; }
  await sleep(1000);
}

const evs = frames.filter(f => f.type === 'session.event');
const byStatus = {};
for (const e of evs) { const s = e.event?.status || 'none'; byStatus[s] = (byStatus[s] || 0) + 1; }
const kinds = {}; for (const e of evs) { const k = e.event?.kind || 'none'; kinds[k] = (kinds[k] || 0) + 1; }
const sk = evs.filter(e => e.event?.streamKind).length;

console.log('\n=== 分叉判定 ===');
console.log(`session.event 总数 = ${evs.length}`);
console.log(`首次事件延迟 = ${firstEventAt === null ? 'N/A(无事件)' : firstEventAt + 'ms'}`);
console.log(`首次 final 延迟 = ${firstFinalAt === null ? 'N/A(未收到)' : firstFinalAt + 'ms'}`);
console.log(`status 分布 = ${JSON.stringify(byStatus)}`);
console.log(`kind 分布 = ${JSON.stringify(kinds)}`);
console.log(`带 streamKind 的帧 = ${sk}/${evs.length}`);

// 再确认 REST 侧确实产生了消息（排除"根本没跑起来"）
const ms = await api(`/rc/v1/sessions/${SID}/messages?limit=50&expand=full`);
const items = ms.json?.items || [];
console.log(`\nREST /messages 条数 = ${items.length}（网关侧确实落库）`);
const seqs = items.map(x => x.seq);
console.log(`seq 列表 = ${seqs.join(',')}`);

// 列表页数据是否也更新（App 左栏徽标依赖它）
const li = await api('/rc/v1/sessions?limit=200');
const me = (li.json?.items || []).find(x => x.id === SID);
console.log(`列表项：messageCount=${me?.messageCount} status=${me?.status} lastMessagePreview="${(me?.lastMessagePreview || '').slice(0, 40)}"`);

const GATEWAY_PUSHES = evs.length > 0;
console.log(`\n>>> 分叉结论：${GATEWAY_PUSHES
  ? '【网关在推】⇒ 根因在 App 侧（订阅/应用/UI 重组）'
  : '【网关没推】⇒ 根因在网关侧'}`);
ws.close();
console.log(`CREATED_SESSION=${SID}`);