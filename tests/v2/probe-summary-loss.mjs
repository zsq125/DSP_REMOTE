// tests/v2/probe-summary-loss.mjs — 定位 expand=summary 相对 full 丢失的行
import { fetchAll } from './common.mjs';

const sid = process.argv[2];
const s = await fetchAll(sid, 'summary');
const f = await fetchAll(sid, 'full');
const S = new Map(s.items.map(x => [x.seq, x]));
const F = new Map(f.items.map(x => [x.seq, x]));

const onlyF = [...F.keys()].filter(k => !S.has(k)).sort((a, b) => a - b);
const onlyS = [...S.keys()].filter(k => !F.has(k)).sort((a, b) => a - b);
console.log(`summary=${s.items.length} full=${f.items.length}`);
console.log(`full-only=${onlyF.length} summary-only=${onlyS.length}`);
console.log('\n--- full 有、summary 无（前 25）---');
for (const k of onlyF.slice(0, 25)) {
  const x = F.get(k);
  console.log(`  seq=${k} role=${x.role} kind=${x.kind} ` +
    `reasoning=${(x.reasoning || '').length} toolInput=${JSON.stringify(x.toolInput || '').length} ` +
    `toolOutput=${(x.toolOutput || '').length} collapsedSummary=${(x.collapsedSummary || '').length}`);
}
