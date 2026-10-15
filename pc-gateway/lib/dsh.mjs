// pc-gateway/lib/dsh.mjs — 对 ctx.sessionController 的适配层
//
// 本文件把 DSH 内层 SessionController 的真实返回结构翻译成契约 §3.2 的字段。
// 内层实测形状见 pc-gateway/README.md「内层 API 实测形状」。
import { badRequest, notFound, dshUnavailable, mapInnerError, RcError } from './errors.mjs';

const PREVIEW_MAX = 160;

/**
 * 历史全量缓存（`#infos`）的 TTL，毫秒。
 *
 * 用户问题 4：「长时间未打开应用后，点任何会话仍要快速显示最新对话」。
 * 根因是 `history()` 的缓存**永不失效**——只有 App 自己 prompt() 后才 invalidate，
 * 所以 PC 端活动产生的新消息，在网关眼里永远是旧快照。长时间后再点开会话，
 * App 拿到的是几小时前的历史，看起来就是「最新对话不显示」。
 *
 * 20s 的取舍：`messages()` 打开会话时会走这里，过期 ⇒ 重新 inspect 一次（单会话实测
 * 几十 ms），用户几乎无感；而 PC 端若有活动，App 点开会话 20s 内就能看到新内容。
 * 比「永久缓存」新鲜，比「每次必查」便宜。
 */
const HISTORY_TTL_MS = 20_000;

/**
 * Maximum attachment size the gateway will serve, in **bytes of decoded image**.
 *
 * Contract: `GET /rc/v1/sessions/{id}/attachments/{aid}` returns 413 when the
 * image exceeds this; it never silently truncates. 8 MiB comfortably holds a
 * high-resolution screenshot while keeping a single response bounded.
 */
export const ATTACHMENT_MAX_BYTES = 8 * 1024 * 1024;

/**
 * 内层事件 → 契约消息行。
 *
 * ## 向后兼容
 * `text` 字段**语义完全不变**（纯文本；工具仍是 `[tool] <name>`），老 App 不受影响。
 * 新增字段全部可选，老客户端会忽略。
 *
 * ## 字段来源（实测自 DSH 真实事件，非猜测）
 * - `assistant/message`：`data.message.content` 是**块数组**：
 *   `{type:'reasoning', text}` 与 `{type:'text', text}`。
 *   旧实现把非 text 块直接渲染成 `[reasoning]` 占位 ⇒ **内容被丢弃**（用户抱怨的根因）。
 * - `tool/call`：`{turn, step, callId, name, arguments}`，`arguments` 是 **JSON 字符串**。
 * - `tool/result`：`{turn, step, message:{role:'tool', source:{kind:'tool',callId}, toolCallId,
 *   content:[{type:'text',text}], isError}}`。
 *
 * @param {'none'|'summary'|'full'} expand
 *   `none`    = 只给折叠摘要（最小体积）
 *   `summary` = 默认；给摘要 + reasoning/工具输出的**截断**内容
 *   `full`    = 全文（App 点击"展开"时按需取）
 */
export function eventsToMessages(events, expand = 'summary') {
  const out = [];
  const callById = new Map();
  // ⚠ F-2（2026-10-12 P1）：`cap` 只截断**单条记录的字段内容**，
  // **绝不限制返回的行数** —— 行数只由 limit/before 分页游标决定。
  const FIELD_CAP = 2000;                 // 字符
  const clip = (s) => {
    const t = String(s ?? '');
    if (expand === 'none') return '';                  // none：完全不带内容字段
    if (expand === 'full' || t.length <= FIELD_CAP) return t;
    return t.slice(0, FIELD_CAP) + `…[truncated ${t.length}]`;
  };
  // F-3: the `none` level must ALSO clear toolInput, otherwise an 8KB+ raw
  // argument leaks out and the "none carries no big fields" promise breaks.
  const toolInputOf = (raw) => {
    if (expand === 'none') return null;                // F-3
    let input = raw ?? null;
    try { input = typeof input === 'string' ? JSON.parse(input) : input; } catch { /* keep raw */ }
    return input;
  };

  /**
   * Image references carried by a content block list (requirement: images).
   *
   * Source: `dsh-api-session-controller/lib/types/types.d.ts` -- assistant/user
   * content blocks can be `{type:'image', attachment: ImageAttachmentRef}`, and
   * `dsh-attachment/lib/types/types.d.ts` defines that ref as
   * `{attachmentId, mediaType, bytes, width, height, name?, originalDimensions?}`.
   *
   * `attachmentId` is documented as an OPAQUE storage identifier -- never parsed
   * here, only passed back to the read endpoint.
   *
   * Contract: an empty array means "no images"; the key is always present (F-5 rule).
   */
  const imagesOf = (blocks) => (blocks ?? [])
    .filter(b => b?.type === 'image' && b.attachment)
    .map(b => ({
      attachmentId: b.attachment.attachmentId ?? null,
      mediaType: b.attachment.mediaType ?? null,
      bytes: b.attachment.bytes ?? null,
      width: b.attachment.width ?? null,
      height: b.attachment.height ?? null,
      name: b.attachment.name ?? null,
    }));

  for (const ev of events) {
    const d = ev.data ?? {};
    switch (ev.type) {
      case 'user/message': {
        // source.kind !== 'user'（agent-instructions / runtime-context 等）是系统注入，
        // 按契约 §3.2 归为 role 'system'，否则 App 会把它们当用户消息重复渲染。
        out.push({
          seq: ev.seq, role: d.source?.kind === 'user' ? 'user' : 'system', ts: ev.time, status: 'final',
          messageId: d.id ?? null,
          // 内部字段：用于「等 requestId 落盘」，对外输出时剥离
          rpcId: d.source?.rpcId ?? null,
          kind: 'message',
          text: (d.content ?? []).map(p => (p.type === 'text' ? p.text : `[${p.type}]`)).join(''),
          collapsedSummary: preview((d.content ?? []).map(p => (p.type === 'text' ? p.text : `[${p.type}]`)).join(''), 120),
          images: imagesOf(d.content),          // image blocks are refs only (no bytes)
        });
        break;
      }
      case 'assistant/message': {
        const content = d.message?.content ?? [];
        const reasoning = content.filter(b => b.type === 'reasoning').map(b => b.text ?? '').join('\n');
        const text = content.filter(b => b.type !== 'reasoning' && b.type !== 'image').map(b => b.text ?? '').join('');
        out.push({
          seq: ev.seq, role: 'assistant', ts: ev.time, status: 'final',
          messageId: d.message?.id ?? null,
          kind: 'message',
          text: text || `[reasoning]`,          // semantics unchanged: still fall back
          reasoning: reasoning ? clip(reasoning) : null,   // full text (expand=full)
          hasReasoning: reasoning.length > 0,
          collapsedSummary: preview(text || reasoning, 120),
          images: imagesOf(content),
        });
        break;
      }
      case 'system/message': {
        out.push({
          seq: ev.seq, role: 'system', ts: ev.time, status: 'final',
          messageId: d.message?.id ?? null,
          kind: 'message',
          text: (d.message?.content ?? []).map(p => (p.type === 'text' ? p.text : `[${p.type}]`)).join(''),
          images: imagesOf(d.message?.content),
        });
        break;
      }
      case 'tool/call': {
        // arguments 是 JSON 字符串；解析失败就原样带出，不吞。
        const input = toolInputOf(d.arguments);
        const row = {
          seq: ev.seq, role: 'tool', ts: ev.time, status: 'final',
          messageId: null,
          kind: 'tool-call',
          text: `[tool] ${d.name ?? 'unknown'}`,    // ← 语义不变
          toolName: d.name ?? null,
          toolCallId: d.callId ?? null,
          toolInput: input,
          isError: false,
          collapsedSummary: `[tool] ${d.name ?? 'unknown'}`,
          images: [],                    // a tool call carries no message images
        };
        callById.set(d.callId, row);
        out.push(row);
        break;
      }
      case 'tool/result': {
        const msg = d.message ?? {};
        const callId = msg.toolCallId ?? msg.source?.callId ?? null;
        const text = (msg.content ?? []).map(p => (p.type === 'text' ? p.text : `[${p.type}]`)).join('');
        const failed = msg.isError === true;
        // 结果单独成行（App 用 toolCallId 与 call 行配对成一张可展开卡片）
        const row = {
          seq: ev.seq, role: 'tool', ts: ev.time, status: failed ? 'failed' : 'final',
          messageId: msg.id ?? null,
          kind: 'tool-result',
          text: `[tool] result`,                 // ← 语义不变（tool 行仍是摘要）
          toolCallId: callId,
          toolOutput: expand === 'none' ? null : clip(text),   // ★ 可展开内容
          isError: failed,
          collapsedSummary: preview(text, 120),
          images: imagesOf(msg.content),   // a tool result may echo image output
        };
        out.push(row);
        // 兼容旧行为：把 call 行的失败状态一并标记
        const call = callById.get(callId);
        if (call && failed) call.status = 'failed', call.isError = true;
        break;
      }
      default:
        break; // turn/step/session 元事件不进消息流
    }
  }
  return out;
}

/** 预览文本：单行、截断 */
export function preview(text, max = PREVIEW_MAX) {
  const s = String(text ?? '').replace(/\s+/g, ' ').trim();
  return s.length > max ? s.slice(0, max) : s;
}

export class DshAdapter {
  #sc;
  #enrichCache = new Map(); // sessionId -> {at, item}
  #infos = new Map();       // sessionId -> {messages, meta, at}  历史全量缓存（带 TTL）
  #inflight = new Map();

  constructor(sc) { this.#sc = sc; }

  get ok() { return !!this.#sc && typeof this.#sc.list === 'function'; }

  #signal() { return new AbortController().signal; }

  async #guard(fn) {
    if (!this.ok) throw dshUnavailable('inner DSH session controller is not ready');
    try { return await fn(); }
    catch (e) { throw mapInnerError(e); }
  }

  /** 会话是否存在（不激活 Agent）。内部用于 GAP-06「先 404 后 400」。 */
  async exists(sessionId) {
    return this.#guard(async () => {
      const p = await this.#sc.projections({ sessionId }, this.#signal());
      return p !== null && p !== undefined;
    });
  }

  /**
   * 冷读某会话的全部消息 + 元信息（inspect 实测返回 meta + 零基密集 events）。
   * @param {'none'|'summary'|'full'} expand 需求 3：折叠/展开的内容级别
   */
  async history(sessionId, expand = 'summary') {
    // 缓存按 expand 分级：展开态不污染默认（summary）缓存，反之亦然
    const cacheKey = `${expand}|${sessionId}`;
    const hit = this.#infos.get(cacheKey);
    // TTL 过期即重读（问题 4：长时间后再开会话必须看到最新内容，而不是旧快照）
    if (hit && Date.now() - hit.at < HISTORY_TTL_MS) return hit;
    if (this.#inflight.has(cacheKey)) return this.#inflight.get(cacheKey);
    const p = this.#guard(async () => {
      const insp = await this.#sc.inspect(sessionId, this.#signal());
      const messages = eventsToMessages(insp.events ?? [], expand);
      return { meta: insp.meta ?? {}, messages };
    });
    this.#inflight.set(cacheKey, p);
    try {
      const v = await p;
      this.#infos.set(cacheKey, { ...v, at: Date.now() });
      // 内存有界：会话数 × 3 档 expand，全量消息数组不便宜；超过 600 条即裁最老的。
      while (this.#infos.size > 600) {
        const oldest = this.#infos.keys().next().value;
        if (oldest === undefined) break;
        this.#infos.delete(oldest);
      }
      return v;
    } finally { this.#inflight.delete(cacheKey); }
  }

  /** 同步取已缓存的历史消息（供 WS 回填用；未缓存返回 null） */
  cacheFor(sessionId) {
    return this.#infos.get(`summary|${sessionId}`)?.messages
      ?? this.#infos.get(`full|${sessionId}`)?.messages
      ?? this.#infos.get(`none|${sessionId}`)?.messages
      ?? null;
  }

  /** 失效缓存：会话被写后必须重读（三个 expand 级别一起清） */
  invalidate(sessionId) {
    for (const e of ['none', 'summary', 'full']) this.#infos.delete(`${e}|${sessionId}`);
    this.#enrichCache.delete(sessionId);
  }

  /** 内部 SessionSummary → 契约列表项（含 createdAt/messageCount/lastMessagePreview） */
  #toItem(row, enriched) {
    const projections = row.projections?.values ?? {};
    const title = preview(projections.title ?? '', 120) || '(untitled)';
    const createdAt = enriched?.createdAt ?? row.updatedAt;
    return {
      id: row.sessionId,
      title,
      status: row.running ? 'running' : 'idle',
      createdAt,
      updatedAt: row.updatedAt,
      cwd: row.cwd ?? '',
      messageCount: enriched?.messageCount ?? 0,
      lastMessagePreview: enriched?.lastMessagePreview ?? '',
      agentAvailable: !!row.agentAvailable,
      // ── 子代理归属（App 端「子代理切换器」必需）────────────────────
      // 来源：DSH `SessionSummary` 本身就带这两个字段（实测 types.d.ts:166-167），
      // 之前网关**没有映射**，导致 App 拿不到父子关系、只能把子代理平铺到左栏。
      // 向后兼容：老 App 不认识这两个字段，多返回不影响。
      parentSessionId: row.parentSessionId ?? null,
      isSubagent: row.origin === 'subagent' || row.parentSessionId != null,
      subagentLabel: typeof projections.subagent?.identity?.label === 'string'
        ? projections.subagent.identity.label
        : null,
    };
  }

  /**
   * 会话列表（不做重活 inspect）。参数校验在调用方做（GAP-03/21）。
   * @returns {{items: object[], total: number}}
   */
  async listRows() {
    const res = await this.#guard(() => this.#sc.list({}, this.#signal()));
    const rows = res?.items ?? [];
    // GAP-21：按 updatedAt 降序
    rows.sort((a, b) => (b.updatedAt - a.updatedAt) || compareId(a.sessionId, b.sessionId));
    return rows;
  }

  /** 补齐某一行的 createdAt / messageCount / lastMessagePreview（带缓存 + 超时降级） */
  async enrich(row) {
    const hit = this.#enrichCache.get(row.sessionId);
    if (hit && Date.now() - hit.at < 60000) return hit.item;
    const timeout = new Promise(resolve => setTimeout(() => resolve(null), 4000));
    const work = this.history(row.sessionId).then(h => ({
      createdAt: h.meta.createdAt ?? row.updatedAt,
      messageCount: h.messages.length,
      lastMessagePreview: preview(h.messages.at(-1)?.text ?? '', PREVIEW_MAX),
    })).catch(() => null);
    const item = (await Promise.race([work, timeout])) ?? null;
    if (item) this.#enrichCache.set(row.sessionId, { at: Date.now(), item });
    return item;
  }

  /**
   * 取一页（已排序的行），带 cursor（opaque base64(`${updatedAt}|${id}`)）。
   *
   * ⚠ 实测坑（PM 2026-10-11 D-1）：早先用 `findIndex` 定位游标起点，在
   * 「每页重新调用 list()、行集合/顺序可能变化」时会跳行（实测 135 条只翻出 129 条）。
   * 现在改成**按游标过滤**（"严格排在游标之后的全部行" 再取前 limit 条），
   * 语义与排序共用同一个比较函数，构造上就不会漏行。
   */
  async listPage(limit, cursor, workspace, enrich = true) {
    let rows = await this.listRows();
    // Workspace filter (2026-12): applied BEFORE paging so the two are orthogonal --
    // a page of N is always N sessions *of that workspace*, and nextCursor keeps
    // walking the same filtered set. Omitted => full list, byte-identical to before.
    if (typeof workspace === 'string' && workspace.length > 0) {
      rows = rows.filter(r => r.cwd === workspace);
    }
    let after = rows;
    if (cursor !== undefined && cursor !== null) {
      const decoded = decodeCursor(cursor);
      if (decoded === null) throw badRequest('cursor is not a valid opaque pagination cursor');
      after = rows.filter(r => r.updatedAt < decoded.updatedAt
        || (r.updatedAt === decoded.updatedAt && compareId(r.sessionId, decoded.id) > 0));
    }
    const slice = after.slice(0, limit);
    // enrich=false: 列表页只需要行字段。每行一次 inspect() 全量历史是「展开工作区
    // 会话列表卡住」的根因（实测 153 会话 / 每页 2.5~3s），故默认关闭；
    // 需要预览的调用方显式传 true。
    const enriched = enrich ? await Promise.all(slice.map(r => this.enrich(r))) : slice.map(() => null);
    const items = slice.map((r, i) => this.#toItem(r, enriched[i]));
    // 规范 §9.6：还有更多未返回项时 nextCursor **必须非 null**。
    const more = after.length > limit;
    const nextCursor = more ? encodeCursor(slice[slice.length - 1].updatedAt, slice[slice.length - 1].sessionId) : null;
    return { items, total: rows.length, nextCursor };
  }

  /** 状态统计 */
  async stats() {
    const rows = await this.listRows();
    return { total: rows.length, running: rows.filter(r => r.running).length };
  }

  /** 会话行（轻量，供搜索索引映射 title/cwd/updatedAt）。 */
  async listRowsLight() {
    return this.listRows();
  }

  /** 按行对象补齐 enrich（供快照分页复用缓存与超时语义）。 */
  async enrichById(sessionId, rowLike) {
    return this.enrich({
      sessionId,
      updatedAt: rowLike?.updatedAt ?? 0,
    });
  }

  /**
   * 消息分页（GAP-19）：items 按 seq 升序；`before` = 只取 seq < before 的最新 limit 条；
   * nextCursor = 本页最小 seq（无更早则 null）。
   *
   * 2026-10-13 增量浏览（用户需求 3/4）：
   *  - `aroundSeq`：以该 seq 为中心的窗口（定位搜索命中 / 跳转到某条）。返回
   *    `prevCursor`（更早方向，= 窗口内最小 seq）与 `nextCursor`（更新方向）；
   *    `hasNewer` 标明窗口之后还有没有消息。
   *  - `after`：取 seq > after 的**最旧** limit 条（向下增量），nextCursor 语义同上。
   *  - 兼容：只传 limit ⇒ 仍是「最新 limit 条」（旧行为原样保留）。
   *
   * 实测坑：内层 `prompt()` 返回 `{accepted:true}` 时消息只是进了 inbox **队列**，
   * 尚未持久化（busy 会话里两条 prompt 只出现在 `projections.inbox['next-turn']`）。
   * 因此用 requestId 等它的 durable `user/message`（`source.rpcId`）真正落盘再返回，
   * 否则调用方 prompt 后立刻读 messages 会看不到自己刚发的消息。
   */
  async messages(sessionId, limit, before, waitRequestId = undefined, expand = 'summary', aroundSeq = undefined, after = undefined) {
    await this.requireSession(sessionId);
    if (waitRequestId) await this.#awaitDurable(sessionId, waitRequestId);
    const all = (await this.history(sessionId, expand)).messages;

    // ── 中心窗口（搜索定位 / seq 跳转）─────────────────────────
    if (aroundSeq !== undefined && aroundSeq !== null) {
      const idx = all.findIndex(m => m.seq >= aroundSeq);
      const anchor = idx === -1 ? all.length : idx;
      const start = Math.max(0, anchor - Math.floor(limit / 2));
      const page = all.slice(start, start + limit);
      const hasEarlier = start > 0;
      const hasNewer = start + limit < all.length;
      return {
        items: page,
        nextCursor: hasEarlier ? page[0]?.seq ?? null : null,   // 向上翻页游标（旧语义沿用）
        prevCursor: hasEarlier ? page[0]?.seq ?? null : null,
        nextAfter: hasNewer ? (page.at(-1)?.seq ?? null) : null, // 向下增量游标
        hasNewer,
      };
    }

    // ── 向下增量（seq > after 的最旧 limit 条）──────────────────
    if (after !== undefined && after !== null) {
      const page = all.filter(m => m.seq > after).slice(0, limit);
      const nextAfter = page.length === limit && all.some(m => m.seq > page[page.length - 1].seq)
        ? page[page.length - 1].seq : null;
      return { items: page, nextCursor: null, prevCursor: page[0]?.seq ?? null, nextAfter, hasNewer: nextAfter !== null };
    }

    // ── 旧行为：最新 limit 条 / before 向上翻页 ─────────────────
    const filtered = before === undefined || before === null
      ? all
      : all.filter(m => m.seq < before);
    const page = filtered.slice(Math.max(0, filtered.length - limit));
    const hasEarlier = page.length > 0 && page[0].seq > 0
      && all.some(m => m.seq < page[0].seq);
    return { items: page, nextCursor: hasEarlier ? page[0].seq : null };
  }

  /** 轮询等待某 requestId 的用户消息持久化（超时按现状返回，不阻塞） */
  async #awaitDurable(sessionId, requestId, timeoutMs = 12000) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const all = (await this.history(sessionId)).messages;
      if (all.some(m => m.role === 'user' && m.rpcId === requestId)) return true;
      if (Date.now() > deadline) return false;
      this.invalidate(sessionId);
      await new Promise(r => setTimeout(r, 400));
    }
  }

  /** GAP-06：先 404 再 400 —— 任何针对 {id} 的写操作先做存在性校验 */
  async requireSession(sessionId) {
    if (typeof sessionId !== 'string' || sessionId.length === 0) throw notFound('session not found');
    const ok = await this.exists(sessionId);
    if (!ok) throw notFound(`session ${sessionId} not found`);
  }

  async create(cwd) {
    const value = await this.#guard(() => this.#sc.create(cwd ? { cwd } : {}));
    return value.sessionId;
  }

  /**
   * 发送 prompt。
   *
   * ## mode：queue | steer（2026-10-12 用户需求 1）
   * DSH 契约（`lib/types/session.d.ts`）：`'queue' appends a turn; 'steer' interrupts the running one`。
   * 用户明确要求"**用户发送的消息优先级最高**"⇒ **默认 `steer`**，App 亦默认 steer。
   *
   * ## 关于 resume（已查清，保留但不是修复）
   * DSH 的 `prompt()` 内部**自己就会 resume**（`lib/types/commands.js:300` 的
   * `await this.resolveAgent(...)`，随后 `:330 agent.followup(message)`，
   * 而 `followup → send(msg,'next-turn',wakeup=true) → wakeDriver() → setPhase('running')`
   * 见 `dsh-agent-loop/lib/index.js:806/:854/:861`）。
   * 所以这里额外调一次 `resolveAgent` **不是**唤醒缺失的那一步；保留它是因为它能把
   * `session/writer-held` / `session/not-found` **提前**映射成正确的 HTTP 状态码，
   * 对 App 的三种失败文案（只读 / PC 端占用 / 真失败）很有用。
   *
   * 注：`SessionController.resolveAgent` 是 **throw 型**（`commands.js:479-484`
   * `if ('error' in found) throw found.error`），**永远不返回 `{error}`**，
   * 故下面必须用 try/catch 捕获，不能只判 `result.error`。
   */
  async prompt(sessionId, text, requestId, mode = 'steer') {
    // ① 预检查（只为拿到权威错误码；真正的 resume 在 DSH 的 prompt 内部）
    let busy = false;
    if (!this.ok) throw dshUnavailable('inner DSH session controller is not ready');
    try {
      const result = await this.#sc.resolveAgent(sessionId);
      // ── 诊断日志（2026-10-12）：resolveAgent 的真实返回形状必须可见 ──
      {
        const err = result?.error;
        let code = err?.code ?? err?.['~standard']?.['~code'] ?? null;
        let detail = '';
        try {
          const syms = err ? Object.getOwnPropertySymbols(err).map(String) : [];
          detail = JSON.stringify({
            sessionId, mode,
            hasAgent: !!result?.agent,
            hasError: !!err,
            errCode: code,
            errOwnKeys: err ? Object.keys(err) : null,
            errSymbolKeys: syms.length ? syms : null,
            errString: err ? String(err.message ?? err) : null,
          });
        } catch (e) {
          detail = `{"sessionId":"${sessionId}","logError":"${String(e?.message ?? e)}"}`;
        }
        console.log('[rc-gateway] resume', detail);
      }
      if (result && result.error) {
        const code = result.error.code ?? result.error?.['~standard']?.['~code'];
        if (code === 'session/agent-busy') busy = true;
        else if (code === 'session/writer-held') throw new RcError('session_busy', 'session is held by another DSH instance');
        else if (code === 'session/not-found') throw new RcError('not_found', `session ${sessionId} not found`);
        else busy = true;
      }
    } catch (e) {
      // resolveAgent uses THROW semantics -- this is where errors really appear.
      const raw = String(e?.message ?? e);
      console.log(`[rc-gateway] resume threw for ${sessionId} (mode=${mode}): ${raw}`);
      const code = e?.code ?? e?.['~standard']?.['~code'] ?? null;
      if (code === 'session/writer-held' || /writer-held/i.test(raw)) {
        throw new RcError('session_busy', 'session is held by another DSH instance');
      }
      if (code === 'session/not-found' || /not-found|not found/i.test(raw)) {
        throw new RcError('not_found', `session ${sessionId} not found`);
      }
      if (e instanceof RcError) throw e;
      busy = true;
    }
    void busy;

    // ② 投递
    await this.#guard(() => this.#sc.prompt({
      sessionId, requestId, mode,
      content: [{ type: 'text', text }],
    }, this.#signal()));
    this.invalidate(sessionId);
  }

  /**
   * Read one durable image attachment (requirement: images in messages).
   *
   * Source: `dsh-api-session-controller/lib/types/types.d.ts:332-341`
   *   SessionAttachmentRequest { sessionId, attachmentId }
   *   SessionAttachmentValue  { attachment: ImageAttachmentRef, data: string(base64) }
   * and `commands.d.ts:51-54` / `index.d.ts:166-169` for the method itself.
   *
   * `attachmentId` is an OPAQUE storage id (see dsh-attachment types): never parse it.
   *
   * @param {number} maxBytes refuse anything larger instead of silently truncating
   */
  async attachment(sessionId, attachmentId, maxBytes = ATTACHMENT_MAX_BYTES) {
    await this.requireSession(sessionId);
    const value = await this.#guard(() => this.#sc.attachment({ sessionId, attachmentId }));
    const ref = value?.attachment ?? {};
    // Size guard: base64 grows ~4/3, so compare on the decoded byte length that
    // DSH itself reports (`bytes`), and reject loudly rather than truncating.
    const declared = Number.isInteger(ref.bytes) ? ref.bytes : null;
    if (declared !== null && declared > maxBytes) {
      const err = new RcError('invalid_request', `attachment exceeds the ${maxBytes} byte limit`);
      err.http = 413;
      throw err;
    }
    const data = typeof value?.data === 'string' ? value.data : '';
    // Second guard in case `bytes` was missing: measure what actually arrived.
    const decodedBytes = Math.floor((data.length * 3) / 4);
    if (decodedBytes > maxBytes) {
      const err = new RcError('invalid_request', `attachment exceeds the ${maxBytes} byte limit`);
      err.http = 413;
      throw err;
    }
    return { attachment: ref, data };
  }

  /** Cancel is idempotent: only actually cancel when the session is running (GAP-08). */
  async cancel(sessionId) {
    const rows = await this.listRows();
    const row = rows.find(r => r.sessionId === sessionId);
    if (!row) {
      // 列表里没有不等于不存在（可能是 subagent 之外的边界），仍做一次存在性校验
      await this.requireSession(sessionId);
      return;
    }
    if (!row.running) return; // GAP-08：幂等 200
    try {
      await this.#guard(() => this.#sc.cancel({ sessionId }));
    } catch (e) {
      // 内层对「已无活动回合」抛 session/not-found → 契约要求幂等 200
      if (e?.code === 'not_found') return;
      throw e;
    }
  }

  /**
   * 订阅用：跟踪某会话的 live 事件（follow 快照 + 增量）。
   *
   * `assistantStream: true` 会额外产出 `{type:'assistant-stream', frame}`：
   *   start → 一次新的输出尝试开始
   *   chunk → 一段增量文本（`frame.chunk`）
   *   end   → 该次尝试收尾（committed 时带 `eventType` 与 `seq`，abandoned 表示无持久消息）
   * 这些是**进程内**瞬态帧（不落 session 日志），因此 seq 无意义，App 侧应按
   * 「attemptId + index」拼接，并在收到 committed 的 `assistant/message` 后以它为准。
   */
  async *follow(sessionId, signal) {
    const it = this.#sc.follow({ address: { kind: 'session', sessionId }, assistantStream: true }, signal);
    for await (const frame of it) yield frame;
  }

  /** 会话状态（running/agentAvailable） */
  async statusOf(sessionId) {
    const rows = await this.listRows();
    const row = rows.find(r => r.sessionId === sessionId);
    if (!row) return null;
    return { running: !!row.running, agentAvailable: !!row.agentAvailable };
  }
}

/**
 * 对外消息行（规范 §9.7）。
 *
 * 字段集 = 契约 §3.2 的六项 **+ `requestId`**：
 *  - `requestId` 取自 durable `source.rpcId`（用户消息才有，其余角色为 null），
 *    它是**崩溃恢复对账的权威键**；
 *  - `messageId` 语义不变 = 网关受理 id，仅用于同会话幂等重放，**不用于跨进程对账**
 *    （内层受理时不可能知道 durable id，详见 README §4.6）。
 */
/**
 * 对外消息行（规范 §9.7 + 需求 3 扩展字段）。
 *
 * 字段集 = 契约 §3.2 的六项 **+ `requestId`**：
 *  - `requestId` 取自 durable `source.rpcId`（用户消息才有，其余角色为 null），
 *    它是**崩溃恢复对账的权威键**；
 *  - `messageId` 语义不变 = 网关受理 id，仅用于同会话幂等重放，**不用于跨进程对账**
 *    （内层受理时不可能知道 durable id，详见 README §4.6）。
 *
 * ## F-5（2026-10-12 裁决）：扩展字段**键恒存在**
 * 需求 3 的 8 个扩展键（`kind`/`reasoning`/`hasReasoning`/`toolName`/`toolCallId`/
 * `toolInput`/`toolOutput`/`isError`/`collapsedSummary`）**必须永远出现在 JSON 里**，
 * 无值时给 `null`（或 `""`/`false`），**不得省略键**。
 * 原因：App 侧会用 `"key" in row` 或直接取键来判空，省略键会 NPE。
 */
const EXPAND_KEYS = [
  'kind', 'reasoning', 'hasReasoning', 'toolName', 'toolCallId',
  'toolInput', 'toolOutput', 'isError', 'collapsedSummary', 'images',
];

export function publicMessage(m) {
  const out = {
    // ---- 契约 §3.2 六项 + requestId（语义不变）----
    seq: m.seq,
    role: m.role,
    text: m.text,
    ts: m.ts,
    status: m.status,
    messageId: m.messageId,
    requestId: m.rpcId ?? null,
    // ---- 需求 3 扩展字段：键恒存在，无值给 null ----
    kind: m.kind ?? 'message',
    reasoning: m.reasoning ?? null,
    hasReasoning: m.hasReasoning === true,
    toolName: m.toolName ?? null,
    toolCallId: m.toolCallId ?? null,
    toolInput: m.toolInput ?? null,
    toolOutput: m.toolOutput ?? null,
    isError: m.isError === true,
    collapsedSummary: m.collapsedSummary ?? null,
    // Image references (never bytes). Always present: an empty array means
    // "this message has no images". Independent of `expand`, because it carries
    // no payload -- only an opaque id plus the intrinsic size metadata.
    images: Array.isArray(m.images) ? m.images : [],
  };
  return out;
}

/** 供自检/单测使用：列出契约保证恒存在的扩展键。 */
export const ALWAYS_PRESENT_KEYS = EXPAND_KEYS;

/**
 * id 的**确定性**字典序比较（纯码点比较）。
 *
 * ⚠ 实测坑（PM 2026-10-11 D-1）：排序里用 `localeCompare`，而游标定位用 `>`（码点序），
 * 两者对形如 `session-94ee…` 与 `4daeb9a6-…`（有无 `session-` 前缀）的混合 id 给出**不同顺序**
 * ⇒ 游标从中间切入时会跳过若干行（实测 135 条只能翻出 129 条，漏 6 条）。
 * 现在排序与定位共用这一个比较函数，保证「排序结果」与「游标语义」严格一致。
 */
function compareId(a, b) {
  const x = String(a); const y = String(b);
  return x < y ? -1 : x > y ? 1 : 0;
}

export function encodeCursor(updatedAt, id) {
  return Buffer.from(`${updatedAt}|${id}`, 'utf8').toString('base64url');
}

export function decodeCursor(cursor) {
  try {
    const s = Buffer.from(String(cursor), 'base64url').toString('utf8');
    const i = s.indexOf('|');
    if (i < 0) return null;
    const updatedAt = Number(s.slice(0, i));
    const id = s.slice(i + 1);
    if (!Number.isFinite(updatedAt) || !id) return null;
    return { updatedAt, id };
  } catch { return null; }
}
