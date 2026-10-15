// tests/v2/verify-workspace-filter.mjs -- Read-only verification of
// GET /rc/v1/sessions?workspace=<cwd>.
//
// Contract:
//   no workspace           -> unchanged full list
//   workspace=<existing>   -> only that cwd, nextCursor semantics unchanged
//   workspace=<missing>    -> 200 with items: [] and nextCursor: null (NOT 404)
//   Windows paths (backslashes, spaces, CJK, percent-encoding) must decode right
//   paging is orthogonal to the filter
//
// Run:  node tests/v2/verify-workspace-filter.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const BASE = process.env.DSH_GATEWAY || 'http://127.0.0.1:8765';

const tokenFile = path.join(ROOT, 'pc-gateway', '.state', 'token.txt');
if (!fs.existsSync(tokenFile)) { console.error('token file missing'); process.exit(2); }
const H = { Authorization: 'Bearer ' + fs.readFileSync(tokenFile, 'utf8').trim() };

let pass = 0, fail = 0;
const A = (c, m, x = '') => { if (c) { pass++; console.log('[PASS] ' + m); } else { fail++; console.log('[FAIL] ' + m + (x ? ' :: ' + x : '')); } };

async function sessions(qs) {
  const r = await fetch(`${BASE}/rc/v1/sessions${qs}`, { headers: H });
  const body = await r.json().catch(() => null);
  return { status: r.status, body };
}

console.log('════ verify-workspace-filter ════\n');

// baseline
const all = await sessions('?limit=200');
A(all.status === 200, '不传 workspace 返回 200');
const allItems = all.body?.items || [];
A(allItems.length > 0, `不传 workspace 返回全部会话（n=${allItems.length}）`);
console.log(`   基准：全量 ${allItems.length} 条`);

// pick a real cwd
const cwds = [...new Set(allItems.map(s => s.cwd).filter(Boolean))];
A(cwds.length > 0, `会话带有 cwd 字段（distinct=${cwds.length}）`);
const target = cwds.sort((a, b) => {
  const na = allItems.filter(s => s.cwd === a).length;
  const nb = allItems.filter(s => s.cwd === b).length;
  return nb - na;                       // pick the busiest one
})[0];
const targetCount = allItems.filter(s => s.cwd === target).length;
console.log(`   取样工作区：${target}  （${targetCount} / ${allItems.length} 条）\n`);

// 1) filter works
const filtered = await sessions('?limit=200&workspace=' + encodeURIComponent(target));
A(filtered.status === 200, '带 workspace 返回 200');
const fItems = filtered.body?.items || [];
A(fItems.every(s => s.cwd === target), '★ 每一条的 cwd 都等于该工作区');
A(fItems.length === targetCount, `★ 条数等于该工作区的真实条数（${fItems.length} == ${targetCount}）`);
A(fItems.length < allItems.length || targetCount === allItems.length,
  '★ 条数不大于全量（过滤生效）');

// 2) missing workspace -> empty 200
const missing = await sessions('?limit=200&workspace=' + encodeURIComponent('Z:\\definitely\\not\\here'));
A(missing.status === 200, '★ 不存在的 workspace 返回 200 而非 404');
A((missing.body?.items || []).length === 0, '不存在时 items 为空');
A(missing.body?.nextCursor == null, '不存在时 nextCursor 为 null');

// 3) Windows / CJK / space / percent-encoding
console.log('');
const encodings = [
  ['裸反斜杠路径（会自动编码）', target],
  ['percent-encoded', encodeURIComponent(target)],
];
for (const [label, value] of encodings) {
  const r = await sessions('?limit=200&workspace=' + value);
  const n = (r.body?.items || []).length;
  A(r.status === 200 && n === targetCount, `【${label}】正确过滤（n=${n}，期望 ${targetCount}）`);
}
// a path with a space and CJK is exercised by building a synthetic one
const spaced = `${target}\\子目录 with space`;
const rSpaced = await sessions('?limit=200&workspace=' + encodeURIComponent(spaced));
A(rSpaced.status === 200 && (rSpaced.body?.items || []).length === 0,
  '【含空格与中文的路径】正确解码且无匹配项');

// 4) paging is orthogonal to the filter
console.log('');
const pageSize = 2;
const seen = [];
let cursor = null, pages = 0;
do {
  const qs = `?limit=${pageSize}&workspace=${encodeURIComponent(target)}` + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : '');
  const p = await sessions(qs);
  pages++;
  const items = p.body?.items || [];
  if (items.length > pageSize) { fail++; console.log(`[FAIL] 第 ${pages} 页超过 limit（${items.length} > ${pageSize}）`); }
  for (const it of items) {
    if (it.cwd !== target) { fail++; console.log(`[FAIL] 第 ${pages} 页混入了别的 cwd: ${it.cwd}`); }
  }
  seen.push(...items.map(s => s.id));
  cursor = p.body?.nextCursor ?? null;
  if (pages > 50) break;
} while (cursor);
A(new Set(seen).size === seen.length, `分页不重复（${pages} 页，共 ${seen.length} 条，去重 ${new Set(seen).size}）`);
A(seen.length === targetCount, `分页不漏（翻到末尾 ${seen.length} == ${targetCount}）`);

// 5) limit bounds unchanged
console.log('');
const badLimit = await sessions('?limit=0&workspace=' + encodeURIComponent(target));
A(badLimit.status === 400, `limit=0 仍返回 400（实际 ${badLimit.status}）`);
const bigLimit = await sessions('?limit=9999');
A(bigLimit.status === 400, `limit 超上限仍返回 400（实际 ${bigLimit.status}）`);

console.log('\nVERIFY-WORKSPACE-FILTER  PASS=' + pass + '  FAIL=' + fail);
process.exit(fail === 0 ? 0 : 1);