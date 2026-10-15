# tests/ — 测试脚手架（测试 subagent 独占目录）

本目录归**测试 subagent** 独占；产品代码在 `pc-gateway/` 与 `android-app/`，不要往这里写产品代码。

所有脚本输出 `PASS/FAIL/SKIP` 计数，退出码非 0 表示存在 FAIL，证据 JSON 落到 `tests/.evidence/`。
**判定只依据可复现的命令输出/报告文件**，不看控制台"绿字"，不采信自述。

| 路径 | 用途 |
|---|---|
| `lib/harness.mjs` | 公共库：断言收集、证据落盘、HTTP/WS 客户端（Node 24 内置 WebSocket） |
| `lib/mock-gateway.mjs` | **仅用于自测测试脚本本身**的假网关（绝不用于验收判定） |
| `contract/run.mjs` | 协议契约测试（L3，对**真实网关**）：规范 §3 全端点 / 全 WS 消息 / 错误路径 |
| `xref/run.mjs` | 交叉验证：用 PC 真实 DSH 数据源对拍网关输出（**只读**） |
| `regress/conn-defects/run.mjs` | 连接缺陷回归 D1~D4 + 守卫 K1/K2/K3/D4b（零依赖、零构建，不改 `android-app/`） |
| `probe/host-bypass.mjs` | host 网段校验对抗探针（27 拒绝 + 11 放行，长期回归） |
| `build/verify-apk.ps1` | 构建产物静态校验（aapt2 / apksigner / lint XML / test XML） |
| `build/parse-tests.mjs` / `build/parse-lint.mjs` | JUnit / lint XML 解析（PowerShell `[xml]` 会被 `classname` 噎住，故用 Node） |
| `device/check.ps1` | 真机 13 步清单（**需用户配合**） |
| `device/check-scan.ps1` | 扫码专项（真机，核心判据 = `dumpsys media.camera` 的 Active Camera Clients） |

**前置与红线**：真实网关已启动、token 在 `pc-gateway\.state\token.txt`；**严禁占用 3080、严禁杀 PID 7072**。

---

## 1. 协议契约测试 `contract/run.mjs`

```powershell
$tok = (Get-Content .\pc-gateway\.state\token.txt -Raw).Trim()
node tests\contract\run.mjs --base http://127.0.0.1:8765 --token $tok               # 含 pair/rotate
node tests\contract\run.mjs --base http://127.0.0.1:8765 --token $tok --skip-rotate # 保持 token 不变
node tests\contract\run.mjs --base http://127.0.0.1:8765 --token $tok --hooks       # 409 分支（网关需 -TestHooks）
node tests\contract\run.mjs --base http://127.0.0.1:8765 --token $tok --no-hooks    # 反向：钩子必须 404
node tests\contract\run.mjs ... --verbose                                           # 排障：打印 WS 收到的帧类型
```

**判定标准**：`TOTAL=n PASS=n FAIL=0` 且退出码 0。**已知副作用**：`pair/rotate` 会真正作废旧 token，脚本会打印新 token，后续命令用它。

**覆盖**（对齐规范 §3 与 §7 裁定）：通用/status/sessions/messages/新建/prompt/cancel/rotate 的字段、枚举、错误路径与边界（`limit=0/201/abc/-5`→400、坏 cursor 不 500、**不存在会话+非法 body → 先 404 后 400**、`requestId` 幂等且不重复入队、rotate 不断连旧连接、`fp==token[:8]`、`updatedAt` 降序、`seq` 升序、分页无重无漏）；WebSocket 鉴权失败**零帧 + close 4401**、hello 字段、订阅才推、ack 带 `sessionId/requestId/messageId`、未知 type→error、`v!=1` 不崩、多客户端并发、ack 不广播、**静置 30s 服务端不断开**；门控钩子 `--hooks` 下给 409（REST+WS）、`--no-hooks` 下必须 **404**（防泄漏）。

## 2. 交叉验证 `xref/run.mjs`

**只读**：只用 `readFileSync/statSync`，跑完比对所有读过文件的 mtime（XREF-08）。

```powershell
node tests\xref\run.mjs --base http://127.0.0.1:8765 --token $tok
```

独立数据源：`~/.dsh/storages/workspace.json`（`{unit, global, tables:{workspaces:{<key>:{path,title,sessionIds[]}}}}`）、`~/.dsh/sessions/<slug>/<id>/session.v4.jsonl.zstd`（zstd JSONL，Node 24 `zlib.zstdDecompressSync`）、`~/.dsh/storages/session_projcache/sessions/*.json`。

**覆盖**：会话集合相等（缺/多都报）、cwd 一致、title 一致、messageCount 与 jsonl 计数一致、消息前 60 字符一致、seq 单调、PC 文件 mtime 未变、PID 7072 存活、3080 仍在监听、网关占 8765。

## 3. 连接缺陷回归 `regress/conn-defects/run.mjs`

```powershell
node tests\regress\conn-defects\run.mjs      # 期望 TOTAL=13 PASS=13 FAIL=0（退出码 0）
```

覆盖 CONN-D1~D4（修复前必须 FAIL）与守卫 CONN-K1/K2/K3/D4b（必须始终 PASS）；零依赖、零构建，**不修改** `android-app/`，对产品构建零影响。判定标准见 [docs/02-测试用例.md](../docs/02-测试用例.md) §11.3。

## 4. 构建产物校验 `build/verify-apk.ps1`

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tests\build\verify-apk.ps1 -SkipBuild            # 只静态校验
powershell -NoProfile -ExecutionPolicy Bypass -File tests\build\verify-apk.ps1 -RunTests -RunLint    # 构建+单测+lint+静态校验
node tests\build\parse-tests.mjs      # 解析 test-results XML（failures/errors/用例数）
node tests\build\parse-lint.mjs       # 解析 lint XML（Error 计数）
```

**判定**：`aapt2 dump badging` 解析 package/minSdk26/targetSdk34/权限集合；`aapt2 dump xmltree` 解析 Service 与 `foregroundServiceType`、明文 HTTP 配置；`apksigner verify --print-certs` 通过；**解析 XML 的 Error 计数与 failures/errors/用例数**（不看控制台"绿字"）。

## 5. 真机清单 `device/check.ps1` / `device/check-scan.ps1`

**需用户配合**（相机、真实息屏/Doze、厂商 ROM、TEE 等模拟器无法覆盖）。`check.ps1` 把可照抄的 `adb` 命令整理成带注释的分步清单（安装、dumpsys 核对、token 明文落盘检索、电池优化白名单、通知 ongoing/优先级、Doze 存活、息屏保活、网络切换、扫码、厂商自启动）；`check-scan.ps1` 专测 CONN-D6 扫码缺陷。

```powershell
$env:ANDROID_HOME='E:\system\android-sdk'
powershell -NoProfile -ExecutionPolicy Bypass -File tests\device\check.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tests\device\check-scan.ps1 -Serial <device-serial> -Package com.deepseek.dshremote
```

测试方**不代签**真机结论，需用户回传原始输出/截图。

## 6. 自测脚手架本身

```powershell
node tests\lib\mock-gateway.mjs --port 8799
node tests\contract\run.mjs --base http://127.0.0.1:8799 --token aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa --no-hooks
```

`mock-gateway.mjs` 实现规范 §3 的一端子集（WS hello/pong/ack/error、订阅语义、幂等、409 钩子、rotate），专门验证**测试脚本的断言逻辑本身**，**绝不能**用于对真实网关的验收判定。

---

## 环境事实（脚本内已固定）

- JDK `E:\system\java17`（17.0.9）；Android SDK `E:\system\android-sdk`（build-tools 34.0.0）
- `aapt2.exe` / `apksigner.bat` / `adb.exe` 已安装，脚本用绝对路径；Gradle 备用 `E:\system\gradle-8.7\bin\gradle.bat`（开发应用 Wrapper）
- 模拟器**可用**（AEHD 2.2.0 + AVD `dsh_test`，访问宿主用 `10.0.2.2`）；真机 HONOR ADT-AN00（`<device-serial>`）
- 受保护对象：**PID 7072（3080 端口的 DSH）不得被杀**；不得改 `~/.dsh` 下任何文件；网关用 8765
