// tests/v2/scan-image-samples.mjs — 只读扫描：找带图片的现成样本
// 纪律：**纯 GET**。不创建会话、不发 prompt、不写任何数据。
import { api, fetchAll } from './common.mjs';

const list = await api('/rc/v1/sessions?limit=200');
if (list.status !== 200) { console.error('list failed', list.status); process.exit(2); }
const items = list.json.items || [];
console.log(`sessions total = ${items.length}`);

let scanned = 0, withImagesField = 0, withImagesNonEmpty = 0;
const samples = [];

for (const s of items) {
  if (s.messageCount <= 0) continue;
  scanned++;
  // 只取最后一页（最新消息最可能带图），控制成本
  const r = await api(`/rc/v1/sessions/${s.id}/messages?limit=60&expand=full`);
  if (r.status !== 200) continue;
  const its = r.json.items || [];
  const hasField = its.filter(x => 'images' in x).length;
  if (hasField) withImagesField++;
  const nonEmpty = its.filter(x => Array.isArray(x.images) && x.images.length > 0);
  if (nonEmpty.length) {
    withImagesNonEmpty++;
    if (samples.length < 10) {
      samples.push({
        sid: s.id, title: (s.title || '').slice(0, 40),
        seq: nonEmpty[0].seq, images: nonEmpty[0].images,
      });
    }
  }
  // 只扫前 40 个会话，避免全量拉取造成网关/DSH 压力
  if (scanned >= 40) break;
}

console.log(`scanned sessions = ${scanned}`);
console.log(`messages containing an 'images' key = ${withImagesField}`);
console.log(`messages with NON-EMPTY images = ${withImagesNonEmpty}`);
console.log('\n--- samples ---');
samples.forEach(s => console.log(`  ${s.sid} seq=${s.seq} title="${s.title}" images=${JSON.stringify(s.images).slice(0, 200)}`));
if (!samples.length) console.log('  (none found)');