// tests/v2/drive-app.mjs — 通过真机 UI 驱动 + 网关侧写入，验证 bug2/bug3
// 纪律：写只用**我自建**的测试会话；对 App 只做 UI 驱动（切换会话属于正常使用，不改他人数据）。
import { api } from './common.mjs';
import { execFileSync } from 'node:child_process';

const ADB = 'E:\\system\\android-sdk\\platform-tools\\adb.exe';
const SN = process.env.DSH_DEVICE || '192.168.2.65:37091';
const PKG = 'com.deepseek.dshremote';
const sleep = ms => new Promise(r => setTimeout(r, ms));

export function adb(args, opts = {}) {
  return execFileSync(ADB, ['-s', SN, ...args], {
    encoding: opts.binary ? 'buffer' : 'utf8',
    timeout: opts.timeout || 30000,
    ...opts,
  });
}
export function uiTexts() {
  adb(['shell', 'uiautomator', 'dump', '/sdcard/_ui.xml'], { timeout: 20000 });
  const xml = adb(['exec-out', 'cat', '/sdcard/_ui.xml'], { binary: true }).toString('utf8');
  return [...xml.matchAll(/text="([^"]{1,80})"/g)].map(m => m[1]);
}

/** 确保 App 在前台且 UI dump 有内容（无线 adb 常把 App 挤到后台） */
export async function ensureForeground(pkg = PKG, tries = 4) {
  for (let i = 0; i < tries; i++) {
    const act = adb(['shell', 'dumpsys', 'activity', 'activities']).split('\n')
      .find(l => l.includes('topResumedActivity')) || '';
    if (!act.includes(pkg)) {
      adb(['shell', 'am', 'start', '-n', `${pkg}/.ui.MainActivity`]);
      await sleep(4000);
      continue;
    }
    const t = uiTexts();
    if (t.length > 0) return true;
    await sleep(2000);
  }
  return false;
}
export function tapCenter(text) {
  const xml = adb(['exec-out', 'cat', '/sdcard/_ui.xml'], { binary: true }).toString('utf8');
  const re = new RegExp(`text="${text}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`);
  const m = xml.match(re);
  if (!m) return false;
  const cx = Math.round((+m[1] + +m[3]) / 2), cy = Math.round((+m[2] + +m[4]) / 2);
  adb(['shell', 'input', 'tap', String(cx), String(cy)]);
  return { cx, cy };
}
export { api, sleep, ADB, SN, PKG };