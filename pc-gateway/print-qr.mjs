/**
 * pc-gateway/print-qr.mjs — 在终端打印 ASCII 二维码
 * 用法：node print-qr.mjs '<单行 JSON>'
 * 需要依赖 qrcode（package.json 声明）。若依赖缺失，降级为提示手输，不报错退出。
 */
const payload = process.argv[2];
if (!payload) {
  console.error('[print-qr] 缺少 payload 参数');
  process.exit(2);
}

try {
  const QR = await import('qrcode');
  // type:'terminal'：使用半块字符渲染彩色/单色终端二维码
  const s = await QR.toString(payload, { type: 'terminal', small: true, errorCorrectionLevel: 'L' });
  console.log(s);
} catch (e) {
  console.log('[print-qr] 二维码渲染不可用（' + (e?.message ?? e) + '），请手动输入上方配对信息。');
}
