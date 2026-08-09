# Android WeChat LAN API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Root 手机上用独立 `wechat_bridge` + LSPosed `wechat_hook`，对局域网暴露微信文本/图片/文件收发与群 @ API（与 `nexus_phone` 解耦）。

**Architecture:** Bridge（前台服务）监听 HTTP `:8787` 与 abstract UDS `@nexus_wechat`；Hook 注入锁定版本的微信进程，经 UDS 上报登录态与入站消息、执行发送。先用 FakeHook 把 Bridge/API 测通，再在真机锁定微信版本上替换真实 Hook。

**Tech Stack:** Kotlin（Bridge App）、LSPosed API + Kotlin/Java（Hook）、本机 Abstract Unix Domain Socket、NanoHTTPD 或 Ktor CIO（Bridge HTTP）、JUnit4（JVM 协议/事件单测）、真机 adb 验收。

**Spec:** [`docs/superpowers/specs/2026-08-09-android-wechat-lan-api-design.md`](../specs/2026-08-09-android-wechat-lan-api-design.md)

## Global Constraints

- 仅安卓手机微信；禁止 Windows / 网页协议方案
- 与 `nexus_phone`、`zygisk_module` **零代码依赖**；新建 `wechat_protocol/`、`wechat_bridge/`、`wechat_hook/`
- 微信必须 **锁定单一版本**（实现时写入 `SupportedWeChat.VERSION_NAME`）；版本不符拒绝发送
- 仅实验小号；发送 Bridge 内串行，默认间隔 800ms
- MVP **无 HTTP 鉴权**；Task 14 只预留接口，不实现 Token
- 单文件 ≤ 25MB；不做朋友圈/支付/加好友
- 内部 UDS 名：`@nexus_wechat`；默认 HTTP：`0.0.0.0:8787`
- 风险：非官方 Hook，可能违协议/封号——文档与 README 必须写明

---

## File Structure

```text
wechat_protocol/                 # 纯 JVM 库：帧编解码 + JSON DTO（Bridge 与单测共用）
  src/main/kotlin/.../protocol/
  src/test/kotlin/.../

wechat_bridge/                   # 独立 Android Application
  app/src/main/kotlin/.../
    BridgeApp.kt
    service/BridgeForegroundService.kt
    http/BridgeHttpServer.kt
    uds/HookUdsServer.kt
    queue/SendQueue.kt
    store/EventStore.kt
    store/MediaStore.kt
    fake/FakeHookClient.kt       # 开发/单测用，不打进 release
  app/src/test/java/.../

wechat_hook/                     # LSPosed 模块（Android library / module APK）
  src/main/assets/xposed_init
  src/main/kotlin/.../
    MainHook.kt
    uds/BridgeUdsClient.kt
    send/SendDispatcher.kt
    recv/RecvDispatcher.kt
    version/SupportedWeChat.kt
```

---

### Task 1: Shared UDS frame + JSON envelope (JVM)

**Files:**
- Create: `wechat_protocol/build.gradle.kts`
- Create: `wechat_protocol/src/main/kotlin/com/nexus/wechat/protocol/WechatFrame.kt`
- Create: `wechat_protocol/src/main/kotlin/com/nexus/wechat/protocol/WechatMsg.kt`
- Create: `wechat_protocol/src/test/kotlin/com/nexus/wechat/protocol/WechatFrameTest.kt`
- Modify: repo root or `settings.gradle` only if adding included build; prefer standalone Gradle under `wechat_protocol/` runnable with `./gradlew test`

**Interfaces:**
- Produces:
  - `object WechatFrameTypes { HELLO=1; SEND_TEXT=2; SEND_IMAGE=3; SEND_FILE=4; SEND_RESULT=5; MSG_IN=6; MEDIA_READY=7; PING=8; PONG=9 }`
  - `fun encodeFrame(type: Int, payload: ByteArray, flags: Int = 0): ByteArray` — header `u8 type | u8 flags | u32 LE len | payload`，`len <= 16_777_216`
  - `fun decodeFrames(buffer: ByteArray): Pair<List<DecodedFrame>, ByteArray>` — 返回完整帧 + 残余 bytes
  - data classes: `HelloPayload`, `SendTextPayload`, `SendResultPayload`, `MsgInPayload`, `MediaReadyPayload`（Gson 或 kotlinx.serialization；选一种并全计划统一——**用 org.json 手工亦可，但单测断言字段名固定如下**）

`SendTextPayload` JSON 字段：`request_id` (string), `chat_id` (string), `text` (string), `ats` (string array, default `[]`).

`MsgInPayload` JSON 字段：`msg_id`, `chat_id`, `from_id`, `is_group` (bool), `text` (string|null), `ats` (array), `media_id` (string|null), `media_kind` (`image`|`file`|null), `media_name` (string|null), `ts` (long unix sec).

- [ ] **Step 1: Write failing tests**

```kotlin
@Test
fun encodeDecode_roundTrip() {
    val payload = """{"request_id":"r1"}""".toByteArray()
    val frame = WechatFrame.encode(WechatFrameTypes.SEND_TEXT, payload)
    val (frames, rest) = WechatFrame.decodeAll(frame)
    assertEquals(0, rest.size)
    assertEquals(1, frames.size)
    assertEquals(WechatFrameTypes.SEND_TEXT, frames[0].type)
    assertArrayEquals(payload, frames[0].payload)
}

@Test
fun decodeAll_partialTrailing_keptInRest() {
    val full = WechatFrame.encode(WechatFrameTypes.PING, ByteArray(0))
    val partial = full + byteArrayOf(WechatFrameTypes.HELLO.toByte(), 0) // incomplete next frame
    val (frames, rest) = WechatFrame.decodeAll(partial)
    assertEquals(1, frames.size)
    assertEquals(2, rest.size)
}
```

- [ ] **Step 2: Run tests — expect FAIL**

Run: `cd wechat_protocol && ./gradlew test`（Windows: `gradlew.bat test`）  
Expected: compile error / test fail（类不存在）

- [ ] **Step 3: Minimal implementation**

实现 `WechatFrame.encode` / `decodeAll` 与 `WechatFrameTypes` 常量；payload JSON 解析可放后续 Task，本 Task 只保证二进制帧。

- [ ] **Step 4: Run tests — expect PASS**

- [ ] **Step 5: Commit**

```bash
git add wechat_protocol
git commit -m "feat(wechat_protocol): UDS frame encode/decode"
```

---

### Task 2: EventStore cursor API (JVM, in Bridge test source or protocol)

**Files:**
- Create: `wechat_bridge/app/src/main/kotlin/com/nexus/wechat/bridge/store/EventStore.kt`
- Create: `wechat_bridge/app/src/test/java/com/nexus/wechat/bridge/store/EventStoreTest.kt`
- Create: `wechat_bridge/` Android Gradle 工程骨架（`settings.gradle.kts`, `app/build.gradle.kts`, `minSdk 29`, `applicationId com.nexus.wechat.bridge`）若尚未创建——本 Task 一并建好，使 `test` 可跑

**Interfaces:**
- Produces:
  - `class EventStore { fun append(event: BridgeEvent): Long /* cursor */; fun after(cursor: Long): List<BridgeEvent>; fun latestCursor(): Long }`
  - `data class BridgeEvent(val cursor: Long, val type: String, val payload: JSONObject)` — `type` 首版固定 `"message"`

- [ ] **Step 1: Failing test**

```kotlin
@Test
fun appendAndPoll_afterCursor() {
    val store = EventStore()
    val c1 = store.append(BridgeEvent(0, "message", JSONObject().put("msg_id", "m1")))
    val c2 = store.append(BridgeEvent(0, "message", JSONObject().put("msg_id", "m2")))
    assertTrue(c2 > c1)
    assertEquals(0, store.after(c2).size)
    assertEquals(1, store.after(c1).size)
    assertEquals("m2", store.after(c1)[0].payload.getString("msg_id"))
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `cd wechat_bridge && ./gradlew.bat :app:testDebugUnitTest --tests *.EventStoreTest`

- [ ] **Step 3: Implement in-memory `EventStore`**（线程安全：`synchronized` 或单线程 executor；内存列表，cursor 从 1 递增）

- [ ] **Step 4: Run — expect PASS**

- [ ] **Step 5: Commit**

```bash
git add wechat_bridge
git commit -m "feat(wechat_bridge): EventStore with cursor polling"
```

---

### Task 3: Bridge HTTP — health + events (no Hook yet)

**Files:**
- Create: `wechat_bridge/app/src/main/kotlin/com/nexus/wechat/bridge/http/BridgeHttpServer.kt`
- Create: `wechat_bridge/app/src/main/kotlin/com/nexus/wechat/bridge/state/BridgeState.kt`
- Create: `wechat_bridge/app/src/main/kotlin/com/nexus/wechat/bridge/service/BridgeForegroundService.kt`
- Create: `wechat_bridge/app/src/main/AndroidManifest.xml`（`FOREGROUND_SERVICE`, `INTERNET`, `POST_NOTIFICATIONS`）
- Create: `wechat_bridge/app/src/test/java/com/nexus/wechat/bridge/http/BridgeHttpRouterTest.kt`
- Dependency: `org.nanohttpd:nanohttpd:2.3.1` 或等价；路由逻辑抽成可单测的纯函数/类 `BridgeHttpRouter`

**Interfaces:**
- Consumes: `EventStore`, `BridgeState`
- Produces:
  - `BridgeState`: `hookConnected: Boolean`, `loggedIn: Boolean`, `wechatVersion: String?`, `supportedVersion: String`
  - `GET /v1/health` → JSON as spec
  - `GET /v1/events?after=` → `{ "cursor": <latest>, "events": [ ... ] }`
  - Listen `0.0.0.0:8787`

- [ ] **Step 1: Failing router unit tests**（不启真实 socket，直接调 router）

```kotlin
@Test
fun health_reportsDisconnectedHook() {
    val state = BridgeState(supportedVersion = "8.0.49")
    val body = BridgeHttpRouter(state, EventStore()).handle("GET", "/v1/health", emptyMap(), null)
    assertEquals(200, body.status)
    assertEquals("ok", body.json.getString("bridge"))
    assertEquals("disconnected", body.json.getString("hook"))
    assertEquals(false, body.json.getBoolean("logged_in"))
}

@Test
fun events_emptyAfterZero() {
    val store = EventStore()
    val body = BridgeHttpRouter(BridgeState(supportedVersion = "x"), store)
        .handle("GET", "/v1/events", mapOf("after" to "0"), null)
    assertEquals(200, body.status)
    assertEquals(0, body.json.getJSONArray("events").length())
}
```

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement `BridgeHttpRouter` + NanoHTTPD wrapper + FGS 启动 server**  
  Notification channel：`wechat_bridge`；标题「WeChat Bridge」。

- [ ] **Step 4: Run unit tests PASS；真机/模拟器安装后**  
  `adb shell curl` 不可用则用电脑：`adb forward tcp:8787 tcp:8787` 后  
  `curl http://127.0.0.1:8787/v1/health`  
  Expected: JSON `bridge=ok`, `hook=disconnected`

- [ ] **Step 5: Commit**

```bash
git add wechat_bridge
git commit -m "feat(wechat_bridge): HTTP health and events endpoints"
```

---

### Task 4: UDS server + FakeHook (text send/recv loopback)

**Files:**
- Create: `wechat_bridge/app/src/main/kotlin/com/nexus/wechat/bridge/uds/HookUdsServer.kt`
- Create: `wechat_bridge/app/src/main/kotlin/com/nexus/wechat/bridge/queue/SendQueue.kt`
- Create: `wechat_bridge/app/src/main/kotlin/com/nexus/wechat/bridge/fake/FakeHookClient.kt`
- Create: `wechat_bridge/app/src/test/java/com/nexus/wechat/bridge/uds/HookUdsServerTest.kt`（若 LocalSocket 单测困难：把「收到 SEND_TEXT → 更新状态 / 入队结果」抽成 `HookSession` 纯逻辑单测，UDS 读写真机手动）
- Create: `wechat_bridge/app/src/main/kotlin/com/nexus/wechat/bridge/uds/HookSession.kt`

**Interfaces:**
- Consumes: `WechatFrame`, `EventStore`, `BridgeState`
- Produces:
  - `HookUdsServer` listen abstract namespace `nexus_wechat`（Android `LocalServerSocket` 名 **不含** leading `@`；创建时用 `"nexus_wechat"` + `LocalSocketAddress.Namespace.ABSTRACT`）
  - `SendQueue.enqueueText(chatId, text, ats): CompletableFuture<SendResult>` — 串行，间隔 800ms；无 hook → 立即失败映射 HTTP 503
  - `FakeHookClient`：连接 UDS，对 `SEND_TEXT` 回 `SEND_RESULT{ok:true}`，并可主动推一条 `MSG_IN`
  - `POST /v1/messages/text` → 202/200 + `msg_id`；无 hook → 503

- [ ] **Step 1: Failing test for `SendQueue` / `HookSession`**

```kotlin
@Test
fun sendText_withoutHook_failsUnavailable() {
    val session = HookSession(BridgeState(supportedVersion = "x"), EventStore())
    val r = session.requestSendText("wxid_a", "hi", emptyList(), timeoutMs = 100)
    assertFalse(r.ok)
    assertEquals("hook_unavailable", r.error)
}
```

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement `HookSession` + `SendQueue` + UDS server + `POST /v1/messages/text`**  
  FakeHook：`adb shell` 或 Bridge debug 菜单「Start FakeHook」按钮（仅 debug）。

- [ ] **Step 4: 手动验收**  
  启动 Bridge + FakeHook →  
  `curl -X POST http://127.0.0.1:8787/v1/messages/text -H "Content-Type: application/json" -d "{\"chat_id\":\"wxid_a\",\"text\":\"hi\"}"`  
  Expected: `ok=true`  
  FakeHook 推送消息后 `GET /v1/events?after=0` 能看到事件。

- [ ] **Step 5: Commit**

```bash
git add wechat_bridge wechat_protocol
git commit -m "feat(wechat_bridge): UDS hook session, send queue, FakeHook"
```

---

### Task 5: Media store + image/file HTTP (FakeHook)

**Files:**
- Create: `wechat_bridge/app/src/main/kotlin/com/nexus/wechat/bridge/store/MediaStore.kt`
- Create: `wechat_bridge/app/src/test/java/com/nexus/wechat/bridge/store/MediaStoreTest.kt`
- Modify: `BridgeHttpRouter.kt` — `POST /v1/messages/image|file`, `GET /v1/media/{id}`
- Modify: `FakeHookClient.kt` — 处理 `SEND_IMAGE`/`SEND_FILE`，回 `MEDIA_READY`+`MSG_IN`

**Interfaces:**
- Produces:
  - `MediaStore.saveOutgoing(bytes, name): File` → `cache/out/{uuid}_{name}`
  - `MediaStore.registerIncoming(path, kind, name): String` → `media_id`
  - `MediaStore.open(mediaId): File?`
  - 拒绝 `bytes.size > 25 * 1024 * 1024` → HTTP 400 `file_too_large`

- [ ] **Step 1: Failing test**

```kotlin
@Test
fun rejectOversize() {
    val dir = createTempDir()
    val store = MediaStore(dir, maxBytes = 1024)
    assertFailsWith<MediaStore.TooLarge> {
        store.saveOutgoing(ByteArray(2048), "a.bin")
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement MediaStore + multipart 路由 + FakeHook 媒体环回**

- [ ] **Step 4: curl 发小图，events 里带 `media.url`，再 GET 下载字节一致**

- [ ] **Step 5: Commit**

```bash
git add wechat_bridge
git commit -m "feat(wechat_bridge): media upload download and FakeHook loopback"
```

---

### Task 6: `/v1/me` `/v1/chats` `/v1/chats/{id}/members` from Hook state

**Files:**
- Modify: `BridgeState.kt` — 增加 `me`, `chats`, `membersByChat`
- Modify: `HookSession.kt` — 解析 `HELLO` JSON：`user_id`, `nick`, `chats[]`, `version`
- Modify: `BridgeHttpRouter.kt` — 三个 GET
- Modify: `FakeHookClient.kt` — HELLO 带样例通讯录

**Interfaces:**
- `HELLO` payload JSON：
  ```json
  {
    "wechat_version": "8.0.49",
    "logged_in": true,
    "user_id": "wxid_bot",
    "nick": "bot",
    "chats": [
      {"chat_id": "wxid_a", "title": "Alice", "is_group": false},
      {"chat_id": "123@chatroom", "title": "Family", "is_group": true,
       "members": [{"user_id": "wxid_a", "display": "Alice"}]}
    ]
  }
  ```
- 版本 ≠ `supportedVersion` → `health.wechat_version_mismatch=true`，`SendQueue` 拒绝（error=`version_mismatch`）

- [ ] **Step 1: Failing tests for version mismatch reject + chats route**

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement**

- [ ] **Step 4: PASS + curl `/v1/chats` 见 FakeHook 数据**

- [ ] **Step 5: Commit**

```bash
git add wechat_bridge
git commit -m "feat(wechat_bridge): me/chats/members and version gate"
```

---

### Task 7: Pin WeChat APK + LSPosed module skeleton

**Files:**
- Create: `wechat_hook/README.md` — 写明：仅小号、锁版本、违协议风险、安装 LSPosed 步骤
- Create: `wechat_hook/` Gradle 模块（`com.nexus.wechat.hook`），依赖 `de.robv.android.xposed:api`（compileOnly）
- Create: `wechat_hook/src/main/assets/xposed_init` 内容一行：`com.nexus.wechat.hook.MainHook`
- Create: `wechat_hook/src/main/kotlin/com/nexus/wechat/hook/MainHook.kt`
- Create: `wechat_hook/src/main/kotlin/com/nexus/wechat/hook/version/SupportedWeChat.kt`
- Create: `wechat_hook/src/main/AndroidManifest.xml` — `xposedmodule` / `xposeddescription` / `xposedminversion` meta-data
- Create: `wechat_hook/SUPPORTED_WECHAT.md` — 记录选定的 `versionName`、`versionCode`、APK sha256（APK 本体可不进 git，太大则 git-annex/本地路径说明）

**Interfaces:**
- `SupportedWeChat.VERSION_NAME`：实现时填入真实锁定值（例如选定后写死 `"8.0.49"`——**以设备上 `dumpsys package com.tencent.mm | grep versionName` 为准**）
- `MainHook` implements `IXposedHookLoadPackage`；仅当 `lpparam.packageName == "com.tencent.mm"` 时工作

- [ ] **Step 1: 在设备上安装候选微信 APK，记录 versionName/versionCode，写入 `SupportedWeChat` 与 `SUPPORTED_WECHAT.md`**

- [ ] **Step 2: 关闭微信自动更新（系统设置 / 冻结更新组件——记入 README）**

- [ ] **Step 3: 实现空 `MainHook`：loadPackage 时 `XposedBridge.log("nexus_wechat_hook loaded")`**

- [ ] **Step 4: 编译安装模块 → LSPosed 勾选微信 → 强制停止微信 → 冷启动 → logcat 见 loaded**

- [ ] **Step 5: Commit（勿提交巨大 APK）**

```bash
git add wechat_hook
git commit -m "feat(wechat_hook): LSPosed skeleton and pinned WeChat version docs"
```

---

### Task 8: Hook UDS client — HELLO / PING / version

**Files:**
- Create: `wechat_hook/src/main/kotlin/com/nexus/wechat/hook/uds/BridgeUdsClient.kt`
- Modify: `MainHook.kt` — 启动 client 线程
- Create: `wechat_hook/src/main/kotlin/com/nexus/wechat/hook/state/LoginProbe.kt` — 探测是否登录（首版可用「找到已知登录类/或延迟假 logged_in=false直到 Task 9」；**不得**假阳性 `logged_in=true`）

**Interfaces:**
- Client 连接 `LocalSocketAddress("nexus_wechat", ABSTRACT)`，连不上则每 2s 重试
- 连接成功发送 `HELLO`（version 从 `PackageManager` 读；`logged_in` 以 `LoginProbe` 为准）
- 响应 `PING` → `PONG`
- Bridge 侧已有逻辑更新 `BridgeState`

- [ ] **Step 1: 安装 Bridge + Hook；微信冷启动后 `GET /v1/health` 期望 `hook=connected`，`wechat_version` 有值**

- [ ] **Step 2: 实现 `BridgeUdsClient` + 重试 + HELLO**

- [ ] **Step 3: 若版本与 `SupportedWeChat` 不一致，HELLO 仍上报真实版本，由 Bridge 置 `wechat_version_mismatch`**

- [ ] **Step 4: logcat 标签统一 `NexusWeChatHook`

- [ ] **Step 5: Commit**

```bash
git add wechat_hook wechat_bridge
git commit -m "feat(wechat_hook): UDS client HELLO and reconnect"
```

---

### Task 9: Real SEND_TEXT + MSG_IN (device reverse-engineering lab)

**Files:**
- Create: `wechat_hook/src/main/kotlin/com/nexus/wechat/hook/send/SendDispatcher.kt`
- Create: `wechat_hook/src/main/kotlin/com/nexus/wechat/hook/recv/RecvDispatcher.kt`
- Create: `wechat_hook/HOOK_NOTES.md` — 记录对本锁定版本找到的类名/方法签名（**只写当前版本**）
- Modify: `BridgeUdsClient` 分发 `SEND_*`

**Interfaces:**
- Consumes: UDS `SEND_TEXT` → 调用微信内部发送；完成 `SEND_RESULT`
- Produces: 入站消息 → `MSG_IN`（字段同 Task 1）
- **本 Task 允许**在 `HOOK_NOTES.md` 记载 jadx 搜索关键词与最终签名；代码里用 `XposedHelpers.findClass` / `findAndHookMethod`，禁止硬编码指令地址

**Lab steps（必须按序）：**

- [ ] **Step 1: jadx 打开锁定版微信 APK；搜索发送相关（如 `NetSceneSendMsg`、`sendText` 等——以实际反编译结果为准），在 `HOOK_NOTES.md` 写下候选类.method**

- [ ] **Step 2: 写最小 Hook：手动从 logcat 触发或临时硬编码 chat_id，验证能发出一条文本给小号自己的文件传输助手 / 另一测试号**

- [ ] **Step 3: 接通 `SendDispatcher.handle(SendTextPayload)` ← UDS**

- [ ] **Step 4: Hook 入站消息路径，构造 `MsgInPayload` 推 Bridge；用 `GET /v1/events` 验证**

- [ ] **Step 5: 端到端 curl 文本收发通过后 Commit**

```bash
git add wechat_hook
git commit -m "feat(wechat_hook): text send and receive for pinned WeChat"
```

---

### Task 10: Group @ + members list

**Files:**
- Modify: `SendDispatcher.kt` — `ats` 非空时走带 @ 的发送 API（以 `HOOK_NOTES.md` 新条目为准）
- Modify: `RecvDispatcher.kt` — 解析 at 列表为 `wxid[]`
- Modify: `LoginProbe` / HELLO 构建 — 填充群 `members`
- Modify: Bridge routes already done in Task 6

- [ ] **Step 1: 真机准备测试群；`GET /v1/chats/{id}/members` 返回至少 2 个成员**

- [ ] **Step 2: `POST /v1/messages/text` 带 `ats`；手机上看见灰色 @ 或可点击 @**

- [ ] **Step 3: 群内真人 @ 小号时，events 里 `ats` 含小号 wxid**

- [ ] **Step 4: 更新 `HOOK_NOTES.md`**

- [ ] **Step 5: Commit**

```bash
git add wechat_hook wechat_bridge
git commit -m "feat(wechat_hook): group mention send and parse"
```

---

### Task 11: Image/file send & receive (real Hook)

**Files:**
- Modify: `SendDispatcher.kt` — `SEND_IMAGE` / `SEND_FILE`（路径来自 Bridge 可达位置；若微信读不了 Bridge 私有目录，Bridge 先拷到 `/data/local/tmp/nexus_wechat/` 并 `chmod 0644`，路径放进 payload `path` 字段）
- Modify: `RecvDispatcher.kt` — 导出到 `/data/local/tmp/nexus_wechat/in/{media_id}` 后发 `MEDIA_READY`
- Modify: `wechat_protocol` / Bridge — `SendImagePayload` 增加 `path`（若 Task 1 未含则补字段与测试）
- Modify: `SendQueue` — multipart 存盘后发 UDS

**Interfaces:**
- `SEND_IMAGE` JSON: `request_id`, `chat_id`, `path`, `name`
- `MEDIA_READY` JSON: `media_id`, `path`, `kind`, `name`
- 大小预检仍在 Bridge

- [ ] **Step 1: 扩展协议字段单测**

- [ ] **Step 2: 实现发送图片到测试好友**

- [ ] **Step 3: 实现接收图片/文件 → `/v1/media/{id}` 可下载**

- [ ] **Step 4: 25MB+ 被 Bridge 拒绝的回归测试**

- [ ] **Step 5: Commit**

```bash
git add wechat_protocol wechat_bridge wechat_hook
git commit -m "feat(wechat): image and file send/receive via hook"
```

---

### Task 12: Hardening, docs, acceptance, auth stub

**Files:**
- Modify: `wechat_bridge/README.md` — 安装、端口、curl 示例、无鉴权警告
- Modify: `wechat_hook/README.md` — LSPosed、锁版本、小号
- Modify: `doc/README.md` — 链到 bridge/hook README 与本 plan
- Modify: `docs/superpowers/specs/2026-08-09-android-wechat-lan-api-design.md` — 状态改为 `Implemented (MVP)` 或 `In progress`（以验收为准）
- Create: `wechat_bridge/.../http/AuthFilter.kt` — 接口 `fun check(req): Boolean` 默认 `return true`，并打 `TODO_AUTH` 日志一次（**不实现 Token**）

**Acceptance checklist（全部勾完才算 MVP）：**

- [ ] `GET /v1/health`：`hook=connected`, `logged_in=true`, 无 version mismatch  
- [ ] 局域网其它设备发文本到好友/群成功  
- [ ] 群 `ats` 真 @  
- [ ] 图片/文件可发可收  
- [ ] 停 Bridge 不影响微信；杀微信后发送 503；重启恢复  
- [ ] 打一通 AI 电话：`nexus_phone` 旁路仍正常  

- [ ] **Step 1: 跑完整验收并记下 logcat 关键词**

- [ ] **Step 2: 写 README + 更新 doc 索引**

- [ ] **Step 3: 加入 `AuthFilter` 占位**

- [ ] **Step 4: Commit**

```bash
git add wechat_bridge wechat_hook doc docs
git commit -m "docs(wechat): MVP acceptance notes and auth stub"
```

---

## Self-Review (plan vs spec)

| Spec 要求 | Task |
|-----------|------|
| Bridge + Hook 独立、非 Windows | 全局约束 + Task 7 |
| HTTP health/me/chats/members/text/image/file/events/media | Task 3–6, 5, 11 |
| UDS 内部协议 | Task 1, 4, 8 |
| 串行发送 + 间隔 | Task 4 |
| 锁版本 + mismatch 拒发 | Task 6–7 |
| 群 @ | Task 10 |
| 无鉴权 MVP、后续 Token | Task 12 stub |
| 与 nexus_phone 解耦 | 全局约束 |
| FakeHook 先通 API | Task 4–6 |

无 TBD 步骤；Hook 具体类名留在 Task 9 lab（依赖选定 APK，无法在计划中写死伪签名）。

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-09-android-wechat-lan-api.md`.

**Two execution options:**

1. **Subagent-Driven（推荐）** — 每 Task 新开子代理，Task 间复查  
2. **Inline Execution** — 本会话按 executing-plans 连续做并设检查点  

你要哪一种？
