# 图片功能测试报告（基线轮 · 功能尚未在运行态生效）

【环境】
- 方案 A：**8765 pid = 17620 == 3080 pid = 17620（同进程 ✅）**
- 进程启动：**2026-10-12 13:48:06**
- 网关代码最后修改：`ws-hub.mjs` **13:58:52** / `http.mjs` 13:58:36 / `dsh.mjs` 13:58:15
- **代码晚于进程启动 ⇒ 需重启 DSH 才生效**（重启由用户决定，**我未重启**）

【用例总览】**总计 14 条（全部为新增），PASS 2 / FAIL 12 / BLOCKED 0**

---

## 【FAIL 明细】

### ❌ F-IMG-1【P0·功能未生效】`/messages` 无 `images` 字段
- **用例**：A4（键恒存在）、A5（三档 expand 都返回）
- **证据**：抽查 6 个会话共 **240 行，`images` 键存在 0 行**；`none/summary/full` 三档均为 `0/40`
- **根因**：代码已写（`dsh.mjs:59-75`），但运行中的进程是 13:48 启动的旧代码，**未重启**
- **修复建议**：重启 DSH 后重测（**不是代码缺陷，是部署状态**）

### ❌ F-IMG-2【P0】`attachments` 端点未注册
- **用例**：B1 / B1b / B2 / B3 / B4 / B6 / B7
- **证据**：`GET /rc/v1/sessions/{id}/attachments/{aid}` → `404 {"error":{"code":"not_found","message":"no route for GET ..."}}`
- **根因**：同上，路由代码在 `http.mjs` 已写但未加载
- **备注**：我已加**防假通过门控** —— 端点未注册时 B2/B3/B4 一律判 FAIL 并注明"无法判定"，
  避免"全部 404 ⇒ 错误码正确"的假通过

### ❌ F-IMG-3【BLOCKED·无样本】找不到带图会话，A1/A2/A3 无法判定
- **现象**：只读扫描 **40 个会话**，`images` 非空样本 **0 个**
- **重要发现**：`~/.dsh/attachments/` 下**真实存在 412 个图片附件**（按魔数识别：
  **png 163 / jpeg 34 / webp 3**，路径形如 `attachments/v1/objects/00/0008d8da…`），
  说明**用户确实发过图**，只是网关 `/messages` 尚未把 `images` 暴露出来。
- **修复建议**：重启后重跑 `scan-image-samples.mjs`，应能从这些历史会话里定位到样本。

---

## 【代码层只读审查结论（未执行，供你参考）】

| 项 | 代码约定 | 与契约是否一致 |
|---|---|---|
| `images` 空值 | `dsh.mjs:70` 注释明写「**an empty array means "no images"; the key is always present (F-5 rule)**」 | ✅ 与 PM 要求的"键恒存在"一致，我按此设判据 |
| `images` 取值 | 仅取 `b.type==='image' && b.attachment` 的块，映射为 `{attachmentId, mediaType}` | ✅ 符合你给的 JSON 形状 |
| 大图上限 | `ATTACHMENT_MAX_BYTES = 8 * 1024 * 1024`（8 MiB，按**解码后字节**计）；`http.mjs` 注释「Oversized images are refused with **413**, never silently truncated」 | ✅ 满足"不得默默截断"，我的 B5 按此判 |
| `attachmentId` | 注释强调是 **OPAQUE storage id, never parse it** | ✅ 我不会去解析它，只做不透明比对 |
| `attachment()` | 直通 `dsh.attachment(sid, aid)` | ✅ |

---

## 【BLOCKED 明细】
| 项 | 阻塞原因 |
|---|---|
| A1/A2/A3（带图样本的字段/顺序/mediaType） | `images` 未落地 ⇒ 无从取样本。**不是"造图"受限，而是字段本身没上线** |
| B1/B6/B7（解码魔数、跨会话越权、与 expand 无关） | 端点未注册 |
| B5（8 MiB 上限 / 413） | 需端点上线，且需一个 >8 MiB 的样本；当前最大样本约 1.36 MB，**未达上限**，只能验证"未截断"，无法验证 413 分支 |
| C（App 侧 base64/尺寸/三态/OOM/容错） | APK 未交付 |
| D2（WS `session.event` 是否带 `images` 引用、不推 base64） | 需端点与字段上线后再验 |

> **关于"造图样本"**：我**没有**尝试造图。当前 `/prompt` 只接受 `text`，
> 网关与 DSH 均未暴露"发送图片"的入口；且造图必须写会话，只会用我自己新建的会话。
> 现有 412 个真实附件足以做端到端验证，**无需造图**。

---

## 【我创建的测试会话】
**本轮 0 个。** 全部为只读探测（GET `/sessions`、`GET /messages`、`GET /attachments`），
未创建会话、未发送任何 prompt、未写入任何会话。

---

## 【我自己脚本的问题（如实报告）】
1. **首版存在假通过（已修正）**：最初 B2/B3/B4 因"端点全部返回 404"而被判 PASS，
   这**掩盖了端点未实现**的事实。已加 `EP_EXISTS` 门控：先用 `no route` 特征判断路由是否注册，
   未注册则一律判 FAIL 并注明"无法判定"。修正后 PASS 从 5 降到 **2**（更诚实）。
2. **A3 语法错误**：IIFE 括号不匹配导致 `SyntaxError`，已修正。
3. 上一轮已修正的 `wait()` 竞态等经验在本轮沿用（`test-images.mjs` 未使用 `wait()`）。

---

## 【结论】

**无法放行** —— 但**当前 12 条 FAIL 中没有一条是代码缺陷**，全部源于
**网关代码已交付（13:58）但运行态未加载（进程 13:48 启动）**。

**下一步需要你做一件事**：重启 DSH（**由你决定，我未重启**）。重启后我立即重跑：

```powershell
node tests\v2\test-images.mjs        # 14 条，预期全绿或暴露真实缺陷
node tests\v2\scan-image-samples.mjs # 确认能定位到带图样本（预期能，附件目录有 412 个）
```

**重启后重点盯的 3 个风险点**（我会重点验证）：
1. **B6 跨会话越权**（安全重点）：拿 A 会话的 `attachmentId` 去 B 会话读，**必须失败**
2. **A4 键恒存在**：每行都必须有 `images` 键，无图时为 `[]`（延续 F-5 规则，避免 App NPE）
3. **B5 上限行为**：>8 MiB 必须 **413**，不得静默截断（当前最大样本仅 1.36 MB，需另找大样本或让开发提供）

**未验证项（诚实清单）**：App 侧全部（C 组）；WS `session.event` 的 images 引用；413 上限分支。