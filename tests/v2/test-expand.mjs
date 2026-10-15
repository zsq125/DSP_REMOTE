// tests/v2/test-expand.mjs — 需求 3: /messages 新字段与 expand 三级 + 缓存正确性 + 向后兼容
// 纪律：**只读**。本文件不创建、不写入任何会话；对既有会话仅 GET。
import { api, fetchAll } from './common.mjs';

const cases = [];
const ok = (id, name, pass, detail = '') => {
  cases.push({ id, name, pass, detail });
  console.log(`[${pass ? 'PASS' : 'FAIL'}] ${id} ${name}${pass ? '' : ' :: ' + detail}`);
};
const hasVal = v => v !== null && v !== undefined && v !== '' &&
  !(typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === 0);

// ---- 选一个**只读**的、含 tool/reasoning 的历史会话 ----
const list = await api('/rc/v1/sessions?limit=60');
if (list.status !== 200) { console.error('list failed', list.status); process.exit(2); }
const cand = (list.json.items || []).filter(s => s.status === 'idle' && s.messageCount > 40);
if (!cand.length) { console.error('no stable session with enough history'); process.exit(2); }
const SID = cand[0].id;
console.log(`[INFO] read-only session: ${SID} (idle, ${cand[0].messageCount} msgs)`);

// 三级全量取回（before 游标，确定性）
const N = await fetchAll(SID, 'none');
const S = await fetchAll(SID, 'summary');
const F = await fetchAll(SID, 'full');
if (N.err || S.err || F.err) { console.error('fetch err', N.err, S.err, F.err); process.exit(2); }
const M = arr => new Map(arr.map(r => [r.seq, r]));
const mN = M(N.items), mS = M(S.items), mF = M(F.items);
const common = [...mS.keys()].filter(k => mF.has(k) && mN.has(k)).sort((a, b) => a - b);
console.log(`[INFO] counts none=${N.items.length} summary=${S.items.length} full=${F.items.length} common=${common.length}\n`);

const any = (m, pred) => [...m.values()].some(pred);
const cnt = (m, pred) => [...m.values()].filter(pred).length;
const maxLen = (m, f) => Math.max(0, ...[...m.values()].map(x => (x[f] || '').length));

// ---------- E1 字段存在性（向后兼容：旧 7 字段不变） ----------
const BASE_KEYS = ['seq', 'role', 'text', 'ts', 'status', 'messageId', 'requestId'];
{
  const okAll = [mN, mS, mF].every(m => [...m.values()].every(x => BASE_KEYS.every(k => k in x)));
  ok('E1', '向后兼容：原有 7 字段在三级 expand 下均存在', okAll, '');
}

// ---------- E2 新字段存在性 ----------
const NEW_KEYS = ['kind', 'collapsedSummary'];
{
  const okAll = [mN, mS, mF].every(m => [...m.values()].every(x => NEW_KEYS.every(k => k in x)));
  ok('E2', '新字段 kind / collapsedSummary 在三级下均存在', okAll, '');
}

// ---------- E3 expand=none：大字段应为空 ----------
{
  const rBad = cnt(mN, x => hasVal(x.reasoning) && (x.reasoning || '').length > 200);
  const oBad = cnt(mN, x => hasVal(x.toolOutput));
  const iBad = cnt(mN, x => hasVal(x.toolInput) && JSON.stringify(x.toolInput || '').length > 400);
  ok('E3', 'expand=none：reasoning/toolOutput/toolInput 不应返回大内容',
    rBad === 0 && oBad === 0 && iBad === 0,
    `reasoning>200字符=${rBad} toolOutput有值=${oBad} toolInput>400字符=${iBad}`);
}

// ---------- E4 expand=summary：大字段被截断（约 2000） ----------
{
  const rMax = maxLen(mS, 'reasoning'), oMax = maxLen(mS, 'toolOutput');
  ok('E4', 'expand=summary：大字段被截断到约 2000 字符',
    rMax > 0 && rMax <= 2100 && oMax > 0 && oMax <= 2100,
    `reasoning max=${rMax} toolOutput max=${oMax}`);
}

// ---------- E5 expand=full：全文（应 > summary 上限） ----------
{
  const rMax = maxLen(mF, 'reasoning'), oMax = maxLen(mF, 'toolOutput');
  const rS = maxLen(mS, 'reasoning'), oS = maxLen(mS, 'toolOutput');
  ok('E5', 'expand=full：reasoning/toolOutput 全文（长度 > summary）',
    (rMax >= rS && oMax >= oS) && (rMax > 2100 || oMax > 2100 || common.length === 0),
    `full: r=$rMax o=$oMax ; summary: r=$rS o=$oS`);
}

// ---------- E6 kind 取值 ----------
{
  const kinds = new Set([...mF.values()].map(x => x.kind));
  const legal = new Set(['message', 'reasoning', 'tool-call', 'tool-result', null, undefined]);
  const bad = [...kinds].filter(k => !legal.has(k));
  ok('E6', 'kind 取值合法（message/reasoning/tool-call/tool-result）', bad.length === 0,
    `observed=${[...kinds].map(k => JSON.stringify(k)).join(',')}`);
}

// ---------- E7 toolCallId 配对 call↔result ----------
{
  const calls = new Set([...mF.values()].filter(x => x.kind === 'tool-call' && x.toolCallId).map(x => x.toolCallId));
  const results = [...mF.values()].filter(x => x.kind === 'tool-result');
  const withId = results.filter(x => x.toolCallId);
  const matched = withId.filter(x => calls.has(x.toolCallId));
  ok('E7', 'toolCallId 能把 tool-call 与 tool-result 配对',
    calls.size > 0 && matched.length > 0 && matched.length === withId.length,
    `calls=${calls.size} results=${results.length} results有id=${withId.length} 成功配对=${matched.length}`);
}

// ---------- E8 tool-call 必有 toolName / toolInput ----------
{
  const tcs = [...mF.values()].filter(x => x.kind === 'tool-call');
  const badName = tcs.filter(x => !x.toolName).length;
  const noInput = tcs.filter(x => !('toolInput' in x)).length;
  ok('E8', 'tool-call 行携带 toolName 与 toolInput 字段',
    tcs.length > 0 && badName === 0 && noInput === 0,
    `tool-call=${tcs.length} 缺toolName=${badName} 缺toolInput键=${noInput}`);
}

// ---------- E9 isError 与 status=failed 的关系 ----------
{
  const failed = [...mF.values()].filter(x => x.status === 'failed');
  const withFlag = failed.filter(x => x.isError === true);
  const notFailedWithErr = [...mF.values()].filter(x => x.status !== 'failed' && x.isError === true).length;
  ok('E9', 'status=failed ⇒ isError=true；非 failed 不应为 true',
    failed.length === 0 || (withFlag.length === failed.length && notFailedWithErr === 0),
    `failed=${failed.length} 其中isError=true=${withFlag.length} 非failed却isError=true=${notFailedWithErr}`);
}

// ---------- E10 text 语义不变 ----------
{
  const toolRows = [...mF.values()].filter(x => x.role === 'tool');
  const okTool = toolRows.every(x => /^\[tool\] /.test(x.text || ''));
  ok('E10', '向后兼容：tool 行 text 仍为 "[tool] <name>"',
    toolRows.length === 0 || okTool,
    `tool 行=${toolRows.length} 不符合=${toolRows.filter(x => !/^\[tool\] /.test(x.text || '')).length}`);
}

// ---------- E11 非法 expand 值的行为 ----------
{
  const r = await api(`/rc/v1/sessions/${SID}/messages?limit=5&expand=bogus`);
  const s = await api(`/rc/v1/sessions/${SID}/messages?limit=5&expand=summary`);
  const is400 = r.status === 400 && r.json?.error?.code === 'invalid_request';
  const fellBack = r.status === 200 && r.json?.items?.length === s.json?.items?.length;
  ok('E11', '非法 expand=bogus → 400 或回退 default（记录实际行为）',
    is400 || fellBack,
    is400 ? '400 invalid_request' : (fellBack ? '回退到 default(200)' : `status=${r.status}`));
}

// ---------- E12 缓存正确性：不同 expand 不互相污染 ----------
{
  // 先取 full（大字段最长），再取 none（应不受 full 缓存影响）
  const f1 = await api(`/rc/v1/sessions/${SID}/messages?limit=100&expand=full`);
  const n1 = await api(`/rc/v1/sessions/${SID}/messages?limit=100&expand=none`);
  const s1 = await api(`/rc/v1/sessions/${SID}/messages?limit=100&expand=summary`);
  const bigR = cnt(new Map((f1.json.items || []).map(x => [x.seq, x])), x => (x.reasoning || '').length > 2100);
  const bigAfterNone = cnt(new Map((n1.json.items || []).map(x => [x.seq, x])), x => (x.reasoning || '').length > 2100);
  ok('E12', '缓存不互相污染：取 full 后 none 仍无大字段',
    bigR > 0 && bigAfterNone === 0,
    `full大字段=${bigR} 随后none大字段=${bigAfterNone}`);
  const afterS = cnt(new Map((s1.json.items || []).map(x => [x.seq, x])), x => (x.reasoning || '').length > 2100);
  ok('E12b', '缓存不互相污染：none 之后 summary 截断仍生效',
    afterS === 0, `summary大字段=${afterS}`);
}

// ---------- E13 同 expand 重复请求一致 ----------
{
  const a = await api(`/rc/v1/sessions/${SID}/messages?limit=100&expand=full`);
  const b = await api(`/rc/v1/sessions/${SID}/messages?limit=100&expand=full`);
  const ja = JSON.stringify(a.json?.items);
  const jb = JSON.stringify(b.json?.items);
  ok('E13', '同 expand 重复请求 → 内容一致（命中缓存）', ja === jb,
    `lenA=${ja?.length} lenB=${jb?.length} equal=${ja === jb}`);
}

// ---------- E14 三级行数一致性（关键：无内容丢失） ----------
{
  const onlyF = [...mF.keys()].filter(k => !mS.has(k)).length;
  const onlyN = [...mN.keys()].filter(k => !mF.has(k)).length;
  ok('E14', 'expand 三级返回**相同的消息行集合**（不应因截断丢行）',
    onlyF === 0 && onlyN === 0,
    `none=${N.items.length} summary=${S.items.length} full=${F.items.length} ` +
    `summary独有=${onlyS()} full有summary无=${onlyF} none有full无=${onlyN}`);
  function onlyS() { return [...mS.keys()].filter(k => !mF.has(k)).length; }
}

const pass = cases.filter(c => c.pass).length;
console.log(`\n== expand: TOTAL=${cases.length} PASS=${pass} FAIL=${cases.length - pass}`);
process.exit(pass === cases.length ? 0 : 1);
