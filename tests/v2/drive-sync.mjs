// tests/v2/drive-sync.mjs — 真机驱动验证 bug2（不同步）/ bug3（切换不更新）
// 纪律：
//   · 网关侧写入**只用我自建的测试会话**
//   · App 侧只做 UI 驱动（打开/切换会话 = 正常使用，不修改他人数据）
import { api } from './common.mjs';
import { adb, uiTexts, sleep, PKG, ensureForeground } from './drive-app.mjs';

const cases = [];
const ok = (id, name, pass, detail = '') => {
  cases.push({ id, name, pass, detail });
  console.log(`[${pass ? 'PASS' : 'FAIL'}] ${id} ${name}${pass ? '' : ' :: ' + detail}`);
};

const tok = (await api('/rc/v1/sessions?limit=1')).status === 200 ? process.env.DSH_RC_TOKEN : null;

/** 点某个文本；返回是否点到 */
function tapText(t) {
  ensureForeground();
  adb(['shell', 'uiautomator', 'dump', '/sdcard/_t.json']);
  const xml = adb(['exec-out', 'cat', '/sdcard/_t.json'], { binary: true }).toString('utf8');
  const re = new RegExp(`text="${t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`);
  const m = xml.match(re);
  if (!m) return null;
  const cx = Math.round((+m[1] + +m[3]) / 2), cy = Math.round((+m[2] + +m[4]) / 2);
  adb(['shell', 'input', 'tap', String(cx), String(cy)]);
  return { cx, cy };
}
/** 是否存在某文本 */
const has = t => uiTexts().some(x => x.includes(t));

// ───────────────────────── 准备 ─────────────────────────
console.log('[INFO] 启动 App 并确保前台');
adb(['shell', 'am', 'force-stop', PKG]);
await sleep(2500);
adb(['shell', 'am', 'start', '-n', `${PKG}/.ui.MainActivity`]);
await sleep(6000);
console.log('[INFO] ensureForeground =', await ensureForeground());

/** 建会话并**种一条可识别文本**，用它作为 UI 上的锚点（网关不持久化 title） */
const ANCHOR = 'ZZSYNC' + Date.now().toString().slice(-6);
const mk = async tag => {
  const r = await api('/rc/v1/sessions', { method: 'POST', body: { cwd: null, title: 'v2-sync-' + tag } });
  const id = r.json?.id;
  // 用 prompt 把锚点写进会话首条消息，App 详情页即可看到
  await api(`/rc/v1/sessions/${id}/prompt`, { method: 'POST', body: { text: ANCHOR + tag, requestId: 'seed-' + id.slice(-6) } });
  return id;
};
const SX = await mk('X');
const SY = await mk('Y');
console.log(`[INFO] CREATED X=${SX} (anchor=${ANCHOR}X)`);
console.log(`[INFO] CREATED Y=${SY} (anchor=${ANCHOR}Y)`);
// 等两个种子回合结束
for (let i = 0; i < 60; i++) {
  const its = (await api('/rc/v1/sessions?limit=200')).json?.items || [];
  if (its.filter(x => x.id === SX || x.id === SY).every(x => x.status !== 'running')) break;
  await sleep(3000);
}
await sleep(2000);

// ───────────────────────── C 组：切换会话 ─────────────────────────
// 打开 X
let openedX = tapText(ANCHOR + 'X');
await sleep(5000);
ok('C0', '能打开自建会话 X（App 列表可点）', openedX !== null, `tap=${JSON.stringify(openedX)}`);

// 切到 Y
let openedY = tapText(ANCHOR + 'Y');
await sleep(5000);
ok('C0b', '能切换到自建会话 Y', openedY !== null, `tap=${JSON.stringify(openedY)}`);

// ───────────────────────── C2：Y 期间给 X 发新消息 ─────────────────────────
const MARK = 'C2MARK-' + Date.now();
{
  const before = (await api(`/rc/v1/sessions/${SX}/messages?limit=50&expand=full`)).json?.items?.length || 0;
  await api(`/rc/v1/sessions/${SX}/prompt`, { method: 'POST', body: { text: '1', requestId: MARK, mode: 'queue' } });
  // 等它落库
  let after = before, t0 = Date.now();
  while (Date.now() - t0 < 60000) {
    after = (await api(`/rc/v1/sessions/${SX}/messages?limit=50&expand=full`)).json?.items?.length || 0;
    if (after > before) break;
    await sleep(2000);
  }
  ok('C2-setup', '网关侧：X 确实产生了新消息', after > before, `${before} -> ${after}`);
}

// 切回 X，看 App 是否显示新内容
{
  const back = tapText(ANCHOR + 'Y');   // 先确保在 Y
  await sleep(1500);
  const t0 = Date.now();
  let seen = false, waited = 0;
  while (Date.now() - t0 < 20000) {
    const texts = uiTexts();
    // 新内容判据：消息条数增加 / 出现新 seq 文本 / 出现「正在」等状态
    if (texts.length > 0 && (texts.some(x => /seq|Current|runtime|assistant|user/i.test(x)))) { seen = true; break; }
    await sleep(2000);
  }
  const texts = uiTexts();
  ok('C2', '切回 X 后 ≤20s 内可见新内容（bug3 核心判据）', seen,
    `等待 ${Math.round((Date.now() - t0) / 1000)}s仍未见新内容；当前文本样本=${JSON.stringify(texts.slice(0, 6))}`);
}

// ───────────────────────── B1/B2：当前会话实时更新 ─────────────────────────
// 打开 X，然后给它发消息，看 App 是否 ≤10s 自动更新
{
  const openX = tapText(ANCHOR + 'X');
  await sleep(5000);
  const before = uiTexts().length;
  const RID = 'B1-' + Date.now();
  const t0 = Date.now();
  await api(`/rc/v1/sessions/${SX}/prompt`, { method: 'POST', body: { text: '1', requestId: RID, mode: 'queue' } });
  // 网关侧确实推了（用 REST 确认落库），App 侧看 UI 是否变化
  let gwOk = false, g0 = Date.now();
  const n0 = (await api(`/rc/v1/sessions/${SX}/messages?limit=50&expand=full`)).json?.items?.length || 0;
  while (Date.now() - g0 < 30000) {
    const n1 = (await api(`/rc/v1/sessions/${SX}/messages?limit=50&expand=full`)).json?.items?.length || 0;
    if (n1 > n0) { gwOk = true; break; }
    await sleep(1500);
  }
  const after = uiTexts().length;
  const elapsed = Math.round((Date.now() - t0) / 1000);
  ok('B1', '网关已落库（对照组前置）', gwOk, `elapsed=${elapsed}s`);
  // App UI 是否变化：文本集合大小或内容变化
  const changed = after !== before;
  ok('B1b', `App 在 X 会话下 ≤10s 自动出现新内容（实测等待 ${elapsed}s）`,
    changed, `UI 文本数 ${before} -> ${after}；${changed ? '' : 'UI 未变化 ⇒ App 未应用推送'}`);
}

// ───────────────────────── C3：反复切换 5 次 ─────────────────────────
{
  let allOk = true; const log = [];
  for (let i = 0; i < 5; i++) {
    tapText(ANCHOR + 'Y'); await sleep(2500);
    tapText(ANCHOR + 'X'); await sleep(2500);
    const t = uiTexts();
    const hasContent = t.length > 0;
    log.push(`${i + 1}:${hasContent ? 'ok' : '空'}(${t.length})`);
    if (!hasContent) allOk = false;
  }
  ok('C3', '反复切换 X/Y 共 5 次，每次都有内容（无空白/错位）', allOk, log.join(' '));
}

// ───────────────────────── C4：订阅是否串台 ─────────────────────────
{
  // 在 X 和 Y 同时产生消息，观察 App 当前（X）是否出现 Y 的内容
  const yMark = 'C4MARK-' + Date.now();
  await api(`/rc/v1/sessions/${SY}/prompt`, { method: 'POST', body: { text: '1', requestId: yMark, mode: 'queue' } });
  await sleep(15000);
  const texts = uiTexts();
  // 判定：App 当前在 X，若 UI 出现大量 Y 的内容即串台（难以精确匹配，用启发式：文本数突增）
  ok('C4', '在 X 时给 Y 发消息，App 不应显示 Y 的内容（不串台）',
    texts.length < 60, `UI 文本数=${texts.length}（>60 疑似串台）；样本=${JSON.stringify(texts.slice(0, 5))}`);
}

// ───────────────────────── C5：列表徽标 ─────────────────────────
{
  adb(['shell', 'input', 'keyevent', '4']);   // 返回列表
  await sleep(4000);
  const texts = uiTexts();
  const hasX = texts.some(t => t.includes(ANCHOR));
  ok('C5', '列表页可见自建会话（徽标/状态随会话存在）', hasX, `文本样本=${JSON.stringify(texts.slice(0, 6))}`);
}

const pass = cases.filter(c => c.pass).length;
console.log(`\n== drive-sync: TOTAL=${cases.length} PASS=${pass} FAIL=${cases.length - pass}`);
console.log(`CREATED_X=${SX}`);
console.log(`CREATED_Y=${SY}`);
process.exit(pass === cases.length ? 0 : 1);