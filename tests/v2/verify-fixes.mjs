// tests/v2/verify-fixes.mjs -- offline proof for F-1..F-5 (no gateway restart needed).
// Uses the real gateway modules so the assertions run against the shipped code.
import assert from 'node:assert/strict';
import {
  eventsToMessages, publicMessage, ALWAYS_PRESENT_KEYS,
} from '../../pc-gateway/lib/dsh.mjs';
import { normalizeMode, isValidMode, DEFAULT_PROMPT_MODE } from '../../pc-gateway/lib/prompt-mode.mjs';

let pass = 0, fail = 0;
const A = (c, m, x = '') => { if (c) { pass++; console.log('[PASS] ' + m); } else { fail++; console.log('[FAIL] ' + m + ' :: ' + x); } };

// ---- build a synthetic event log of N rows, some with big reasoning/tool payloads ----
function makeEvents(n, bigChars = 5000) {
  const evs = [];
  for (let i = 0; i < n; i++) {
    if (i % 3 === 0) {
      evs.push({
        seq: i, time: i, type: 'assistant/message',
        data: { message: { id: 'a' + i, content: [
          { type: 'reasoning', text: 'R'.repeat(bigChars) },
          { type: 'text', text: 'answer ' + i },
        ] } },
      });
    } else if (i % 3 === 1) {
      evs.push({
        seq: i, time: i, type: 'tool/call',
        data: { callId: 'c' + i, name: 'tool' + i, arguments: JSON.stringify({ pad: 'I'.repeat(bigChars) }) },
      });
    } else {
      evs.push({
        seq: i, time: i, type: 'tool/result',
        data: { message: { id: 'r' + i, toolCallId: 'c' + (i - 1), isError: false, content: [{ type: 'text', text: 'O'.repeat(bigChars) }] } },
      });
    }
  }
  return evs;
}

const events = makeEvents(300);
console.log('=== F-2: expand 不得影响行数 ===');
const perExpand = {};
for (const expand of ['none', 'summary', 'full']) {
  const rows = eventsToMessages(events, expand).map(publicMessage);
  perExpand[expand] = { rows: rows.length, maxSeq: Math.max(...rows.map(r => r.seq)) };
  console.log(`  expand=${expand.padEnd(8)} rows=${perExpand[expand].rows}  maxSeq=${perExpand[expand].maxSeq}`);
}
A(perExpand.none.rows === perExpand.summary.rows
  && perExpand.summary.rows === perExpand.full.rows,
  'F-2 三档行数一致（summary 不得截断行）',
  JSON.stringify(perExpand));
A(perExpand.none.rows === 300, 'F-2 行数 = 事件数（无任何行被丢弃）', String(perExpand.none.rows));
A(perExpand.none.maxSeq === perExpand.summary.maxSeq
  && perExpand.summary.maxSeq === perExpand.full.maxSeq,
  'F-2 三档 maxSeq 一致（最新消息不丢）');

console.log('\n=== F-2 补充：单字段截断只影响字段 ===');
const sumRows = eventsToMessages(events, 'summary').map(publicMessage);
const fullRows = eventsToMessages(events, 'full').map(publicMessage);
const noneRows = eventsToMessages(events, 'none').map(publicMessage);
A(sumRows.length === fullRows.length, 'summary 与 full 行数相同');
const s0 = sumRows[0].reasoning ?? '';
const f0 = fullRows[0].reasoning ?? '';
A(f0.length > s0.length, 'summary 的 reasoning 比 full 短（字段级截断生效）', `${s0.length} vs ${f0.length}`);
A(s0.includes('[truncated'), 'summary 的 reasoning 带 [truncated] 标记（App 可据此按需再取）');
A(f0.includes('[truncated') === false, 'full 的 reasoning 不带截断标记');

console.log('\n=== F-3: expand=none 必须清空 toolInput ===');
const noneCall = noneRows.find(r => r.kind === 'tool-call');
const fullCall = fullRows.find(r => r.kind === 'tool-call');
A(noneCall.toolInput === null, 'F-3 none 档 toolInput 为 null', JSON.stringify(noneCall.toolInput));
A(fullCall.toolInput !== null, 'full 档 toolInput 有内容');
A(noneCall.reasoning === null || noneCall.reasoning === '', 'F-3 none 档 reasoning 为空');

console.log('\n=== F-5: 扩展键恒存在 ===');
for (const key of ALWAYS_PRESENT_KEYS) {
  const hasEverywhere = noneRows.every(r => Object.prototype.hasOwnProperty.call(r, key))
    && sumRows.every(r => Object.prototype.hasOwnProperty.call(r, key))
    && fullRows.every(r => Object.prototype.hasOwnProperty.call(r, key));
  A(hasEverywhere, `F-5 键 ${key} 恒存在（三档全部行）`);
}
const sysRow = publicMessage({ seq: 1, role: 'system', text: 'seed', ts: 0, status: 'final', messageId: null });
A(Object.prototype.hasOwnProperty.call(sysRow, 'collapsedSummary')
  && sysRow.collapsedSummary === null, 'F-5 system 行也有 collapsedSummary 且为 null');

console.log('\n=== F-4: mode=null 必须 400（normalizeMode 抛错）===');
A(isValidMode(null) === false, 'F-4 mode=null 非法');
A(isValidMode('') === false, 'F-4 mode="" 非法');
A(isValidMode(1) === false, 'F-4 mode=1 非法');
A(isValidMode(['steer']) === false, 'F-4 mode=["steer"] 非法');
A(isValidMode('STEER') === false, 'F-4 mode="STEER" 非法（大小写敏感）');
A(isValidMode(undefined) === true, 'mode 省略合法');
A(isValidMode('steer') === true && isValidMode('queue') === true, 'steer/queue 合法');
try { normalizeMode(null); A(false, 'normalizeMode(null) 应抛错'); }
catch (e) { A(e.rcCode === 'invalid_request', 'F-4 抛错码为 invalid_request（REST→400 / WS→error 帧）', String(e.rcCode)); }
A(normalizeMode(undefined) === DEFAULT_PROMPT_MODE, '缺省 mode = steer');

console.log('\n=== 向后兼容 ===');
A(eventsToMessages(events, 'summary').map(publicMessage).every(r => r.role && r.text !== undefined),
  '契约字段 role/text 仍在');
A(eventsToMessages(events, 'summary').find(r => r.kind === 'tool-call').text.startsWith('[tool] '),
  'tool 的 text 语义未变（仍为 [tool] <name>）');

console.log('\n' + '='.repeat(60));
console.log(`F-1..F-5 VERIFY  PASS=${pass}  FAIL=${fail}`);
process.exit(fail > 0 ? 1 : 0);
