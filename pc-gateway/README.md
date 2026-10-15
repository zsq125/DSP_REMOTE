# pc-gateway — DSH 手机远程控制 PC 端网关

DSH Cordis 插件 `@local/dsh-remote-gateway`：在**第二个 DSH 实例**里自建 `node:http` 服务，向局域网暴露契约 v1 的 REST + WebSocket 接口，让 Android App 远程查看/控制 PC 上的 DSH 任务。

- 契约：[`../docs/01-架构与接口规范-v1.md`](../docs/01-架构与接口规范-v1.md)（§3 接口、§7 GAP 裁定）
- 依赖：Node ≥ 22（插件本身只用 `ws` + `qrcode`；`WebSocket`/`fetch` 仅测试用）

---

## 1. 快速开始

```powershell
pc-gateway\start-gateway.cmd            # 推荐：.cmd 包装器（绕过 RemoteSigned，不改系统策略）
pc-gateway\start-gateway.cmd -TestHooks # 开启测试门控钩子（DSH_RC_TEST_HOOKS=1）
pc-gateway\start-gateway.cmd -LanHost 192.168.2.27   # 多网卡自动选择不对时强制指定
pc-gateway\stop-gateway.cmd
# 等价写法（环境允许直接跑 .ps1 时）
powershell -ExecutionPolicy Bypass -File .\pc-gateway\start-gateway.ps1
```

> 本机 `ExecutionPolicy = RemoteSigned`，直接 `& .\start-gateway.ps1` 会被拒（*"not digitally signed"*）。`.cmd` 内部就是 `powershell -NoProfile -ExecutionPolicy Bypass -File ...`，**只影响该子进程**。

启动脚本流程：检查端口占用（占用即报错，**不静默换端口**）→ 生成/读取 token → **动态生成** `.state/gateway.patch.yml`（绝对路径由 `$PSScriptRoot` 推导，仓库可搬目录）→ 后台拉起 `dsh --profile web --patch <生成> --port 3099 --no-open` → **轮询 `/rc/v1/ping` 直到就绪** → 写 `.state/gateway.json` → 终端打印 ASCII 二维码。

> ⚠ **两个端口不要混**：`8765` = **手机侧 App 端口**（网关自己监听）；`3099` = **第二个 DSH 实例自身**的 web 端口，只在 PC 本机用，不要暴露给手机。

### 局域网地址选择（规范 §8）

VMware 的 `VMnet8`/`VMnet1` 常排在真实 `WLAN` 之前；若取"第一个非 loopback IPv4"会选中 `192.168.125.1` 这类 **NAT 网段 ⇒ 手机不可达、扫码必然失败**。

**规则**（`lib/http.mjs` 的 `selectLanIpv4()`）：排除 `internal` → 排除 `169.254.*` → 排除虚拟网卡（接口名匹配 `vmware|virtualbox|vbox|hyper-v|vethernet|veth|loopback|docker|wsl|tap|npcap|bluetooth|zerotier|tailscale|hamachi|radmin|sandbox`，忽略大小写）→ 其余按优先级取最高（**有默认路由 > 无默认路由**、物理网卡 > 虚拟网卡；默认路由下一跳用 `route print -4` 读，Windows **不支持** `route print -0`）→ 启动时**打印全部候选 + 选中项 + 理由**（`describeLanIpv4()`）→ `-LanHost <ip>` 强制覆盖（同时影响二维码与 `.state/gateway.json`）。

本机实测：

```
默认路由下一跳 = ["192.168.2.1"]
[rc-gateway] 局域网候选地址（手机需能直连的那个）：
  ✗ 192.168.211.1    VMware Network Adapter VMnet1 排除：虚拟网卡
  ✗ 192.168.125.1    VMware Network Adapter VMnet8 排除：虚拟网卡
  ★ 192.168.2.27     WLAN         所在网段有默认路由
  ✗ 127.0.0.1        Loopback Pseudo-Interface 1 排除：loopback
  => 选中 192.168.2.27（WLAN）：所在网段有默认路由
```

> ⚠ 本机**无法自证「手机能否真正连上」**（Windows 防火墙对从本机访问本机 IP 不生效）⇒ 属**需用户真机确认**项。
> **防火墙**：本机已有入站 Allow 规则 `Node.js JavaScript Runtime` → `E:\system\nodejs\node.exe`（TCP **Any** 端口，Profile **Public**），WLAN 正是 Public 且网关跑在同一 `node.exe` ⇒ **无需手动放行**；换机器若无该规则需放行 `node.exe` 或 TCP 8765。
> **兜底**：App 支持**手动输入 `host:port` + token**，自动选择不对时可绕过二维码直接手输。

### 状态文件

| 文件 | 内容 |
|---|---|
| `.state/token.txt` | 32 位十六进制 token，首次生成，**重启不变** |
| `.state/gateway.json` | `{host, port, token, fp, pid, startedAt}` |
| `.state/gateway.patch.yml` | 启动时自动生成的 patch（每次覆盖，勿手改） |
| `.state/idempotency.json` | `requestId → {messageId, sessionId}` 幂等映射 |
| `.state/dsh-instance.log` | 第二个 DSH 实例的 stdout/stderr |
| `.state/pairing.json.txt` | 二维码内容（§3.4 单行 JSON） |
| `.state/runtime.json` | 插件运行时自报（peer/port/fp/pid）——**启动脚本打印地址的唯一权威来源** |
| `.state/test-created-sessions.json` | **新建会话台账**（见 §7）：`[{id, createdAt, cwd, source}]` |

配对二维码内容（规范 §3.4）：

```json
{"v":1,"name":"DESKTOP-1V6A47A","host":"192.168.1.5","port":8765,"token":"<32hex>","fp":"<token前8位>"}
```

---

## 2. 文件树

```
pc-gateway/
├── index.js              Cordis 插件入口（导出 {name, inject, apply} 对象）
├── lib/
│   ├── http.mjs          REST 端点 + 参数校验 + 错误映射 + WS upgrade 挂载
│   ├── ws-hub.mjs        /rc/v1/stream 集线器（hello/subscribe/prompt/cancel/ack/error/pong）
│   ├── dsh.mjs           内层 SessionController 适配层（事件→消息、列表项、分页、幂等等待）
│   ├── store.mjs         token 持久化/轮换 + requestId 幂等映射 + 一次性 409 门控
│   └── errors.mjs        契约错误码 ↔ HTTP 状态 ↔ 内层 RemoteError 映射
├── start-gateway.ps1 / .cmd    启动脚本（动态生成 patch、轮询就绪、打印二维码）
├── stop-gateway.ps1  / .cmd    停止脚本（按 pid 停 + 确认端口释放）
├── print-qr.mjs          终端 ASCII 二维码渲染
├── package.json
└── .state/               运行时状态（见上表）
```

> 所有 `*.ps1` **必须纯 ASCII**（PowerShell 5.1 会按 GBK 解码无 BOM 文件，中文会吃掉右引号导致语法错误，根因见 01 §9.4）；编码 UTF-8 无 BOM、CRLF；改动后必须用 PowerShell 5.1 **实际起停一次**验证。

---

## 3. 插件形态：两个必须遵守的坑

```js
// ✅ 必须是「带 inject 的对象」
export const plugin = {
  name: '@local/dsh-remote-gateway',
  inject: ['sessionController'],
  apply(ctx, config = {}) { /* ... */ },
};
export default plugin;
```

1. **不能只 `export function apply`**：loader 的 `unwrapExports`（`cordis-plugin-loader/lib/index.js:663`）会做 `exports.default ?? exports`，纯函数模块被解析成 `apply` 函数本身，函数没有 `inject` ⇒ 报 `cannot get property "sessionController" without inject`。
2. **patch 只能按 `id` 覆盖已存在条目；新增条目必须用顶层 `- insert:`**。写成 `- id: remote-gateway / name: ...` 会报 `patch: entry "remote-gateway" not found`。

生成的 patch 形如：

```yaml
- insert:
    - id: remote-gateway
      name: 'file:///<绝对路径>/pc-gateway/index.js'
      config: { host: '0.0.0.0', port: 8765, defaultCwd: '...', testHooks: false }
```

`ctx.effect()` 的回调**返回 disposer**（不是 `async` 函数）；启动失败要单独 `.catch` 打日志，否则 DSH 只在启动日志留一句 `1 entry did not activate`。

---

## 4. 内层 API 实测形状（`ctx.sessionController`）

> 本节是**实测结论**，测试与维护以此为准。包路径：`E:\system\node_env\npm-global\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai\dsh-api-session-controller`

### 4.1 `list(request, signal)` → `{ items: SessionSummary[] }`

```jsonc
{
  "sessionId": "session-21e7b431-...", "updatedAt": 1791651392296,
  "agentAvailable": false, "running": false, "blank": false,
  "cwd": "F:\\project\\...\\trae_api",
  "projections": { "kind": "cached", "asOfSeq": 2771,
                   "values": { "title": "...", "inbox": {"next-turn": [], "next-step": []},
                               "sessionStats": {...}, "turnOutline": [...] } }
}
```

- **没有** `createdAt` / `title` / `messageCount` 顶层字段 ⇒ `createdAt` 需从 `inspect()` 取、`title` 取 `projections.values.title`。
- 实测 110 个会话 `list()` 约 **305 ms**；`running` 布尔值就是契约 `status` 的来源（`running` / 其余 `idle`）。

### 4.2 `inspect(sessionId, signal)` → `{ meta, inheritedEventCount, events }`

- `meta = {version, id, createdAt, cwd, isSeeded, delegationDepth, agentPreset}`
- `events[i] = {type, seq, time, data}`，**seq 零基且密集**。
- 会话不存在 → 抛错（`session "..." not found`）⇒ 映射 404。
- **性能**：冷会话单次 ≈17 ms，但并发读严重串行化（并发 50 ≈ **9.9 s**；顺序读 110 ≈ **14.5 s**，单次最大 1365 ms）⇒ 网关对 `list` 的补齐（`createdAt`/`messageCount`/`preview`）做了**缓存 + 4s 超时降级**。

### 4.3 `follow({address}, signal)` → 异步生成器

首帧 `{type:'snapshot', header, cursor, records[], hasMore, projections}`，之后 `{type:'event', event}` 与 `{type:'assistant-stream', frame}`。

- **实测坑 1**：snapshot 只是**当前游标处的开窗**——实测一个已落盘 4 条 prompt 的新会话 snapshot 为 `cursor=6 / records=7` 且**不含**那些 prompt，之后若无新事件循环会一直挂。⇒ 网关在 snapshot 到达时用自己缓存的历史把更早消息补推，并按 `seq` 去重。
- **实测坑 2**：**同一会话开多条并发 `follow` 会互相干扰**（3 条并发时实测其中一条完全不吐帧）⇒ 网关每会话只开**一条** follow，广播给所有订阅者。

### 4.4 `page({address, throughSeq, beforeSeq?, maxMessages?}, signal)` → `{records, hasMore}`

- **`throughSeq` 必填**且为整数 ≥ -1（`-1` = 最新）；传 `undefined` 报 `throughSeq must be an integer greater than or equal to -1`。
- 网关的消息分页走 `inspect()` 全量 + 内存切片（更可控），`page()` 备用。

### 4.5 `projections({sessionId}, signal)` → `{asOfSeq, values} | null`

会话不存在返回 **`null`**（不抛错）⇒ 最便宜的存在性判据，网关的 404 前置校验用它。

### 4.6 `prompt({sessionId, requestId, mode, content}, signal)` → `{accepted: true}`

- **没有 `messageId`** ⇒ 网关生成 uuid 并持久化 `requestId → messageId` 映射。
- ⚠ **返回 `{accepted:true}` 时消息只是进了 inbox 队列、尚未持久化**（busy 会话连发两条，`inbox['next-turn']` 只有**后**一条）⇒ 网关为 `GET /messages` 提供可选 `?waitRequestId=<id>`：轮询到 `data.source.rpcId === requestId` 的 durable `user/message` 出现才返回（上限 12 s）。
- ⚠ **内层不按 `rpcId` 去重**：实测同一 busy 会话连发两个不同 `requestId`，两条 durable 消息的 `source.rpcId` **都是第一次那个 id** ⇒ 幂等**只能**以网关自己的 `(sessionId, requestId)` 记录为准。

### 4.7 `cancel({sessionId})` → `{accepted: true}`

对冷/idle 会话抛 `session/not-found` ⇒ 网关先查列表，只有 `running` 才真调 `cancel`，否则直接幂等 200（GAP-08）。

### 4.8 `create({cwd?})` → `{sessionId, agentPreset}`

### 4.9 `control(signal)` → 异步生成器

首帧 `{type:'baseline', value:{projections:{}}}`，之后 `{type:'projection', sessionId, key, value, seq}`。（网关当前用 `list()` 轮询取状态，未用此流。）

### 4.10 事件 → 契约消息行的映射（`lib/dsh.mjs`）

| 内层事件 | 契约 `role` | `text` | `status` |
|---|---|---|---|
| `user/message`（`source.kind==='user'`） | `user` | content 拼接 | `final` |
| `user/message`（`source.kind` 为 `agent-instructions` / `runtime-context` 等） | **`system`** | content 拼接 | `final` |
| `assistant/message` | `assistant` | content 拼接 | `final` |
| `system/message` | `system` | content 拼接 | `final` |
| `tool/call` | `tool` | `[tool] <name>` | `final` |
| `tool/result` | — | 不单独出行，只把对应 `tool/call` 标 `failed` | — |
| 其他（`turn/*`、`step/*`、`session/*`、`agent/*`、`request/*`） | — | 不进消息流 | — |

> 「系统注入的 `user/message` 归为 `system`」是实测发现：它们 `role:'user'` 但 `source.kind` 不是 `user`，若当用户消息渲染，App 会重复显示 AGENTS.md / runtime context。

---

## 5. 错误码映射（`lib/errors.mjs`）

| 契约 code | HTTP | 触发 |
|---|---|---|
| `unauthorized` | 401 | token 缺失/错误/非 Bearer |
| `invalid_request` | 400 | `limit` 越界或非整数、`before` 非法、`cwd` 不存在、`text` 空或非字符串、`requestId` 缺失、body 非 JSON、`v != 1`、未知 WS `type` |
| `not_found` | 404 | 未知路由、会话不存在 |
| `session_busy` | 409 | 内层 `session/writer-held`、`session/agent-busy`，或测试钩子 |
| `dsh_unavailable` | 503 | 内层无 RemoteError code（未就绪/崩溃）；`GET /status` 例外，仍 200 且 `dsh.ok=false`（GAP-18） |
| `internal` | 500 | 其余 |

**校验顺序（GAP-06）**：所有 `/sessions/{id}/xxx` 写操作**先做存在性校验（404）再校验 body（400）**。

---

## 6. 一次性 409 测试钩子（GAP-22）

仅当 `DSH_RC_TEST_HOOKS=1`（或 `-TestHooks`）时注册：

```
POST /rc/v1/_test/busy/{sessionId}   → 200 {"v":1,"armed":true,"sessionId":"..."}
```

效果：该会话的**下一次** prompt 返回一次 409 `session_busy`（**REST 与 WS 两侧都生效**），之后恢复正常。默认环境该路由 404 `not_found`。
另有 `POST /rc/v1/_test/echo-prompt`：接受 prompt 但**不投递模型**，仅登记幂等并返回 `{accepted,messageId,sessionId}`（用于字段/幂等类测试，避免真实模型调用）。

---

## 7. assistant 逐 token 流式（`session.event` + `status:"streaming"`）

**内层帧形状**：`follow({address, assistantStream:true})` 额外产出 `{type:'assistant-stream', frame}`：

```jsonc
start {attemptId, revision, startedAfterSeq, turn, step}
chunk {attemptId, revision, index, time, chunk:{type:'text', text, index, blockType?}}
end   {attemptId, revision, index, outcome:{kind:'committed', eventType:'assistant/message', seq} | {kind:'abandoned'}}
```

**契约映射**（契约 §3.3 的 `event` 只有 `{kind,role,text,seq,ts,status,messageId}`，无"多段增量"形状）：

| 内层帧 | 推给客户端的 `session.event.event` |
|---|---|
| `start` | **不发帧**（仅重置拼接缓冲） |
| `chunk` | `{kind:'message', role:'assistant', text:<本段>, seq:<已累积字符数>, ts, status:'streaming', messageId:null}` |
| `end`（committed） | `{role:'assistant', text:<完整拼接>, seq:<最终长度>, ts, status:'final', messageId:null}` |
| `end`（abandoned） | 同上但 `status:'failed'` |
| 无文本的 `end` | **不发帧** |

- `seq` = 本次 attempt 的**已累积字符数** ⇒ 同一 attempt 内严格递增，App 按 seq 追加即天然去重；交错推送的**旧 attempt** chunk 按 `attemptId` 丢弃。
- 权威版本仍是随后落盘的 `assistant/message` 事件（带真实 `seq`/`messageId`），App 按它覆盖即可。
- ⚠ **`assistant-stream` 是进程内瞬态**：实测（网关 3099 实例 follow 由 3080 实例执行的会话）`assistant-stream` 帧**不可见**，只有执行 agent 的那个实例能拿到。⇒ **对"PC GUI（3080）里正在跑的会话"，网关只能拿到回合级的 `assistant/message`（`status:'final'`），拿不到逐 token 流**；逐 token 流只在"会话由本网关所在的 3099 实例执行"（手机自己新建/发起）时可用。这是 DSH 内层架构决定，网关侧无法绕过。
- 纯函数级映射测试：15/15 PASS（不联网、不新建会话）。

---

## 8. 新建会话台账（安全要求）

背景：开发自测曾创建 126 个测试会话、其中 97 个跑了真实模型回合（真实 token 消耗 + 污染用户会话目录，见 `docs/09-历史结论与教训.md` §4）。

**现状**：网关在 `POST /rc/v1/sessions` 成功后**自动**把 `{id, createdAt, cwd, source}` 追加到 `.state/test-created-sessions.json`，因此**任何**经本网关新建的会话都可核对、可清理（清理由 PM 与用户决定，开发侧不自行删除）：

```json
[{ "id": "session-102d64c9-...", "createdAt": 1791669413699, "cwd": null,
   "source": "gateway:POST /rc/v1/sessions" }]
```

台账写入失败只告警、不影响建会话主流程。**禁止**用文件名/标题模糊匹配识别"测试会话"。

## 9. 自测

`tests/` 目录归测试 subagent 独占；开发侧自测脚本是临时文件（用完即删）。历史自测口径（供回归参考）：只读回归 41 项 PASS；门控钩子环境 81 项 PASS、默认环境 72 项 PASS（并断言钩子路由 404）；流式映射纯函数 15 项 PASS。

> ⚠ 全量自测若**新建真实会话并发 prompt，每次都会真实调用 LLM**（单条往返实测几十秒到几分钟）。**优先只读回归 + 门控钩子**；确需新建会话时由网关自动登记台账。

## 10. 未验证项与已知限制

1. **真实 409 写锁路径未实测**：需对"正被 3080 实例写锁持有"的会话发 prompt（会干扰用户当前对话，属安全红线）⇒ 确定性验证走门控钩子；`session/writer-held → 409` 映射代码已实现。
2. **对 PC GUI（3080）里正在跑的会话拿不到逐 token 流**（见 §7 末）；若 App 要做打字机效果**必须改架构**（让网关实例直接驱动该会话），并非网关层能单独解决——需 PM 决策。
3. **`control()` 流未使用**：状态变更走 `list()` 轮询 + 写操作后主动广播。
4. **消息历史全量驻留内存**：每个被读过的会话把 `inspect()` 全部消息缓存 60 s；超大会话（数万事件）有内存与延迟压力。
5. **`GET /sessions` 的补齐字段有 4 s 上限**：超时则 `createdAt` 退化为 `updatedAt`、`messageCount`/`lastMessagePreview` 为 0/空（仍满足契约类型）。
6. **`GET /messages` 的 `waitRequestId`** 是可选扩展参数；不传即普通语义。
7. **`status` 枚举映射**：`list()` 只给 `running` 布尔值 ⇒ 只产出 `running`/`idle`，契约里的 `unknown` 不会出现。
8. **局域网明文 HTTP**，无 TLS（规范明确不做）。
9. **流式 `seq` 是"本次 attempt 的字符位置"**，与会话事件 `seq` 语义不同，不能放进同一全局递增序列；App 需按"当前是否有进行中的 streaming 消息"分别维护游标。
