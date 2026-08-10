# Android TIM LAN API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship independent `tim_protocol` + `tim_bridge` + `tim_hook` so TIM 4.1.0 can connect over UDS and exchange text with a friend/group via HTTP `:8788`.

**Architecture:** Copy the wechat Bridge↔Hook UDS frame pattern under `com.nexus.tim.*`. Bridge is UDS server + NanoHTTPD on **8788**; Hook is LSPosed client for **`com.tencent.tim`** only. P0 = connect + HELLO; P1 = SEND_TEXT / MSG_IN after reverse-engineering TIM.

**Tech Stack:** Kotlin 2.x, Android Gradle Plugin 8.7+, NanoHTTPD, Xposed API 82, JUnit 4, Magisk/LSPosed on device.

## Global Constraints

- TIM pin: package `com.tencent.tim`, versionName `4.1.0`, versionCode `4050`
- HTTP bind `0.0.0.0:8788`; UDS abstract name `nexus_tim`
- Bridge id `com.nexus.tim.bridge` (debug `.debug`); Hook id `com.nexus.tim.hook`
- No filehelper; `chat_id` = friend QQ or group id (format locked in P1 notes)
- No Redis/Webhook/Token/image in this plan (P2 later)
- Do not modify `wechat_*` behavior except optional doc index links
- Spec: `docs/superpowers/specs/2026-08-10-android-tim-lan-api-design.md`

## File map (create)

```text
tim_protocol/
  build.gradle.kts, settings.gradle.kts, gradle wrapper (copy from wechat_protocol)
  src/main/kotlin/com/nexus/tim/protocol/TimFrame.kt
  src/main/kotlin/com/nexus/tim/protocol/TimMsg.kt
  src/test/kotlin/com/nexus/tim/protocol/TimFrameTest.kt

tim_bridge/
  (Gradle Android app; include :tim_protocol via projectDir)
  app/.../TimApp.kt, ui/MainActivity.kt, service/TimForegroundService.kt
  app/.../http/TimHttpServer.kt, TimHttpRouter.kt
  app/.../uds/HookUdsServer.kt, HookSession.kt
  app/.../state/BridgeState.kt, store/EventStore.kt, queue/SendQueue.kt
  app/.../API.md, README.md

tim_hook/
  (LSPosed module; include :tim_protocol)
  app/.../MainHook.kt, version/SupportedTim.kt
  app/.../uds/BridgeUdsClient.kt, state/LoginProbe.kt
  app/.../send/SendDispatcher.kt, recv/RecvDispatcher.kt  (P1)
  SUPPORTED_TIM.md, assets/xposed_init, meta in AndroidManifest
```

---

### Task 1: `tim_protocol` frame codec

**Files:**
- Create: `tim_protocol/**` (JVM module mirroring `wechat_protocol`)
- Create: `TimFrame.kt`, `TimMsg.kt`, `TimFrameTest.kt`

**Interfaces:**
- Produces: `TimFrameTypes` (HELLO=1 … PONG=9 same ints as wechat), `TimFrame.encode/decodeAll`, `TimMsgFields` (`chat_id`, `text`, `user_id`/`uin`, `nick`, `logged_in`, `tim_version`, …)

- [ ] **Step 1:** Copy `wechat_protocol` Gradle wrapper + `build.gradle.kts`; set `group = "com.nexus.tim"`, root name `tim_protocol`.
- [ ] **Step 2:** Add `TimFrame.kt` / `TimMsg.kt` (rename packages; keep wire format identical to `WechatFrame`).
- [ ] **Step 3:** Port `WechatFrameTest` → `TimFrameTest`; run `gradlew.bat test` — PASS.
- [ ] **Step 4:** Commit `feat(tim_protocol): UDS frame encode/decode`.

---

### Task 2: `tim_bridge` P0 skeleton (HTTP + UDS server)

**Files:**
- Create: `tim_bridge/` Android app (settings include `../tim_protocol`)
- Minimal UI: Start/Stop service; show Hook connected / me uin
- `TimForegroundService`: start UDS + HTTP 8788
- `GET /v1/health`, `/v1/me`, `/v1/events`, `POST /v1/messages/text` (queue; fails if hook down)

**Interfaces:**
- Consumes: `TimFrame`, `TimMsgFields`
- Produces: `BridgeState.hookConnected`, `HookSession.onConnected/onDisconnected/onFrame`, health JSON keys `bridge`, `hook`, `tim_version`, `logged_in`, `user_id` (uin)

- [ ] **Step 1:** Scaffold Gradle from `wechat_bridge` (slim: no Redis/Jedis/Compose extras if faster — simple View UI OK).
- [ ] **Step 2:** Unit-test health JSON when disconnected (`hook=disconnected`).
- [ ] **Step 3:** Implement servers; `assembleDebug` PASS.
- [ ] **Step 4:** Commit `feat(tim_bridge): HTTP :8788 and UDS nexus_tim server`.

---

### Task 3: `tim_hook` P0 load + UDS client + HELLO

**Files:**
- Create: `tim_hook/` LSPosed module
- `SupportedTim` PACKAGE/VERSION_NAME/VERSION_CODE
- `MainHook` scope `com.tencent.tim` main process only
- `BridgeUdsClient` connect `@nexus_tim`, send HELLO with version + login probe (best-effort; stub logged_in if unknown)
- `SUPPORTED_TIM.md`, `xposed_init`, scope meta-data

**Interfaces:**
- Consumes: `TimFrame.encode(HELLO, …)`
- Produces: HELLO payload with `tim_version`, `logged_in`, `user_id`, `nick`

- [ ] **Step 1:** Scaffold from `wechat_hook` (minify/multidex same caveats).
- [ ] **Step 2:** `assembleDebug` + `adb install`; LSPosed enable scope TIM; force-stop TIM; logcat `NexusTimHook` shows loaded.
- [ ] **Step 3:** With Bridge running, `curl :8788/v1/health` → `hook=connected`.
- [ ] **Step 4:** Commit `feat(tim_hook): LSPosed load, UDS client, HELLO pin 4.1.0`.

---

### Task 4: P0 device acceptance + short docs

**Files:**
- Create: `tim_bridge/API.md`, `tim_hook/README.md`, link from `docs/tim-lan-api-guide.md` (P0 section)
- Update: `doc/README.md` index row

- [ ] **Step 1:** Document install order (Bridge → enable module → cold start TIM).
- [ ] **Step 2:** Record `adb`/`curl` acceptance output in guide.
- [ ] **Step 3:** Commit `docs(tim): P0 usage and API stub`.

---

### Task 5: Reverse TIM text send/recv (research)

**Files:**
- Create: `tim_hook/HOOK_NOTES.md`
- Pull APK: `adb shell pm path com.tencent.tim` → `adb pull` → jadx/androguard notes

- [ ] **Step 1:** Identify login uin API / SharedPreferences / manager class.
- [ ] **Step 2:** Identify outbound text send entry (troop vs c2c).
- [ ] **Step 3:** Identify inbound message callback / DB insert.
- [ ] **Step 4:** Document class/method signatures and `chat_id` format; commit notes.

---

### Task 6: P1 text SEND_TEXT + MSG_IN

**Files:**
- Modify: `tim_hook` `SendDispatcher`, `RecvDispatcher`, `LoginProbe`
- Modify: `tim_bridge` send path already stubbed
- Test: friend QQ + group each one message

- [ ] **Step 1:** Implement send; HTTP POST returns ok; peer sees text.
- [ ] **Step 2:** Implement recv → MSG_IN → `/v1/events`.
- [ ] **Step 3:** Update API.md `chat_id` rules; commit `feat(tim): text send/recv MVP`.

---

## Spec coverage check

| Spec item | Task |
|-----------|------|
| Independent tim_* | 1–3 |
| Port 8788 / UDS nexus_tim | 2–3 |
| Pin 4.1.0/4050 | 3 |
| P0 health connected | 3–4 |
| P1 text friend/group | 5–6 |
| No filehelper | 6 docs |
| No Redis/image | omitted (P2) |

## Execution

User approved spec and said continue → **inline execution** starting Task 1 in this session.
