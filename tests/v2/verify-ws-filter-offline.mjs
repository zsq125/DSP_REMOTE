// tests/v2/verify-ws-filter-offline.mjs -- Offline check of the workspace filter
// against the REAL dsh.mjs listPage, without needing a 3080 restart.
// In-process: a stub list() with mixed cwds, then assert the contract.
import { DshAdapter } from '../../pc-gateway/lib/dsh.mjs';

let pass = 0, fail = 0;
const A = (c, m, x = '') => { if (c) { pass++; console.log('[PASS] ' + m); } else { fail++; console.log('[FAIL] ' + m + (x ? ' :: ' + x : '')); } };

const CWDS = ['F:\\project\\alpha', 'F:\\project\\beta', 'F:\\project\\子目录 with space', 'F:\\project\\alpha'];

function stub(countPerCwd) {
  const rows = [];
  for (const cwd of Object.keys(countPerCwd)) {
    for (let i = 0; i < countPerCwd[cwd]; i++) {
      rows.push({
        sessionId: `${cwd}#${i}`,
        cwd,
        title: `${cwd} session ${i}`,
        updatedAt: 1000 - i,
      });
    }
  }
  rows.sort((a, b) => b.updatedAt - a.updatedAt);
  return new DshAdapter({
    list: async () => ({ items: rows }),
  });
}

console.log('════ workspace filter (offline, real listPage) ════\n');

const counts = { 'F:\\project\\alpha': 3, 'F:\\project\\beta': 2, 'F:\\project\\子目录 with space': 1 };
const d = stub(counts);

// 1) no filter => everything
const all = await d.listPage(50, null);
A(all.items.length === 6, `不传 workspace 返回全部（n=${all.items.length}，期望 6）`);

// 2) filter
const alpha = await d.listPage(50, null, 'F:\\project\\alpha');
A(alpha.items.length === 3, `过滤后条数正确（n=${alpha.items.length}，期望 3）`);
A(alpha.items.every(s => s.cwd === 'F:\\project\\alpha'), '每一条 cwd 都等于该工作区');
A(alpha.items.length < all.items.length, '过滤后条数小于全量');

// 3) CJK + space path
const cjk = await d.listPage(50, null, 'F:\\project\\子目录 with space');
A(cjk.items.length === 1 && cjk.items[0].cwd === 'F:\\project\\子目录 with space',
  '★ 含中文与空格的路径能正确过滤');

// 4) missing workspace
const missing = await d.listPage(50, null, 'Z:\\nope');
A(missing.items.length === 0 && missing.nextCursor == null,
  '★ 不存在的 workspace => items 空且 nextCursor 为 null');

// 5) filter + pagination are orthogonal
const p1 = await d.listPage(2, null, 'F:\\project\\alpha');
A(p1.items.length === 2, `过滤+limit=2 => 每页 2 条（n=${p1.items.length}）`);
A(p1.nextCursor != null, '还有更多时 nextCursor 非 null');
const p2 = await d.listPage(2, p1.nextCursor, 'F:\\project\\alpha');
const ids = new Set([...p1.items, ...p2.items].map(s => s.id));
A(ids.size === 3, `翻完两页正好 3 条不重复不漏（实际 ${ids.size}）`);
A([...p1.items, ...p2.items].every(s => s.cwd === 'F:\\project\\alpha'), '翻页结果也不混入别的 cwd');

// 6) empty string means "no filter" (backward compatible)
const empty = await d.listPage(50, null, '');
A(empty.items.length === 6, `workspace="" 视为不过滤（n=${empty.items.length}）`);
const undef = await d.listPage(50, null, undefined);
A(undef.items.length === 6, 'workspace 缺省视为不过滤');

console.log('\nVERIFY-WS-FILTER-OFFLINE  PASS=' + pass + '  FAIL=' + fail);
process.exit(fail === 0 ? 0 : 1);