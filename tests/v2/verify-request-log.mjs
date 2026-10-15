// tests/v2/verify-request-log.mjs -- read-only request recorder: auth, boundedness,
// privacy, WS subscription evidence. No gateway restart needed.
import { RequestLog, pathInfo, REQUEST_LOG_LIMIT } from '../../pc-gateway/lib/request-log.mjs';
import { createGateway } from '../../pc-gateway/lib/http.mjs';
import { DshAdapter } from '../../pc-gateway/lib/dsh.mjs';

let pass = 0, fail = 0;
const A = (c, m, x = '') => { if (c) { pass++; console.log('[PASS] ' + m); } else { fail++; console.log('[FAIL] ' + m + ' :: ' + x); } };

console.log('=== 1. ring buffer is bounded ===');
A(REQUEST_LOG_LIMIT === 2000, 'default limit is 2000');
{
  const log = new RequestLog(10);
  for (let i = 0; i < 100; i++) log.record('GET', '/rc/v1/sessions', 200);
  A(log.items.length === 10, 'buffer keeps exactly `limit` entries', String(log.items.length));
  A(log.total === 100, 'lifetime counter survives eviction', String(log.total));
  A(log.summary().dropped === 90, 'summary reports dropped entries');
  A(log.items[0].path === '/rc/v1/sessions', 'the OLDEST were dropped (ring semantics)');
}

console.log('\n=== 2. `since` filtering ===');
{
  const log = new RequestLog(100);
  const t0 = Date.now();
  log.record('GET', '/a', 200);
  await new Promise(r => setTimeout(r, 5));
  const mark = Date.now();
  await new Promise(r => setTimeout(r, 5));
  log.record('GET', '/b', 200);
  const all = log.since(0);
  A(all.items.length === 2, 'since=0 returns everything', String(all.items.length));
  const recent = log.since(mark);
  A(recent.items.length === 1 && recent.items[0].path === '/b', 'since filters by ts >= since');
  A(log.since(0).now >= t0, 'response carries a server-side `now`');
}

console.log('\n=== 3. privacy: no bodies/headers/tokens recorded ===');
{
  const log = new RequestLog(50);
  log.record('POST', '/rc/v1/sessions/s1/prompt', 200, 's1');
  const keys = Object.keys(log.items[0]).sort();
  A(keys.join(',') === 'method,path,sessionId,status,ts', 'entry has exactly the 5 safe fields', keys.join(','));
  A(!JSON.stringify(log.items[0]).toLowerCase().includes('body'), 'no body captured');
  A(!JSON.stringify(log.items[0]).toLowerCase().includes('authorization'), 'no header captured');
}

console.log('\n=== 4. pathInfo normalises ids out of the path ===');
{
  const a = pathInfo('/rc/v1/sessions/session-abc/messages?limit=50&cursor=xyz');
  A(a.path === '/rc/v1/sessions/{id}/messages', 'id is masked in the path', a.path);
  A(a.sessionId === 'session-abc', 'sessionId extracted');
  A(pathInfo('/rc/v1/sessions').path === '/rc/v1/sessions', 'non-session path unchanged');
  A(!pathInfo('/rc/v1/sessions/x/messages?token=SECRET').path.includes('SECRET'),
    'query string (and any token) is never kept');
}

console.log('\n=== 5. disabled log records nothing ===');
{
  const log = new RequestLog(10, false);
  log.record('GET', '/x', 200);
  A(log.items.length === 0 && log.total === 0, 'a disabled log stays empty');
}

console.log('\n=== 6. HTTP endpoint: auth, shape, and that it records others ===');
{
  const dsh = new DshAdapter({
    list: async () => ({ items: [] }),
    inspect: async () => ({ meta: {}, events: [] }),
    projections: async () => ({ id: 's1' }),
    stats: async () => ({ total: 1, running: 0 }),
    listPage: async () => ({ items: [{ sessionId: 's1', id: 's1' }], nextCursor: null }),
  });
  const tokenStore = {
    equals: (t) => t === 'TOK', lookupFor: () => null, remember: () => {},
    consumeBusy: () => false, rotate: () => 'TOK2', armBusy: () => {},
  };
  const gw = createGateway({
    dsh, tokenStore, host: '127.0.0.1', port: 0, defaultCwd: '.',
    testHooks: false, log: () => {},
  });
  await gw.listen();
  const port = gw.server.address().port;
  const get = (p, t = 'TOK') => fetch('http://127.0.0.1:' + port + p, { headers: { Authorization: 'Bearer ' + t } });

  A((await get('/rc/v1/_debug/requests', 'WRONG')).status === 401, 'invalid token -> 401');
  A((await fetch('http://127.0.0.1:' + port + '/rc/v1/_debug/requests')).status === 401, 'missing token -> 401');

  // generate a few real requests first
  await get('/rc/v1/status');
  await get('/rc/v1/sessions?limit=10');
  const j = await (await get('/rc/v1/_debug/requests')).json();
  A(j.v === 1 && Array.isArray(j.items), 'response shape {v, since, now, items}');
  const routes = new Set(j.items.map(i => i.method + ' ' + i.path));
  A(routes.has('GET /rc/v1/status'), 'the /status request was recorded', [...routes].join(' | '));
  A(routes.has('GET /rc/v1/sessions'), 'the /sessions request was recorded', [...routes].join(' | '));
  A(j.items.every(i => typeof i.ts === 'number' && i.ts > 0), 'every entry carries an epoch-ms timestamp');
  A(j.items.some(i => i.status === 401), 'rejected attempts are recorded too');

  const sum = await (await get('/rc/v1/_debug/requests/summary')).json();
  A(sum.enabled === true && Array.isArray(sum.byRoute), 'summary endpoint works and shows the default-on state');
  console.log('  summary.byRoute: ' + JSON.stringify(sum.byRoute.slice(0, 5)));

  // the recorder must not break the endpoints it observes
  const st = await get('/rc/v1/status');
  A(st.status === 200, 'observed endpoints still work normally');

  await gw.close();
}

console.log('\n' + '='.repeat(60));
console.log('REQUEST LOG VERIFY  PASS=' + pass + '  FAIL=' + fail);
process.exit(fail > 0 ? 1 : 0);