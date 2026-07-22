# Nexus Phone：基于 Fossify 完全魔改 — 设计说明

**日期：** 2026-07-22  
**分支：** `feature/nexus-phone-fossify`  
**状态：** 设计已确认，待写实现计划  

## 1. 背景与目标

现有 `nexus_app`（`com.nexus.assistant`）已具备默认电话接管、双卡策略、AI 旁路（PCM）、存档与 Webhook，但拨号盘 / 通话记录 / 联系人仅为 MVP，无法作为日常默认电话使用。

决策：**不以自研 Compose 薄壳为主路径**；改为将 [Fossify Phone](https://github.com/FossifyOrg/Phone) 源码拷入 Nexus 单仓，**彻底改包名并深度魔改**，逐步迁入现有 `nexus_app` 能力，最终只保留一个 App。

### 1.1 目标

- 唯一用户可见 App：`com.nexus.phone`（显示名建议：Nexus Phone）
- 日常体验：Fossify 级拨号盘、通话记录、联系人、来电与通话中 UI
- AI 能力：与现行框架一致（策略接听、PCM 旁路、存档、Webhook、短信通知、配置）
- Magisk HAL（`nexus_audio_hook` / `zygisk_module`）保持不变

### 1.2 非目标（本设计不阻塞「完整迁移」）

- 设置页大改版 / 深色 Compose 四 Tab（可后置）
- 通话记录 Nexus 角标增强（可后置）
- 静麦保 TX 等 HAL Deferred TODO（不在迁移中顺手修）
- 从旧包 `com.nexus.assistant` **自动迁移** SharedPreferences（自用可手动重配）

## 2. 已确认决策

| 项 | 选择 |
|----|------|
| 代码落点 | Nexus 单仓内嵌 Fossify 源码（非独立 fork 仓、非 submodule） |
| 旧 App | 以新 App 为唯一目标；逐步迁 `nexus_app` 能力，完成后归档/删除 |
| InCall UI | **保留 Fossify** 的 InCall / CallActivity |
| AI 来电路径 | **跳过响铃/来电全屏 UI** → `answer()` → **复用 Fossify 通话中 UI** + 启动 PCM |
| HUMAN 路径 | 不拦截，走 Fossify 原有来电 UI |
| 包名 | `applicationId` / 代码包：`com.nexus.phone`（可与旧 App 并列安装过渡） |
| 迁移节奏 | 完整迁移为终点；按里程碑 M0–M5 分步验收（不做大爆炸单 PR） |
| 许可证 | 自用项目；Fossify GPL 不作为阻塞因素记录在案 |

## 3. 仓库与工程布局（M0）

```text
Nexus/
├── nexus_phone/                 # 新唯一 App（Fossify 拷贝 + 改包名）
│   ├── app/                     # applicationId = com.nexus.phone
│   └── …                        # 上游 modules（如 commons）按 Fossify 结构保留并改包名
├── nexus_app/                   # 迁移期能力源码；M5 归档或删除
├── zygisk_module/               # HAL，本设计不改
└── doc/                         # M5 更新框架总览
```

### 3.1 包名与品牌

| 项 | 值 |
|----|-----|
| `applicationId` | `com.nexus.phone` |
| Kotlin/Java 包 | `com.nexus.phone.*`（由 `org.fossify.phone` 及所依赖 commons 包全面替换） |
| 显示名 | Nexus Phone（或「Nexus」） |
| 旧包 | `com.nexus.assistant` 过渡期可并列；配置不自动搬家 |

### 3.2 M0 验收

1. 分支上可编译安装 `com.nexus.phone`
2. 可设为默认电话：拨号、记录、联系人、接打正常
3. Fossify 自带来电 / 通话中 UI 可用（**尚未**接 AI）
4. 应用名 / 图标已去 Fossify 对外品牌（自用级清理即可）

### 3.3 M0 明确不做

- 不迁 AI / Webhook / 旧 Settings
- 不改 `zygisk_module`
- 不删 `nexus_app`

## 4. 里程碑 M1–M5

| 阶段 | 内容 | 验收 |
|------|------|------|
| **M0** | Fossify 进仓、改包名、默认电话壳 | 见 §3.2 |
| **M1** | 配置真源：`ConfigRepository`、双卡策略、`SimCatalog`、接管开关语义 | 可持久化策略与相关 prefs |
| **M2** | InCall 挂钩：AI / 拒接 / 人工 + PCM 会话 | 见 §5 |
| **M3** | Webhook、`CallFinalizer` / `call.json`、短信水位 | 挂断通知+存档；短信 Webhook |
| **M4** | 设置能力嵌入 Fossify 设置树 | LLM / 模型 / 开场白 / Webhook / 接管 / 双卡均可配置 |
| **M5** | 归档或删除 `nexus_app`；更新 `doc/00_framework_overview.md` | 单 App；无对旧包运行时依赖 |

推荐实现顺序严格按 M0→M5；每阶段可独立真机验收后再进入下一阶段。

## 5. InCall 与 AI 挂钩（M2）

### 5.1 原则

- Manifest **只保留一套**带 `IN_CALL_SERVICE_UI=true` 的 InCallService：**Fossify 的那套**
- **不**再注册旧 `NexusInCallService` 作为 UI InCall（避免双 UI 抢绑）
- 从现有 `NexusInCallService.handleState` 抽出与 UI 无关的 **`CallPolicyController`**，挂到 Fossify 通话状态回调
- PCM / 旁路继续用普通 / 前台 `Service`（如迁入后的 `NexusBypassService`），不充当 InCall UI

### 5.2 状态机

```text
RINGING
  → 解析 slot（PhoneAccount → SimCatalog）
  → 读 SimPolicy
       AI     → 跳过 Fossify 来电/响铃全屏 UI
              → answer()
              → 标记 aiMode
       REJECT → reject()
       HUMAN  → 不拦截，走 Fossify 原有来电 UI

ACTIVE + aiMode
  → 展示 / 复用 Fossify CallActivity（通话中 UI）
  → BypassCommands.startSession（PCM：VAD → ASR → LLM → TTS → UL 注入）

ACTIVE + HUMAN（非 AI）
  → 仅 Fossify 通话中 UI（无旁路会话）

DISCONNECT / DISCONNECTING
  → BypassCommands.endSession
  → CallFinalizer（内存 Webhook → call.json）
```

### 5.3 与旧行为对齐的要点

- 保留 `AiAnswerReceiver` 类 fallback 语义（Telecom 未正确绑定本包 InCall 时），迁到 `com.nexus.phone` 包名
- AI 自动接听保留「立即 + 短延迟重试 `answer()`」以应对部分机型 bind 竞态（与现实现一致）
- **DialerTakeover** 语义保留为「本包为默认电话 + 策略引擎生效」；实现改为面向 `com.nexus.phone`，不再 enable/disable 旧 `NexusInCallService` 组件开关那一套（Fossify InCall 始终为 UI）

### 5.4 M2 验收

1. AI 策略卡：来电不出现（或立即关闭）响铃全屏 → 自动接听 → Fossify 通话中界面 + PCM 会话启动
2. HUMAN：Fossify 来电 UI → 用户接听 → 通话中 UI，无 AI 旁路
3. REJECT：来电被拒接
4. 挂断后会话结束；M3 完成前存档/Webhook 可暂缺，但不得泄漏旁路会话

## 6. 代码迁移动线

逻辑从 `nexus_app` 迁入 `nexus_phone` 时建议放在独立命名空间，便于与 Fossify UI 代码区分，例如：

```text
nexus_app/.../config/*      → nexus_phone/.../nexus/config/
nexus_app/.../ai/*          → nexus_phone/.../nexus/ai/
nexus_app/.../service/*     → nexus_phone/.../nexus/service/
nexus_app/.../archive/*     → nexus_phone/.../nexus/archive/
nexus_app/.../notify/*      → nexus_phone/.../nexus/notify/
策略相关 telecom 逻辑       → CallPolicyController + 挂钩适配层
Settings UI                 → M4 嵌入 Fossify 设置（可先内部 NexusSettingsActivity 入口）
```

- sherpa-onnx AAR、jni、`ModelPaths` / 模型导入随 AI 模块迁移
- 配置 prefs 名可继续使用 `nexus_config` / `nexus_sms`（新包私有，与旧包隔离）

## 7. 设置与品牌（M4）

- 在 Fossify 设置中增加 **「Nexus / AI」** 分组或入口
- 功能对齐现 `SettingsActivity`：接管、双卡、开场白、STT/TTS、Speaker、LLM、Webhook、存档路径
- 第一版允许朴素实现（复用表单逻辑）；视觉大改后置
- 应用图标可在 M0 或 M4 更换；关于页去掉 Fossify 商店/社区外链（自用级）

## 8. 「完整迁移」完成定义（M5）

同时满足：

1. 只安装 `com.nexus.phone` 即可完成：默认电话 + AI 接听 + 旁路 + 存档 + Webhook + 短信通知 + 配置
2. Manifest 无第二套 UI InCall；无对 `com.nexus.assistant` 的运行时依赖
3. `nexus_app/` 已归档（如 `docs/superpowers/archive/…`）或删除；`doc/00_framework_overview.md` 指向 `nexus_phone`
4. 真机回归：三策略、挂断存档、设置可改

## 9. 风险与约束

| 风险 | 缓解 |
|------|------|
| 双 InCall 抢绑 | 仅 Fossify 注册 UI InCall；旧 UI InCall 不迁入 Manifest |
| AI 接听与来电全屏抢焦点 | AI 路径显式跳过/关闭响铃 UI，再 `answer()`，ACTIVE 后只走 CallActivity |
| Fossify 与 Nexus 构建体系差异 | M0 先独立编过；再按模块迁依赖（Gson、sherpa AAR 等） |
| commons 模块包名连锁修改 | M0 一次性脚本化改名 + 全量编译 |
| 上游 Fossify 更新 | 自用 fork 心态：不强制跟踪上游；需要时再 cherry-pick |
| 配置空窗 | 新包首次安装为空配置；文档注明需重配 |

## 10. 测试策略

- **M0：** 安装、默认电话角色、拨号/记录/联系人、人工接打
- **M1：** 单元测试沿用/迁移 `SimCatalog` 等 JVM 测；真机改策略持久化
- **M2：** 真机三策略；确认 AI 无来电全屏、有通话中 UI、有旁路日志/行为
- **M3：** 存档 `call.json` 字段与 Webhook 行为对齐现行框架文档
- **M4：** 设置项读写与旁路/通知联动
- **M5：** 卸载旧包后仅新包回归；文档路径检查

优先保持可 JVM 单测的纯逻辑（策略、存档写入、号码 normalize）与 UI/Telecom 分离，便于在 `CallPolicyController` 层回归。

## 11. 分支与后续文档

- **实现分支：** `feature/nexus-phone-fossify`（本 spec 所在分支）
- **下一步：** 按本 spec 编写 `docs/superpowers/plans/2026-07-22-nexus-phone-fossify-mod.md`（writing-plans），再按 M0 起执行
- **参考上游：** https://github.com/FossifyOrg/Phone（拷贝时记录 commit SHA 于 plan 或 README）

## 12. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-07-22 | 初稿：单仓魔改 Fossify、M0–M5、AI 跳过响铃 UI 并复用通话中 UI |
