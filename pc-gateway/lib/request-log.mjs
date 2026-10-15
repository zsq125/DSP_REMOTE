// pc-gateway/lib/request-log.mjs -- READ-ONLY request recorder for diagnostics.
//
// Purpose and boundaries:
//  - Exists so the phone App's request pattern can be observed objectively
//    (which endpoints it hits on cold start, whether it re-subscribes on the WS,
//    whether it unsubscribes when switching sessions) without a device logcat.
//  - It is STRICTLY a passive observer: it never alters a request, never mutates
//    gateway state, and must never be able to fail a request. Recording is
//    wrapped so a bug here cannot break the hot path.
//  - Privacy: records ONLY method / path / status / timestamp / sessionId.
//    Never bodies, headers, tokens or message content.
//  - Bounded: a ring buffer of at most `limit` entries; the oldest are dropped.
//
// The endpoint lives under the `_debug` prefix and is token-authenticated like
// every other route.

/** Default ring-buffer size. */
export const REQUEST_LOG_LIMIT = 2000;

export class RequestLog {
  /**
   * @param {number} limit ring buffer capacity
   * @param {boolean} enabled master switch (default on so diagnostics work out of the box)
   */
  constructor(limit = REQUEST_LOG_LIMIT, enabled = true) {
    this.limit = limit > 0 ? limit : REQUEST_LOG_LIMIT;
    this.enabled = enabled;
    /** @type {{ts:number, method:string, path:string, status:number, sessionId:?string}[]} */
    this.items = [];
    this.total = 0;          // lifetime count, including entries already dropped
  }

  /**
   * Record one request. Must never throw and must never affect the response.
   * @param {string} method
   * @param {string} path path WITHOUT the query string (keeps cursors/tokens out)
   * @param {number} status
   * @param {?string} sessionId
   */
  record(method, path, status, sessionId = null) {
    if (!this.enabled) return;
    try {
      this.total++;
      this.items.push({
        ts: Date.now(),              // server clock, epoch ms
        method: String(method || '?').toUpperCase(),
        path: typeof path === 'string' ? path : '',
        status: Number(status) || 0,
        sessionId: sessionId ?? null,
      });
      // Ring buffer: drop the oldest once over capacity.
      const overflow = this.items.length - this.limit;
      if (overflow > 0) this.items.splice(0, overflow);
    } catch {
      // A diagnostics failure must never break the gateway.
    }
  }

  /**
   * Entries at or after `since` (epoch ms). `since` omitted/0 returns everything.
   * @returns {{since:number, now:number, count:number, total:number, items:object[]}}
   */
  since(sinceMs = 0) {
    const from = Number.isFinite(Number(sinceMs)) ? Number(sinceMs) : 0;
    const items = from > 0 ? this.items.filter(i => i.ts >= from) : this.items.slice();
    return { since: from, now: Date.now(), count: items.length, total: this.total, items };
  }

  /** Grouped view, handy for "what did the App hit on cold start?". */
  summary() {
    const byKey = new Map();
    for (const i of this.items) {
      const key = i.method + ' ' + i.path;
      byKey.set(key, (byKey.get(key) || 0) + 1);
    }
    return {
      limit: this.limit,
      enabled: this.enabled,
      buffered: this.items.length,
      total: this.total,
      dropped: this.total - this.items.length,
      byRoute: [...byKey.entries()].map(([route, count]) => ({ route, count })).sort((a, b) => b.count - a.count),
    };
  }

  /** WS control frames are the evidence for the subscription-leak investigation. */
  recordWs(action, sessionId = null) {
    this.record('WS', action, 200, sessionId);
  }

  clear() { this.items = []; }
}

/**
 * Extracts the session id from a gateway path when present.
 * Keeps the recorded path itself free of ids so it can be grouped.
 */
export function pathInfo(rawPath) {
  const m = /^\/rc\/v1\/sessions\/([^/]+)(\/[^?]*)?/.exec(rawPath || '');
  // The whole query string is dropped, which also keeps long paths out of the log:
  // `?workspace=F:\project\...` never reaches a recorded entry.
  if (!m) return { path: (rawPath || '').split('?')[0], sessionId: null };
  const suffix = m[2] ? m[2].split('?')[0] : '';
  return {
    path: '/rc/v1/sessions/{id}' + suffix,
    sessionId: decodeURIComponent(m[1]),
  };
}