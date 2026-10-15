// pc-gateway/lib/http.mjs -- REST endpoints (contract sec 3.2 + v1.1 GAP-03..08/18/20/22).
//
// DISCIPLINE: this file is pure ASCII on purpose. Writing UTF-8 sources through
// PowerShell Set-Content / Add-Content / Out-File corrupts them (GBK decoding
// swallows the following statement into a comment). Use the write/edit tools.
import http from 'node:http';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import nodeChildProcess from 'node:child_process';
import { RcError, badRequest, unauthorized, notFound, mapInnerError } from './errors.mjs';
import { normalizeMode } from './prompt-mode.mjs';
import { WsHub } from './ws-hub.mjs';
import { publicMessage } from './dsh.mjs';
import { RequestLog, pathInfo } from './request-log.mjs';
import { workspacesFromStorage, workspacesFromSessions, countSessionsByPath, withLiveSessionCount } from './workspaces.mjs';
import { SearchIndex } from './search-index.mjs';

/**
 * Persistent full-text search index over the durable session logs (2026-10-13,
 * user request 4: global keyword search that jumps straight to the hit).
 * Built lazily; a background pass starts on first gateway creation and a
 * throttled stat pass refreshes it every 30s on query.
 */
let searchIndex = null;
export function searchIndexOf() { return searchIndex; }

/** Create (once) and kick the background first pass of the search index. */
function createSearchIndex(dsh) {
  if (searchIndex) return searchIndex;
  searchIndex = new SearchIndex(stateDir, async () => {
    // Session metadata for result titles: prefer the SWR snapshot (instant);
    // fall back to a live walk if the snapshot has never been built.
    try { return await allSessionsSWR(dsh); }
    catch { return []; }
  });
  searchIndex.start();
  return searchIndex;
}

/** PC workspace storage (same source of truth as the desktop app: ~/.dsh). */
const WORKSPACE_FILE = process.env.DSH_RC_WORKSPACE_FILE
  ?? path.join(os.homedir(), '.dsh', 'storages', 'workspace.json');

export const VERSION = '1.0.0';

/** Where newly created sessions are logged (injected by index.js via setStateDir). */
let stateDir = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..', '.state');
export function setStateDir(dir) { stateDir = dir; }

const V = { v: 1 };
const now = () => Date.now();

/** GAP-03: a non-integer limit, or one outside 1..200, is a 400. */
function parseLimit(raw, def = 50) {
  if (raw === undefined || raw === null || raw === '') return def;
  const s = String(raw);
  if (!/^-?\d+$/.test(s)) throw badRequest(`limit must be an integer between 1 and 200 (got ${JSON.stringify(raw)})`);
  const n = Number(s);
  if (!Number.isInteger(n) || n < 1 || n > 200) throw badRequest(`limit must be an integer between 1 and 200 (got ${n})`);
  return n;
}

function parseBefore(raw) {
  if (raw === undefined || raw === null || raw === '') return undefined;
  const s = String(raw);
  if (!/^-?\d+$/.test(s)) throw badRequest(`before must be a non-negative integer (got ${JSON.stringify(raw)})`);
  const n = Number(s);
  if (!Number.isInteger(n) || n < 0) throw badRequest(`before must be a non-negative integer (got ${n})`);
  return n;
}

async function readJson(req, limitBytes = 1 << 20) {
  const chunks = [];
  let size = 0;
  for await (const c of req) {
    size += c.length;
    if (size > limitBytes) throw badRequest('request body too large');
    chunks.push(c);
  }
  const raw = Buffer.concat(chunks).toString('utf8');
  if (raw.trim().length === 0) return {};
  try { return JSON.parse(raw); }
  catch { throw badRequest('request body is not valid JSON'); }
}

function bearer(req, url) {
  const h = req.headers.authorization;
  if (typeof h === 'string') {
    const m = /^Bearer\s+(.+)$/i.exec(h.trim());
    if (m) return m[1].trim();
  }
  return url.searchParams.get('token') ?? '';
}

/**
 * Ledger of sessions this gateway created, written to
 * `pc-gateway/.state/test-created-sessions.json`.
 *
 * Background: a self-test once created 126 sessions and drove 97 real model
 * turns, polluting the user's session list. Any session created through this
 * gateway MUST be logged so it can be audited and cleaned up later (the PM
 * requires that we never delete them ourselves).
 */
export function recordCreatedSession(sessionId, cwd) {
  const file = path.join(stateDir, 'test-created-sessions.json');
  try {
    fs.mkdirSync(stateDir, { recursive: true });
    let arr = [];
    try {
      const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (Array.isArray(raw)) arr = raw;
    } catch { /* first write */ }
    if (arr.some(x => x?.id === sessionId)) return;
    arr.push({ id: sessionId, createdAt: Date.now(), cwd: cwd ?? null, source: 'gateway:POST /rc/v1/sessions' });
    fs.writeFileSync(file, JSON.stringify(arr, null, 2), 'utf8');
  } catch (e) {
    // A ledger failure must never break the main flow (the session exists either way).
    console.warn(`[rc-gateway] failed to record created session ${sessionId}: ${e?.message ?? e}`);
  }
}

function send(res, status, body) {
  const buf = Buffer.from(JSON.stringify(body), 'utf8');
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': buf.length,
    'cache-control': 'no-store',
  });
  res.end(buf);
}

/**
 * Records every response on its way out, then delegates to `send`.
 *
 * `reqLog`/`req` are bound by [bindRecorder] inside the request handler, so call
 * sites read exactly like before. Recording is wrapped: a diagnostics bug can
 * never turn a good response into a failure.
 */
let activeLog = null;
let activeReq = null;
function bindRecorder(log, req) { activeLog = log; activeReq = req; }

function recordingSend(res, status, body) {
  try {
    if (activeLog && activeReq) {
      const info = pathInfo(activeReq.url || '');
      activeLog.record(activeReq.method || '?', info.path, status, info.sessionId);
    }
  } catch {
    // diagnostics must never break the response
  }
  return send(res, status, body);
}

function errBody(e) {
  return { v: 1, error: { code: e.rcCode ?? 'internal', message: e.message ?? 'internal error' } };
}

/**
 * Virtual-adapter name patterns (case-insensitive).
 *
 * Why: the enumeration order of `os.networkInterfaces()` is not guaranteed, and
 * VMware VMnet8/VMnet1 usually appear before the real WLAN. Taking "the first
 * non-loopback IPv4" therefore lands on an address the phone cannot reach, so
 * QR pairing fails even though pairing code generation itself is correct.
 */
export const VIRTUAL_IFACE_PATTERNS =
  /vmware|virtualbox|vbox|hyper-v|vethernet|veth|loopback|docker|wsl|tap|npcap|bluetooth|zerotier|tailscale|hamachi|radmin|sandbox/i;

/** Same /24 subnet. */
function sameSubnet24(a, b) {
  const pa = a.split('.').slice(0, 3).join('.');
  const pb = b.split('.').slice(0, 3).join('.');
  return pa === pb;
}

/**
 * Next hop of the default route(s), i.e. the gateways that appear on lines of the
 * form `0.0.0.0  0.0.0.0  <gateway>  <ifIndex>`. Empty on failure (harmless).
 */
export function defaultRouteNextHops() {
  // Windows: `route print -0` is unsupported (prints usage, non-zero exit) => use `-4`.
  for (const args of [['print', '-4'], ['print']]) {
    try {
      const out = nodeChildProcess.execFileSync('route', args, { encoding: 'utf8', timeout: 5000, stdio: ['ignore', 'pipe', 'ignore'] });
      const hops = [...out.matchAll(/^\s*0\.0\.0\.0\s+0\.0\.0\.0\s+(\d+\.\d+\.\d+\.\d+)/gm)].map(m => m[1]);
      if (hops.length) return hops;
    } catch { /* try the next form */ }
  }
  return [];
}

/**
 * One candidate IPv4 address plus its verdict.
 * @param {string} name interface name
 * @param {string} address IPv4
 * @param {boolean} internal is loopback
 * @param {boolean} hasDefaultRoute does this NIC's subnet carry the default route (real LAN, preferred)
 * @param {string[]} reasons why it was excluded / how it was chosen (printed at startup)
 */
function candidate(name, address, internal, hasDefaultRoute, reasons) {
  const virtual = VIRTUAL_IFACE_PATTERNS.test(name);
  const linkLocal = /^169\.254\./.test(address);
  const excluded = internal ? 'loopback' : linkLocal ? 'link-local 169.254.*' : virtual ? 'virtual adapter' : null;
  return { name, address, internal, hasDefaultRoute, virtual, linkLocal, excluded, reasons };
}

/** Candidate ordering: usable first; real NIC with a default route beats virtual/no-route. */
function score(c) {
  if (c.excluded) return -1;
  return (c.hasDefaultRoute ? 100 : 0) + (c.virtual ? 0 : 10);
}

/**
 * List every candidate and pick the one the phone can actually reach (spec v1.2).
 * @param {{ifaces?: object, defaultRoutes?: string[]}} [opts] injectable, for unit tests
 * @returns {{candidates: object[], selected: object|null}}
 */
export function selectLanIpv4(opts = {}) {
  const ifaces = opts.ifaces ?? os.networkInterfaces();
  const defaultRoutes = opts.defaultRoutes ?? defaultRouteNextHops();
  const out = [];
  for (const [name, list] of Object.entries(ifaces)) {
    for (const ni of list ?? []) {
      if (ni.family !== 'IPv4' && ni.family !== 4) continue;
      const hasDefaultRoute = defaultRoutes.some(r => sameSubnet24(r, ni.address));
      const reasons = [];
      if (ni.internal) reasons.push('loopback');
      if (/^169\.254\./.test(ni.address)) reasons.push('link-local 169.254.*');
      if (VIRTUAL_IFACE_PATTERNS.test(name)) reasons.push(`virtual adapter (iface name matches ${name})`);
      if (reasons.length === 0) reasons.push(hasDefaultRoute ? 'real LAN with a default route' : 'usable but no default route');
      out.push(candidate(name, ni.address, !!ni.internal, hasDefaultRoute, reasons));
    }
  }
  const usable = out.filter(c => !c.excluded);
  usable.sort((a, b) => score(b) - score(a));
  return { candidates: out, selected: usable[0] ?? null };
}

/** Startup banner: every candidate, the chosen one, and the reason (spec v1.2 sec.2). */
export function describeLanIpv4(result) {
  const lines = ['[rc-gateway] LAN IPv4 candidates (the phone can only reach the last one):'];
  for (const c of result.candidates) {
    const mark = c.excluded ? 'x' : (result.selected && c.address === result.selected.address ? '*' : ' ');
    const why = c.excluded ? `excluded: ${c.excluded}` : (c.reasons.join('; ') || 'usable');
    lines.push(`  ${mark} ${c.address.padEnd(16)} ${String(c.name).padEnd(12)} ${why}`);
  }
  if (result.selected) {
    const s = result.selected;
    lines.push(`  => selected ${s.address} (${s.name}): ${s.reasons.join('; ') || 'usable and highest priority'}`);
  } else {
    lines.push('  => no usable address; listening on 127.0.0.1 (the phone cannot connect). '
      + 'Pass -LanHost <ip> at startup to choose one explicitly.');
  }
  return lines.join('\n');
}

/** Convenience accessor: the final host used by the QR code / state file. */
export function lanIpv4(override) {
  if (override) return override;
  return selectLanIpv4().selected?.address ?? '127.0.0.1';
}

/**
 * Workspace list.
 *  1. Authoritative path: identity, title and order come from `workspace.json`
 *     (the same data source as the desktop GUI).
 *  2. Fallback on failure: group by session `cwd`, so the App never shows a blank
 *     screen (requirement sec 3.2).
 *  3. `sessionCount` is computed live, never from the stored `sessionIds`
 *     snapshot (that snapshot is stale: 11 of 12 workspaces were wrong, e.g.
 *     tiga-survivor_v1 stored 4 while the real count was 47). Walking every page
 *     is mandatory, with a 5s TTL so we do not hammer the inner API.
 */
const WORKSPACE_COUNT_TTL_MS = 5_000;
let sessionCountCache = { at: 0, counts: null };

/**
 * Walk every session row (no enrichment).
 *
 * Counting workspaces only needs `cwd`, so this deliberately avoids the
 * per-row `inspect()` that `listPage(enrich=true)` performs: with 150+ sessions
 * that turned `/workspaces` into a multi-second request (user report: "entering
 * the app's workspace list takes forever to load").
 */
async function fetchAllSessions(dsh) {
  const all = [];
  let cursor = null;
  for (let i = 0; i < 500; i++) {
    const page = await dsh.listPage(200, cursor, null, false);
    all.push(...page.items);
    if (!page.nextCursor) break;
    cursor = page.nextCursor;
  }
  return all;
}

/**
 * Stale-while-revalidate snapshot of the full session list (2026-10-13, user
 * request 1/2: "must never stall"). First call after boot serves the DISK
 * snapshot (~2ms) and refreshes in the background; every later call serves the
 * in-memory snapshot (0ms) until the background walk completes. `updatedAt`
 * ordering is preserved because the snapshot IS the walked list.
 */
const LIST_SNAPSHOT_FILE = () => path.join(stateDir, 'sessions-snapshot.json');
let listSnapshot = { at: 0, rows: null };
let listRefreshing = null;

function snapshotRows() { return listSnapshot.rows; }

async function refreshListSnapshot(dsh) {
  if (listRefreshing) return listRefreshing;
  listRefreshing = (async () => {
    try {
      const rows = await fetchAllSessions(dsh);
      listSnapshot = { at: Date.now(), rows };
      try { await fs.promises.writeFile(LIST_SNAPSHOT_FILE(), JSON.stringify({ v: 1, at: listSnapshot.at, rows })); }
      catch { /* snapshot persistence is best-effort */ }
    } catch { /* keep previous snapshot */ }
    finally { listRefreshing = null; }
  })();
  return listRefreshing;
}

/**
 * Full session list with SWR semantics:
 *  1. memory snapshot (fresh or stale) -> return immediately,
 *     kick a background refresh if older than TTL.
 *  2. no memory snapshot -> try the disk snapshot (instant, survives restarts),
 *     kick a background refresh.
 *  3. nothing at all -> await the first walk (cold boot, one-time).
 */
async function allSessionsSWR(dsh, { ttlMs = 30_000 } = {}) {
  const nowMs = Date.now();
  if (listSnapshot.rows) {
    if (nowMs - listSnapshot.at > ttlMs && !listRefreshing) refreshListSnapshot(dsh);
    return listSnapshot.rows;
  }
  try {
    const raw = await fs.promises.readFile(LIST_SNAPSHOT_FILE(), 'utf8');
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed?.rows)) {
      listSnapshot = { at: parsed.at ?? 0, rows: parsed.rows };
      if (Date.now() - listSnapshot.at > ttlMs && !listRefreshing) refreshListSnapshot(dsh);
      return listSnapshot.rows;
    }
  } catch { /* no disk snapshot yet */ }
  await refreshListSnapshot(dsh);
  return listSnapshot.rows ?? [];
}

async function listWorkspaces(dsh) {
  // 1) authoritative source
  let items = null;
  try {
    const raw = await fs.promises.readFile(WORKSPACE_FILE, 'utf8');
    const fromStorage = workspacesFromStorage(JSON.parse(raw));
    if (fromStorage.length > 0) items = fromStorage;
  } catch (e) {
    console.warn(`[rc-gateway] workspace.json unavailable (${e?.code ?? e?.message}); falling back to cwd grouping`);
  }

  // 2) fallback: group by session cwd
  if (items === null) {
    const all = await allSessionsSWR(dsh);
    return workspacesFromSessions(all);
  }

  // 3) live counts -- from the SWR snapshot (instant, stale-while-revalidate).
  try {
    const all = await allSessionsSWR(dsh);
    const counts = countSessionsByPath(all, items);
    return withLiveSessionCount(items, counts);
  } catch (e) {
    console.warn(`[rc-gateway] live session count failed (${e?.message}); keeping stored counts`);
    return items;
  }
}

export function createGateway({ dsh, tokenStore, host, port, defaultCwd, testHooks, log, lanIpv4Override, requestLog }) {
  const serverName = os.hostname();
  const startedAt = now();
  // Read-only request recorder (diagnostics). Default enabled so the phone's
  // request pattern can be verified without a device; disable with
  // `new RequestLog(2000, false)` or by passing a pre-built instance.
  const reqLog = requestLog ?? new RequestLog();
  const hub = new WsHub({ tokenStore, dsh, serverName, version: VERSION, log, requestLog: reqLog });
  hub.testHooks = !!testHooks;

  // Boot the persistent search index once per process (background first walk).
  createSearchIndex(dsh);

  const lan = selectLanIpv4();
  const peer = lanIpv4Override ?? lan.selected?.address ?? '127.0.0.1';

  const server = http.createServer(async (req, res) => {
    let url;
    try { url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`); }
    catch { return recordingSend(res, 400, errBody(badRequest('malformed request URL'))); }
    const p = url.pathname;

    try {
      // Bind the recorder for this request so every `recordingSend` below logs it.
      bindRecorder(reqLog, req);
      // ---- unauthenticated ----
      if (p === '/rc/v1/ping' && req.method === 'GET') {
        return recordingSend(res, 200, { v: 1, name: serverName, version: VERSION, pairRequired: true, ts: now() });
      }

      // ---- authenticate ----
      const token = bearer(req, url);
      if (!tokenStore.equals(token)) {
        // Record the rejected attempt too (without the token itself).
        activeLog?.record(req.method, pathInfo(p).path, 401, pathInfo(p).sessionId);
        throw unauthorized();
      }

      // ---- read-only request recorder (diagnostics) ----
      // Records what the client asked for; never affects the response.
      if (p === '/rc/v1/_debug/requests' && req.method === 'GET') {
        const since = url.searchParams.get('since');
        return recordingSend(res, 200, { v: 1, ...reqLog.since(since ? Number(since) : 0) });
      }
      if (p === '/rc/v1/_debug/requests/summary' && req.method === 'GET') {
        return recordingSend(res, 200, { v: 1, ...reqLog.summary() });
      }
      if (p === '/rc/v1/_debug/requests' && req.method === 'DELETE') {
        reqLog.clear();
        return recordingSend(res, 200, { v: 1, cleared: true });
      }

      // ---- status: 200 even when DSH is unavailable (GAP-18) ----
      if (p === '/rc/v1/status' && req.method === 'GET') {
        let ok = false, total = 0, running = 0;
        try {
          const s = await dsh.stats();
          ok = true; total = s.total; running = s.running;
        } catch { ok = false; }
        return recordingSend(res, 200, { v: 1, dsh: { ok }, sessions: { total, running }, uptimeMs: now() - startedAt, ts: now() });
      }

      // ---- session list (GAP-21, descending + opaque cursor) ----
      // `?workspace=<cwd>` filters by cwd BEFORE paging (additive, backward
      // compatible: omitting it changes nothing). Windows paths arrive URL-encoded
      // and are decoded by URLSearchParams, so backslashes/spaces/CJK all work.
      //
      // enrich=false by design (user report: "opening a workspace's session list
      // hangs"). Enriching one row costs a full `inspect()` of that session's
      // history, so a page of 50 used to mean 50 inspects (~2.5-3s) before the
      // App could render a single row. messageCount / lastMessagePreview are not
      // used by the sidebar, so the rows now ship immediately and the counts are
      // filled in lazily by the caller when it actually opens a session.
      // Pass `?enrich=1` to restore the old (slow, fully populated) behaviour.
      if (p === '/rc/v1/sessions' && req.method === 'GET') {
        const limit = parseLimit(url.searchParams.get('limit'));
        const cursor = url.searchParams.get('cursor');
        const workspace = url.searchParams.get('workspace');
        const enrich = url.searchParams.get('enrich') === '1';
        // SWR snapshot paging (2026-10-13): serve from the in-memory snapshot
        // and never block on `sc.list()` (user: "expanding a workspace must not
        // stall"). Snapshot ordering matches listRows() exactly, so the cursor
        // algebra below is byte-identical to the old path.
        const rows = [...(await allSessionsSWR(dsh))];
        rows.sort((a, b) => (b.updatedAt - a.updatedAt) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
        let after = rows;
        if (workspace && workspace.length > 0) after = after.filter(r => r.cwd === workspace);
        if (cursor !== undefined && cursor !== null && cursor !== '') {
          let dec = null;
          try {
            const [ua, id] = Buffer.from(cursor, 'base64url').toString('utf8').split('|');
            if (ua !== undefined && id !== undefined) dec = { updatedAt: Number(ua), id };
          } catch { dec = null; }
          if (dec === null || !Number.isFinite(dec.updatedAt)) throw badRequest('cursor is not a valid opaque pagination cursor');
          after = after.filter(r => r.updatedAt < dec.updatedAt
            || (r.updatedAt === dec.updatedAt && r.id > dec.id));
        }
        const slice = after.slice(0, limit);
        const enriched = enrich ? await Promise.all(slice.map(r => dsh.enrichById(r.id, r))) : slice.map(() => null);
        const items = slice.map((r, i) => ({
          ...r,
          createdAt: enriched[i]?.createdAt ?? r.updatedAt,
          messageCount: enriched[i]?.messageCount ?? 0,
          lastMessagePreview: enriched[i]?.lastMessagePreview ?? '',
        }));
        const more = after.length > limit;
        const nextCursor = more
          ? Buffer.from(`${slice[slice.length - 1].updatedAt}|${slice[slice.length - 1].id}`).toString('base64url')
          : null;
        return recordingSend(res, 200, { v: 1, items, nextCursor });
      }

      // ---- workspaces (requirement sec 3.2; additive, breaks no existing endpoint) ----
      if (p === '/rc/v1/workspaces' && req.method === 'GET') {
        const items = await listWorkspaces(dsh);
        return recordingSend(res, 200, { v: 1, items });
      }

      // ---- create session (GAP-04: cwd absent/null is fine, nonexistent is a 400) ----
      if (p === '/rc/v1/sessions' && req.method === 'POST') {
        const body = await readJson(req);
        if (body.cwd !== null && body.cwd !== undefined && typeof body.cwd !== 'string') {
          throw badRequest('cwd must be a string or null');
        }
        if (body.title !== null && body.title !== undefined && typeof body.title !== 'string') {
          throw badRequest('title must be a string or null');
        }
        if (typeof body.cwd === 'string' && body.cwd.trim().length > 0) {
          if (!fs.existsSync(body.cwd)) throw badRequest(`cwd does not exist: ${body.cwd}`);
        }
        const id = await dsh.create(typeof body.cwd === 'string' && body.cwd.trim() ? body.cwd : defaultCwd);
        // Audit requirement: every session this gateway creates MUST be logged,
        // so it can be reconciled and cleaned up later without guessing.
        recordCreatedSession(id, typeof body.cwd === 'string' ? body.cwd : null);
        return recordingSend(res, 200, { v: 1, id });
      }

      // ---- messages (GAP-19) ----
      let m = /^\/rc\/v1\/sessions\/([^/]+)\/messages$/.exec(p);
      if (m && req.method === 'GET') {
        const id = decodeURIComponent(m[1]);
        const limit = parseLimit(url.searchParams.get('limit'));
        const before = parseBefore(url.searchParams.get('before'));
        // Requirement 3: `expand` selects how much reasoning/tool content is
        // included. It NEVER changes the row count -- only field truncation.
        // The App re-fetches with expand=full when the user expands a block.
        const expandRaw = url.searchParams.get('expand');
        const expand = expandRaw === 'none' || expandRaw === 'full' ? expandRaw : 'summary';
        // Optional waitRequestId: wait until that requestId is durable before
        // replying (an inner prompt only enqueues, it does not persist).
        const waitRequestId = url.searchParams.get('waitRequestId') ?? undefined;
        // 2026-10-13 incremental browsing: jump-to-seq window and downward pages.
        const aroundRaw = url.searchParams.get('aroundSeq');
        const aroundSeq = aroundRaw !== null && /^-?\d+$/.test(aroundRaw) ? Number(aroundRaw) : undefined;
        const afterRaw = url.searchParams.get('after');
        const after = afterRaw !== null && /^-?\d+$/.test(afterRaw) ? Number(afterRaw) : undefined;
        const r = await dsh.messages(id, limit, before, waitRequestId, expand, aroundSeq, after);
        const body = { v: 1, items: r.items.map(publicMessage), nextCursor: r.nextCursor };
        if (r.prevCursor !== undefined) body.prevCursor = r.prevCursor;
        if (r.nextAfter !== undefined) body.nextAfter = r.nextAfter;
        if (r.hasNewer !== undefined) body.hasNewer = r.hasNewer;
        return recordingSend(res, 200, body);
      }

      // ---- global search (2026-10-13, user request 4) ----
      // GET /rc/v1/search?q=<keywords>[&limit=20][&sessionId=<id>]
      // Literal substring search over the persistent index of all durable
      // session logs; title-only matches are included as hits without seq.
      if (p === '/rc/v1/search' && req.method === 'GET') {
        const q = url.searchParams.get('q');
        if (q === null || q.trim().length === 0) throw badRequest('q must not be empty');
        const limitRaw = url.searchParams.get('limit');
        const limit = limitRaw === null ? 20 : parseLimit(limitRaw, 20);
        const sessionId = url.searchParams.get('sessionId') ?? null;
        const idx = searchIndex ?? createSearchIndex(dsh);
        const result = await idx.query(q, { limit, sessionId });
        return recordingSend(res, 200, result);
      }

      // ---- images: read one durable attachment (requirement: images) ----
      // Source of truth: dsh-api-session-controller `attachment({sessionId,attachmentId})`
      // -> `{ attachment: ImageAttachmentRef, data: base64 }` (types.d.ts:332-341).
      // `expand` does NOT apply here: this endpoint always returns real bytes.
      // Oversized images are refused with 413, never silently truncated.
      let a = /^\/rc\/v1\/sessions\/([^/]+)\/attachments\/([^/]+)$/.exec(p);
      if (a && req.method === 'GET') {
        const sid = decodeURIComponent(a[1]);
        const aid = decodeURIComponent(a[2]);
        const v = await dsh.attachment(sid, aid);
        return recordingSend(res, 200, { v: 1, attachment: v.attachment, data: v.data });
      }

      // ---- prompt (GAP-05/06/07/10 + idempotency) ----
      m = /^\/rc\/v1\/sessions\/([^/]+)\/prompt$/.exec(p);
      if (m && req.method === 'POST') {
        const id = decodeURIComponent(m[1]);
        // GAP-06: 404 (path/resource) before 400 (body).
        // NOTE: without this explicit check the 404 only appeared by accident,
        // because the inner resolveAgent only reported not-found as a side effect.
        await dsh.requireSession(id);
        const body = await readJson(req);
        if (typeof body.text !== 'string') throw badRequest('text must be a string');
        if (body.text.trim().length === 0) throw badRequest('text must not be blank');
        if (typeof body.requestId !== 'string' || body.requestId.length === 0) throw badRequest('requestId is required');
        // mode: 'queue' | 'steer' (requirement 1; defaults to steer).
        // DSH contract (lib/types/session.d.ts:137-139): queue appends a turn,
        // steer interrupts it. And `Agent.steer`
        // (dsh-agent/lib/types/runtime-types.d.ts:194) documents
        // "An idle driver starts a turn" -- so steer both interrupts a running
        // turn AND wakes an idle session, whereas queue on an idle session just
        // sits in the inbox forever.
        // The user requires "the phone's message has top priority", hence the
        // default is steer when the field is omitted.
        // F-4: validation is shared with the WS channel (lib/prompt-mode.mjs);
        // an explicit `null` mode is invalid too => 400, consistent with
        // "", 1 and ["steer"].
        const mode = normalizeMode(body.mode);
        // Idempotency: the same requestId returns the same messageId.
        const prev = tokenStore.lookupFor(id, body.requestId);
        if (prev) return recordingSend(res, 200, { v: 1, accepted: true, messageId: prev.messageId, sessionId: prev.sessionId, mode });
        if (testHooks && tokenStore.consumeBusy(id)) throw new RcError('session_busy', 'session is held by another DSH instance (test hook)');
        const messageId = crypto.randomUUID();
        await dsh.prompt(id, body.text, body.requestId, mode);
        tokenStore.remember(body.requestId, messageId, id);
        hub.broadcastStatus(id, 'running');
        return recordingSend(res, 200, { v: 1, accepted: true, messageId, sessionId: id, mode });
      }

      // ---- cancel (GAP-06 404 first, GAP-08 idempotent 200) ----
      m = /^\/rc\/v1\/sessions\/([^/]+)\/cancel$/.exec(p);
      if (m && req.method === 'POST') {
        const id = decodeURIComponent(m[1]);
        await dsh.requireSession(id);
        await readJson(req).catch(() => ({}));
        await dsh.cancel(id);
        hub.broadcastStatus(id, 'idle');
        return recordingSend(res, 200, { v: 1, ok: true });
      }

      // ---- pair/rotate (GAP-01/02: old token dies immediately, connected clients reconnect) ----
      if (p === '/rc/v1/pair/rotate' && req.method === 'POST') {
        const t = tokenStore.rotate();
        return recordingSend(res, 200, { v: 1, token: t, fp: t.slice(0, 8), ts: now() });
      }

      // ---- GAP-22: test-gate for the 409 path, never registered unless enabled ----
      if (testHooks && p.startsWith('/rc/v1/_test/busy/') && req.method === 'POST') {
        const id = decodeURIComponent(p.slice('/rc/v1/_test/busy/'.length));
        // Arm twice by default so the REST and WS channels each hit it once.
        const times = Number(url.searchParams.get('times') ?? '2');
        tokenStore.armBusy(id, Number.isInteger(times) && times > 0 ? times : 2);
        return recordingSend(res, 200, { v: 1, armed: true, times: Number.isInteger(times) && times > 0 ? times : 2, sessionId: id });
      }

      // ---- GAP-22b: dry-run hook (validates fields and idempotency, never
      // submits to the model, never registered unless enabled) ----
      if (testHooks && p === '/rc/v1/_test/echo-prompt' && req.method === 'POST') {
        const body = await readJson(req);
        const sid = body.sessionId;
        const rid = body.requestId;
        if (typeof sid !== 'string' || sid.length === 0) throw badRequest('sessionId is required');
        if (typeof rid !== 'string' || rid.length === 0) throw badRequest('requestId is required');
        if (typeof body.text !== 'string') throw badRequest('text must be a string');
        if (body.text.trim().length === 0) throw badRequest('text must not be blank');
        // Idempotency ledger (same table as a real prompt) keeps behaviour aligned.
        const prev = tokenStore.lookupFor(sid, rid);
        if (prev) {
          return recordingSend(res, 200, { v: 1, accepted: true, messageId: prev.messageId, sessionId: sid, echoed: true });
        }
        const messageId = crypto.randomUUID();
        tokenStore.remember(rid, messageId, sid);
        return recordingSend(res, 200, { v: 1, accepted: true, messageId, sessionId: sid, echoed: true });
      }

      throw notFound(`no route for ${req.method} ${p}`);
    } catch (e) {
      const rc = mapInnerError(e);
      // 500s were completely silent before: mapInnerError drops the inner cause
      // chain, so "Session projection read failed" carried no diagnostics at all.
      // Append the cause chain to .state/errors.log -- it is the only place the
      // REAL inner error (e.g. SESSION_QUERY_CORRUPT_SESSION) survives.
      if (rc.http >= 500) {
        try {
          const chain = [];
          let cur = e;
          for (let i = 0; cur && i < 5; i++) {
            chain.push(`${cur.constructor?.name ?? 'Error'}: ${cur.message}`);
            cur = cur.cause;
          }
          const fsMod = await import('node:fs');
          fsMod.appendFileSync(
            new URL('../.state/errors.log', import.meta.url),
            `${new Date().toISOString()} ${rc.http} ${req.method} ${p} :: ${chain.join(' <- ')}\n`,
          );
        } catch { /* diagnostics must never mask the real error */ }
      }
      return recordingSend(res, rc.http, errBody(rc));
    }
  });

  hub.attach(server, '/rc/v1/stream');

  return {
    server, hub, serverName,
    async listen() {
      await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(port, host, resolve);
      });
      log?.(`[rc-gateway] listening on http://${host}:${port}  (ws: /rc/v1/stream)  testHooks=${!!testHooks}`);
      if (lanIpv4Override) {
        log?.(`[rc-gateway] LAN address: ${peer} (forced via -LanHost, skipping auto-selection)`);
      } else {
        log?.(describeLanIpv4(lan));
      }
    },
    async close() {
      try { await hub.close(); } catch { /* ignore */ }
      try { await new Promise(r => server.close(r)); } catch { /* ignore */ }
      try { hub.wss?.close(); } catch { /* ignore */ }
    },
    peer, lan,
  };
}
