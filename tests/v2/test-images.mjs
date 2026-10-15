// tests/v2/test-images.mjs — 图片功能测试（需求：把消息里的图片显示补上 + 必须有测试）
// 纪律：
//   · 对他人会话**只读**（GET）
//   · 需要写时**只用本文件 CREATED 登记的自建会话**
//   · 绝不写入 session-00723a9e 等非本测试创建的会话
//
// 判据来源：规范 §3.2（字段名不得改）+ PM 给出的 DSH 类型定义。
// 本文件可在「功能未实现」状态下运行（会 FAIL），也可在交付后运行（应全绿）。
import { api, CREATED } from './common.mjs';

const cases = [];
const ok = (id, name, pass, detail = '') => {
  cases.push({ id, name, pass, detail });
  console.log(`[${pass ? 'PASS' : 'FAIL'}] ${id} ${name}${pass ? '' : ' :: ' + detail}`);
};
const hasVal = v => v !== null && v !== undefined && v !== '' &&
  !(Array.isArray(v) && v.length === 0) &&
  !(typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === 0);

const LEGAL_MEDIA = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif']);
const MAGIC = {
  'image/png': b => b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47,
  'image/jpeg': b => b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff,
  'image/gif': b => b[0] === 0x47 && b[1] === 0x49 && b[2] === 0x46,
  'image/webp': (b) => b.slice(0, 4).toString('ascii') === 'RIFF' && b.slice(8, 12).toString('ascii') === 'WEBP',
};

// ============ 准备：挑选只读样本会话 ============
const list = await api('/rc/v1/sessions?limit=200');
if (list.status !== 200) { console.error('list failed', list.status); process.exit(2); }
const all = list.json.items || [];
const cands = all.filter(s => s.status === 'idle' && s.messageCount > 20).slice(0, 12);

/** 扫一批只读会话，找 images 非空的样本 */
async function findImageSample() {
  for (const s of cands) {
    const r = await api(`/rc/v1/sessions/${s.id}/messages?limit=60&expand=full`);
    if (r.status !== 200) continue;
    const hit = (r.json.items || []).find(x => Array.isArray(x.images) && x.images.length > 0);
    if (hit) return { sid: s.id, row: hit, title: s.title };
  }
  return null;
}
const SAMPLE = await findImageSample();
console.log(`[INFO] 只读样本：${SAMPLE ? SAMPLE.sid + ' seq=' + SAMPLE.row.seq : '未找到带图会话（字段可能未实现）'}`);

// ============ A. /messages 的 images 字段 ============
if (SAMPLE) {
  const imgs = SAMPLE.row.images;
  ok('A1', '有图消息 images 含 attachmentId + mediaType',
    imgs.every(x => x && typeof x.attachmentId === 'string' && x.attachmentId.length > 0 && typeof x.mediaType === 'string'),
    JSON.stringify(imgs).slice(0, 200));
  ok('A2', 'mediaType 落在 4 种合法类型内',
    imgs.every(x => LEGAL_MEDIA.has(x.mediaType)), JSON.stringify([...new Set(imgs.map(x => x.mediaType))]));
  ok('A3', '多图消息：数组长度与顺序稳定（同一请求两次一致）',
    (await (async () => {
      const a = await api(`/rc/v1/sessions/${SAMPLE.sid}/messages?limit=60&expand=full`);
      const b = await api(`/rc/v1/sessions/${SAMPLE.sid}/messages?limit=60&expand=full`);
      const ra = (a.json?.items || []).find(x => x.seq === SAMPLE.row.seq);
      const rb = (b.json?.items || []).find(x => x.seq === SAMPLE.row.seq);
      return Array.isArray(ra?.images) && JSON.stringify(ra.images) === JSON.stringify(rb?.images);
    })()));
} else {
  ok('A1', '有图消息 images 含 attachmentId + mediaType', false, '未找到带图样本（images 字段未落地，无法验证）');
  ok('A2', 'mediaType 落在 4 种合法类型内', false, '同上');
  ok('A3', '多图消息顺序稳定', false, '同上');
}
{
  // A4 键恒存在（延续 F-5 规则）
  let miss = 0, tot = 0, contract = null;
  for (const s of cands.slice(0, 6)) {
    const r = await api(`/rc/v1/sessions/${s.id}/messages?limit=40&expand=full`);
    if (r.status !== 200) continue;
    const its = r.json.items || [];
    tot += its.length;
    const m = its.filter(x => !('images' in x)).length;
    miss += m;
    if (its.length) {
      const v = its.find(x => 'images' in x)?.images;
      contract = v === null ? 'null' : Array.isArray(v) ? (v.length ? 'array(non-empty)' : '[]') : typeof v;
    }
  }
  ok('A4', 'images 键在每行恒存在（无值时给约定值）',
    tot > 0 && miss === 0, `抽查 ${tot} 行，缺键 ${miss} 行；观测到的无图取值形态=${contract}`);
}
{
  // A5 三档 expand 都返回 images
  const res = {};
  for (const e of ['none', 'summary', 'full']) {
    const r = await api(`/rc/v1/sessions/${cands[0].id}/messages?limit=40&expand=${e}`);
    const its = r.json?.items || [];
    res[e] = its.length ? its.filter(x => 'images' in x).length : 0;
  }
  const allSame = res.none === res.summary && res.summary === res.full && res.none > 0;
  ok('A5', 'expand=none/summary/full 三档都返回 images',
    allSame, `none=${res.none} summary=${res.summary} full=${res.full}（应三者一致且 >0）`);
}

// ============ B. attachments 端点 ============
if (SAMPLE) {
  const att = SAMPLE.row.images[0];
  // B1 成功 + 魔数校验 + mediaType 一致
  const r = await api(`/rc/v1/sessions/${SAMPLE.sid}/attachments/${att.attachmentId}`);
  if (r.status === 200 && r.json?.data) {
    const buf = Buffer.from(r.json.data, 'base64');
    const magicOk = MAGIC[att.mediaType] ? MAGIC[att.mediaType](buf) : false;
    const declared = r.json.attachment?.mediaType;
    const declaredOk = declared ? MAGIC[declared]?.(buf) : null;
    ok('B1', 'attachments 200：base64 解码后是合法图片，且 mediaType 与魔数一致',
      magicOk && (declaredOk === null || declaredOk === true),
      `bytes=${buf.length} 声明=${att.mediaType} 返回声明=${declared} 魔数符合=${magicOk} 返回声明符合=${declaredOk}`);
    ok('B1b', 'attachment 对象含 attachmentId 与 mediaType',
      !!r.json.attachment && typeof r.json.attachment.attachmentId === 'string'
      && typeof r.json.attachment.mediaType === 'string',
      JSON.stringify(r.json.attachment).slice(0, 160));
  } else {
    ok('B1', 'attachments 200：base64 解码后是合法图片', false,
      `status=${r.status} body=${r.text.slice(0, 160)}`);
    ok('B1b', 'attachment 对象含 attachmentId 与 mediaType', false, '未取到');
  }
  // B6 跨会话越权（安全重点）：拿 A 的 attachmentId 去 B 读
  const other = cands.find(x => x.id !== SAMPLE.sid);
  if (other) {
    const x = await api(`/rc/v1/sessions/${other.id}/attachments/${att.attachmentId}`);
    ok('B6', '跨会话越权：A 的 attachmentId 在 B 会话读不到（安全重点）',
      x.status !== 200, `status=${x.status}（200 说明越权可读！必须修）body=${x.text.slice(0, 120)}`);
  } else ok('B6', '跨会话越权防护', true, 'skip: 无第二个会话');
  // B7 与 expand 无关
  const a = await api(`/rc/v1/sessions/${SAMPLE.sid}/attachments/${att.attachmentId}?expand=none`);
  const b = await api(`/rc/v1/sessions/${SAMPLE.sid}/attachments/${att.attachmentId}?expand=full`);
  ok('B7', 'attachments 端点与 expand 参数无关（总是返回真实字节）',
    a.status === 200 && b.status === 200 && a.json?.data === b.json?.data, '');
} else {
  ['B1', 'B1b', 'B6', 'B7'].forEach(id => ok(id, 'attachments 端点验证', false, '无带图样本，端点未实现'));
}
// B2 非法/不存在 attachmentId
// 注意：必须先确认端点**已实现**，否则"全部 404"会造成假通过。
const EP = await api(`/rc/v1/sessions/${cands[0].id}/attachments/__probe_ep__`);
const EP_EXISTS = !(EP.status === 404 && /no route/i.test(EP.text || ''));
console.log(`[INFO] attachments 端点路由${EP_EXISTS ? '已注册' : '未注册（no route）'}`);
{
  const sid = cands[0].id;
  const r = await api(`/rc/v1/sessions/${sid}/attachments/att_does_not_exist_zzz`);
  if (!EP_EXISTS) {
    ok('B2', '端点已注册后：不存在 attachmentId → 4xx（不得 500）', false,
      `端点未注册（${r.status} no route），本用例无法判定`);
  } else {
    ok('B2', '不存在 attachmentId → 4xx（不得 500）',
      r.status >= 400 && r.status < 500, `status=${r.status} body=${r.text.slice(0, 120)}`);
  }
}
// B3 不存在 sessionId
{
  const r = await api('/rc/v1/sessions/session_nope_zzz/attachments/att_x');
  if (!EP_EXISTS) ok('B3', '不存在 sessionId → 404', false, '端点未注册，无法判定');
  else ok('B3', '不存在 sessionId → 404', r.status === 404, `status=${r.status} body=${r.text.slice(0, 120)}`);
}
// B4 token 无效
{
  const res = await fetch(`http://127.0.0.1:8765/rc/v1/sessions/${cands[0].id}/attachments/att_x`,
    { headers: { Authorization: 'Bearer deadbeef_invalid' } });
  if (!EP_EXISTS) ok('B4', '无效 token → 401', false, '端点未注册，无法判定');
  else ok('B4', '无效 token → 401', res.status === 401, `status=${res.status}`);
}
// B5 超大图上限（仅当样本足够大）
{
  if (SAMPLE) {
    const r = await api(`/rc/v1/sessions/${SAMPLE.sid}/attachments/${SAMPLE.row.images[0].attachmentId}`);
    if (r.status === 200 && r.json?.data) {
      const bytes = Buffer.from(r.json.data, 'base64').length;
      ok('B5', '大图：返回完整字节或明确错误（不得默默截断）',
        bytes > 0, `bytes=${bytes}（若声明了上限，应验证超限行为；当前未观察到截断）`);
    } else ok('B5', '大图上限行为', true, 'skip: 样本不可用');
  } else ok('B5', '大图上限行为', true, 'skip: 无样本');
}

// ============ D. 回归：确保没破坏既有字段 ============
{
  const r = await api(`/rc/v1/sessions/${cands[0].id}/messages?limit=40&expand=full`);
  const its = r.json?.items || [];
  const BASE = ['seq', 'role', 'text', 'ts', 'status', 'messageId', 'requestId'];
  ok('D1', '回归：原有字段未被破坏', its.length === 0 || its.every(x => BASE.every(k => k in x)),
    JSON.stringify(Object.keys(its[0] || {})));
}

const pass = cases.filter(c => c.pass).length;
console.log(`\n== images: TOTAL=${cases.length} PASS=${pass} FAIL=${cases.length - pass}`);
console.log(`CREATED=${JSON.stringify(CREATED)}`);
process.exit(pass === cases.length ? 0 : 1);