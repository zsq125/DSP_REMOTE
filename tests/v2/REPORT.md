# v2 新增功能测试报告

【环境】
- 方案 A 验证：**8765 pid = 9548，3080 pid = 9548，同进程 = True** ✅（3099 第二实例已下线，符合 v2 架构）
- 进程启动时间：2026-10-12 10:21:07
- 网关代码：`pc-gateway/lib/dsh.mjs` 等，最后修改 2026-10-12 09:14~09:15，**早于进程启动 ⇒ 改动已加载**
- token：`<token>`
- 测试时间：2026-10-12

【用例总览】
| 套件 | 总数 | PASS | FAIL |
|---|---|---|---|
| test-mode.mjs（需求 1 mode） | 16 | 15 | **1** |
| test-expand.mjs（需求 3 字段/expand/缓存） | 15 | 12 | **3** |
| test-ws.mjs（需求 3 WS/流式） | 17 | 16 | **1** |
| test-regression.mjs（回归） | 15 | 15 | 0 |
| **合计** | **63** | **58** | **5** |

（另有 5 条是我自己脚本的缺陷，已在执行中修正并复跑通过，**不计入产品 FAIL**）

---

## 【FAIL 明细】

### ❌ F-1【P1】WS prompt 未校验 `mode`，与 REST 不一致
- **用例**：W12
- **现象**：WS `prompt` 帧带 `mode:"bogus"`，服务端回 **`ack ok=true`** 并实际入队
- **根因（证据）**：
  ```
  REST: POST /rc/v1/sessions/{id}/prompt {"mode":"bogus"}  → 400 invalid_request   ✔
  WS  : {"type":"prompt","mode":"bogus"}                   → {"type":"ack","ok":true}  ✘
  ```
  两条入口共用同一契约，但校验只在 REST 侧做了，WS 侧漏检。
- **影响**：App 若走 WS 发指令，非法 `mode` 不会被拦截；更关键的是 **steer/queue 语义在 WS 上可能被静默降级为 steer**，用户"排队不打断"的意图失效。
- **修复建议**：`ws-hub.mjs` 的 prompt 分支复用 REST 的 mode 校验，非法值回 `{"type":"error","id":<同一 id>,"code":"invalid_request"}`。

### ❌ F-2【P1】`expand=summary` 硬截断在 2000 行，丢失最新约 21 条消息
- **用例**：E14
- **现象**：同一会话、同一 before 游标翻页取全量：
  ```
  expand=none    → 2021 行（maxSeq=4058）
  expand=summary → 2000 行（maxSeq=4006）   ← 少了 21 行
  expand=full    → 2021 行（maxSeq=4058）
  ```
  用 limit=50/100/200 翻页，总数恒为 **2000**，maxSeq 恒为 **4006** ⇒ 是**服务端硬上限**，不是分页问题。
- **根因**：summary 模式的取数路径上加了 2000 条上限（应为截断"每个字段的字符数"，却误变成"截断行数"）。丢失的 21 行恰好是**最新的**（seq 4014~4058）。
- **影响（严重）**：默认 `expand=summary` 下，**用户看不到最近的消息**。App 默认走 summary ⇒ 详情页会"少 21 条最新消息"，且随会话增长丢失更多。
- **修复建议**：把 2000 从"行数上限"改为"单字段字符上限"；行数上限应与 none/full 一致或更大。
- **复现**：`node tests/v2/probe-summary-loss.mjs <sid>`

### ❌ F-3【P2】`expand=none` 未真正清空 `toolInput`（泄漏完整工具入参）
- **用例**：E3
- **现象**：`expand=none` 下 `toolInput` 仍是**完整对象**，最长 **8696 字符**（含完整 `command` 文本）：
  ```json
  {"command":"Get-Location; \"=== TOP LEVEL ===\"; Get-ChildItem -Force | Select-O..."}
  ```
  同批：`reasoning` 被正确置空、`toolOutput` 被正确置空，**只有 `toolInput` 漏了**。
- **影响**：`none` 模式的"不返回大字段"承诺不成立；折叠视图仍会传输/渲染大段命令原文，既浪费流量也破坏折叠语义。
- **修复建议**：`none` 下把 `toolInput` 也置为 `{}` 或省略。

### ⚠️ F-4【待裁决】`mode=null` 被接受为 steer
- **用例**：M4-null
- **现象**：`{"mode":null}` → **200 accepted，回显 `mode:"steer"`**
- **说明**：这**可能是有意设计**（JSON `null` 视为"未传"→ 用默认值）。但 `""`/`"STEER"`/`1`/`["steer"]` 全部 400，**唯独 null 走不同路径**，行为不自洽。
- **请裁决**：`null` 应当 400（与其他非法值一致），还是明确"null = 未传"并在契约中写明？

### ℹ️ F-5【非缺陷，但需你确认】`collapsedSummary` 在 7 条 `role=system` 行上缺失
- **用例**：E2
- **现象**：全量 2021 行中有 **7 行没有 `collapsedSummary` 键**，全部是 `role=system` 的种子行（"You are an AI agent powered by DeepSeek Harness…"）
- **判定**：这看起来是**有意为之**（system 种子无需折叠摘要），且不影响功能。但它让 `collapsedSummary` **不是稳定必有字段**。
- **请确认**：若 App 端按 `"collapsedSummary" in row` 判空会 NPE 风险，建议契约明确"该键恒存在，system 行为 `null`"。

---

## 【我自己脚本的缺陷（已修，非产品问题）——如实报告，不放宽判据】
1. **WS `wait()` 竞态**：`splice` 后 `clearTimeout(w.timer)` 访问已移出数组的对象 → 抛错。修正为取局部变量。
2. **W6 误判**：谓词写成 `type==='session.event' && sessionId===SID`，但该订阅会话的事件到达较晚；一度误报"收不到事件"。改为先捕任意 `session.event`，再用 W6d 校验归属。
3. **W7 误判**：`wait()` 会消费帧，导致统计 `streamKind` 时只剩 1 帧 → 误报"无 streamKind"。改为**等回合结束后扫描 frames 全量**，实测 **120 事件中 110 带 `streamKind`**（`message`）。
4. **W8 误判**：在回合刚开始就统计 → streaming=0。改为等待 `streaming>0 && final>0` 后再统计。
5. **W12 时序**：初版在 cancel 之后统计，事件被截断。已把 cancel 移到回合结束之后。
6. **E14 页漂移**：两次 HTTP 请求之间会话数据增长，导致误判"summary 丢行"。改用 `before` 游标**确定性取全量**后才得出 F-2 的真结论。

> 我一度**误报**过一次"full 模式丢失 toolOutput"，复查发现是两次请求间数据增长造成的页漂移，**该结论已撤回**，未写进 FAIL。

---

## 【BLOCKED 明细】
| 项 | 阻塞原因 |
|---|---|
| WS 业务消息端到端（发指令→收流式→业务 ack 对账） | 网关只有 `_test/busy`（造锁）与 `_test/echo-prompt`（明确不投递模型、不推 WS），**无法在不创建会话的前提下验证业务消息**；而创建会话会触发真实模型回合。按纪律不做，保持未验证 |
| App 端（需求 2 对话区、需求 4 视觉对齐） | App 仍在开发，APK 会更新；按你的指示等交付后再跑 |
| `StopButtonPolicy`（idle 不显示停止） | 未在网关 API 契约内，属 App 侧 UI 行为，待 App 交付 |
| 子代理会话写 → 409 | **我刻意未验证**：需要向**他人的子代理会话**发写请求，违反测试纪律。只做只读校验 |

---

## 【我创建的测试会话（sessionId 清单）】
**我仅对自己创建的以下 9 个会话执行过写操作（POST /sessions、POST /prompt、WS prompt/cancel）**：

| # | sessionId | 用途 |
|---|---|---|
| 1 | `session-e9ac9650-7bc5-4c48-a87a-958ffc4989a0` | mode 默认值探查 |
| 2 | `session-354b99f0-16a2-4e01-a495-1dac71a2c9b4` | test-mode.mjs |
| 3 | `session-2e8260e6-8ef4-4330-9e01-80b4bace6ae2` | test-ws.mjs（首跑） |
| 4 | `session-b983e6f3-c6bc-4704-a019-9e0f0b630cbc` | test-ws.mjs |
| 5 | `session-6190f95d-748c-4927-80ab-b0e9825bdee1` | test-ws.mjs |
| 6 | `session-490544ab-91b0-4d43-a0a1-4deb9c24aad7` | test-ws.mjs + 事件形状探查 |
| 7 | `session-36cd6a5f-fae3-40f2-8f7c-b651d7a9b79f` | test-ws.mjs |
| 8 | `session-70e046d8-a93a-49b2-b9fd-f4bcd9bd0e23` | test-ws.mjs |
| 9 | `session-ee05abac-69c9-42c2-a065-a76760dd43ec` | test-ws.mjs（最终） |

**对其他所有会话（含 `session-00723a9e…`、`手机输入法…`、`tiga-survivor_v1`、`workbuddyapi2`、`wake-probe` 等）我只做了 GET 只读操作，未发送任何 prompt 或写操作。** 未向 `session-00723a9e` 发送任何消息。未重启 DSH。

> 注：这些会话标题为空（网关新建会话时 `title` 未持久化显示），但确实存在（`/messages` 可查）。请清理时按上面的 sessionId 精确处理。

---

## 【通过项要点】
- **方案 A**：8765 与 3080 同进程 ✅
- **mode（需求 1）**：默认 steer 回显 ✅；显式 steer/queue ✅；非法值 `bogus`/`""`/`STEER`/`1`/`["steer"]` 全部 400 ✅；steer 对 idle 被接受 ✅；steer/queue 对 running 均被接受 ✅；**同 requestId 重复提交同一 messageId** ✅；同 requestId 换 mode 仍幂等 ✅；缺 text/requestId 仍 400 ✅
- **需求 3 字段**：旧 7 字段三级 expand 下均存在 ✅；`kind` 取值合法 ✅；`toolCallId` 成功配对 call↔result ✅；tool-call 带 `toolName`/`toolInput` ✅；`status=failed ⇒ isError=true` ✅；`text` 语义不变（tool 仍 `[tool] <name>`）✅
- **expand 语义**：summary 大字段截断到 ≤2018 ✅；full 为全文（最长 49933）✅；非法 `expand` 回退 default ✅
- **缓存正确性**：full→none 不污染 ✅；none→summary 截断仍生效 ✅；同 expand 重复请求内容完全一致 ✅
- **WS**：4401 零帧 ✅；hello 首帧 ✅；ping/pong ✅；ack 同 id ✅；**prompt ack 带 requestId** ✅；订阅后收事件且归属正确 ✅；**streamKind 110/120 事件携带且取值合法** ✅；streaming→final 收尾 ✅；未订阅不推 ✅；error 带同 id ✅；cancel ack ✅；未知 type→error ✅
- **回归 15/15**：`/ping` 免鉴权、`/status`、错误/无 token→401、**sessions 分页无重无漏**、nextCursor 非空、limit 非法→400、九字段齐全、**`/workspaces` 12 个且字段/类型正确**、404、WS 冒烟、未知路径 404

---

## 【结论】

**暂不建议放行**，有 2 个 P1 缺陷需修：

1. **F-2（最严重）**：`expand=summary` 硬截断 2000 行 ⇒ **默认模式下用户看不到最新消息**。这是需求 3 的核心功能缺陷，且影响 App 详情页默认体验。**必须修**。
2. **F-1**：WS 未校验 `mode` ⇒ WS 通道上 steer/queue 语义可能静默失效。**建议本轮修**（改动量小）。

F-3（`none` 泄漏 `toolInput`）建议一并修，改动极小。

**未验证项（诚实清单）**
- WS **业务消息**端到端（无干跑手段，需 App 或新钩子）
- App 端需求 2/4、StopButtonPolicy
- 子代理会话 409（**纪律原因主动不测**）
- `mode=null` 语义、F-5 的 `collapsedSummary` 稳定性 → **需你裁决后我补测**

修完 F-1/F-2/F-3 后告诉我，我重跑 `tests/v2/` 全套复验。
