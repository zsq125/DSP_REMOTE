// tests/lib/harness.mjs — 测试公共库：断言收集、证据落盘、HTTP/WS 客户端
// 归属：测试 subagent 独占目录。只读网关/PC 数据，不修改任何产品文件。
import fs from 'node:fs';
import path from 'node:path';

export const EVIDENCE_DIR = path.resolve(process.env.DSH_TEST_EVIDENCE || 'tests/.evidence');

// ---------- 会话消耗记账（PM 硬性纪律：测试只登记，不自行删会话） ----------
export const CREATED = { sessions: [] };
export function trackCreated(id, purpose) {
  if (!id || typeof id !== 'string') return id;
  if (!CREATED.sessions.some(s => s.id === id)) {
    CREATED.sessions.push({ id, at: new Date().toISOString(), purpose: purpose || '' });
  }
  return id;
}
export function saveCreated(file = 'tests/.evidence/created-sessions.json') {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify({
    note: '本轮测试创建的会话，交由 PM 统一清理；测试方不删除任何会话',
    at: new Date().toISOString(),
    count: CREATED.sessions.length,
    sessions: CREATED.sessions,
  }, null, 2));
  console.log(`\n[记账] 已登记 ${CREATED.sessions.length} 个会话 -> ${file}`);
}

export class Report {
  constructor(title) {
    this.title = title;
    this.cases = [];
    this.t0 = Date.now();
  }
  /** id: 用例ID, name: 描述, ok: 是否通过, detail: 证据文本 */
  add(id, name, ok, detail = '') {
    this.cases.push({ id, name, ok: !!ok, detail: String(detail).slice(0, 4000) });
    const tag = ok ? 'PASS' : 'FAIL';
    console.log(`[${tag}] ${id} ${name}${ok ? '' : ' :: ' + detail}`);
    return ok;
  }
  /** 检查式断言：errMsg 为 null/undefined 即通过（对齐 curl 风格） */
  addCheck(id, name, errMsg, extra = '') {
    const ok = errMsg === null || errMsg === undefined || errMsg === true;
    return this.add(id, name, ok, ok ? extra : String(errMsg));
  }
  /** 异步检查式断言 */
  async addCheckAsync(id, name, p, extra = '') {
    let e; try { e = await p; } catch (err) { e = err?.message ?? String(err); }
    const ok = e === null || e === undefined || e === true;
    return this.add(id, name, ok, ok ? extra : String(e));
  }
  skip(id, name, reason) {
    this.cases.push({ id, name, ok: null, detail: reason });
    console.log(`[SKIP] ${id} ${name} :: ${reason}`);
  }
  get passed() { return this.cases.filter(c => c.ok === true).length; }
  get failed() { return this.cases.filter(c => c.ok === false).length; }
  get skipped() { return this.cases.filter(c => c.ok === null).length; }

  save(name) {
    fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
    const file = path.join(EVIDENCE_DIR, name);
    const out = {
      title: this.title, at: new Date().toISOString(),
      durationMs: Date.now() - this.t0,
      summary: { total: this.cases.length, passed: this.passed, failed: this.failed, skipped: this.skipped },
      cases: this.cases,
    };
    fs.writeFileSync(file, JSON.stringify(out, null, 2));
    console.log(`\n== ${this.title} ==  TOTAL=${out.summary.total} PASS=${this.passed} FAIL=${this.failed} SKIP=${this.skipped}`);
    console.log(`证据: ${file}`);
    return out.summary;
  }
}

export class Fatal extends Error {}

export function must(cond, msg) { if (!cond) throw new Fatal(msg); }

// ---------- HTTP ----------
/** TOKENS.current：轮换后自动更新，令所有未显式指定 token 的请求使用最新 token */
export const TOKENS = { current: null };
export async function req(base, pathname, { method = 'GET', token, body, headers = {} } = {}) {
  const h = { ...headers };
  const tok = token === null ? null : (token !== undefined ? token : TOKENS.current);
  if (tok !== undefined && tok !== null) h['Authorization'] = `Bearer ${tok}`;
  let payload;
  if (body !== undefined) { h['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  const res = await fetch(base + pathname, { method, headers: h, body: payload });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON 保留原文 */ }
  return { status: res.status, json, text, headers: res.headers };
}

export function isIntMs(v) { return Number.isInteger(v) && v > 1_600_000_000_000 && v < 4_000_000_000_000; }

// ---------- WebSocket（Node 22+ 内置全局 WebSocket，支持 close code） ----------
export function openWs(base, pathname) {
  const url = base.replace(/^http/, 'ws') + pathname;
  const ws = new WebSocket(url);
  const frames = [];
  const waiters = [];
  let cursor = 0;
  ws.addEventListener('message', ev => {
    let j = null; try { j = JSON.parse(ev.data); } catch { /* 记原文 */ }
    const rec = { raw: ev.data, json: j };
    frames.push(rec);
    cursor++; // 任何新帧都让游标前进（历史不可被后续 wait 重放）
    for (let i = waiters.length - 1; i >= 0; i--) {
      const w = waiters[i];
      if (w.match(j, ev.data)) { waiters.splice(i, 1); clearTimeout(w.timer); w.resolve(rec); }
    }
  });
  const closed = new Promise(res => ws.addEventListener('close', ev => res({ code: ev.code, reason: ev.reason })));

  const api = {
    ws, frames, closed,
    send: (o) => ws.send(typeof o === 'string' ? o : JSON.stringify(o)),
    /** 等待**后续**满足 match 的帧（不重放历史），超时返回 null */
    wait(match, timeoutMs = 10000) {
      const from = cursor;
      return new Promise(resolve => {
        const w = { match, resolve };
        w.timer = setTimeout(() => {
          const i = waiters.indexOf(w);
          if (i >= 0) waiters.splice(i, 1);
          resolve(null);
        }, timeoutMs);
        waiters.push(w);
      });
    },
    waitSince(match, fromIndex, timeoutMs = 10000) {
      for (let i = fromIndex; i < frames.length; i++) if (match(frames[i].json, frames[i].raw)) return Promise.resolve(frames[i]);
      return api.wait(match, timeoutMs);
    },
    mark: () => cursor,
    waitType: (t, ms = 10000) => api.wait(j => j && j.type === t, ms),
    clear: () => { frames.length = 0; cursor = 0; },
    close: () => { try { ws.close(); } catch { } },
  };
  return api;
}

export const sleep = ms => new Promise(r => setTimeout(r, ms));

/** 轮询直到 fn 返回真值或超时 */
export async function until(fn, timeoutMs = 20000, stepMs = 400) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) return null;
    await sleep(stepMs);
  }
}

export function arg(name, def = null) {
  const i = process.argv.indexOf('--' + name);
  if (i >= 0 && process.argv[i + 1] && !process.argv[i + 1].startsWith('--')) return process.argv[i + 1];
  const pre = name + '=';
  const hit = process.argv.find(a => a.startsWith(pre));
  if (hit) return hit.slice(pre.length);
  return def;
}
export const hasFlag = name => process.argv.includes('--' + name);
