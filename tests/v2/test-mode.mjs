// tests/v2/test-mode.mjs — 需求 1: mode 参数（steer|queue）契约测试
// 纪律：只对我自己创建的会话写入；其余一律只读。
import { api, CREATED } from './common.mjs';

const cases = [];
const ok = (id, name, pass, detail = '') => {
  cases.push({ id, name, pass, detail });
  console.log(`[${pass ? 'PASS' : 'FAIL'}] ${id} ${name}${pass ? '' : ' :: ' + detail}`);
};
const rid = () => 'v2-' + Date.now() + '-' + Math.floor(Math.random() * 1000);

// ---- 创建我自己的会话（唯一允许写入的对象）----
const cr = await api('/rc/v1/sessions', {
  method: 'POST',
  body: { cwd: null, title: 'v2-test-mode-probe' },
});
if (cr.status !== 200 || !cr.json?.id) {
  console.error('cannot create session:', cr.status, cr.text.slice(0, 200));
  process.exit(2);
}
const SID = cr.json.id;
CREATED.push({ id: SID, purpose: 'v2 mode 参数测试' });
console.log(`[INFO] created test session: ${SID}\n`);

// ---- M1 默认 mode 回显 steer ----
{
  const r = await api(`/rc/v1/sessions/${SID}/prompt`, { method: 'POST', body: { text: '1', requestId: rid() } });
  ok('M1', '不传 mode 时响应回显 mode=steer',
    r.status === 200 && r.json?.accepted === true && r.json?.mode === 'steer',
    `status=${r.status} body=${r.text.slice(0, 160)}`);
}

// ---- M2 显式 steer ----
{
  const r = await api(`/rc/v1/sessions/${SID}/prompt`, { method: 'POST', body: { text: '1', mode: 'steer', requestId: rid() } });
  ok('M2', 'mode=steer 被接受且回显 steer',
    r.status === 200 && r.json?.accepted === true && r.json?.mode === 'steer',
    `status=${r.status} body=${r.text.slice(0, 160)}`);
}

// ---- M3 显式 queue ----
{
  const r = await api(`/rc/v1/sessions/${SID}/prompt`, { method: 'POST', body: { text: '1', mode: 'queue', requestId: rid() } });
  ok('M3', 'mode=queue 被接受且回显 queue',
    r.status === 200 && r.json?.accepted === true && r.json?.mode === 'queue',
    `status=${r.status} body=${r.text.slice(0, 160)}`);
}

// ---- M4 非法 mode 值 ----
for (const [label, val] of [
  ['bogus', 'bogus'], ['空字符串', ''], ['大写 STEER', 'STEER'], ['数字', 1], ['null', null], ['数组', ['steer']],
]) {
  const r = await api(`/rc/v1/sessions/${SID}/prompt`, { method: 'POST', body: { text: '1', mode: val, requestId: rid() } });
  const isErr = r.status === 400 && r.json?.error?.code === 'invalid_request';
  // 若实现选择"忽略非法值回退默认"，记录实际行为而不误判
  ok('M4-' + label, `mode=${label} → 400 invalid_request`,
    isErr, isErr ? '' : `实际 status=${r.status} body=${r.text.slice(0, 160)}（若为回退默认请裁决）`);
}

// ---- M5 steer 对 idle 会话应唤醒 ----
{
  const before = await api('/rc/v1/sessions?limit=200');
  const me = (before.json?.items || []).find(x => x.id === SID);
  const wasIdle = me?.status === 'idle';
  const r = await api(`/rc/v1/sessions/${SID}/prompt`, { method: 'POST', body: { text: '1', mode: 'steer', requestId: rid() } });
  await new Promise(x => setTimeout(x, 2500));
  const after = await api('/rc/v1/sessions?limit=200');
  const now = (after.json?.items || []).find(x => x.id === SID);
  ok('M5', 'steer 对 idle 会话被接受（应唤醒，不报错）',
    r.status === 200 && r.json?.accepted === true,
    `wasIdle=${wasIdle} status=${r.status} 响应=${r.text.slice(0, 120)} 现状态=${now?.status}`);
}

// ---- M6 steer 对 running 会话 ----
{
  const r = await api(`/rc/v1/sessions/${SID}/prompt`, { method: 'POST', body: { text: '1', mode: 'steer', requestId: rid() } });
  ok('M6', 'steer 对 running 会话被接受且不报错',
    r.status === 200 && r.json?.accepted === true,
    `status=${r.status} body=${r.text.slice(0, 160)}`);
}

// ---- M7 queue 对 running 会话 ----
{
  const r = await api(`/rc/v1/sessions/${SID}/prompt`, { method: 'POST', body: { text: '1', mode: 'queue', requestId: rid() } });
  ok('M7', 'queue 对 running 会话被接受（追加不打断）',
    r.status === 200 && r.json?.accepted === true && r.json?.mode === 'queue',
    `status=${r.status} body=${r.text.slice(0, 160)}`);
}

// ---- M8 幂等：同 requestId 重复提交 ----
{
  const R = rid();
  const a = await api(`/rc/v1/sessions/${SID}/prompt`, { method: 'POST', body: { text: '1', mode: 'steer', requestId: R } });
  const b = await api(`/rc/v1/sessions/${SID}/prompt`, { method: 'POST', body: { text: '1', mode: 'steer', requestId: R } });
  const c = await api(`/rc/v1/sessions/${SID}/prompt`, { method: 'POST', body: { text: '1', mode: 'steer', requestId: R } });
  const same = a.json?.messageId && a.json.messageId === b.json?.messageId && b.json.messageId === c.json?.messageId;
  ok('M8', '同 requestId 重复提交 → 同一 messageId（不重复入队）',
    a.status === 200 && b.status === 200 && c.status === 200 && same,
    `ids=${a.json?.messageId}/${b.json?.messageId}/${c.json?.messageId}`);
}

// ---- M9 mode 与 requestId 幂等交互：同 requestId 不同 mode ----
{
  const R = rid();
  const a = await api(`/rc/v1/sessions/${SID}/prompt`, { method: 'POST', body: { text: '1', mode: 'steer', requestId: R } });
  const b = await api(`/rc/v1/sessions/${SID}/prompt`, { method: 'POST', body: { text: '1', mode: 'queue', requestId: R } });
  ok('M9', '同 requestId 换 mode 仍幂等（同一 messageId）',
    a.json?.messageId === b.json?.messageId,
    `steer=${a.json?.messageId} queue=${b.json?.messageId}（若不同说明幂等键未含 mode）`);
}

// ---- M10 缺少 text 仍应 400（回归）----
{
  const r = await api(`/rc/v1/sessions/${SID}/prompt`, { method: 'POST', body: { mode: 'steer', requestId: rid() } });
  ok('M10', '缺 text → 400 invalid_request（回归）',
    r.status === 400 && r.json?.error?.code === 'invalid_request', `status=${r.status} body=${r.text.slice(0, 120)}`);
}

// ---- M11 缺 requestId 仍应 400（回归）----
{
  const r = await api(`/rc/v1/sessions/${SID}/prompt`, { method: 'POST', body: { text: '1', mode: 'steer' } });
  ok('M11', '缺 requestId → 400 invalid_request（回归）',
    r.status === 400 && r.json?.error?.code === 'invalid_request', `status=${r.status} body=${r.text.slice(0, 120)}`);
}

const pass = cases.filter(c => c.pass).length;
console.log(`\n== mode: TOTAL=${cases.length} PASS=${pass} FAIL=${cases.length - pass}`);
console.log(`CREATED_SESSION=${SID}`);
process.exit(pass === cases.length ? 0 : 1);
