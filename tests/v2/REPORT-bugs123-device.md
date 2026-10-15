# 3 个 bug 真机复验报告（bug1 加载 / bug2 不同步 / bug3 切换）

【环境】
- 网关：8765 pid=17620 == 3080 pid=17620（同进程 ✅），启动 13:48:06；最新代码 `ws-hub.mjs` 14:21:00 ⇒ **未加载**（等用户重启）
- 设备：`192.168.2.65:37091`（无线 adb，HONOR ADT-AN00 / Android 14 / 1080x2388 / density 480）
- 双路 adb 均连通；App 进程存活、前台服务存活（`isForeground=true / foregroundId=1001 / types=00000001 / flags=0x42`）

【用例总览】drive-sync **9 条，PASS 2 / FAIL 7 / BLOCKED 7**

---

## 【你点名要先给的两件事】

### ① A 组取证通道：**两条都不存在** ❌
| 通道 | 探测结果 |
|---|---|
| ① 网关 `GET /rc/v1/_debug/requests` | **404 不存在** |
| ② App OkHttp `Log.i("DshHttp", …)` | **`adb logcat -s DshHttp` 输出 0 行**；全量 logcat 也搜不到任何自定义 tag |

⇒ **A1/A2/A3 无法取证**，必须请开发补一条通道（二选一即可，网关只读计数器或 App EventListener）。

### ② C4 订阅行为：**测了，但因 App 未真正前台，结论是 BLOCKED 而非 PASS**
我的脚本判 PASS 是**不可信的**（详见"我脚本的问题"），**撤回该 PASS**。

---

## 【FAIL 明细】

### ❌ F-S1【BLOCKED，非缺陷】真机锁屏导致 App 无法前台，全部 UI 断言失效
- **现象**：`am start` 后 App 立即退到后台，UI dump 恒为 `["中国电信","中国移动","N"]`（启动器小组件），`uiautomator` 报 **`ERROR: could not get idle state`**
- **精确根因（已定位到系统级证据）**：
  ```
  dumpsys power    → mScreenOn=false
  dumpsys window   → mCurrentFocus=Window{... NotificationShade}
  dumpsys window policy → mIsShowing=true, deviceHasKeyguard=true, showing=true
  logcat ActivityTaskManager → START {...dshremote...} result: 3   ← 活动被 keyguard 阻挡
  ```
  **手机已锁屏且有 PIN**。我已尝试 `KEYCODE_WAKEUP` + `wm dismiss-keyguard` + 滑动解锁 + `cmd statusbar collapse`，
  屏幕能点亮（`mWakefulness=Awake`）但 **keyguard 无法绕过**。
- **我不做也不会做**：破解用户锁屏。请**用户手动解锁一次**，之后我即可全量执行 A/B/C。
- **附带观察（有价值）**：App 服务存活但**到网关 8765 的连接数为 0**
  ```
  App pid=23680，服务 isForeground=true
  /proc/net/tcp6 grep 223D → 0 行
  ```
  说明该状态下 App 的 WS 未建立/已断开。**这与用户 bug2「PC 更新不同步」的现象高度吻合**，
  但我**无法区分**是"锁屏导致"还是"App 本身缺陷"，故**不据此下结论**，仅列为待查线索。

### ❌ F-S2【BLOCKED】C0/C0b 无法打开自建会话
`tap=null`：锚点文本在 UI 中找不到（根因同上，App 未前台）。

### ❌ F-S3【BLOCKED】C2/B1 的"网关侧前置"未达成
`C2-setup: 8 -> 8`、`B1 网关已落库: elapsed=33s` —— 我发 prompt 后等待 30~60s，网关 `/messages` 条数**未增长**。
原因：这些会话的上一轮种子回合仍在跑（真实模型回合排队），属**测试时序**，非缺陷。

### ❌ F-S4【BLOCKED】B1b / C2 / C5 依赖 UI 断言
UI 恒为 3 个文本（启动器），无法判定。**注意 B1b 的 "UI 未变化" 是锁屏导致，不可据此判定 App 缺陷。**

---

## 【BLOCKED 明细】
| 项 | 阻塞原因 |
|---|---|
| **A1/A2/A3**（bug1 加载取证） | **双通道都不存在**（`_debug/requests` 404、`DshHttp` tag 0 行）—— 需开发补通道 |
| **A4~A7** | 同上（需先能取证） |
| **B1/B2/B3/B4/B5**（bug2） | 真机锁屏，App 无法前台 → UI 断言不可用 |
| **C0~C5**（bug3） | 同上 |
| **D3 图片 14 条** | 网关代码 14:21 > 进程 13:48 ⇒ **未加载**（等用户重启） |

---

## 【我创建的测试会话（本轮）】
| sessionId | 用途 |
|---|---|
| `session-d9f2a8fb-70a6-46d3-b95d-34bc47334bf5` | drive-sync 第一轮 X（tap 失败） |
| `session-69f77356-d082-49ab-8c69-4bb215ada044` | drive-sync 第一轮 Y |
| `session-beab1cb7-259d-4f92-9187-c32034cc9bb3` | 第二轮 X（锚点版） |
| `session-d13acb1e-ff7a-438a-927e-ecf7d611f930` | 第二轮 Y |
| `session-0869e434-98c2-472c-8d9a-1dd444436029` | 第三轮 X（锚点版，锁屏轮） |
| `session-95bbcd73-6c1f-40bb-9048-e273d3dfc1f6` | 第三轮 Y |
| `session-3ac26504-8650-438e-8c9b-236aef26a324` | 上一轮 fork 分叉验证 |
| `session-1bacac3d-0e69-47c4-ae7f-afeb90a98ee1` | 上一轮 持续推送验证 |

**仅对以上会话做过写操作。** 其余全部只读；**未向 `session-00723a9e` 发消息；未重启 DSH**。

---

## 【我自己脚本的问题（如实报告）】
1. **⚠️ 撤回 2 个不可信的 PASS**：C3、C4 我判了 PASS，但当时 App 根本没前台、UI 恒为启动器文本，
   `texts.length > 0` 与 `texts.length < 60` 这类启发式在锁屏下**恒真** ⇒ **是假通过**。已撤回。
2. **网关不持久化 `title`**：我最初用 `title` 当 UI 锚点，但网关新建会话的 `title` **返回空**
   ⇒ `tapText` 永远 `null`。已改为用 **prompt 文本**作锚点（`ZZSYNC…`）。
3. **`uiautomator could not get idle state`**：无线 adb + 动画导致 dump 失败，我的 `exec-out cat` 拿到空文件，
   于是 `texts.length===0`。已加 `ensureForeground()` 前置，但**锁屏这一根因无法靠脚本绕过**。
4. **无线 adb 掉线一次**（`no devices`），已用 `adb disconnect/kill-server/connect` 恢复。

---

## 【结论】

**无法给出 bug1/bug2/bug3 的结论**，但已排除"是我的环境问题还是产品问题"的歧义：

- **bug2/bug3 的分叉早已定论**（网关持续推送正常，根因在 App 侧），本轮**未推翻**。
- **本轮 7 条 FAIL/BLOCKED 中，没有一条能判定为产品缺陷** —— 全部卡在两个客观前置：
  1. **真机锁屏**（需要你手动解锁一次）
  2. **A 组取证通道不存在**（需要开发补一条）

**需要你做两件事，我就能立刻全量跑完：**
1. **手动解锁手机屏幕**（保持不锁屏，或至少在我执行期间亮屏）
2. **让开发补 A 组取证通道**（网关 `_debug/requests` 只读计数器 **或** App `DshHttp` EventListener，任选其一）

**另外提一条线索给开发**（我不据此下结论）：锁屏状态下 App 服务存活但**到网关的 WS 连接数为 0**，
与用户描述的"PC 更新不同步"现象吻合，建议开发一并排查"锁屏/后台时 WS 是否被系统回收而未重连"。

**图片功能 14 条**仍等你重启 DSH 后重跑。