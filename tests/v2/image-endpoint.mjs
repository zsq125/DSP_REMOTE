// tests/v2/verify-image-endpoint.mjs -- attachment endpoint: auth, 404, cross-session, 413.
//
// Runs WITHOUT a gateway restart and WITHOUT any real image bytes.
//
// Two layers are exercised separately on purpose:
//   A. `DshAdapter.attachment()` directly, with a stubbed inner `sessionController`
//      -- this is the only way to reach the 8 MiB size guard.
//   B. The real HTTP server with a stubbed adapter -- for routing and auth.
import { DshAdapter, ATTACHMENT_MAX_BYTES } from '../../pc-gateway/lib/dsh.mjs';
import { createGateway } from '../../pc-gateway/lib/http.mjs';

let pass = 0, fail = 0;
const A = (c, m, x = '') => { if (c) { pass++; console.log('[PASS] ' + m); } else { fail++; console.log('[FAIL] ' + m + ' :: ' + x); } };

const OWNED = { s1: ['a1'], s2: ['a2'] };

function makeSc(log) {
  return {
    list: async () => ({ items: [] }),
    inspect: async () => ({ meta: {}, events: [] }),
    projections: async ({ sessionId }) => (sessionId in OWNED ? { sessionId } : null),
    attachment: async ({ sessionId, attachmentId }) => {
      if (log) log.push(sessionId + '/' + attachmentId);
      if (!(sessionId in OWNED)) { const e = new Error('nope'); e.code = 'session/not-found'; throw e; }
      if (!OWNED[sessionId].includes(attachmentId)) {
        const e = new Error('Image is not referenced by this session.');
        e.code = 'session/attachment-invalid';
        throw e;
      }
      return {
        attachment: { attachmentId, mediaType: 'image/png', bytes: 1234, width: 800, height: 600 },
        data: Buffer.from('fake-png-bytes').toString('base64'),
      };
    },
  };
}

console.log('=== A1. adapter: success ===');
{
  const v = await new DshAdapter(makeSc()).attachment('s1', 'a1');
  A(typeof v.data === 'string' && v.data.length > 0, 'returns base64 data');
  A(v.attachment.mediaType === 'image/png', 'returns the attachment ref');
  A(v.attachment.width === 800 && v.attachment.height === 600, 'intrinsic size available');
  console.log('  -> ' + JSON.stringify(v).slice(0, 140));
}

console.log('\n=== A2. adapter: unknown session / unreferenced attachment ===');
{
  // DshAdapter maps inner codes onto the CONTRACT codes via mapInnerError,
  // so what escapes is `not_found` / `invalid_request`, not the raw inner code.
  let code = null;
  try { await new DshAdapter(makeSc()).attachment('nope', 'a1'); } catch (e) { code = e.rcCode; }
  A(code === 'not_found', 'unknown session -> contract not_found', String(code));
}
{
  let code = null;
  try { await new DshAdapter(makeSc()).attachment('s1', 'missing'); } catch (e) { code = e.rcCode; }
  A(code === 'invalid_request', 'unreferenced attachment -> contract invalid_request (400)', String(code));
}

console.log('\n=== A3. adapter: cross-session authorization ===');
{
  const log = [];
  let failed = false, code = null;
  try { await new DshAdapter(makeSc(log)).attachment('s2', 'a1'); } catch (e) { failed = true; code = e.rcCode; }
  A(failed, "cross-session read is refused");
  A(code === 'invalid_request', '  refused with invalid_request (400), never 200', String(code));
  A(log.includes('s2/a1'), '  inner call used the REQUESTED sessionId (DSH authorizes)');
}

console.log('\n=== A4. adapter: 8 MiB size guard ===');
{
  const mk = (bytes) => new DshAdapter({
    list: async () => ({ items: [] }),
    projections: async () => ({ id: 's1' }),
    attachment: async () => ({ attachment: { attachmentId: 'a', mediaType: 'image/png', bytes, width: 1, height: 1 }, data: 'AAAA' }),
  });
  const at = ATTACHMENT_MAX_BYTES;
  let ok = true;
  try { await mk(at).attachment('s1', 'a'); } catch { ok = false; }
  A(ok, 'exactly ' + at + ' bytes -> accepted (boundary inclusive)');
  let over = false, code = null, http = null;
  try { await mk(at + 1).attachment('s1', 'a'); } catch (e) { over = true; code = e.rcCode; http = e.http; }
  A(over === true, (at + 1) + ' bytes -> rejected');
  A(code === 'invalid_request', '  error code invalid_request', String(code));
  A(http === 413, '  HTTP status 413', String(http));
  let small = true;
  try { await mk(1024).attachment('s1', 'a'); } catch { small = false; }
  A(small, 'far below the limit -> accepted');
}
{
  const d = new DshAdapter({
    list: async () => ({ items: [] }),
    projections: async () => ({ id: 's1' }),
    attachment: async () => ({
      attachment: { attachmentId: 'a', mediaType: 'image/png' },
      data: 'A'.repeat(Math.ceil((ATTACHMENT_MAX_BYTES + 64) * 4 / 3)),
    }),
  });
  let http = null;
  try { await d.attachment('s1', 'a'); } catch (e) { http = e.http; }
  A(http === 413, 'oversize from payload length when `bytes` is absent -> 413', String(http));
}

const tokenStore = {
  equals: (t) => t === 'TOK', lookupFor: () => null, remember: () => {},
  consumeBusy: () => false, rotate: () => 'TOK2', armBusy: () => {},
};
const gw = createGateway({
  dsh: new DshAdapter(makeSc()), tokenStore, host: '127.0.0.1', port: 0,
  defaultCwd: '.', testHooks: false, log: () => {},
});
await gw.listen();
const port = gw.server.address().port;
const get = (p, token = 'TOK') => fetch('http://127.0.0.1:' + port + p, { headers: { Authorization: 'Bearer ' + token } });

console.log('\n=== B1. endpoint success ===');
{
  const r = await get('/rc/v1/sessions/s1/attachments/a1');
  const j = await r.json();
  A(r.status === 200, 'GET /rc/v1/sessions/{id}/attachments/{aid} -> 200', 'HTTP ' + r.status);
  A(j.v === 1 && j.attachment && typeof j.data === 'string', 'body mirrors SessionAttachmentValue');
  console.log('  body: ' + JSON.stringify(j).slice(0, 150));
}

console.log('\n=== B2. authentication ===');
{
  A((await get('/rc/v1/sessions/s1/attachments/a1', 'WRONG')).status === 401, 'invalid token -> 401');
  A((await fetch('http://127.0.0.1:' + port + '/rc/v1/sessions/s1/attachments/a1')).status === 401, 'missing token -> 401');
}

console.log('\n=== B3. error mapping (never 500) ===');
{
  A((await get('/rc/v1/sessions/nope/attachments/a1')).status === 404, 'unknown session -> 404');
  const r2 = await get('/rc/v1/sessions/s1/attachments/missing');
  A(r2.status === 400, 'unreferenced attachment -> 400 (not 500)', 'HTTP ' + r2.status);
  A((await r2.json())?.error?.code === 'invalid_request', '  error.code = invalid_request');
  A((await get('/rc/v1/sessions/s2/attachments/a1')).status === 400, 'cross-session read -> 400 (not 200)');
}

console.log('\n=== B4. expand does not affect this endpoint ===');
{
  const a = await (await get('/rc/v1/sessions/s1/attachments/a1?expand=none')).json();
  const b = await (await get('/rc/v1/sessions/s1/attachments/a1?expand=full')).json();
  A(a.data === b.data, 'identical bytes regardless of expand');
}

await gw.close();
console.log('\n' + '='.repeat(60));
console.log('IMAGE ENDPOINT VERIFY  PASS=' + pass + '  FAIL=' + fail);
process.exit(fail > 0 ? 1 : 0);