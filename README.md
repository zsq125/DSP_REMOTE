# DSH Remote Control (Control the PC-side DeepSeek Harness from your phone)

# Name:Shaw 
# Email: 2536990321@qq.com

View and control **DSH (DeepSeek Harness)** sessions running on your PC from a phone:
live message stream, sub-agent tree, search & locate, history browsing, send and cancel.

```
┌──────────────┐   HTTP(REST) + WebSocket    ┌──────────────┐   service client   ┌──────────────┐
│  Phone App   │ ─────────────────────────► │  PC Gateway  │ ─────────────────► │ DSH instance │
│  android-app │ ◄───────────────────────── │  pc-gateway  │ ◄───────────────── │  (profile)   │
│  Kotlin/Compose                          │  Node ESM     │                    │              │
└──────────────┘   LAN / Tailscale          └──────────────┘                    └──────────────┘
```

- The **gateway** listens on `0.0.0.0:8765` (the phone-facing port) and connects to the DSH
  instance (default `3099`) internally. These are two different ports — do not mix them up.
- The whole link is **token-authenticated**; the phone binds itself the first time via **QR pairing**.

---

## 1. Directory Layout

| Path | Contents |
|---|---|
| `android-app/` | Android client (Kotlin + Jetpack Compose, ~13k lines) |
| `pc-gateway/` | PC gateway (Node.js ESM, framework-free, only `ws` + `qrcode`) |
| `docs/` | Contract & requirement docs (**read `01-架构与接口规范-v1.md` before changing the protocol**) |
| `tests/` | Test cases, on-device acceptance reports, contract snapshots |
| `需求文档_d.txt` | Original requirements |

### Android source layering (`android-app/app/src/main/java/com/deepseek/dshremote/`)

| Package | Responsibility | Key files |
|---|---|---|
| `protocol/` | Pure-Kotlin DTOs + JSON codec, **zero Android deps, unit-testable on plain JVM** | `Protocol.kt`, `Json.kt` |
| `net/` | REST client / WebSocket transport | `GatewayApi.kt`, `OkHttpTransport.kt` |
| `core/` | Business logic (unit-testable, no UI) | `MessagePager.kt`, `SessionSubscription.kt`, `WorkspaceRepo.kt`, `SubagentTree.kt`, `PendingQueue.kt` |
| `data/` | Persistence | `DshRemoteStore.kt` (DataStore/pairing/queue), `MessageCache.kt` (**local history cache**), `TokenCipher.kt` |
| `service/` | Foreground service, connection keep-alive | `ConnectionService.kt` |
| `ui/` | Compose UI | `MainActivity.kt` (left list + global WS), `DetailPane.kt` (session detail, **the most complex**), `ScannerScreen.kt` (QR pairing) |

---

## 2. Quick Start

### 2.1 Start the gateway (PC)

```powershell
# Defaults: phone port 8765, DSH port 3099, profile=web
pc-gateway\start-gateway.cmd

# Common options
pc-gateway\start-gateway.cmd -Port 8765 -DshPort 3099 -Profile web -LanHost 192.168.1.23
pc-gateway\stop-gateway.cmd
```

On startup the terminal prints a **pairing QR code + pairing info** (the token is persisted in
`token.txt` inside the gateway state directory, so it survives restarts).

> `-LanHost`: use it to force the address written into the QR code when auto-detection picks the
> wrong adapter (common on Tailscale / multi-NIC machines).

### 2.1.1 Re-pairing after a token rotation

Rotating the gateway token (`POST /rc/v1/pair/rotate`, or delete `.state/token.txt` and restart)
invalidates every paired phone immediately (requests start returning `401`).

On the phone: open the sidebar -> **gear icon** (re-pair) -> either **Open scanner** (scan the QR the
gateway prints) or type `host:port` + the 32-hex token -> **Connect**. The page is cancellable, so you
are never locked out — and you do **not** need `pm clear` (which would also wipe the local history).

> Older builds only showed the pairing page while *unpaired*, so a rotated token forced `pm clear`.
> The gear entry exists precisely to avoid that.

### 2.2 Build and install the app

```powershell
cd android-app
.\gradlew assembleDebug                 # output: app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

- The SDK path lives in `android-app/local.properties` (`sdk.dir=...`).
- The first build is slow (Gradle 8.7 + Compose deps); incremental builds take ~15–60s.

### 2.3 Wireless debugging on the phone (the main workflow here)

```powershell
$adb = 'E:\system\android-sdk\platform-tools\adb.exe'

# (1) On the phone: Developer options -> Wireless debugging -> Pair device with pairing code.
#     Send the pairing port and the 6-digit code (the code refreshes in ~30s, so run immediately)
& $adb pair <phone-ip>:<pairing-port> <6-digit-code>

# (2) After pairing the device often shows up automatically over mDNS and is ready to use
& $adb devices
#   adb-<device-serial>-okBRo0._adb-tls-connect._tcp   device

# (3) If it does not appear automatically, connect with the port shown on the
#     "Wireless debugging" main screen
& $adb connect <phone-ip>:<connect-port>
```

**Pitfalls**

| Symptom | Cause / Fix |
|---|---|
| `cannot connect ... connection refused (10061)` | Stale port — on this phone model the port **changes after re-enabling wireless debugging**. Re-open the main screen for the new port. |
| `pair` fails with `protocol fault (couldn't read status message)` | The pairing code expired (~30s). Re-open the pairing dialog, send **code and port together**, and run immediately. |
| `failed to connect` even though TCP is open | Authorization was reset; pair again. |
| `adb devices` is empty but mDNS shows a name | Use that mDNS name as `-s` directly; no `connect` needed. |
| logcat shows no app logs | System spam flushes the buffer: `adb shell logcat -G 16M`, or stream in the background with `adb logcat -s DshBoot:D DshHttp:D`. |

---

## 3. Tests

```powershell
cd android-app
.\gradlew testDebugUnitTest          # pure-JVM unit tests (40+ cases: protocol/paging/subscription/cache)
```

- Unit tests **do not need** an emulator: `protocol/` and `core/` deliberately avoid Android APIs.
- Runtime evidence (foreground service / notifications) uses Robolectric.
- On-device acceptance reports live in `tests/v2/REPORT*.md`.

---

## 4. Key Design Decisions (read before changing code)

### 4.1 One single WebSocket, globally

`MainActivity` owns the **only** WS, subscribes to the currently selected session, and **forwards**
frames to `DetailPane`.
- Lesson learned: the WS used to live inside `DetailPane`, so while the pane was not mounted
  `subscribe=0` and no live frames arrived after a cold start.
- Switching sessions only sends a `subscribe` frame — it does **not** reconnect (`wsReused=true`).

### 4.2 Message loading: stack semantics (LWW)

- `loadGen` is a **globally increasing** generation counter across sessions (`remember {}` — **not**
  `remember(displayId)`). Every load takes a new generation and becomes the stack top; a **slow older
  response is discarded** when it returns, and in-flight requests are never cancelled.
- Last writer wins, which prevents "content overwritten by the previous session" during fast switching.

### 4.3 First screen and history: three-stage rhythm

| Stage | When | Amount |
|---|---|---|
| First screen | Opening a session | `FIRST_PAGE_LIMIT = 8` (must be fast) |
| Upward prefetch | Within 3 items of the top | 8 rows/page |
| Background backfill | Silently after the first screen | `BACKFILL_PAGE_LIMIT = 200`, until it reaches the deepest local cached seq or the `1000`-row cap |

### 4.4 Local history cache (`data/MessageCache.kt`)

Requirement: "show the newest first, then dynamically sync history down to the phone's local maximum".

- One `files/history/<sessionId>.json` per session; only durable rows (`seq >= 0`) are stored; the file
  header records `oldestSeq / newestSeq / reachedStart` (the local max/min indices).
- **Atomic writes** (write `.tmp`, then rename) + **1.5s debounced flush** (the typewriter changes the
  window every frame; without debouncing it becomes an I/O storm).
- Open flow:
  1. **Show the local cache instantly** (no network wait);
  2. The network only reconciles incrementally: fill the **gap** missed while offline with
     `after=<local newest>`, then merge the newest page;
  3. Backfill older history in the background until the beginning of the session.
- **Self-healing**: if the cache's newest end and the gateway's newest page differ by more than
  `CACHE_GAP_RESET_LIMIT (2000)`, the cache is considered corrupt/misaligned, so it is dropped and
  reloaded — the window never contains a hole.

### 4.5 Scroll following: only a user gesture may stop it

- One rule: **only a user drag** stops following the newest message; data changes never do.
- Returning to the bottom (`lastVisible >= total-1`) re-enables following automatically.
- Typewriter: streaming chunks update the **same row**, so the row count does not change; a
  `liveFollowTick` triggers one follow per frame.
- Pin to the bottom with `listState.scrollToItem(idx, Int.MAX_VALUE)`; the default behaviour aligns the
  newest row to the **top** of the viewport (which looks like "it does not follow down").

### 4.6 Per-session state (including sub-agent tabs)

- Every session-isolated piece of state (`pager`, `listState`, `loadPhase`, drafts, …) must be keyed by
  **`displayId = the session actually displayed`** (including **sub-agent tabs**), not by the main
  session id.
- Unsent drafts are hoisted to an **Activity-level** `SnapshotStateMap` (so they survive pane unmounts
  caused by list refreshes) and stored per session id.

### 4.7 Live frames and the seq guard

- `appendLive` rejects frames with **seq <= the window's max seq** (`REJECTED (replay)`): the gateway
  **replays backlogged history** on subscribe, and accepting those frames would push the focus window
  out and scramble the content.
- A streaming chunk's `seq` is the **cumulative character count of the current attempt**, not a durable
  message sequence — never treat it as a message id.
- Optimistic sends use a **negative seq** (`-ts`) to stay apart from durable rows (`seq >= 0`); after a
  successful send they are reconciled and de-duplicated by `requestId`.

### 4.8 Foreground return / reconnect refresh (`ON_PAUSE -> ON_RESUME`)

After a long time in the background, data must be refreshed or the user sees stale content:

- Session messages: `DetailPane` observes the lifecycle and pulls the newest page once on resume.
- Left-hand list: `MainActivity` re-fetches workspaces and all expanded workspaces' sessions on resume;
  the WS `onOpen` (reconnect) also triggers one refresh.
- **Use the nonce pattern**: the observer only does `nonce++` and the real work lives in
  `LaunchedEffect(nonce)`; otherwise the closure is frozen at the first composition (when `paired` may
  not be ready yet).
- List **order** changes must be detected too: compare an **ordered signature** (concatenated
  `id:updatedAt` in order). Comparing counts or sets only will miss pure reordering.

---

## 5. Development Pitfalls (hard-won)

1. **`visible(n) = rows.takeLast(n)`**: prepending older pages adds history at the front, so if you do
   **not** raise `visibleCount` to the new window length, the fetched history is **never rendered**
   (it looks like "scrolling up does nothing").
2. **`prepend` must not trim from the newest end**: `(fresh + rows).take(windowSize)` pushes out the
   newest messages the user is reading. Prepend only, and stop at the cap instead.
3. **Do not use `positionChanged()` for Compose gesture detection**: it returns `false` once the event
   has been consumed by `LazyColumn`. Accumulate the delta yourself in `PointerEventPass.Initial`
   (before consumption).
4. **`LaunchedEffect` keys must include every state that selects a branch**: missing one (e.g.
   `cacheReachedStart`) creates "it should re-run but never does" bugs.
5. **Floating overlays do not intercept touches**: `Box(Modifier.fillMaxSize())` without `pointerInput`
   consumes nothing, so touches pass through to the list below; only the 34dp strip that really has a
   gesture handler intercepts.
6. **A fixed-height sibling crushes `weight(1f)`**: the stage minimap used to be a sibling of the
   message list taking 252dp, squeezing the message area to half a screen. Put it in the same `Box` as
   an overlay instead.
7. **Key per-session state with `displayId`**, otherwise switching a sub-agent tab neither swaps the
   window nor loads that tab's history.
8. **Never write non-ASCII source files with PowerShell `Set-Content/Add-Content/Out-File`**: they
   decode as GBK, swallow characters, and glue comments onto the next line (silently turning code into
   comments). Use an editor / the `write` tool; keep gateway-side sources **pure ASCII**.
9. **Delete your own temporary artifacts**: `target/` grows out of control (`incremental/` is safe to
   delete, `deps/*.pdb|*.o` are safe, **never** delete `.fingerprint/` or the executables). Verify with
   a `cargo build` showing 0 recompiles.
10. **Make debug hooks reconcilable**: the gateway's `/rc/v1/_debug/requests` records what arrived, and
    the app's `DshHttp` records what was sent — comparing both tells you whether a request was never
    sent or never received.

---

## 6. Debugging Handbook

### Log tags

| Tag | Source |
|---|---|
| `DshBoot` | `DshTrace.log` — loading / paging / cache / lifecycle / subscription traces |
| `DshHttp` | `GatewayApi` — method/path/status/duration of every REST call (**never** headers, bodies or the token) |

```powershell
adb logcat -c                                    # clear first
adb logcat -s DshBoot:D DshHttp:D                # stream
adb logcat -d -s DshBoot:D > $env:TEMP\log.txt   # capture a window
```

### Useful keywords

| Log line | Meaning |
|---|---|
| `loadFirst limit=8` / `loadFirst done firstSeq=..lastSeq=..` | First-screen load and resulting window |
| `loadFirst CACHE n=.. oldest=.. newest=..` | Local history cache hit |
| `loadFirst MERGE-NEWER gapFilled=.. added=..` | Incremental reconciliation result |
| `backfill before=.. added=.. oldest=..` / `backfill STOP cap=1000` | Background history sync progress and stop reason |
| `appendLive REJECTED (replay) seq=..` | The seq guard working (normal; replayed frames dropped) |
| `userScrolledAway -> true reason=drag-up/gesture` | User started browsing history; following disabled |
| `resume-refresh sid=..` / `list-resume` | Foreground-return refresh |
| `switch-only subscribedId=.. wsReused=true` | Session switch reuses the same WS |

### REST self-test (hit the gateway from the PC)

```powershell
$h = @{ Authorization = 'Bearer <32-hex token from token.txt>' }
Invoke-RestMethod -Headers $h 'http://127.0.0.1:8765/rc/v1/workspaces'
Invoke-RestMethod -Headers $h 'http://127.0.0.1:8765/rc/v1/sessions?limit=50'
Invoke-RestMethod -Headers $h 'http://127.0.0.1:8765/rc/v1/sessions/<sessionId>/messages?limit=8&expand=summary'
```

`/messages` cursor semantics (read before touching pagination):

| Parameter | Direction | Meaning |
|---|---|---|
| `limit` only | — | The **newest** N rows |
| `before=<seq>` | older | One page older than that seq; the returned `nextCursor` continues further back |
| `after=<seq>` | newer | One page newer than that seq; returns `nextAfter` / `hasNewer` |
| `aroundSeq=<seq>` | centred | Opens a window centred on that seq (search locate); returns `prevCursor` / `nextAfter` |

---

## 7. Protocol & Documentation Index

| Document | Purpose |
|---|---|
| `docs/01-架构与接口规范-v1.md` | **Interface contract** (REST/WS fields, error codes, idempotency) |
| `docs/02-测试用例.md` | Acceptance cases and pass criteria |
| `docs/06-用户使用指南.md` | End-user guide |
| `docs/08-手机端UI重构与远程客户端需求.md` | UI/interaction requirements |
| `docs/09-历史结论与教训.md` | Historical conclusions (avoid repeating past bugs) |
| `docs/10-方案A切换与交接.md` | Architecture switch and handover |
| `docs/11-DSH视觉token提取.md` | Design tokens extracted from the DSH visual spec |
| `pc-gateway/README.md` | Gateway implementation details |

---

## 8. Pre-commit Checklist

```powershell
# 1) Build
cd android-app; .\gradlew assembleDebug

# 2) Unit tests
.\gradlew testDebugUnitTest

# 3) Install on a real device and eyeball it (first screen / scroll up / minimap / send)
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

- Changing the protocol => update `docs/01`, the gateway `lib/*.mjs`, and the app's `protocol/`, then
  run the contract snapshots in `tests/`.
- Changing paths => check the directory sanitisation in `data/MessageCache.kt` (no path traversal).
- Before wrapping up => delete the temporary files you created (`.tmp-*`, test screenshots on the
  device); do not let evidence pile up in the repository.
