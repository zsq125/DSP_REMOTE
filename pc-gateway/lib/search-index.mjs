// pc-gateway/lib/search-index.mjs -- persistent full-text index over the
// durable session logs (global search feature, user request 2026-10-13).
//
// Why not the inner SessionController.search()? That API requires the
// `dsh-session-query(-sqlite)` plugin to be mounted in the profile; this
// deployment (web profile) does not mount it, so `sc.search()` throws
// "session search is unavailable". Building our own index here keeps the
// deployment untouched.
//
// Design:
//  - Source of truth: `~/.dsh/sessions/<encoded-cwd>/<sessionId>/session.v4.jsonl.zstd`
//    (concatenated zstd frames, one frame per flush). We already proved this
//    layout is directly readable (session repair investigation).
//  - One partition file per session: `<stateDir>/search-index/<sessionId>.jsonl`.
//    Line 0 is the partition meta {sid, mtimeMs, size, count}; the rest are
//    compact row arrays [seq, role, ts, text] (text capped at ROW_TEXT_CAP).
//  - Refresh = stat every durable file; rebuild partitions whose mtime/size
//    changed; delete partitions for files that disappeared. Serialized through
//    a promise chain so concurrent queries never race a rebuild.
//  - Queries are case-insensitive literal substring (AND over whitespace
//    tokens). No FTS dependency, CJK works out of the box.
//  - Memory: rows are kept per session in a Map; ~100k rows x <=1500 chars is
//    tens of MB, acceptable inside the main process.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import zlib from 'node:zlib';
import { eventsToMessages, preview } from './dsh.mjs';

const ZSTD_MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);
const ROW_TEXT_CAP = 1500;          // searchable text per row (chars)
const MAX_ROWS_PER_SESSION = 20000; // defensive cap per session
const REFRESH_CHECK_INTERVAL_MS = 30_000;

const SESSIONS_ROOT = path.join(os.homedir(), '.dsh', 'sessions');

export class SearchIndex {
  #stateDir;
  #dir;                 // <stateDir>/search-index
  #metaPath;
  #getSessions;         // () => Promise<Array<{id,title,cwd,updatedAt}>>
  #rows = new Map();    // sessionId -> Array<[seq, role, ts, text]>
  #files = new Map();   // absPath -> {sid, mtimeMs, size, count}
  #complete = false;    // true once a full walk finished
  #loaded = null;       // promise: existing partitions read from disk
  #chain = Promise.resolve(); // serializes refresh passes
  #lastCheck = 0;

  constructor(stateDir, getSessions) {
    this.#stateDir = stateDir;
    this.#dir = path.join(stateDir, 'search-index');
    this.#metaPath = path.join(stateDir, 'search-meta.json');
    this.#getSessions = getSessions;
  }

  /** Load whatever partitions already exist on disk. Fast; never rebuilds. */
  load() {
    this.#loaded ??= (async () => {
      try {
        const meta = JSON.parse(await fs.promises.readFile(this.#metaPath, 'utf8'));
        this.#complete = meta.complete === true;
        for (const [abs, f] of Object.entries(meta.files ?? {})) this.#files.set(abs, f);
      } catch { /* first run: no meta yet */ }
      try { await fs.promises.mkdir(this.#dir, { recursive: true }); } catch { /* exists */ }
      let names = [];
      try { names = await fs.promises.readdir(this.#dir); } catch { /* absent */ }
      for (const name of names) {
        if (!name.endsWith('.jsonl')) continue;
        const sid = name.slice(0, -'.jsonl'.length);
        try { this.#rows.set(sid, await this.#readPartition(path.join(this.#dir, name))); }
        catch { /* unreadable partition: leave it out, next refresh rewrites it */ }
      }
    })();
    return this.#loaded;
  }

  /** Kick a full walk+rebuild in the background (fire and forget). */
  start() {
    this.load().then(() => this.#enqueue(true)).catch(() => {});
  }

  /**
   * Ensure the index is as fresh as the refresh interval allows, then run q.
   * Called per request: the stat pass is throttled and always non-blocking.
   */
  async query(q, { limit = 20, sessionId = null } = {}) {
    await this.load();
    const now = Date.now();
    if (now - this.#lastCheck > REFRESH_CHECK_INTERVAL_MS) {
      this.#lastCheck = now;
      this.#enqueue(false); // background stat + rebuild; query uses current rows
    }
    return this.#search(String(q ?? '').trim(), limit, sessionId);
  }

  get stats() {
    let rows = 0;
    for (const list of this.#rows.values()) rows += list.length;
    return { complete: this.#complete, sessions: this.#rows.size, rows };
  }

  // ── internals ────────────────────────────────────────────────────────────

  /** Serialize refresh passes; `full` marks the first complete walk. */
  #enqueue(full) {
    this.#chain = this.#chain
      .then(() => this.#refreshPass(full))
      .catch(() => {}); // a failed pass must not poison the chain
    return this.#chain;
  }

  async #refreshPass(full) {
    let entries = [];
    try { entries = walkSessionFiles(SESSIONS_ROOT); } catch { return; }
    const seen = new Set();
    for (const abs of entries) {
      seen.add(abs);
      let st;
      try { st = await fs.promises.stat(abs); } catch { continue; }
      const known = this.#files.get(abs);
      if (known && known.mtimeMs === st.mtimeMs && known.size === st.size) continue;
      const sid = path.basename(path.dirname(abs));
      try {
        const count = await this.#rebuildPartition(abs, sid, st.mtimeMs, st.size);
        this.#files.set(abs, { sid, mtimeMs: st.mtimeMs, size: st.size, count });
      } catch { /* unreadable/corrupt file: skip it this pass */ }
    }
    // Drop partitions whose durable file disappeared.
    for (const [abs, f] of [...this.#files]) {
      if (seen.has(abs)) continue;
      this.#files.delete(abs);
      this.#rows.delete(f.sid);
      try { await fs.promises.rm(path.join(this.#dir, `${f.sid}.jsonl`), { force: true }); } catch { /* best effort */ }
    }
    this.#complete = true;
    try {
      const files = Object.fromEntries(this.#files);
      await writeAtomic(this.#metaPath, JSON.stringify({ version: 1, complete: true, files }));
    } catch { /* diagnostics only */ }
    if (full) { /* first pass: nothing more to do */ }
  }

  /** Decompress one durable log and rewrite its partition file. Returns row count. */
  async #rebuildPartition(abs, sid, mtimeMs, size) {
    const buf = await fs.promises.readFile(abs);
    const starts = [];
    let pos = 0;
    for (;;) {
      const i = buf.indexOf(ZSTD_MAGIC, pos);
      if (i < 0) break;
      starts.push(i);
      pos = i + 4;
    }
    starts.push(buf.length);
    const out = [];
    for (let k = 0; k < starts.length - 1; k++) {
      let text;
      try { text = zlib.zstdDecompressSync(buf.slice(starts[k], starts[k + 1])).toString('utf8'); }
      catch { continue; } // a torn tail frame is normal for a file being written
      for (const line of text.split('\n')) {
        if (!line.trim()) continue;
        let ev;
        try { ev = JSON.parse(line); } catch { continue; }
        const msgs = eventsToMessages([ev], 'summary');
        for (const m of msgs) {
          const t = preview(m.text ?? '', ROW_TEXT_CAP);
          if (!t) continue;
          out.push([m.seq, m.role, m.ts, t]);
          if (out.length >= MAX_ROWS_PER_SESSION) break;
        }
        if (out.length >= MAX_ROWS_PER_SESSION) break;
      }
    }
    this.#rows.set(sid, out);
    const part = path.join(this.#dir, `${safeName(sid)}.jsonl`);
    const body = [JSON.stringify({ sid, mtimeMs, size, count: out.length })]
      .concat(out.map(r => JSON.stringify(r)))
      .join('\n');
    await writeAtomic(part, body);
    return out.length;
  }

  async #readPartition(part) {
    const raw = await fs.promises.readFile(part, 'utf8');
    const lines = raw.split('\n');
    const rows = [];
    for (let i = 1; i < lines.length; i++) {
      const l = lines[i];
      if (!l.trim()) continue;
      try { rows.push(JSON.parse(l)); } catch { /* skip bad line */ }
    }
    return rows;
  }

  /** Literal AND-of-tokens substring search over the in-memory rows. */
  async #search(q, limit, sessionId) {
    const tokens = q.toLowerCase().split(/\s+/).filter(Boolean);
    if (tokens.length === 0) return { items: [], complete: this.#complete, ...this.stats };
    const sessions = await this.#getSessions().catch(() => []);
    const byId = new Map(sessions.map(s => [s.id, s]));
    const results = new Map(); // sid -> {sessionId, title, cwd, updatedAt, hitCount, hits}

    const consider = (sid, hit) => {
      const meta = byId.get(sid);
      if (sessionId && sid !== sessionId) return;
      let r = results.get(sid);
      if (!r) {
        r = {
          sessionId: sid,
          title: meta?.title ?? '(untitled)',
          cwd: meta?.cwd ?? '',
          updatedAt: meta?.updatedAt ?? 0,
          hitCount: 0,
          hits: [],
        };
        results.set(sid, r);
      }
      r.hitCount++;
      if (hit && r.hits.length < 3) r.hits.push(hit);
    };

    for (const [sid, rows] of this.#rows) {
      // newest first inside a session so the top hits are the most recent ones
      for (let i = rows.length - 1; i >= 0; i--) {
        const [seq, role, ts, t] = rows[i];
        const lower = t.toLowerCase();
        let ok = true;
        let first = -1;
        for (const tok of tokens) {
          const at = lower.indexOf(tok);
          if (at < 0) { ok = false; break; }
          if (first < 0) first = at;
        }
        if (!ok) continue;
        consider(sid, { seq, role, ts, snippet: snippetOf(t, first) });
      }
    }
    // Title-only matches (no body hit) still surface the session.
    for (const s of sessions) {
      if (results.has(s.id) || sessionId) continue;
      const lower = String(s.title ?? '').toLowerCase();
      if (tokens.every(tok => lower.includes(tok))) consider(s.id, null);
    }

    const items = [...results.values()]
      .sort((a, b) =>
        (b.hits[0]?.ts ?? b.updatedAt) - (a.hits[0]?.ts ?? a.updatedAt) || b.hitCount - a.hitCount)
      .slice(0, sessionId ? Math.max(limit, 50) : limit);
    return { v: 1, items, complete: this.#complete, ...this.stats };
  }
}

function snippetOf(t, at) {
  const start = Math.max(0, at - 40);
  const end = Math.min(t.length, at + 90);
  return (start > 0 ? '…' : '') + t.slice(start, end) + (end < t.length ? '…' : '');
}

function safeName(sid) {
  // Session ids are [A-Za-z0-9-]; strip anything else defensively.
  return String(sid).replace(/[^A-Za-z0-9_-]/g, '_');
}

function walkSessionFiles(root) {
  const out = [];
  const stack = [root];
  while (stack.length) {
    const dir = stack.pop();
    let names;
    try { names = fs.readdirSync(dir, { withFileTypes: true }); } catch { continue; }
    for (const e of names) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) stack.push(p);
      else if (e.name === 'session.v4.jsonl.zstd') out.push(p);
    }
  }
  return out;
}

async function writeAtomic(file, body) {
  const tmp = `${file}.tmp-${process.pid}`;
  await fs.promises.writeFile(tmp, body, 'utf8');
  await fs.promises.rename(tmp, file);
}
