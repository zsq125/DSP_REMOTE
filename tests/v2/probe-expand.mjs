// tests/v2/probe-expand.mjs — expand 三级确定性对拍（只读，不写任何会话）
// 用 before 游标取全量，使 summary/full 两次请求看到**完全相同**的 seq 集合，
// 从而精确比对字段差异（排除"两次请求间数据增长"造成的页漂移假象）。
import { fetchAll, HEADERS } from './common.mjs';

const sid = process.argv[2];
if (!sid) { console.error('usage: node probe-expand.mjs <sessionId>'); process.exit(2); }

const none = await fetchAll(sid, 'none');
const sum = await fetchAll(sid, 'summary');
const full = await fetchAll(sid, 'full');
if (none.err || sum.err || full.err) { console.error('fetch err', none.err, sum.err, full.err); process.exit(2); }

const keys = s => new Set(s.items.map(x => x.seq));
const kN = keys(none), kS = keys(sum), kF = keys(full);
const inter = (a, b) => [...a].filter(x => b.has(x));

console.log(`session=${sid}`);
console.log(`counts: none=${none.items.length} summary=${sum.items.length} full=${full.items.length}`);
console.log(`common seq: n&s=${inter(kN, kS).length} n&f=${inter(kN, kF).length} s&f=${inter(kS, kF).length}`);
console.log(`none-only=${[...kN].filter(x => !kS.has(x)).length} summary-only=${[...kS].filter(x => !kN.has(x)).length} full-only=${[...kF].filter(x => !kS.has(x)).length}`);

const map = arr => new Map(arr.items.map(x => [x.seq, x]));
const mN = map(none), mS = map(sum), mF = map(full);
const commonSF = [...kS].filter(x => kF.has(x)).sort((a, b) => a - b);

const stat = (m, pred) => [...m.values()].filter(x => pred(x)).length;
const hasVal = v => v !== null && v !== undefined && v !== '' &&
  !(typeof v === 'object' && Object.keys(v).length === 0);

console.log('\n--- reasoning 字段 ---');
console.log(`none: 有值 ${stat(mN, x => hasVal(x.reasoning))} / 截断上限 ${Math.max(0, ...[...mN.values()].map(x => (x.reasoning || '').length))}`);
console.log(`summary: 有值 ${stat(mS, x => hasVal(x.reasoning))} / 上限 ${Math.max(0, ...[...mS.values()].map(x => (x.reasoning || '').length))}`);
console.log(`full: 有值 ${stat(mF, x => hasVal(x.reasoning))} / 上限 ${Math.max(0, ...[...mF.values()].map(x => (x.reasoning || '').length))}`);

console.log('\n--- toolInput ---');
const tiLen = m => Math.max(0, ...[...m.values()].map(x => JSON.stringify(x.toolInput ?? '').length));
console.log(`none: 有值 ${stat(mN, x => hasVal(x.toolInput))} / 上限 ${tiLen(mN)}`);
console.log(`summary: 有值 ${stat(mS, x => hasVal(x.toolInput))} / 上限 ${tiLen(mS)}`);
console.log(`full: 有值 ${stat(mF, x => hasVal(x.toolInput))} / 上限 ${tiLen(mF)}`);

console.log('\n--- toolOutput ---');
const toLen = m => Math.max(0, ...[...m.values()].map(x => (x.toolOutput || '').length));
console.log(`none: 有值 ${stat(mN, x => hasVal(x.toolOutput))} / 上限 ${toLen(mN)}`);
console.log(`summary: 有值 ${stat(mS, x => hasVal(x.toolOutput))} / 上限 ${toLen(mS)}`);
console.log(`full: 有值 ${stat(mF, x => hasVal(x.toolOutput))} / 上限 ${toLen(mF)}`);

console.log('\n--- collapsedSummary ---');
console.log(`none: 有值 ${stat(mN, x => hasVal(x.collapsedSummary))}`);
console.log(`summary: 有值 ${stat(mS, x => hasVal(x.collapsedSummary))}`);
console.log(`full: 有值 ${stat(mF, x => hasVal(x.collapsedSummary))}`);

// 逐条对比 summary vs full 的 tool-result
let onlySummaryHas = 0, onlyFullHas = 0, both = 0, neither = 0;
const samples = [];
for (const s of commonSF) {
  const a = mS.get(s), b = mF.get(s);
  if (a.kind !== 'tool-result') continue;
  const ha = hasVal(a.toolOutput), hb = hasVal(b.toolOutput);
  if (ha && hb) both++;
  else if (ha && !hb) { onlySummaryHas++; if (samples.length < 3) samples.push(`seq=${s} summary=${(a.toolOutput || '').length} full=${JSON.stringify(b.toolOutput)}`); }
  else if (!ha && hb) onlyFullHas++;
  else neither++;
}
console.log(`\n--- tool-result 明细（summary vs full，相同 seq 集合）---`);
console.log(`both=${both} onlySummaryHas=${onlySummaryHas} onlyFullHas=${onlyFullHas} neither=${neither}`);
samples.forEach(x => console.log('  ' + x));
