// tests/v2/sample-conn.mjs — 锁屏/前台两种状态下，App↔网关 8765 的连接数采样
// 目的：为 bug2「PC 更新不同步」取证——锁屏时 WS 是否被回收且未重连。
// 只读：不发送任何 prompt、不修改任何会话。
// 用法: node sample-conn.mjs <标签> <轮数> <间隔ms>
import { execFileSync } from 'node:child_process';

const ADB = 'E:\\system\\android-sdk\\platform-tools\\adb.exe';
const SN = process.env.DSH_DEVICE || '192.168.2.65:37091';
const LABEL = process.argv[2] || 'sample';
const ROUNDS = Number(process.argv[3] || 6);
const GAP = Number(process.argv[4] || 5000);

const sh = cmd => {
  try { return execFileSync(ADB, ['-s', SN, 'shell', cmd], { encoding: 'utf8', timeout: 20000 }); }
  catch { return ''; }
};
const rows = [];
for (let i = 0; i < ROUNDS; i++) {
  const ts = new Date().toISOString().slice(11, 23);
  const tcp6 = sh("cat /proc/net/tcp6 2>/dev/null | grep 223D");
  const tcp = sh("cat /proc/net/tcp 2>/dev/null | grep 223D");
  const all = (tcp6 + tcp).trim();
  const lines = all ? all.split('\n').filter(Boolean) : [];
  // /proc/net/tcp* 列：sl local rem st tx:rx tr:when retrnsmt uid timeout inode
  // st=01 ESTABLISHED, 08 CLOSE_WAIT, 06 TIME_WAIT, 0A LISTEN
  const stateOf = l => (l.trim().split(/\s+/)[3] || '').toUpperCase();
  const NAME = { '01': 'ESTABLISHED', '08': 'CLOSE_WAIT', '06': 'TIME_WAIT', '0A': 'LISTEN', '02': 'SYN_SENT', '03': 'SYN_RECV' };
  const count = {};
  for (const l of lines) { const s = stateOf(l); count[s] = (count[s] || 0) + 1; }
  const est = count['01'] || 0;
  const detail = Object.entries(count).map(([s, n]) => `${NAME[s] || s}=${n}`).join(',') || 'none';
  const screenOn = (sh("dumpsys power | grep -m1 mWakefulness") || '').includes('Awake');
  const focus = (sh("dumpsys window | grep -m1 mCurrentFocus") || '').split('=').pop().trim();
  const rows2 = { ts, established: est, states: detail, screenOn, focus };
  rows.push(rows2);
  console.log(`[${ts}] ${LABEL}  ESTABLISHED=${est}  [${detail}]  screenOn=${screenOn}  focus=${focus.slice(0, 50)}`);
  if (i < ROUNDS - 1) await new Promise(r => setTimeout(r, GAP));
}
console.log('\nJSON ' + JSON.stringify(rows));