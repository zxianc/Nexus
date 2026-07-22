# Nexus 现行框架总览



**日期：** 2026-07-22  

**机型：** OnePlus 8T / LineageOS + Magisk  

**模块：** 仅 `nexus_audio_hook`（Zygisk HAL）；业务在 **`nexus_phone`**（`org.fossify.nexus.phone`，Kotlin 包 `com.nexus.phone`）



HAL 模块操作见 [`zygisk_module/doc/README.md`](../zygisk_module/doc/README.md)。  

**App 编译 / 正式包签名：** [`nexus_phone/README.md`](../nexus_phone/README.md)。

Fossify 魔改设计：[`docs/superpowers/specs/2026-07-22-nexus-phone-fossify-mod-design.md`](../docs/superpowers/specs/2026-07-22-nexus-phone-fossify-mod-design.md)。  

已归档旧 App 提案：[`docs/superpowers/archive/2026-07-20-nexus-app-architecture/`](../docs/superpowers/archive/2026-07-20-nexus-app-architecture/)。  

文档索引：[`README.md`](README.md)。



---



## 1. 一句话



```text

来电 → Nexus Phone（Fossify UI）/ CallService + 双卡策略

    → AI：跳过响铃全屏 → answer → Fossify 通话中 UI + PCM 旁路

    → HAL 旁路对方下行 PCM（pcm.sock）

    → App：VAD → ASR → DeepSeek → TTS → 帧化注回上行

    → 挂断：内存 Webhook → call.json 落盘

    → 短信 SMS_RECEIVED + Inbox 水位 → 同一 Webhook

配置 → Settings →「Nexus / AI」→ SharedPreferences nexus_config

```



---



## 2. 谁在干活



| 组件 | 干什么 |

|------|--------|

| **`nexus_audio_hook`** | 通话 PCM 旁路 + UL 帧注入；Magisk 侧唯一模块 |

| **`nexus_phone`**（`org.fossify.nexus.phone`） | 默认电话 UI（拨号/记录/联系人/通话中）、双卡策略、ASR/TTS/LLM、存档、Webhook |



配置真源：App SharedPreferences `nexus_config`（**Settings → Nexus / AI**）。  

STT/TTS 默认 `files/models/sense-voice` 与 `vits-zh-hf-fanchen-C`（旧包 `vits-zh-ll` 仍可作回退）。替换模型（下载 / 安装）见 [`01_replace_models.md`](01_replace_models.md)。



**已弃用：** `nexus_app` / `com.nexus.assistant`（迁移期对照源码，勿再作为主安装包）。



---



## 3. Webhook 通知与存档



**原则：** 队列只在内存；先发、失败重试若干次，仍失败则标记，**不写** `notify_queue` 文件轮询。



| 事件 | 行为 |

|------|------|

| 通话挂断 | 内存发 Webhook → 写 `call.json`（含 `notify.status`） |

| 新短信 | `SmsReceiver` → Inbox 水位 → Webhook |



---



## 4. 关键路径



| 路径 | 作用 |

|------|------|

| `/data/vendor/ai_hook/pcm.sock` 或 abstract `@nexus_pcm` | HAL↔App 帧化 PCM |

| App SharedPreferences `nexus_config` | LLM / Webhook / 双卡 / 策略开关 / 模型路径 / speaker |

| App SharedPreferences `nexus_sms` | 短信 Inbox `_id` 水位 |

| App `files/models/` 或 `files/imported/{stt,tts}/` | 模型权重 |

| App `getExternalFilesDir("nexus_calls")/calls/<id>/` | `call.json` + `transcript.txt` 等 |



---



## 5. Magisk



仅安装 **`nexus_audio_hook`**（本仓库 `zygisk_module/`）。  

**TX：** App → UDS `PCM_UL`。



**仍 TODO：** AI 接听静麦保 TX（kona）；见 architecture plan Deferred TODO。

