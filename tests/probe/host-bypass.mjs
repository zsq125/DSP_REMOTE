// tests/probe/host-bypass.mjs — R-2 对抗性探测（独立复核，不采信开发的自证）
// 逐行镜像 Android 端 PairingParser.kt 的 host 判定逻辑：
//   looksLikeIpLiteral() 在主机名分支之前判形态；parseIpLiteral() 只接受点分十进制 IPv4，
//   解析不过返回 null（= 拒绝），**不得回退主机名放行**（D-R2-1 的关键）。
// 用途：① 复核修复  ② 作为长期回归探针
const IPV4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/;
const HOSTNAME = /^[A-Za-z0-9]([A-Za-z0-9._-]{0,253}[A-Za-z0-9])?$/;

function isIpv4(h) {                       // 四段、无前导零、每段 0..255
  const m = IPV4.exec(h);
  if (!m) return false;
  for (let i = 1; i <= 4; i++) {
    const part = m[i];
    if (part.length > 3) return false;
    if (part.length > 1 && part.startsWith('0')) return false;
    const v = parseInt(part, 10);
    if (!(v >= 0 && v <= 255)) return false;
  }
  return true;
}

function looksLikeIpLiteral(h) {           // ← 必须先于主机名分支
  if (!h) return false;
  const lower = h.toLowerCase();
  if (lower.includes(':')) return true;             // IPv6 / 带端口
  if (lower.startsWith('0x') || lower.includes('x')) return true;  // 十六进制
  if ([...h].every(c => (c >= '0' && c <= '9') || c === '.')) return true; // 纯数字/点分
  return false;
}

function parseIpLiteral(h) {               // 只接受点分十进制 IPv4，否则 null(=拒绝)
  const s = String(h).trim();
  if (!s) return null;
  if (s.includes(':') || s.includes('x') || s.includes('X')) return null;
  if (!isIpv4(s)) return null;
  const parts = s.split('.');
  if (parts.length !== 4) return null;
  return parts.map(Number);
}

function isValidHost(h) {
  if (!h) return false;
  if (looksLikeIpLiteral(h)) return parseIpLiteral(h) !== null;
  return HOSTNAME.test(h);
}

function isPrivateHost(host) {
  const h = String(host).trim().toLowerCase();
  if (h === 'localhost' || h === '127.0.0.1' || h === '::1') return true;
  if (looksLikeIpLiteral(h)) {
    const p = parseIpLiteral(h);
    if (!p) return false;                    // ★ 关键修复：解析失败 ⇒ 拒绝（原先回退成主机名放行）
    if (p[0] === 10) return true;
    if (p[0] === 192 && p[1] === 168) return true;
    if (p[0] === 172 && p[1] >= 16 && p[1] <= 31) return true;
    if (p[0] === 169 && p[1] === 254) return true;
    if (p[0] === 127) return true;
    return false;
  }
  return true;                                // 真正的主机名：无法静态判定，放行（有意取舍）
}

const accept = host => isValidHost(host) && isPrivateHost(host);

// ---- PM 指定清单 ----
const MUST_REJECT = [
  '172.15.0.1', '172.32.0.1', '192.169.0.1', '11.0.0.1', '8.8.8.8', '1.1.1.1',
  '0.0.0.0', '192.168.1.1.1', '192.168.1', '192.168.002.1', '1.2.3.999',
  '288.0.0.1', '0177.0.0.1', '2130706433', '0x7f000001', '192.168.1.5:8765',
  '2001:4860:4860::8888', '::ffff:8.8.8.8',
  // ---- 本轮新增对抗样本 ----
  '0X7F000001',            // 大写 X 的十六进制
  '0x7f.0.0.1',            // 十六进制 + 点分混合
  '127.1',                 // 两段简写（等价 127.0.0.1）
  '127.0.0.1.',            // 尾点
  '%31%32%37%2e%30%2e%30%2e%31', // URL 编码
  '１９２.１６８.１.１',        // 全角数字
  '[::1]',                 // IPv6 方括号
  '0x08080808',            // = 8.8.8.8 公网
  '134744072',             // = 8.8.8.8 纯十进制
];
const MUST_ACCEPT = [
  '10.0.0.1', '10.255.255.255', '172.16.0.1', '172.31.255.255',
  '192.168.1.5', '169.254.185.79', '127.0.0.1', 'localhost',
  'my-pc', 'my-pc.local', 'dsh-host',
];

let bad = 0;
console.log('=== MUST_REJECT ===');
for (const h of MUST_REJECT) {
  const a = accept(h);
  if (a) { bad++; console.log(`  [BYPASS]  ${h} -> 被放行`); } else console.log(`  [ok]      ${h} -> 拒绝`);
}
console.log('=== MUST_ACCEPT ===');
for (const h of MUST_ACCEPT) {
  const a = accept(h);
  if (!a) { bad++; console.log(`  [FALSE-REJECT] ${h} -> 被误拒`); } else console.log(`  [ok]      ${h} -> 放行`);
}
console.log(bad === 0 ? '\nALL AS EXPECTED' : `\n${bad} DEVIATION(S)`);
process.exit(bad === 0 ? 0 : 1);
