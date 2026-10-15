// pc-gateway/lib/ws-hub.mjs — /rc/v1/stream WebSocket 集线器（契约 §3.3 + v1.1 GAP-09..14）
import { WebSocketServer } from 'ws';
import crypto from 'node:crypto';
import { RcError } from './errors.mjs';
import { eventsToMessages } from './dsh.mjs';
import { normalizeMode } from './prompt-mode.mjs';

const ERR_CODES = ['unauthorized', 'not_found', 'invalid_request', 'session_busy', 'dsh_unavailable', 'internal'];

/** 内层 follow 的持久事件帧 → 契约的 session.event 数组（不含流式帧） */
function eventsToContract(frame) {
  const out = [];
  if (frame.type !== 'event') return out;
  for (const r of eventsToMessages([frame.event])) {
    // F-5：与 REST 的 publicMessage 保持同一字段集，**键恒存在**，无值给 null。
    // App 才能安全地直接取键（省略键会 NPE）。
    const ev = {
      seq: r.seq, role: r.role, text: r.text,
      ts: r.ts, status: r.status, messageId: r.messageId,
      // 规范 §9.7：user 行带 durable requestId，供 App 崩溃恢复对账
      requestId: r.rpcId ?? null,
      kind: r.kind ?? 'message',
      reasoning: r.reasoning ?? null,
      hasReasoning: r.hasReasoning === true,
      toolName: r.toolName ?? null,
      toolCallId: r.toolCallId ?? null,
      toolInput: r.toolInput ?? null,
      toolOutput: r.toolOutput ?? null,
      isError: r.isError === true,
      collapsedSummary: r.collapsedSummary ?? null,
      // Images travel as REFERENCES only: pushing base64 over the socket would
      // blow up the connection. The App fetches bytes on demand via
      // GET /rc/v1/sessions/{id}/attachments/{aid}.
      images: Array.isArray(r.images) ? r.images : [],
    };
    out.push(ev);
  }
  return out;
}

/**
 * 内层 assistant-stream 帧 → 契约的 session.event 数组（**逐 token 流式**）。
 *
 * 内层帧（实测，见 pc-gateway/README.md §4.11）：
 *   start {attemptId, revision, startedAfterSeq, turn, step}
 *   chunk {attemptId, revision, index, time, chunk:{type:'text', text, index, blockType?}}
 *   end   {attemptId, revision, index, outcome:{kind:'committed', eventType, seq} | {kind:'abandoned'}}
 *
 * 契约 §3.3 的 `session.event.event` 只有 {kind, role, text, seq, ts, status, messageId}，
 * 没有「同一消息的多段增量」这一形状。**v1 不改契约字段名**，做法是：
 *   · chunk    → `{role:'assistant', text:<本段>, seq:<已累积长度>, status:'streaming'}`
 *                —— seq 用已累积字符数，保证**严格递增**，App 按 seq 追加天然去重；
 *   · end      → `{role:'assistant', text:<完整拼接>, seq:<最终长度>, status:'final'}`
 *                —— App 收到这条即可收尾（若随后还有 durable `assistant/message`，
 *                   那是同一条消息的权威版本，App 按 messageId/seq 覆盖即可）。
 * 文本与 seq 长度按 attemptId 维护在 state 中。
 */
export function streamFrameToEvents(frame, state) {
  const f = frame.frame ?? {};
  const out = [];
  const now = f.time ?? Date.now();

  if (f.type === 'start') {
    state.attemptId = f.attemptId;
    state.buf = '';
    state.nextSeq = 1;
    return out;                       // start 只是开窗信号，不发帧
  }

  if (f.type === 'chunk') {
    if (state.attemptId && f.attemptId !== state.attemptId) return out; // 交错推送的旧 attempt
    state.attemptId = f.attemptId;
    const c = f.chunk;
    const text = typeof c === 'string' ? c : (typeof c?.text === 'string' ? c.text : '');
    if (!text) return out;
    state.buf += text;
    out.push({
      kind: 'message', role: 'assistant', text,
      seq: state.nextSeq++, ts: now, status: 'streaming', messageId: null,
      // 需求 3：流式期间也告诉 App 这一段是 reasoning 还是正文，
      // 这样"思考中"的折叠块能实时长出来（否则只能等回合结束才看得到）。
      streamKind: c?.blockType === 'reasoning' || f.blockType === 'reasoning' ? 'reasoning' : 'message',
    });
    return out;
  }

  if (f.type === 'end') {
    if (state.attemptId && f.attemptId !== state.attemptId) return out;
    const committed = f.outcome?.kind === 'committed';
    if (!state.buf) { state.attemptId = null; state.buf = ''; state.nextSeq = 1; return out; }
    out.push({
      kind: 'message', role: 'assistant', text: state.buf,
      seq: state.nextSeq++, ts: now, status: committed ? 'final' : 'failed', messageId: null,
    });
    state.attemptId = null; state.buf = ''; state.nextSeq = 1;
    return out;
  }

  return out;
}

export class WsHub {
  constructor(opts) {
    this.opts = opts;
    /** @type {Set<any>} 全部连接 */
    this.clients = new Set();
    /** @type {Map<any, Set<string>>} 连接 → 它订阅的会话 */
    this.subs = new Map();
    /** @type {Map<string, Set<any>>} 会话 → 订阅该会话的连接 */
    this.bySession = new Map();
    /** @type {Map<string, {abort: AbortController, run: number}>} 会话 → **唯一**的 follow 循环 */
    this.sessionFollows = new Map();
    /** @type {Map<any, Map<string, Set<number>>>} 连接 → (会话 → 已推送 seq)，防重复 */
    this.emitted = new Map();
    this.testHooks = false;
    this.log = opts.log ?? (() => {});
  }

  /** 挂到已有 http server 上（GAP-09：自行处理 upgrade） */
  attach(httpServer, path = '/rc/v1/stream') {
    this.wss = new WebSocketServer({ noServer: true });
    httpServer.on('upgrade', (req, socket, head) => {
      let url;
      try { url = new URL(req.url, 'http://x'); } catch { socket.destroy(); return; }
      if (url.pathname !== path) { socket.destroy(); return; }
      this.wss.handleUpgrade(req, socket, head, ws => this.#onConnection(ws, url));
    });
  }

  #seen(ws, sessionId) {
    let outer = this.emitted.get(ws);
    if (!outer) { outer = new Map(); this.emitted.set(ws, outer); }
    let s = outer.get(sessionId);
    if (!s) { s = new Set(); outer.set(sessionId, s); }
    return s;
  }

  #onConnection(ws, url) {
    const token = url.searchParams.get('token');
    // GAP-09：先完成 upgrade，随后立即 close(4401)，不发任何帧
    if (!this.opts.tokenStore.equals(token)) {
      try { ws.close(4401, 'unauthorized'); } catch { /* ignore */ }
      return;
    }
    this.clients.add(ws);
    this.subs.set(ws, new Set());
    // Read-only diagnostics: WS connection lifetime.
    this.opts.requestLog?.recordWs('connect');

    ws.on('message', raw => this.#onMessage(ws, raw));
    ws.on('close', () => this.#teardown(ws));
    ws.on('error', () => this.#teardown(ws));

    // 契约 §3.3：连接后立即发 hello，且 hello.sessions 与 REST 列表项同字段。
    const timeout = new Promise(res => setTimeout(() => res([]), 8000));
    const sessions = this.opts.dsh.listPage(50, null).then(p => p.items).catch(() => []);
    Promise.race([sessions, timeout]).then(items => {
      this.#send(ws, {
        v: 1, type: 'hello',
        server: { name: this.opts.serverName, version: this.opts.version },
        sessions: Array.isArray(items) ? items : [],
      });
    });
  }

  #teardown(ws) {
    this.clients.delete(ws);
    for (const sessionId of (this.subs.get(ws) ?? [])) {
      const set = this.bySession.get(sessionId);
      if (!set) continue;
      set.delete(ws);
      if (set.size === 0) this.#stopFollow(sessionId);
    }
    this.subs.delete(ws);
    this.emitted.delete(ws);
    // Read-only diagnostics: how many subscriptions this socket dropped.
    this.opts.requestLog?.recordWs('disconnect');
  }

  #send(ws, obj) {
    if (ws.readyState !== 1) return;
    try { ws.send(JSON.stringify(obj)); } catch { /* ignore */ }
  }

  /** 只发给请求发起的那条连接（GAP-13） */
  ack(ws, id, extra = {}) {
    this.#send(ws, { v: 1, type: 'ack', id: id ?? null, ok: true, ...extra });
  }

  err(ws, id, code, message) {
    this.#send(ws, { v: 1, type: 'error', id: id ?? null, code, message });
  }

  async #onMessage(ws, raw) {
    let msg;
    try { msg = JSON.parse(String(raw)); }
    catch { return this.err(ws, null, 'invalid_request', 'frame is not valid JSON'); }
    if (!msg || typeof msg !== 'object') return this.err(ws, null, 'invalid_request', 'frame must be a JSON object');
    const id = typeof msg.id === 'string' ? msg.id : null;
    // GAP-12：v != 1 → error，不断连
    if (msg.v !== 1) return this.err(ws, id, 'invalid_request', 'unsupported protocol version v (expected 1)');

    switch (msg.type) {
      case 'ping': return this.#send(ws, { v: 1, type: 'pong', ts: Date.now() });
      case 'pong': return;
      case 'subscribe': return this.#subscribe(ws, id, msg);
      case 'unsubscribe': return this.#unsubscribe(ws, id, msg);
      case 'prompt': return this.#prompt(ws, id, msg);
      case 'cancel': return this.#cancel(ws, id, msg);
      default:
        // GAP-11：未知 type → error，不静默丢弃，不断连
        return this.err(ws, id, 'invalid_request', `unknown frame type ${JSON.stringify(msg.type ?? null)}`);
    }
  }

  async #requireSession(sessionId) {
    if (typeof sessionId !== 'string' || sessionId.length === 0) throw new RcError('invalid_request', 'sessionId is required');
    const ok = await this.opts.dsh.exists(sessionId);
    if (!ok) throw new RcError('not_found', `session ${sessionId} not found`);
  }

  async #subscribe(ws, id, msg) {
    try {
      const sessionId = msg.sessionId;
      await this.#requireSession(sessionId);
      this.subs.get(ws)?.add(sessionId);
      if (!this.bySession.has(sessionId)) this.bySession.set(sessionId, new Set());
      const first = this.bySession.get(sessionId).size === 0;
      this.bySession.get(sessionId).add(ws);
      // Read-only diagnostics: this is the evidence for the subscription-leak
      // investigation (a client that subscribes without unsubscribing leaks).
      this.opts.requestLog?.recordWs('subscribe', sessionId);
      // 预热历史缓存：snapshot 到达时要用它回填「开窗未覆盖的更早消息」
      this.opts.dsh.history(sessionId).catch(() => null);
      if (first || !this.sessionFollows.has(sessionId)) this.#startFollow(sessionId);
      this.ack(ws, id);
    } catch (e) {
      this.err(ws, id, ERR_CODES.includes(e.rcCode) ? e.rcCode : 'internal', e.message);
    }
  }

  #unsubscribe(ws, id, msg) {
    const sessionId = msg.sessionId;
    this.subs.get(ws)?.delete(sessionId);
    const set = this.bySession.get(sessionId);
    if (set) { set.delete(ws); if (set.size === 0) this.#stopFollow(sessionId); }
    this.opts.requestLog?.recordWs('unsubscribe', sessionId);
    this.ack(ws, id);
  }

  #stopFollow(sessionId) {
    const f = this.sessionFollows.get(sessionId);
    if (f) { f.abort.abort(); this.sessionFollows.delete(sessionId); }
  }

  /**
   * **每个会话只开一条** follow 循环，广播给所有订阅者。
   *
   * 两个实测坑（详见 pc-gateway/README.md）：
   *  1. 同一会话开多条并发 follow 会互相干扰（实测 3 条并发时其中一条完全不吐帧），
   *     所以必须共享单条。
   *  2. 内层 `follow()` 的首帧 snapshot 只是**当前游标处的开窗**（新会话实测 cursor=6），
   *     不含已持久化的 prompt 消息；若之后无新事件就永远不吐。所以 snapshot 到达时
   *     用网关自己的历史缓存把「更早的消息」补推，并按 seq 去重。
   */
  #startFollow(sessionId) {
    if (this.sessionFollows.has(sessionId)) return;
    const abort = new AbortController();
    const run = (this.sessionFollows.get(sessionId)?.run ?? 0) + 1;
    // 流式状态：整个 follow 循环共享一份（同一 attempt 的 chunk 需按序拼接后再广播）
    const stream = { attemptId: null, buf: '', nextSeq: 1 };
    this.sessionFollows.set(sessionId, { abort, run, stream });
    (async () => {
      try {
        for await (const frame of this.opts.dsh.follow(sessionId, abort.signal)) {
          if (abort.signal.aborted) break;
          const cur = this.sessionFollows.get(sessionId);
          if (!cur || cur.run !== run) break;              // 已被更新的循环取代
          const subs = this.bySession.get(sessionId);
          if (!subs || subs.size === 0) break;

          if (frame.type === 'snapshot') {
            const cursor = frame.cursor ?? -1;
            const older = this.opts.dsh.cacheFor(sessionId) ?? [];
            for (const ws of subs) {
              if (ws.readyState !== 1) continue;
              const seen = this.#seen(ws, sessionId);
              for (const row of older) {
                if (row.seq >= cursor || seen.has(row.seq)) continue;
                seen.add(row.seq);
                this.#send(ws, {
                  v: 1, type: 'session.event', sessionId,
                  event: {
                    kind: 'message', role: row.role, text: row.text,
                    seq: row.seq, ts: row.ts, status: row.status, messageId: row.messageId,
                    requestId: row.rpcId ?? null,
                  },
                });
              }
            }
            const st = await this.opts.dsh.statusOf(sessionId).catch(() => null);
            this.#broadcastStatus(sessionId, st?.running ? 'running' : 'idle');

          } else if (frame.type === 'assistant-stream') {
            // 逐 token 流式：不进 #seen（seq 是字符位置，不是会话事件序号）
            for (const ev of streamFrameToEvents(frame, stream)) {
              for (const ws of subs) {
                if (ws.readyState !== 1) continue;
                this.#send(ws, { v: 1, type: 'session.event', sessionId, event: ev });
              }
            }

          } else {
            for (const ev of eventsToContract(frame)) {
              for (const ws of subs) {
                if (ws.readyState !== 1) continue;
                if (Number.isInteger(ev.seq) && ev.seq >= 0) this.#seen(ws, sessionId).add(ev.seq);
                this.#send(ws, { v: 1, type: 'session.event', sessionId, event: ev });
              }
            }
          }
        }
      } catch (e) { this.log(`[rc-gateway] follow(${sessionId}) 结束: ${e?.message ?? e}`); }
      finally {
        const cur = this.sessionFollows.get(sessionId);
        if (cur?.run === run) this.sessionFollows.delete(sessionId);
      }
    })();
  }

  #broadcastStatus(sessionId, status) {
    const frame = { v: 1, type: 'session.status', sessionId, status, ts: Date.now() };
    for (const ws of this.clients) this.#send(ws, frame);
  }

  async #prompt(ws, id, msg) {
    const store = this.opts.tokenStore;
    try {
      if (typeof msg.requestId !== 'string' || msg.requestId.length === 0) throw new RcError('invalid_request', 'requestId is required');      if (typeof msg.text !== 'string' || msg.text.trim().length === 0) throw new RcError('invalid_request', 'text must be a non-empty string');
      await this.#requireSession(msg.sessionId);
      if (this.testHooks && store.consumeBusy(msg.sessionId)) {
        throw new RcError('session_busy', 'session is held by another DSH instance (test hook)');
      }
      const prev = store.lookupFor(msg.sessionId, msg.requestId);
      if (prev) {
        // 幂等重放：GAP-10 对账字段
        return this.ack(ws, id, { sessionId: prev.sessionId, requestId: msg.requestId, messageId: prev.messageId });
      }
      const messageId = crypto.randomUUID();
      // F-1：WS 通道必须与 REST 用**同一套 mode 校验**（见 http.mjs 的 normalizeMode），
      // 否则非法 mode 会被静默降级为 steer，用户"排队不打断"的意图失效。
      const mode = normalizeMode(msg.mode);
      await this.opts.dsh.prompt(msg.sessionId, msg.text, msg.requestId, mode);
      store.remember(msg.requestId, messageId, msg.sessionId);
      this.ack(ws, id, { sessionId: msg.sessionId, requestId: msg.requestId, messageId, mode });
      this.#broadcastStatus(msg.sessionId, 'running');
    } catch (e) {
      this.err(ws, id, ERR_CODES.includes(e.rcCode) ? e.rcCode : 'internal', e.message);
    }
  }

  async #cancel(ws, id, msg) {
    try {
      await this.#requireSession(msg.sessionId);
      await this.opts.dsh.cancel(msg.sessionId); // GAP-08 幂等
      this.ack(ws, id, { sessionId: msg.sessionId });
      this.#broadcastStatus(msg.sessionId, 'idle');
    } catch (e) {
      this.err(ws, id, ERR_CODES.includes(e.rcCode) ? e.rcCode : 'internal', e.message);
    }
  }

  /** REST 侧写入成功后广播状态变化 */
  broadcastStatus(sessionId, status) { this.#broadcastStatus(sessionId, status); }

  async close() {
    for (const f of this.sessionFollows.values()) f.abort.abort();
    this.sessionFollows.clear();
  }
}
