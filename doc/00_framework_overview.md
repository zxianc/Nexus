# Nexus 现行框架总览

**日期：** 2026-07-21  
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
    → 挂断落盘 + 企微 Webhook；短信 ContentObserver → 同一 Webhook
配置 / 模型 → App files/（不再 Magisk 同步）
```

---

## 2. 谁在干活

| 组件 | 干什么 |
|------|--------|
| **`nexus_audio_hook`** | 通话 PCM 旁路 + UL 帧注入；Magisk 侧唯一模块 |
| **`com.nexus.assistant`** | 默认电话接管、策略、ASR/TTS/LLM、存档、企微、Settings |

**已弃用（勿再装 / 已 disable）：** `nexus_runtime`、`nexus_models`，以及 `ai_call` / `nexus_engine` / `nexus_webui` / `nexus_callpolicy` / `nexus_notify`。

配置真源：App SharedPreferences `nexus_config`（Settings 可改；不再维护 `config.json`）。  
STT/TTS 可分别选 `.onnx`（同目录自动带 `tokens.txt` 等）；默认仍为 `files/models/sense-voice` 与 `vits-zh-ll`。

---

## 3. 关键路径

| 路径 | 作用 |
|------|------|
| `/data/vendor/ai_hook/pcm.sock` 或 abstract `@nexus_pcm` | HAL↔App 帧化 PCM |
| App SharedPreferences `nexus_config` | LLM / 企微 / 双卡策略 / 接管开关 |
| App `files/models/` | SenseVoice + VITS |
| App `getExternalFilesDir("nexus_calls")` | 通话存档 |

---

## 4. Magisk

| 模块 | 状态 |
|------|------|
| `nexus_audio_hook` | **保留** |
| `nexus_models` | 弃用（设备可 `touch …/disable`） |
| `nexus_runtime` | 弃用（勿安装） |

一次性从旧 Magisk 配置迁移：`nexus_app/scripts/migrate_magisk_config_once.py`。

**仍 TODO：**
- **AI 接听静麦保 TX**（kona：勿用系统静音 / 勿清 `TX_AIF1_CAP Mixer DEC*`；详见 architecture plan Deferred TODO）
- G3 真机验收清单勾选
