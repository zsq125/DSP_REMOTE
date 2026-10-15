# bug2 根因取证报告：熄屏后 App WS 断开且**完全不重试**

【环境】
- 网关：8765 pid=17620 == 3080 pid=17620，启动 13:48:06；最新代码 14:21:00 ⇒ 未加载（等用户重启）
- 设备：`192.168.2.65:37091`（HONOR ADT-AN00 / Android 14），App pid=23680，
  前台服务存活（`isForeground=true, foregroundId=1001, types=00000001, flags=0x42`）
- 全程**只读**：未发 prompt、未改任何会话、未重启 DSH

---

## ① 先纠正我上一轮的两个错误

### 纠正 A：网关计数器**不是"不存在"，是"未加载"** —— 你说得对
```
磁盘代码：pc-gateway/lib/http.mjs 已有
   if (p === '/rc/v1/_debug/requests' && req.method === 'GET') { … }
   if (p === '/rc/v1/_debug/requests/summary' && req.method === 'GET') { … }
   if (p === '/rc/v1/_debug/requests' && req.method === 'DELETE') { … }
运行时：GET /rc/v1/_debug/requests → 404 no route
进程 13:48:06 < 代码 14:21:00 ⇒ 未加载
```
⇒ **我上一轮"两条通道都不存在"的表述不准确**：计数器**已实现**，会随用户重启自动出现。
**真正缺的只有 App 侧 `DshHttp` EventListener**（开发这轮未加）。

### 纠正 B：⭐ 我上一轮的"WS 连接数为 0"是**我的脚本 bug**，不是产品缺陷
上一轮我用 `grep 223D | grep -c ' 01 '` 统计，因 `/proc/net/tcp6` 的行首有空格对齐，**正则匹配失败**，
导致把 **2 条真实 ESTABLISHED 连接误报为 0**。

修正后按 `/proc/net/tcp6` 第 4 列（st 字段）解析，实测：
```
第4列 st=01 = ESTABLISHED，02 = SYN_SENT，08 = CLOSE_WAIT，06 = TIME_WAIT
```
**⇒ 我上一轮"锁屏时连接数为 0 ⇒ 怀疑 WS 被回收"这个推论当时是错的，特此撤回。**

---

## ② 重新取证的结果（这次解析正确）

### 采样 A：亮屏（无论是否锁屏）
```
[06:56:45] ESTABLISHED=2  screenOn=true
[06:56:49] ESTABLISHED=2  screenOn=true
[06:56:54] ESTABLISHED=2  screenOn=false   ← 熄屏了仍保持 2
[06:56:58] ESTABLISHED=2  screenOn=false
[06:57:03] ESTABLISHED=2  screenOn=false
网关侧对照：Established=2  remote=<phone-ip>:45008 / 45022   ← 与设备侧一致
```
**熄屏初期连接仍在**（2 条），说明 App 保活做得不错。

### 采样 B：熄屏持续后（8 轮 × 5s）
```
[06:57:25] ESTABLISHED=0  [none]
…连续 8 轮全部 0
网关侧同步对照：[14:58:07] Established=0 … [14:58:20] Established=0   ← 双侧一致
```
**⇒ 熄屏一段时间后，设备侧与网关侧连接同时归零。这是真实现象，非脚本误判**（两侧独立观测互证）。

### 采样 C：唤醒后（★ 关键）
```
[06:59:19] EST=0  [none]
[06:59:24] EST=0  [SYN_SENT=2]      ← App 在尝试重连！
[06:59:29] EST=0  [none]
[06:59:35] EST=0  [SYN_SENT=2]      ← 再次尝试
[06:59:41] EST=0  [none]
[06:59:46] EST=0  [none]
[06:59:52] EST=0  [none]
[06:59:57] EST=0  [none]
```
**⇒ App 会发起 SYN，但从未完成握手（EST 始终为 0）。**

### 采样 D：唤醒后 70 秒持续观察（决定性）
```
[07:19:27] EST=0 SYN=0 CLOSE_WAIT=0 total=0
… 连续 14 轮（约 70 秒），每轮间隔 5s
[07:20:34] EST=0 SYN=0 CLOSE_WAIT=0 total=0
```
**⇒ 70 秒内设备侧 8765 相关 socket 数为 0，`SYN_SENT=0`** ⇒ **App 连"重试"都没有了**。
网关侧同时刻：`Listen = 1`，**无任何来自手机的连接**。

---

## ③ 结论：bug2 根因链（证据充分）

```
熄屏/后台
   ↓
Doze / 后台限制 → TCP 连接被系统回收（两侧连接同时归零）
   ↓
App 尝试重连 → 出现 SYN_SENT，但**握手无法完成**（EST 恒为 0）
   ↓
一段时间后 → App **完全停止重试**（70s 内 SYN=0，无任何 socket）
   ↓
表现：PC 端一直在更新，手机永远不变 ⇒ **用户报告的 bug2**
```

**两条独立缺陷（建议开发分别修）**

| # | 缺陷 | 证据 |
|---|---|---|
| **R-1** | **重连握手无法完成**：出现 `SYN_SENT` 但从不进入 `ESTABLISHED`。可能是退避上限后仍持续高频重试触发系统丢包，或 TLS/心跳超时设置不当 | 采样 C |
| **R-2** | **重试彻底停止**：70 秒内 `SYN_SENT=0`、socket 总数为 0 ⇒ 重连逻辑已停摆（可能退避溢出/协程被取消/服务仍存活但重连循环已死） | 采样 D |

**关键补充**：`isForeground=true, foregroundId=1001, flags=0x42` 全程为真 ⇒ **服务没死**，是**重连逻辑死了**。这排除了"服务被杀"，把根因锁定在连接层/重连状态机。

---

## ④ 这解释了 bug2，但**不能解释 bug3**
bug3（切换会话不更新）与熄屏无关，是**纯 App 侧订阅/UI 重组问题**，需解锁后才能验。

---

## ⑤ 仍 BLOCKED
| 项 | 原因 |
|---|---|
| bug2 的**UI 层**验证（B1~B5、C1~C5） | 手机锁屏，App 无法前台，UI 断言不可用（**需用户手动解锁**） |
| bug1 A1~A3 | 网关计数器**未加载**（等用户重启）；App `DshHttp` 埋点**未加**（需开发补） |
| bug1 A4~A7 | 同上 |
| 图片 14 条 | 网关代码未加载 |

---

## ⑥ 我自己脚本的问题（本轮又发现 1 个，已修）
1. ⭐ **`grep -c ' 01 '` 统计 `/proc/net/tcp6` 因行首空格导致误判** ⇒ 把 2 条 ESTABLISHED 误报为 0，
   **并据此得出了错误的根因假设**。已改为按第 4 列 st 字段解析。**这是本轮最重要的自我纠正。**
2. `sample-conn.mjs` 早期版本同样有此 bug，已随脚本修正。
3. 一个 PowerShell 循环采样命令超时被转后台（60 次 adb 调用太慢），改用 Node 采样后正常。

---

## ⑦ 给开发的具体复现步骤（无需解锁，可直接验证 R-1/R-2）
```powershell
$adb='E:\system\android-sdk\platform-tools\adb.exe'; $sn='192.168.2.65:37091'
# 1) 亮屏时确认已连接
& $adb -s $sn shell "cat /proc/net/tcp6 | grep 223D"   # 期望看到 st=01 两行
# 2) 熄屏，等 1~2 分钟
& $adb -s $sn shell input keyevent KEYCODE_SLEEP
# 3) 唤醒，观察是否恢复
& $adb -s $sn shell input keyevent KEYCODE_WAKEUP
# 4) 持续观察 st 列：期望重连成功(st=01)，实测长期为 02(SYN_SENT) 甚至 0(不重试)
& $adb -s $sn shell "cat /proc/net/tcp6 | grep 223D"
# 网关侧对照
Get-NetTCPConnection -LocalPort 8765 -State Established
```

---

## ⑧ 结论

- **bug2 根因已定位到具体环节**：熄屏 ⇒ 连接被回收 ⇒ 重连握手不完成 ⇒ 重试彻底停止。
- 建议开发**优先修 R-2（重试停摆）**，它导致"永久不同步"，症状与用户描述完全一致。
- **仍需用户手动解锁手机**，我才能验证 bug2/bug3 的 **UI 层**是否随之恢复。
- 图片功能与 bug1 取证仍等用户重启 DSH。