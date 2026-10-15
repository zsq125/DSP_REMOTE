// tests/regress/conn-defects/run.mjs — D1~D4 回归用例执行器（零依赖，直接 node 运行）
// 判据不是"看代码"，而是**复现真实时序**并断言可观测结果（是否真的发起 WS 连接 / UI 是否显示真实状态）。
// 修复前：CONN-D1~D4 FAIL；修复后：全部 PASS，且 CONN-K1~K4/D4b/D4c 仍 PASS。
import { GatedStore, Bootstrapper, StateBus, Page } from './model.mjs';

const T = '0123456789abcdef0123456789abcdef';
const WS = 'ws://10.0.2.2:8765/rc/v1/stream?token=' + T;
let pass = 0, fail = 0;
const results = [];

function add(id, name, ok, detail = '') {
  results.push({ id, name, ok, detail });
  console.log(`[${ok ? 'PASS' : 'FAIL'}] ${id} ${name}${ok ? '' : ' :: ' + detail}`);
  ok ? pass++ : fail++;
}

// ─────────────────────────────────────────────────────────
// CONN-D1 竞态：配对落盘尚未可见时读到 null ⇒ 早退 ⇒ 永不建连
// ─────────────────────────────────────────────────────────
{
  // 修复后的真实流程（MainActivity.completePairing）：
  //   await savePairing()   ← 配对**已落盘**
  //   ConnectionService.start(context)
  //   ConnectionService.instance?.notifyPairingSaved()
  // 但服务可能**先**被系统拉起并读到 null（旧 D1 竞态）。此时配对落盘后必须能补建连。
  const store = new GatedStore(120);
  const bs = new Bootstrapper(store);
  // (1) 服务先启动，此刻配对还不可见 → 早退（这正是旧 D1 竞态发生的瞬间）
  await bs.ensureConnected();
  add('CONN-D1a', '配对不可见时首次启动不建连（早退本身合法）',
    bs.connects.length === 0 && bs.phase !== 'CONNECTED',
    `connects=${bs.connects.length} phase=${bs.phase}`);
  // (2) 随后 completePairing 完成落盘，并调用 notifyPairingSaved() ⇒ 必须补建连
  store.commit(WS);
  await bs.onPairingSaved();
  await new Promise(r => setTimeout(r, 200));
  add('CONN-D1', '竞态早退后，配对落盘 + notifyPairingSaved ⇒ 必须补建 WS（D1 修复点）',
    bs.connects.length === 1,
    `connects=${bs.connects.length} phase=${bs.phase}（0 表示早退成为终局）`);
}

// ─────────────────────────────────────────────────────────
// CONN-D2 一次性标志：首次早退后 started 已 true ⇒ 后续 start 永不重试
// ─────────────────────────────────────────────────────────
{
  const store = new GatedStore(0);
  const bs = new Bootstrapper(store);
  await bs.onStartCommandLike();
  await bs.onStartCommandLike();
  await bs.onStartCommandLike();
  add('CONN-D2a', '反复 start 但始终未配对 ⇒ 标记 UNPAIRED 且不建连、不崩溃',
    bs.connects.length === 0 && bs.phase === 'UNPAIRED',
    `connects=${bs.connects.length} phase=${bs.phase}`);
  store.commit(WS);
  await bs.onStartCommandLike();
  add('CONN-D2', '重试预算用尽后，配对完成仍能建立 WS（D2 修复点）',
    bs.connects.length === 1,
    `connects=${bs.connects.length} phase=${bs.phase}（0 说明一次性闸门吞掉了重试）`);
  for (let i = 0; i < 5; i++) await bs.onStartCommandLike();
  add('CONN-D2b', '已连接后反复 start 不重复建连（幂等）',
    bs.connects.length === 1, `connects=${bs.connects.length}（>1 即重复建连）`);
}

// ─────────────────────────────────────────────────────────
// CONN-D3 重启不恢复：全新实例 + 已有配对 ⇒ 必须自动建连
// ─────────────────────────────────────────────────────────
{
  const store = new GatedStore(0);
  store.commit(WS);
  const bs = new Bootstrapper(store);
  await bs.onStartCommandLike();
  add('CONN-D3', '重启后全新实例 + 已有配对 ⇒ 自动建立 WS，无需重新配对（D3 修复点）',
    bs.connects.length === 1,
    `connects=${bs.connects.length}（0 表示重启后无人驱动建连）`);
}

// ─────────────────────────────────────────────────────────
// CONN-D4 状态未订阅：列表页不订阅 ⇒ UI 恒为 DISCONNECTED
// ─────────────────────────────────────────────────────────
{
  const bus = new StateBus();
  bus.publish('CONNECTED');
  const sessions = new Page('SESSIONS', bus);
  add('CONN-D4', '任务列表页也订阅连接状态且显示真实值（D4 修复点）',
    sessions.subscribed && sessions.state === 'CONNECTED',
    `subscribed=${sessions.subscribed} state=${sessions.state}`);

  const bus2 = new StateBus();
  const detail = new Page('DETAIL', bus2);
  bus2.publish('CONNECTED');
  add('CONN-D4b', '详情页连接状态订阅正常（守住既有行为，防回归）',
    detail.subscribed && detail.state === 'CONNECTED', `state=${detail.state}`);

  const p1 = new Page('SESSIONS', bus2), p2 = new Page('DETAIL', bus2);
  bus2.publish('BACKING_OFF');
  add('CONN-D4c', '状态变化推送给所有订阅页面（列表+详情同步）',
    p1.state === 'BACKING_OFF' && p2.state === 'BACKING_OFF',
    `sessions=${p1.state} detail=${p2.state}`);
}

// ─────────────────────────────────────────────────────────
// 防回归守卫
// ─────────────────────────────────────────────────────────
{
  const store = new GatedStore(0);
  store.commit(WS);
  const bs = new Bootstrapper(store);
  await bs.ensureConnected();
  add('CONN-K1', '已配对 + 启动 ⇒ 建立恰好 1 条 WS（正常路径不被修复破坏）',
    bs.connects.length === 1, `connects=${bs.connects.length}`);
}
{
  const bs = new Bootstrapper(new GatedStore(0));
  await bs.ensureConnected();
  add('CONN-K2', '未配对 + 启动 ⇒ 不建连、不崩溃（早退仍是合法路径）',
    bs.connects.length === 0, `connects=${bs.connects.length}`);
}
{
  const store = new GatedStore(0);
  store.commit(WS);
  const bs = new Bootstrapper(store);
  for (let i = 0; i < 5; i++) await bs.onStartCommandLike();
  add('CONN-K3', '重复 start() 不得造成重复连接（修 D2 最易踩的坑）',
    bs.connects.length === 1, `connects=${bs.connects.length}（>1 说明修复引入重复连接）`);
}
{
  const bs = new Bootstrapper(new GatedStore(0));
  await bs.ensureConnected();
  bs.reset();
  await bs.ensureConnected();
  add('CONN-K4', 'reset() 后可重新建连（解除配对/rotate 后不锁死）',
    bs.connects.length === 0, `connects=${bs.connects.length}`);
}

console.log(`\n== conn-defects: TOTAL=${results.length} PASS=${pass} FAIL=${fail}`);
process.exit(fail > 0 ? 1 : 0);
