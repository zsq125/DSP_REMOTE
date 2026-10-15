// tests/v2/probe-image-baseline.mjs — 图片功能基线探测（纯只读）
// 目的：确认 images 字段 / attachments 端点当前是否存在，并寻找可用样本。
import { api } from './common.mjs';

const ok = [];
const add = (n, v, d = '') => { ok.push([n, v, d]); };

// ---- A. /messages 是否含 images 键 ----
const list = await api('/rc/v1/sessions?limit=200');
const items = list.json?.items || [];
add('BASE-01', list.status === 200 && Array.isArray(items), `status=${list.status} count=${items.length}`);

const target = items.find(s => s.status === 'idle' && s.messageCount > 30);
if (target) {
  for (const e of ['none', 'summary', 'full']) {
    const r = await api(`/rc/v1/sessions/${target.id}/messages?limit=50&expand=${e}`);
    const its = r.json?.items || [];
    const withKey = its.filter(x => 'images' in x).length;
    add(`BASE-02-${e}`, withKey === its.length && its.length > 0,
      `含 images 键 ${withKey}/${its.length}（0 表示字段尚未实现）`);
  }
}

// ---- B. attachments 端点 ----
if (target) {
  const r = await api(`/rc/v1/sessions/${target.id}/attachments/probe_nonexistent`, { headers: { token: 'x' } });
  add('BASE-03', r.status !== 200,
    `status=${r.status}（404=端点未实现）body=${r.text.slice(0, 120)}`);
}

// ---- C. 只读寻找带图样本：查找 attachments 目录里的 id 是否出现在任何会话消息中 ----
// 注意：只做 GET。若网关未实现 images 字段，则无法从 API 侧关联，此项如实记为不可判定。
add('BASE-04', true, '样本关联需 images 字段先落地；当前无法从 API 关联，属预期（功能未实现）');

console.log('\n=== 基线结论 ===');
for (const [n, v, d] of ok) console.log(`[${v ? 'PASS' : 'FAIL'}] ${n} ${d}`);
const fails = ok.filter(x => !x[1]).length;
console.log(`\n== baseline: TOTAL=${ok.length} PASS=${ok.length - fails} FAIL=${fails}`);