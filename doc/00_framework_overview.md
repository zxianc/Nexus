# Nexus 现行框架总览

**日期：** 2026-07-22  
**机型：** OnePlus 8T / LineageOS + Magisk  
**模块：** 仅 `nexus_audio_hook`（Zygisk HAL）；业务在 Kotlin App `com.nexus.assistant`

细节实现（HAL Hook）见 [`04_architecture_runtime.md`](04_architecture_runtime.md)（文中 Go 路径为历史）。  
App 架构设计见 [`docs/superpowers/specs/2026-07-20-nexus-app-architecture-design.md`](../docs/superpowers/specs/2026-07-20-nexus-app-architecture-design.md)。

---

## 1. 一句话

```text
来电 → Nexus 默认电话 / InCallService（双卡策略）
    → HAL 旁路对方下行 PCM（pcm.sock）
    → App：VAD → ASR → DeepSeek → TTS → 帧化注回上行
    → 挂断：内存 Webhook（失败重试后标记）→ 结构化 call.json 落盘
    → 短信 SMS_RECEIVED + Inbox 水位 → 同一 Webhook
配置 / 模型 → App SharedPreferences / files
```

---

## 2. 谁在干活

| 组件 | 干什么 |
|------|--------|
| **`nexus_audio_hook`** | 通话 PCM 旁路 + UL 帧注入；Magisk 侧唯一模块 |
| **`com.nexus.assistant`** | 默认电话接管、策略、ASR/TTS/LLM、存档、Webhook、Settings |

**已弃用（勿再装 / 已 disable）：** `nexus_runtime`、`nexus_models`，以及 `ai_call` / `nexus_engine` / `nexus_webui` / `nexus_callpolicy` / `nexus_notify`。

配置真源：App SharedPreferences `nexus_config`（Settings 可改）。  
STT/TTS 可分别选 `.onnx`（同目录自动带附属文件）；默认 `files/models/sense-voice` 与 `vits-zh-ll`。TTS Speaker ID 可配。

---

## 3. Webhook 通知与存档

**原则：** 队列只在内存；先发、失败重试若干次，仍失败则标记，**不写** `notify_queue` 文件轮询。

| 事件 | 行为 |
|------|------|
| 通话挂断 | 内存发 Webhook（含对方 + 本机收件行）→ 写 `call.json`（含 `notify.status`） |
| 新短信 | `SmsReceiver`（`SMS_RECEIVED`）唤醒进程 → 查 Inbox 水位 → Webhook（发件人 + 收件人本机行） |

Webhook 正文标明：

- 通话：`对方` / `本机: 卡N 运营商 号码`
- 短信：`发件人` / `收件人: 卡N …`

本机号码来自 SIM 元数据（Settings「刷新卡信息」）；读不到则显示「号码未知」。

---

## 4. 关键路径

| 路径 | 作用 |
|------|------|
| `/data/vendor/ai_hook/pcm.sock` 或 abstract `@nexus_pcm` | HAL↔App 帧化 PCM |
| App SharedPreferences `nexus_config` | LLM / Webhook / 双卡 / 接管 / 模型路径 / speaker |
| App SharedPreferences `nexus_sms` | 短信 Inbox `_id` 水位 |
| App `files/models/` 或 `files/imported/{stt,tts}/` | 模型权重 |
| App `getExternalFilesDir("nexus_calls")/calls/<id>/` | `call.json` + `transcript.txt` 等 |

---

## 5. Magisk

| 模块 | 状态 |
|------|------|
| `nexus_audio_hook` | **保留** |
| `nexus_models` | 弃用（设备可 `touch …/disable`） |
| `nexus_runtime` | 弃用（勿安装） |

一次性从旧 Magisk 配置迁移：`nexus_app/scripts/migrate_magisk_config_once.py`。

**TX 路径：** App → UDS `PCM_UL`（HAL 已移除 `tx_inject.pcm` / 测试音等旧路径）。

**仍 TODO：**
- **AI 接听静麦保 TX**（kona：勿用系统静音 / 勿清 `TX_AIF1_CAP Mixer DEC*`；详见 architecture plan Deferred TODO）

**G3：** 2026-07-22 已标记完成（遇问题再修）；见 [验收清单](../docs/superpowers/plans/checklists/2026-07-20-app-mvp-acceptance.md)。  
**M5 / Task 15：** HAL 已删除 `tx_inject.pcm` / 测试音 / DL 落盘等旧路径（仅 UDS 帧化）。
