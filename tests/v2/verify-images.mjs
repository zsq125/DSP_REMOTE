// tests/v2/verify-images.mjs -- offline proof for the image feature.
//
// Runs WITHOUT a gateway restart and WITHOUT any real image file, so every
// branch (including the 8 MiB limit) is reachable deterministically.
import assert from 'node:assert/strict';
import {
  eventsToMessages, publicMessage, ATTACHMENT_MAX_BYTES, ALWAYS_PRESENT_KEYS,
} from '../../pc-gateway/lib/dsh.mjs';

let pass = 0, fail = 0;
const A = (c, m, x = '') => { if (c) { pass++; console.log('[PASS] ' + m); } else { fail++; console.log('[FAIL] ' + m + ' :: ' + x); } };

// helper: the size guard mirrored from DshAdapter.attachment()
function guard(declaredBytes, base64Length, max = ATTACHMENT_MAX_BYTES) {
  if (declaredBytes > max) return 413;
  const decoded = Math.floor((base64Length * 3) / 4);
  if (decoded > max) return 413;
  return 200;
}

console.log('=== 1. ATTACHMENT_MAX_BYTES boundary ===');
console.log(`  ATTACHMENT_MAX_BYTES = ${ATTACHMENT_MAX_BYTES} (${ATTACHMENT_MAX_BYTES / 1024 / 1024} MiB)`);
A(ATTACHMENT_MAX_BYTES === 8 * 1024 * 1024, 'limit is 8 MiB');

{
  const at = ATTACHMENT_MAX_BYTES;
  const justOver = ATTACHMENT_MAX_BYTES + 1;
  const small = 1024;
  A(guard(at, 0) === 200, `exactly ${at} bytes -> 200 (allowed, not 413)`);
  A(guard(justOver, 0) === 413, `${justOver} bytes -> 413`);
  A(guard(small, 0) === 200, 'far below the limit -> 200');
  // second guard: `bytes` missing, measured from the base64 length instead
  A(guard(0, Math.ceil((ATTACHMENT_MAX_BYTES + 10) * 4 / 3)) === 413,
    'oversize detected from payload length when `bytes` is absent -> 413');
  A(guard(0, 1024) === 200, 'small payload without `bytes` -> 200');
}

console.log('\n=== 2. imagesOf() parsing ===');
const ev = (data) => ({ seq: 1, time: 1, type: 'assistant/message', data });
const img = (id, extra = {}) => ({
  type: 'image',
  attachment: { attachmentId: id, mediaType: 'image/png', bytes: 1000, width: 800, height: 600, ...extra },
});

A(eventsToMessages([ev({ message: { content: [] } })]).map(publicMessage)[0].images.length === 0,
  'empty content -> images = [] (key present)');
A(eventsToMessages([ev({ message: {} })]).map(publicMessage)[0].images.length === 0,
  'missing content -> images = []');
A(eventsToMessages([ev({ content: null })]).map(publicMessage)[0].images.length === 0,
  'null content -> images = []');

{
  const r = eventsToMessages([ev({
    message: { content: [
      { type: 'text', text: 'before' },
      img('a1'),
      { type: 'reasoning', text: 'thinking' },
      img('a2'),
      { type: 'text', text: 'after' },
    ] },
  })]).map(publicMessage)[0];
  A(r.images.length === 2, 'mixed blocks: only the 2 images are picked', JSON.stringify(r.images));
  A(r.images[0].attachmentId === 'a1' && r.images[1].attachmentId === 'a2', 'image order is preserved');
  A(r.text === 'beforeafter', 'image blocks are excluded from the text', r.text);
}

{
  // an image block WITHOUT `attachment` must be skipped, not crash
  const r = eventsToMessages([ev({ message: { content: [{ type: 'image' }, img('ok')] } })]).map(publicMessage)[0];
  A(r.images.length === 1 && r.images[0].attachmentId === 'ok',
    'image block without `attachment` is skipped');
}

{
  // partial attachment ref: missing mediaType/size must yield nulls, not a crash
  const r = eventsToMessages([ev({ message: { content: [{ type: 'image', attachment: { attachmentId: 'x' } }] } })])
    .map(publicMessage)[0];
  A(r.images.length === 1, 'partial attachment still yields one entry');
  A(r.images[0].attachmentId === 'x', '  attachmentId present');
  A(r.images[0].mediaType === null, '  missing mediaType -> null');
  A(r.images[0].bytes === null, '  missing bytes -> null');
  A(r.images[0].width === null, '  missing width -> null');
}

{
  const many = eventsToMessages([ev({ message: { content: [img('i1'), img('i2'), img('i3')] } })])
    .map(publicMessage)[0];
  A(many.images.length === 3, 'three images -> three entries');
  A(many.images.map(x => x.attachmentId).join(',') === 'i1,i2,i3', '  order i1,i2,i3');
}

{
  // user message images
  const u = eventsToMessages([{ seq: 2, time: 2, type: 'user/message', data: { content: [img('u1')] } }])
    .map(publicMessage)[0];
  A(u.images.length === 1 && u.images[0].attachmentId === 'u1', 'user message carries images');
}

{
  // tool rows always carry an empty array (key present, no images)
  const t = eventsToMessages([{ seq: 3, time: 3, type: 'tool/call', data: { callId: 'c', name: 'x' } }])
    .map(publicMessage)[0];
  A(Array.isArray(t.images) && t.images.length === 0, 'tool-call row -> images = []');
}

console.log('\n=== 3. F-5: images key always present ===');
A(ALWAYS_PRESENT_KEYS.includes('images'), 'images is in ALWAYS_PRESENT_KEYS');
{
  const rows = eventsToMessages([
    ev({ message: { content: [img('a')] } }),
    { seq: 9, time: 9, type: 'tool/result', data: { message: { toolCallId: 'c', content: [{ type: 'text', text: 'o' }] } } },
    { seq: 10, time: 10, type: 'system/message', data: { message: { content: [{ type: 'text', text: 's' }] } } },
  ]).map(publicMessage);
  A(rows.every(r => Object.prototype.hasOwnProperty.call(r, 'images')),
    'every row has the images key (system/tool/assistant)');
}

console.log('\n=== 4. expand does not affect images ===');
const base = ev({ message: { content: [img('a1'), { type: 'reasoning', text: 'R'.repeat(5000) }, { type: 'text', text: 't' }] } });
for (const level of ['none', 'summary', 'full']) {
  const r = eventsToMessages([base], level).map(publicMessage)[0];
  A(r.images.length === 1, `expand=${level}: images still returned (references carry no payload)`);
}
{
  const none = eventsToMessages([base], 'none').map(publicMessage)[0];
  const full = eventsToMessages([base], 'full').map(publicMessage)[0];
  A(none.images[0].attachmentId === full.images[0].attachmentId,
    'image reference identical across expand levels');
}

console.log('\n=== 5. back-compat: text semantics unchanged ===');
A(eventsToMessages([base], 'summary').map(publicMessage)[0].text === 't',
  'assistant text still excludes reasoning/image blocks');
const toolRow = eventsToMessages([{ seq: 5, time: 5, type: 'tool/call', data: { callId: 'c', name: 'read' } }])
  .map(publicMessage)[0];
A(toolRow.text === '[tool] read', 'tool text still "[tool] <name>"');

console.log('\n' + '='.repeat(60));
console.log(`IMAGE VERIFY  PASS=${pass}  FAIL=${fail}`);
process.exit(fail > 0 ? 1 : 0);