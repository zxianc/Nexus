# Nexus Phone Fossify 魔改 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Fossify Phone 拷入单仓并改包名为 `com.nexus.phone`，按 M0–M5 迁入现有 `nexus_app` 的 AI/配置/通知能力，最终只保留一个默认电话 App。

**Architecture:** 保留 Fossify `CallService`（唯一 UI InCall）与 `CallActivity`；抽出 `CallPolicyController` 处理双卡策略。AI 来电跳过响铃全屏、`answer()` 后复用 Fossify 通话中 UI，并启动迁入后的 `NexusBypassService` PCM 会话。配置仍用 SharedPreferences `nexus_config`。HAL `zygisk_module` 不动。

**Tech Stack:** Kotlin、Android Gradle（Fossify 上游构建）、Fossify Commons（Maven，M0 不强制 fork）、JUnit4、Gson、sherpa-onnx AAR、现有 `nexus_app` 音频/AI 代码。

**Spec:** [`../specs/2026-07-22-nexus-phone-fossify-mod-design.md`](../specs/2026-07-22-nexus-phone-fossify-mod-design.md)

## Global Constraints

- `applicationId` / Android namespace：`com.nexus.phone`（debug 可带 `.debug` suffix，与上游一致）
- 代码主包：`com.nexus.phone.*`（由 `org.fossify.phone` 改名）
- Nexus 业务包：`com.nexus.phone.nexus.*`（从 `com.nexus.assistant.*` 迁入）
- Manifest **仅一套** `IN_CALL_SERVICE_UI=true`：`CallService`；**禁止**注册旧 `NexusInCallService` 为 UI InCall
- AI RINGING：跳过来电/响铃全屏 → `answer()` → ACTIVE 时 Fossify `CallActivity` + PCM
- HUMAN：不拦截 Fossify 来电 UI
- prefs：`nexus_config` / `nexus_sms`（新包私有；不从旧包自动迁移）
- 不改 `zygisk_module`；不修静麦保 TX Deferred TODO
- 上游钉死：拷贝时记录 Fossify Phone **git SHA** 到 `nexus_phone/UPSTREAM.md`
- 分支：`feature/nexus-phone-fossify`
- 机型基线：OnePlus 8T / LineageOS + Magisk（与 `doc/00_framework_overview.md` 一致）

## Scope / phase gates

| Gate | 完成条件 | 对应 Tasks |
|------|----------|------------|
| **G0** | `nexus_phone` 可编译安装；默认电话壳可用（无 AI） | 1–2 |
| **G1** | 配置/双卡策略可持久化；设置入口可读改 | 3–4 |
| **G2** | 三策略 + AI 跳过响铃 UI + 通话中 UI + PCM 会话启停 | 5–7 |
| **G3** | 挂断存档 + Webhook + 短信通知 | 8–9 |
| **G4** | 设置能力对齐旧 Settings；文档更新；`nexus_app` 归档 | 10–11 |

未过当前 Gate 不得进入下一 Gate 的实现任务。

---

## File map

| Path | Responsibility |
|------|----------------|
| `nexus_phone/` | Fossify Phone 拷贝根工程（独立 Gradle） |
| `nexus_phone/UPSTREAM.md` | 上游仓库 URL + 钉死的 commit SHA |
| `nexus_phone/gradle.properties` | `APP_ID=com.nexus.phone` 等 |
| `nexus_phone/app/src/main/kotlin/com/nexus/phone/` | 改名后的 Fossify UI/拨号代码 |
| `nexus_phone/app/.../services/CallService.kt` | 唯一 UI InCall；挂钩 `CallPolicyController` |
| `nexus_phone/app/.../helpers/CallManager.kt` | Fossify 通话状态（挂钩时只读/薄封装） |
| `nexus_phone/app/.../activities/CallActivity.kt` | 来电+通话中 UI（AI 路径跳过响铃展示） |
| `nexus_phone/app/.../activities/SettingsActivity.kt` | Fossify 设置；M4 加 Nexus 入口 |
| `nexus_phone/app/.../nexus/config/*` | 从 `nexus_app` 迁入的配置 |
| `nexus_phone/app/.../nexus/policy/CallPolicyController.kt` | 纯策略：RINGING/ACTIVE/DISCONNECT |
| `nexus_phone/app/.../nexus/policy/CallSessionState.kt` | aiMode / slot / peer 等会话标记 |
| `nexus_phone/app/.../nexus/service/*` | BypassCommands、NexusBypassService、SessionBridge |
| `nexus_phone/app/.../nexus/ai/*`、`audio/*`、`protocol/*`、`archive/*`、`notify/*` | 从 `nexus_app` 迁入 |
| `nexus_phone/app/.../nexus/telecom/AiAnswerReceiver.kt` | PHONE_STATE fallback |
| `nexus_phone/app/.../nexus/telecom/DialerTakeover.kt` | 默认电话 + 策略引擎开关（适配新包） |
| `nexus_phone/app/.../nexus/ui/NexusSettingsActivity.kt` | M4：旧设置能力（可先整页迁入） |
| `nexus_app/` | G4 前只读源；G4 归档 |
| `zygisk_module/` | 不改 |
| `doc/00_framework_overview.md` | G4 更新指向 `nexus_phone` |

**上游关键事实（执行前必读）：**

- Fossify Phone 为单模块 `:app`，Commons 来自 Maven（`implementation(libs.fossify.commons)`），**不是** Phone 仓内源码。
- M0 **不强制** fork Commons；运行时 classpath 仍可含 `org.fossify.commons`。应用身份以 `com.nexus.phone` 为准。若日后要零 Fossify 包名，另开任务 fork Commons（本计划不阻塞 G0–G4）。
- `CallService.onCallAdded` 当前会 `startActivity(CallActivity)`；AI 路径必须在此分支前短路。

---

### Task 1: 拷贝 Fossify Phone 并钉死上游

**Files:**
- Create: `nexus_phone/`（整仓拷贝）
- Create: `nexus_phone/UPSTREAM.md`
- Modify: 根 `.gitignore`（若需忽略 `nexus_phone/**/build`、`.gradle`；通常已有通用规则则跳过）

**Interfaces:**
- Produces: 可独立打开的 Android 工程目录 `nexus_phone/`；`UPSTREAM.md` 含 SHA

- [ ] **Step 1: 浅克隆并记录 SHA**

在仓库根（PowerShell）：

```powershell
$sha = (git ls-remote https://github.com/FossifyOrg/Phone.git refs/heads/main).Split("`t")[0]
Write-Host "UPSTREAM_SHA=$sha"
git clone --depth 1 https://github.com/FossifyOrg/Phone.git nexus_phone
Set-Location nexus_phone
git rev-parse HEAD | Out-File -Encoding utf8 ..\nexus_phone_SHA.txt
Set-Location ..
# 去掉嵌套 .git，使源码纳入 Nexus 仓
Remove-Item -Recurse -Force nexus_phone\.git
```

- [ ] **Step 2: 写 UPSTREAM.md**

```markdown
# Upstream

- Repo: https://github.com/FossifyOrg/Phone
- Commit: <粘贴 Step 1 的 SHA>
- Copied: 2026-07-22
- Notes: Single-module :app; commons via Maven (org.fossify.commons). Not forked in-tree.
```

- [ ] **Step 3: 确认工程可被 Gradle 识别**

```powershell
cd nexus_phone
.\gradlew.bat :app:tasks --all
```

Expected: 列出 `assembleCoreDebug` 或类似 flavor 任务（上游有 `core` / `foss` / `gplay`）。

- [ ] **Step 4: Commit**

```powershell
git add nexus_phone UPSTREAM.md 2>$null; git add nexus_phone
git commit -m "vendor: add Fossify Phone sources as nexus_phone"
```

（若误生成 `nexus_phone_SHA.txt` 在根目录，删掉或并入 UPSTREAM 后不要提交垃圾文件。）

---

### Task 2: 改包名为 `com.nexus.phone` 并完成 G0 品牌/编译

**Files:**
- Modify: `nexus_phone/gradle.properties`（`APP_ID`）
- Modify: `nexus_phone/app/src/main/res/values/strings.xml`（应用名）
- Move/Rename: `org/fossify/phone/**` → `com/nexus/phone/**`
- Modify: 所有 `package org.fossify.phone` / import
- Modify: `AndroidManifest.xml` 中组件名若写死旧包则同步
- Modify: 关于页 / 外链字符串（自用级去掉商店宣传即可）

**Interfaces:**
- Produces: 安装后包名 `com.nexus.phone`（或 `com.nexus.phone.debug`）

- [ ] **Step 1: 改 APP_ID**

`nexus_phone/gradle.properties`:

```properties
APP_ID=com.nexus.phone
```

（保留 `VERSION_NAME` / `VERSION_CODE` 或按需改为 `0.1.0-nexus` / 提高 versionCode，避免与已装 Fossify 冲突——新 applicationId 本就不会覆盖 Fossify。）

- [ ] **Step 2: 批量改 Kotlin 包路径**

在 `nexus_phone` 下（示例；执行前先 `rg` 确认无遗漏）：

```powershell
# 移动目录
New-Item -ItemType Directory -Force -Path app\src\main\kotlin\com\nexus\phone | Out-Null
Move-Item app\src\main\kotlin\org\fossify\phone\* app\src\main\kotlin\com\nexus\phone\
Remove-Item -Recurse -Force app\src\main\kotlin\org

# 文本替换（Kotlin/XML/Manifest）
Get-ChildItem -Recurse -Include *.kt,*.xml,*.kts,*.pro | ForEach-Object {
  (Get-Content $_.FullName -Raw) `
    -replace 'org\.fossify\.phone','com.nexus.phone' `
    -replace 'org/fossify/phone','com/nexus/phone' |
    Set-Content $_.FullName -NoNewline
}
```

- [ ] **Step 3: 改显示名**

在 `strings.xml` 将 app_name 改为 `Nexus Phone`（具体 key 以文件内为准，常见 `app_launcher_name` / `app_name`）。

- [ ] **Step 4: 编译 debug APK**

```powershell
cd nexus_phone
.\gradlew.bat :app:assembleCoreDebug
```

Expected: `BUILD SUCCESSFUL`；APK 位于 `app/build/outputs/apk/core/debug/`。

若 Commons / 签名 / JDK 报错：按上游 README 对齐 JDK 版本；debug 无 keystore 时应仍可 `assembleCoreDebug`。

- [ ] **Step 5: 真机 G0 验收（人工）**

1. `adb install -r` 该 APK  
2. 设为默认电话应用  
3. 拨号盘拨出、通话记录、联系人浏览  
4. 来电显示 Fossify 来电 UI，可接听  

Expected: 均正常；**无** Nexus AI 行为。

- [ ] **Step 6: Commit**

```powershell
git add nexus_phone
git commit -m "feat(nexus_phone): rebrand package to com.nexus.phone"
```

**Gate G0 通过后再做 Task 3。**

---

### Task 3: 迁入配置层（M1）+ JVM 单测

**Files:**
- Create: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/config/NexusConfig.kt`
- Create: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/config/ConfigRepository.kt`
- Create: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/config/SimCatalog.kt`
- Create: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/config/SimInfoReader.kt`
- Create: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/config/LocalLine.kt`（若旧工程有）
- Create: `nexus_phone/app/src/test/java/com/nexus/phone/nexus/config/SimCatalogTest.kt`
- Modify: `nexus_phone/app/build.gradle.kts` — 增加 `gson`、`testImplementation(junit)`（若尚未有）

**Interfaces:**
- Consumes: 旧实现语义（从 `nexus_app/.../config/` 复制后改 package）
- Produces:
  - `enum class SimPolicy { HUMAN, AI, REJECT }` + `fromWire` / `toWire`
  - `data class NexusConfig(...); fun policyForSlot(slot: Int): SimPolicy`
  - `class ConfigRepository(context: Context) { fun load(): NexusConfig; fun save(cfg: NexusConfig); fun refreshSimMetadata(): NexusConfig }`
  - `object SimCatalog { fun merge(...); fun slotFromPhoneAccount(...) }`

- [ ] **Step 1: 先加失败的 SimCatalog 测试（包名已是新包）**

从 `nexus_app/app/src/test/java/com/nexus/assistant/config/SimCatalogTest.kt` 复制为：

`nexus_phone/app/src/test/java/com/nexus/phone/nexus/config/SimCatalogTest.kt`

将所有 `com.nexus.assistant` 改为 `com.nexus.phone.nexus`。

- [ ] **Step 2: 跑测确认失败或缺类**

```powershell
cd nexus_phone
.\gradlew.bat :app:testCoreDebugUnitTest --tests com.nexus.phone.nexus.config.SimCatalogTest
```

Expected: 编译失败或测试失败（类尚未迁入）。

- [ ] **Step 3: 复制并改 package 的配置源码**

从 `nexus_app/app/src/main/java/com/nexus/assistant/config/` 复制全部 `.kt` 到  
`nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/config/`，package 改为 `com.nexus.phone.nexus.config`。

`build.gradle.kts` dependencies 增加（若缺）：

```kotlin
implementation("com.google.code.gson:gson:2.11.0")
testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 4: 再跑单测**

```powershell
.\gradlew.bat :app:testCoreDebugUnitTest --tests com.nexus.phone.nexus.config.SimCatalogTest
```

Expected: `BUILD SUCCESSFUL`，测试 PASS。

- [ ] **Step 5: Commit**

```powershell
git add nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/config nexus_phone/app/src/test nexus_phone/app/build.gradle.kts
git commit -m "feat(nexus_phone): migrate config layer and SimCatalog tests"
```

---

### Task 4: DialerTakeover 适配 + 最小设置入口（M1）

**Files:**
- Create: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/telecom/DialerTakeover.kt`
- Create: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/ui/NexusSettingsActivity.kt`（可先从旧 Settings 精简：仅接管开关 + 双卡策略 + 保存）
- Modify: `nexus_phone/app/src/main/AndroidManifest.xml` — 注册 `NexusSettingsActivity`
- Modify: `nexus_phone/app/src/main/kotlin/com/nexus/phone/activities/SettingsActivity.kt` — 增加「Nexus / AI」入口

**Interfaces:**
- Produces:
  - `object DialerTakeover { fun isEnabled(ctx): Boolean; fun setEnabled(ctx, enable): Result<String>; fun probe(ctx): ... }`
  - 语义：`dialerTakeover` prefs + 是否默认电话；**不再** enable/disable `NexusInCallService`（该组件不存在）
  - `NexusSettingsActivity` 可读写 `ConfigRepository`

- [ ] **Step 1: 实现精简 DialerTakeover**

从旧 `DialerTakeover.kt` 复制后删除所有 `NexusInCallService` / `setOwnComponentEnabled` ICS 相关逻辑；保留：

- `RoleManager.ROLE_DIALER` 检测  
- `prepareRoleRequest` / 申请默认电话 Intent  
- `prefs` 中 `dialer_takeover` 读写（经 `ConfigRepository`）

伪代码要点：

```kotlin
object DialerTakeover {
    fun isEnabled(context: Context): Boolean =
        ConfigRepository(context).load().dialerTakeover

    fun setEnabled(context: Context, enable: Boolean): Result<String> {
        val repo = ConfigRepository(context)
        val cfg = repo.load()
        repo.save(cfg.copy(dialerTakeover = enable))
        return if (enable) {
            if (isDefaultDialer(context)) Result.success("Nexus 策略已开启")
            else Result.failure(IllegalStateException("请先设为默认电话"))
        } else {
            Result.success("Nexus 策略已关闭（仍可为默认电话，但不自动 AI 接听）")
        }
    }
}
```

（`isDefaultDialer` 用 `RoleManager`，与旧 Settings 相同。）

- [ ] **Step 2: 最小 NexusSettingsActivity**

先只做：

- 显示是否默认电话  
- 接管开关（调用 `DialerTakeover.setEnabled`）  
- 每张 SIM 的策略按钮（AI / 人工 / 拒接）→ `repo.save`  
- 「刷新卡信息」→ `repo.refreshSimMetadata()`  

可用与旧 App 相同的程序化 View 或简单 XML；不要求美化。

- [ ] **Step 3: Manifest + Fossify Settings 入口**

Manifest：

```xml
<activity
    android:name=".nexus.ui.NexusSettingsActivity"
    android:exported="false"
    android:label="Nexus AI" />
```

在 `SettingsActivity` 列表中增加一项，点击：

```kotlin
startActivity(Intent(this, NexusSettingsActivity::class.java))
```

- [ ] **Step 4: 真机 G1 验收**

1. 打开设置 → Nexus / AI  
2. 改卡策略并杀进程重启，策略仍在  
3. 开关接管写入 prefs  

- [ ] **Step 5: Commit**

```powershell
git commit -am "feat(nexus_phone): add DialerTakeover and Nexus settings entry"
```

**Gate G1 通过后再做 Task 5。**

---

### Task 5: CallPolicyController（纯逻辑）+ 单测（M2 前置）

**Files:**
- Create: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/policy/CallSessionState.kt`
- Create: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/policy/CallPolicyController.kt`
- Create: `nexus_phone/app/src/test/java/com/nexus/phone/nexus/policy/CallPolicyControllerTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class PolicyAction {
    AnswerAi,          // 跳过响铃 UI + answer
    Reject,            // reject
    ShowIncomingUi,    // 走 Fossify 默认来电
    StartBypass,       // ACTIVE 且 aiMode
    EndBypass,         // DISCONNECT
    None
}

data class PolicyDecision(
    val actions: List<PolicyAction>,
    val aiMode: Boolean,
)

object CallSessionState {
    @Volatile var aiMode: Boolean = false
    @Volatile var wasAiMode: Boolean = false
    @Volatile var slot: Int = 0
    @Volatile var peerNumber: String = ""
    @Volatile var policyWire: String = "human"
    fun reset() { aiMode = false /* 其余按旧 CallStore 对齐 */ }
}

class CallPolicyController(
    private val loadConfig: () -> NexusConfig,
    private val takeoverEnabled: () -> Boolean,
    private val slotResolver: (accountId: String?, sortOrder: Int?) -> Int,
) {
    fun onRinging(accountId: String?, sortOrder: Int?, peerNumber: String): PolicyDecision
    fun onActive(): PolicyDecision
    fun onDisconnected(): PolicyDecision
}
```

- [ ] **Step 1: 写失败测试**

```kotlin
class CallPolicyControllerTest {
    @Test
    fun ringing_ai_requestsAnswerAndSkipsIncomingUi() {
        val cfg = NexusConfig.default().copy(
            dialerTakeover = true,
            sims = listOf(SimConfig(0, "卡1", policy = SimPolicy.AI)),
        )
        val c = CallPolicyController(
            loadConfig = { cfg },
            takeoverEnabled = { true },
            slotResolver = { _, _ -> 0 },
        )
        val d = c.onRinging("acct", 0, "10086")
        assertTrue(d.actions.contains(PolicyAction.AnswerAi))
        assertFalse(d.actions.contains(PolicyAction.ShowIncomingUi))
        assertTrue(d.aiMode)
    }

    @Test
    fun ringing_human_showsIncomingUi() {
        val cfg = NexusConfig.default().copy(
            dialerTakeover = true,
            sims = listOf(SimConfig(0, "卡1", policy = SimPolicy.HUMAN)),
        )
        val c = CallPolicyController(
            loadConfig = { cfg },
            takeoverEnabled = { true },
            slotResolver = { _, _ -> 0 },
        )
        val d = c.onRinging(null, 0, "10086")
        assertTrue(d.actions.contains(PolicyAction.ShowIncomingUi))
        assertFalse(d.actions.contains(PolicyAction.AnswerAi))
    }

    @Test
    fun takeoverOff_doesNotAutoAnswer() {
        val cfg = NexusConfig.default().copy(
            dialerTakeover = false,
            sims = listOf(SimConfig(0, "卡1", policy = SimPolicy.AI)),
        )
        val c = CallPolicyController(
            loadConfig = { cfg },
            takeoverEnabled = { false },
            slotResolver = { _, _ -> 0 },
        )
        val d = c.onRinging(null, 0, "10086")
        assertTrue(d.actions.contains(PolicyAction.ShowIncomingUi))
        assertFalse(d.actions.contains(PolicyAction.AnswerAi))
    }
}
```

- [ ] **Step 2: 跑测失败**

```powershell
.\gradlew.bat :app:testCoreDebugUnitTest --tests com.nexus.phone.nexus.policy.CallPolicyControllerTest
```

Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现 Controller**

行为对齐旧 `NexusInCallService.handleState`：

- takeover off → `ShowIncomingUi` only（或 `None` + 让 CallService 走原逻辑；测试以 `ShowIncomingUi` 为准）  
- AI → 更新 `CallSessionState`，返回 `AnswerAi`  
- REJECT → `Reject`  
- HUMAN → `ShowIncomingUi`  
- `onActive`：若 `CallSessionState.aiMode` → `StartBypass`  
- `onDisconnected` → `EndBypass` + `CallSessionState.aiMode = false`

- [ ] **Step 4: 跑测通过并 Commit**

```powershell
.\gradlew.bat :app:testCoreDebugUnitTest --tests com.nexus.phone.nexus.policy.CallPolicyControllerTest
git add nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/policy nexus_phone/app/src/test
git commit -m "feat(nexus_phone): add CallPolicyController with unit tests"
```

---

### Task 6: 挂钩 CallService（跳过 AI 响铃 UI）（M2）

**Files:**
- Modify: `nexus_phone/app/src/main/kotlin/com/nexus/phone/services/CallService.kt`
- Create: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/policy/CallPolicyBindings.kt`（组装 Controller + Telecom slot 解析）

**Interfaces:**
- Consumes: `CallPolicyController`、`SimCatalog.slotFromPhoneAccount`、`ConfigRepository`、`DialerTakeover`
- Produces: `CallService` 在 RINGING 时按决策执行；AI 不 `startActivity(CallActivity)` 直至适合展示通话中（见下）

- [ ] **Step 1: 阅读并定位 `onCallAdded` 中 `startActivity(CallActivity)` 分支**

上游逻辑：几乎总会启动 `CallActivity`。改为：

```kotlin
override fun onCallAdded(call: Call) {
    super.onCallAdded(call)
    CallManager.onCallAdded(call)
    CallManager.inCallService = this
    call.registerCallback(callListener)

    val peer = call.details?.handle?.schemeSpecificPart.orEmpty()
    val accountId = call.details?.accountHandle?.id
    // sortOrder：经 TelecomManager.getPhoneAccount 读取，封装在 CallPolicyBindings
    val decision = CallPolicyBindings.controller(this).onRinging(accountId, sortOrder, peer)

    when {
        decision.actions.contains(PolicyAction.Reject) -> {
            call.reject(false, null)
            return
        }
        decision.actions.contains(PolicyAction.AnswerAi) -> {
            // 不启动来电全屏；立即 + 400ms 重试 answer
            answerWithRetry(call)
            // 不在 RINGING 启动 CallActivity
            callNotificationManager.setupNotification(/* 可用低优先级或静默，避免抢焦点 */)
            return
        }
        else -> {
            // 原 Fossify 通知 + CallActivity 逻辑保持
            /* 保留原 lowPriority / startActivity 代码 */
        }
    }
}
```

在 `callListener.onStateChanged`：

```kotlin
when (state) {
    Call.STATE_ACTIVE -> {
        val d = CallPolicyBindings.controller(this@CallService).onActive()
        if (d.actions.contains(PolicyAction.StartBypass)) {
            // Task 7 接 BypassCommands；此处可先留 Log
            startActivity(CallActivity.getStartIntent(this@CallService)) // 复用通话中 UI
        }
    }
    Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
        CallPolicyBindings.controller(this@CallService).onDisconnected()
        // Task 7 EndBypass
    }
}
```

注意：HUMAN 路径保持原 `onCallAdded` 行为；AI 在 ACTIVE 时才 `startActivity(CallActivity)`，避免响铃 UI。

- [ ] **Step 2: 编译**

```powershell
.\gradlew.bat :app:assembleCoreDebug
```

Expected: SUCCESS。

- [ ] **Step 3: 真机（无 PCM 也可先验策略）**

- 卡策略 AI：来电**无**全屏响铃页（或一闪即关），自动接通后出现通话中 UI  
- HUMAN：仍有来电 UI  
- REJECT：拒接  

- [ ] **Step 4: Commit**

```powershell
git commit -am "feat(nexus_phone): hook CallPolicyController into CallService"
```

---

### Task 7: 迁入 PCM / Bypass 服务并接线（M2 完成 → G2）

**Files:**
- Copy+rename packages from `nexus_app` → `nexus_phone/.../nexus/`：
  - `protocol/*`、`audio/*`、`ai/*`、`service/*`（含 `NexusBypassService`、`SessionBridge`、`BypassCommands`）
- Copy: `nexus_app/app/libs/sherpa-onnx-*.aar` → `nexus_phone/app/libs/`
- Modify: `nexus_phone/app/build.gradle.kts` — `implementation(files("libs/sherpa-onnx-….aar"))`、ndk abiFilters 与旧 App 对齐（`arm64-v8a`）
- Modify: `AndroidManifest.xml` — `NexusBypassService`（`foregroundServiceType="phoneCall"`）、相关权限（INTERNET、FOREGROUND_SERVICE、FOREGROUND_SERVICE_PHONE_CALL 等，对照旧 Manifest）
- Modify: `BypassCommands` 内 class name / action 字符串改为 `com.nexus.phone…`
- Modify: `CallService` ACTIVE/DISCONNECT 调用 `BypassCommands.startSession` / `endSession`
- Create: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/telecom/AiAnswerReceiver.kt`（改包名后注册）

**Interfaces:**
- Produces: AI ACTIVE 时旁路会话启动；挂断结束；**暂不**要求 Webhook 成功（M3）

- [ ] **Step 1: 复制模块并全局替换 package / applicationId 字符串**

```text
com.nexus.assistant → com.nexus.phone.nexus
```

例外：不要把 Fossify UI 包改错。只在 `nexus/` 树内替换。

`BypassCommands` 示例：

```kotlin
object BypassCommands {
    private const val CLS = "com.nexus.phone.nexus.service.NexusBypassService"
    private const val ACTION_START = "com.nexus.phone.nexus.action.START_SESSION"
    private const val ACTION_END = "com.nexus.phone.nexus.action.END_SESSION"
    private const val ACTION_HUMAN = "com.nexus.phone.nexus.action.HUMAN_MODE"
    // startForegroundService / startService 同旧实现
}
```

- [ ] **Step 2: Manifest 注册 Service / Receiver / 权限**

对照 `nexus_app/app/src/main/AndroidManifest.xml`，**不要**复制：

- `NexusInCallService`  
- `IncomingCallActivity` / `InCallActivity` / 旧 `DialerActivity`（Fossify 已有）

**要**复制：`NexusBypassService`、`SmsReceiver`（可放到 Task 8）、`AiAnswerReceiver`。

- [ ] **Step 3: CallService 接 BypassCommands**

ACTIVE + `StartBypass` → `BypassCommands.startSession(this)`  
DISCONNECT → `BypassCommands.endSession(this)`

- [ ] **Step 4: 编译 + 单元测试（协议测若有则跑）**

```powershell
.\gradlew.bat :app:assembleCoreDebug :app:testCoreDebugUnitTest
```

- [ ] **Step 5: 真机 G2 验收**

1. HAL 模块已装；AI 策略来电  
2. 跳过响铃 UI → 通话中 UI → logcat 可见 Bypass/Session 启动  
3. 挂断后会话结束（无泄漏）  
4. HUMAN 无 Bypass  

- [ ] **Step 6: Commit**

```powershell
git commit -am "feat(nexus_phone): migrate PCM bypass stack and wire CallService"
```

**Gate G2 通过后再做 Task 8。**

---

### Task 8: 存档 + Webhook（M3）

**Files:**
- Copy: `archive/*`、`notify/WebhookNotifier.kt` 及相关
- Modify: `NexusBypassService` / `CallFinalizer` 挂断路径（保持旧语义：内存 Webhook → `call.json`）
- Test: 迁移 `CallArchiveWriterTest`

**Interfaces:**
- Produces: 挂断后 `getExternalFilesDir("nexus_calls")/calls/<id>/call.json`

- [ ] **Step 1: 迁移 archive/notify 源码与测试，改 package**

- [ ] **Step 2: 跑 `CallArchiveWriterTest`**

```powershell
.\gradlew.bat :app:testCoreDebugUnitTest --tests com.nexus.phone.nexus.archive.CallArchiveWriterTest
```

Expected: PASS。

- [ ] **Step 3: 确认挂断路径调用 `CallFinalizer.finalizeCall`**

与旧 `NexusBypassService` 一致；若复制完整则已具备，只需修包名编译。

- [ ] **Step 4: 真机** — AI 通话挂断后目录出现 `call.json`；Webhook 可先用可观测 URL 或禁用开关验证 `notify.status`

- [ ] **Step 5: Commit**

```powershell
git commit -am "feat(nexus_phone): migrate call archive and webhook delivery"
```

---

### Task 9: 短信通知水位（M3 → G3）

**Files:**
- Copy: `SmsReceiver.kt`、`SmsWatcher.kt`
- Modify: Manifest `SMS_RECEIVED` receiver
- Modify: Application / 设置页确保 `SmsWatcher.ensureRegistered`

- [ ] **Step 1: 迁入并改 package / prefs `nexus_sms`**

- [ ] **Step 2: Manifest 注册（permission `BROADCAST_SMS` 等与旧一致）**

- [ ] **Step 3: 真机发测试短信（通知开启时）→ Webhook 或 log**

- [ ] **Step 4: Commit**

```powershell
git commit -am "feat(nexus_phone): migrate SMS webhook watermark watcher"
```

**Gate G3 通过后再做 Task 10。**

---

### Task 10: 完整 Nexus 设置（M4）

**Files:**
- Replace/expand: `NexusSettingsActivity.kt` — 对齐旧 `SettingsActivity` 全部可配项（开场白、LLM、模型选择、Speaker、Webhook、存档路径）
- Copy helpers: `ModelPaths`、`ModelFileImport`（已在 Task 7 则只接线 UI）
- Modify: Fossify `SettingsActivity` 入口文案「Nexus / AI」

**Interfaces:**
- Produces: 旧 Settings 能力均可在新包配置；开关即时 / 文本保存策略可先保持旧「底部分保存」

- [ ] **Step 1: 将旧 SettingsActivity 逻辑迁入 `NexusSettingsActivity`（改包名与 Intent）**

- [ ] **Step 2: 编译安装；逐项保存并 `adb shell run-as com.nexus.phone.debug` 或应用内读回验证**

- [ ] **Step 3: Commit**

```powershell
git commit -am "feat(nexus_phone): port full Nexus settings into Fossify settings entry"
```

---

### Task 11: 归档 nexus_app + 更新框架文档（M5 → G4）

**Files:**
- Move: `nexus_app/` → `docs/superpowers/archive/2026-07-22-nexus-app-pre-phone/` **或** 删除前在 README 说明（推荐归档关键源码快照 + 删除可构建工程二选一；默认：**保留 `nexus_app` 目录但在根 README/doc 标记 deprecated**，避免一次删光无法对照——执行时与用户确认）
- Modify: `doc/00_framework_overview.md` — 业务 App 改为 `nexus_phone` / `com.nexus.phone`
- Modify: `docs/superpowers/specs/2026-07-22-nexus-phone-fossify-mod-design.md` 状态 → 已实现（或部分）
- Create: `nexus_phone/README.md` — 构建、安装、默认电话、模型路径、与 HAL 关系

**推荐归档策略（写进步骤）：**

1. 更新 `doc/00_framework_overview.md` 声明唯一 App 为 `nexus_phone`  
2. 在 `nexus_app/README.md` 顶部加「DEPRECATED：请用 nexus_phone」  
3. 不在本 Task 强制 `git rm -r nexus_app`（除非用户明确要求）

- [ ] **Step 1: 更新 `doc/00_framework_overview.md`**

关键替换：

- `com.nexus.assistant` → `com.nexus.phone`  
- 设置入口 → Fossify 设置内「Nexus / AI」  
- 仍仅 Magisk 模块 `nexus_audio_hook`

- [ ] **Step 2: 写 `nexus_phone/README.md`（构建命令、flavor `coreDebug`、上游 SHA 链接 UPSTREAM.md）**

- [ ] **Step 3: 真机 G4 清单**

1. 卸载旧 `com.nexus.assistant` 后仅新包  
2. 默认电话 + 三策略 + 存档 + 设置  
3. Manifest 无第二套 UI InCall（`rg InCallService nexus_phone/app/src/main`）

- [ ] **Step 4: Commit**

```powershell
git add doc/00_framework_overview.md nexus_phone/README.md nexus_app/README.md
git commit -m "docs: point framework overview to nexus_phone; deprecate nexus_app"
```

**Gate G4 = 完整迁移完成定义（spec §8）。**

---

## 风险速查（执行时）

| 症状 | 处理 |
|------|------|
| 来电仍弹出 Fossify 全屏 | AI 分支未 `return`；检查通知 FSI |
| AI 接通无通话中 UI | ACTIVE 未 `startActivity(CallActivity)` |
| 双 UI / 无 AI | 确认未注册 `NexusInCallService` |
| sherpa UnsatisfiedLinkError | abiFilters / jni 打包与旧 App 对齐 |
| 默认电话不是本 App | `APP_ID`、debug suffix、Role 申请 |

---

## Spec coverage（自检）

| Spec 项 | Task |
|---------|------|
| 单仓内嵌 Fossify | 1 |
| `com.nexus.phone` 包名 | 2 |
| M0 默认电话壳 | 2 / G0 |
| 配置 / 双卡 / 接管 | 3–4 / G1 |
| CallPolicyController + Fossify InCall | 5–6 |
| AI 跳过响铃、复用通话中 UI | 6 |
| PCM 旁路 | 7 / G2 |
| 存档 + Webhook | 8 |
| 短信 | 9 / G3 |
| 设置嵌入 | 4、10 |
| 文档 / 单 App | 11 / G4 |
| 不改 HAL / 不自动迁 prefs | Global Constraints |

## Placeholder scan

无 TBD；Commons 不 fork 已在 File map 明确为非阻塞。上游类名以拷贝后仓库为准，若 Fossify 更新导致路径漂移，以 `CallService.kt` / `CallActivity.kt` 实际内容为准微调挂钩，不改变本计划行为语义。
