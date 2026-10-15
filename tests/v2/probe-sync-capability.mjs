// tests/v2/probe-sync-capability.mjs — 验证网关"持续同步"能力是否完备（bug2/bug3 的前置条件）
// 关键点：事件必须是**持续**推送，而不只是"订阅瞬间来一次"。
// 纪律：只对我自己创建的会话写。
import { api } from './common.mjs';

const TOKEN = process.env.DSH_RC_TOKEN;
const sleep = ms => new Promise(r => setTimeout(r, ms));

const cr = await api('/rc/v1/sessions', { method: 'POST', body: { cwd: null, title: 'v2-sync-capability' } });
if (cr.status !== 200 || !cr.json?.id) { console.error('create failed'); process.exit(2); }
const SID = cr.json.id;
console.log(`[INFO] CREATED_SESSION=${SID}`);

const frames = [];
const ws = new WebSocket('ws://127.0.0.1:8765/rc/v1/stream?token=' + TOKEN);
ws.addEventListener('message', e => { try { frames.push({ at: Date.now(), ...JSON.parse(e.data) }); } catch { } });
await sleep(2500);
ws.send(JSON.stringify({ v: 1, type: 'subscribe', id: 's1', sessionId: SID }));
await sleep(1500);
const mark1 = frames.length;

// ---- 第一次 prompt：订阅**之后**才发，考察是否真的持续推 ----
ws.send(JSON.stringify({ v: 1, type: 'prompt', id: 'p1', sessionId: SID, text: '1', requestId: 'cap-a-' + Date.now() }));
await sleep(35000);
const ev1 = frames.slice(mark1).filter(f => f.type === 'session.event');
console.log(`\n[第1轮] 订阅后发 prompt -> 收到事件 ${ev1.length}`);

// ---- 等待回合结束 ----
let waited = 0;
while (waited < 60000) {
  const fin = frames.filter(f => f.type === 'session.event' && f.event?.status === 'final').length;
  const st = (await api('/rc/v1/sessions?limit=200')).json?.items?.find(x => x.id === SID)?.status;
  if (fin > 0 && st !== 'running') break;
  await sleep(3000); waited += 3000;
}
const idle = (await api('/rc/v1/sessions?limit=200')).json?.items?.find(x => x.id === SID)?.status;
console.log(`[第1轮] 回落后 status=${idle}，累积事件=${frames.filter(f => f.type === 'session.event').length}`);

// ---- 第二次 prompt：**同一订阅不重连**，考察"持续"能力 ----
const mark2 = frames.length;
ws.send(JSON.stringify({ v: 1, type: 'prompt', id: 'p2', sessionId: SID, text: '1', requestId: 'cap-b-' + Date.now() }));
await sleep(35000);
const ev2 = frames.slice(mark2).filter(f => f.type === 'session.event');
console.log(`[第2轮] 不重订阅再发 prompt -> 收到新事件 ${ev2.length}`);

// ---- 统计 ----
const all = frames.filter(f => f.type === 'session.event');
const st = frames.filter(f => f.type === 'session.status');
console.log(`\n=== 网关持续同步能力 ===`);
console.log(`session.event 累积 = ${all.length}`);
console.log(`session.status 推送 = ${st.length}${st.length ? ' 样例=' + JSON.stringify(st[0]).slice(0, 120) : '（未见 status 推送）'}`);
console.log(`第1轮事件 = ${ev1.length}，第2轮事件 = ${ev2.length}`);
const PUSHES_CONTINUOUSLY = ev2.length > 0;
console.log(`\n>>> 结论：${PUSHES_CONTINUOUSLY
  ? '【网关持续推送正常】同一订阅在多轮之间都能收到新事件 ⇒ App 若不更新，根因确在 App 侧'
  : '【网关只推一次】第二轮收不到事件 ⇒ 网关侧存在"订阅后不再推送"缺陷，需网关修'}`);

ws.close();
console.log(`CREATED_SESSION=${SID}`);