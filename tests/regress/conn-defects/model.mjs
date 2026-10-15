// tests/regress/conn-defects/model.mjs — D1~D4 的可注入确定性模型（纯 JS，零依赖、零构建）
//
// 为什么用模型而不是直接测产品类：
//   ConnectionService 依赖真实 Service 生命周期 + 真实 DataStore 异步写入时序；
//   而我不得修改任何产品代码（android-app/ 只读），无法给产品类注入替身。
//   本模型 1:1 复刻产品侧关键逻辑（本轮已同步为**修复后**版本）：
//     ① Bootstrapper.ensureConnected()  —— 幂等、可重试、未配对不是终局
//     ② onPairingSaved()  —— 配对落盘后显式驱动（修 D1）
//     ③ onStartCommandLike() —— 每次 start 都重新驱动（修 D2，不再有一次性 started 闸门）
//     ④ 全新实例 + 已有配对 ⇒ 自动建连（修 D3）
//     ⑤ StateBus —— 任意页面可订阅且立即回放最新状态（修 D4）
//   不 import 任何 com.deepseek.dshremote.*，对 android-app 构建零影响。
//   ⚠️ 本模型是产品逻辑的**镜像**；产品自身的 ConnectionBootstrapperTest（19 条）
//      才是权威。两者必须同时为绿 —— 模型锁契约，产品单测锁实现。

/** 可注入的异步存储：读取延迟可配，制造 DataStore 写入未落盘的窗口。 */
export class GatedStore {
  constructor(delayMs = 0) { this.delayMs = delayMs; this.visible = null; }
  loadPairing() {
    return new Promise(res => setTimeout(() => res(this.visible), this.delayMs));
  }
  commit(v) { this.visible = v; }
}

/** 复刻修复后的 ConnectionBootstrapper（core/ConnectionBootstrapper.kt）。 */
export class Bootstrapper {
  constructor(store, maxUnpairedRetries = 3) {
    this.store = store;
    this.max = maxUnpairedRetries;
    this.attempts = 0;
    this.isConnected = false;
    this.retryBudgetExhausted = false;
    this.connects = [];          // ← 可观测结果：真正发起过的 WS 连接
  }
  get phase() {
    if (this.isConnected) return 'CONNECTED';
    if (this.retryBudgetExhausted) return 'UNPAIRED';
    if (this.attempts > 0) return 'WAITING_PAIRING';
    return 'IDLE';
  }
  async ensureConnected() {
    if (this.isConnected) return false;                 // 幂等
    const n = ++this.attempts;
    if (n >= this.max) this.retryBudgetExhausted = true;
    const pairing = await this.store.loadPairing();
    if (pairing === null || pairing === undefined) return false;  // 未配对：不是终局
    this.isConnected = true;
    this.retryBudgetExhausted = false;
    this.connects.push(pairing);
    return true;
  }
  onPairingSaved() { return this.ensureConnected(); }    // 修 D1
  onStartCommandLike() { return this.ensureConnected(); } // 修 D2
  reset() { this.isConnected = false; this.retryBudgetExhausted = false; this.attempts = 0; }
}

/** 复刻修复后的 ConnectionStateBus：任意页面订阅 + 立即回放最新状态（修 D4）。 */
export class StateBus {
  constructor() { this.latest = 'DISCONNECTED'; this.subs = new Map(); this.seq = 0; }
  subscribe(fn) {
    const id = ++this.seq;
    this.subs.set(id, fn);
    fn(this.latest);                 // ★ 立即回放当前状态（修复"新页面显示默认值"）
    return id;
  }
  unsubscribe(id) { this.subs.delete(id); }
  publish(s) { this.latest = s; for (const fn of [...this.subs.values()]) fn(s); }
}

/** UI 页面：无论在哪个 Screen，都能订阅到真实状态。 */
export class Page {
  constructor(screen, bus) {
    this.screen = screen;
    this.state = 'DISCONNECTED';
    this.subscribed = false;
    this.token = bus.subscribe(s => { this.subscribed = true; this.state = s; });
  }
  dispose() { this.unsubscribe?.(this.token); }
}
