# Nexus 现行框架总览

**日期：** 2026-07-19  
**机型：** OnePlus 8T / LineageOS + Magisk  
**模块：** `nexus_audio_hook`（HAL）+ `nexus_runtime`（守护进程）+ `nexus_models`（权重）

细节实现（HAL Hook、VAD、线程）见 [`04_architecture_runtime.md`](04_architecture_runtime.md)。

---

## 1. 一句话

```text
来电策略 →（AI 卡）自动接听
    → HAL 旁路对方下行 PCM
    → ai_call：VAD → STT → DeepSeek → TTS → 注回通话上行
    → 挂断落盘 call_*.txt + .notify
    → nexus_notify → 企微群 Webhook
短信 → nexus_notify 轮询 inbox → 同一 Webhook
配置 → 本机 WebUI 改 config.json
```

---

## 2. 有哪些进程、分别干什么

| 进程 | 干什么 | 开机 | `restart_callstack` |
|------|--------|------|---------------------|
| **HAL + `libai_hook.so`** | 通话中采对方下行 PCM（`pcm.sock` / `ai_dl.pcm`）；播放 `tx_inject.pcm` 给对方 | 注入后常驻 | 不杀 |
| **`nexus_engine`** | 常驻本地 STT（SenseVoice）+ TTS（VITS） | `service.sh` | **会重启** |
| **`ai_call`** | 读 PCM → 切句识别 → LLM → TTS；挂断写存档 + `.notify` | `service.sh` | **会重启** |
| **`nexus_callpolicy`** | 双卡策略：`human` 响铃 / `ai` 自动接 / `reject` 拒接 | `service.sh` | **不杀**（热读配置） |
| **`nexus_notify`** | 通话 `.notify` + 双卡短信 → 企微 Webhook | `service.sh` | **不杀**（热读配置） |
| **`nexus_webui`** | 本机 `http://127.0.0.1:8787` 改配置、看进程/日志 | `service.sh` | **不杀** |

配置真源：`/data/adb/nexus/config.json`。

---

## 3. 数据怎么流通

### 3.1 AI 代接通话

```text
对方来电
    ▼
nexus_callpolicy 发现 RINGING（telephony.registry）
    │ sims[].policy == ai
    ▼ 自动接听 (HEADSETHOOK …)
通话接通 / OFFHOOK
    ▼
HAL libai_hook 打开 incall-rec **下行**
    ├─→ ai_dl.pcm（落盘备份）
    └─→ pcm.sock  ──APCM+PCM──►  ai_call
                                      │
                                      ├─ 16k mono + VAD 切句
                                      ├─ nexus_engine STT → 文字
                                      ├─ DeepSeek（带通内上下文）→ 回复
                                      └─ nexus_engine TTS → tx_inject.pcm
                                              │
                                              ▼
                                         HAL incall-music → 对方听到

挂断（registry 空闲防抖 或 UDS 断开）
    ▼
ai_call 写 calls/call_*.txt（时间/主叫/本机/策略/摘要/对话）
       + call_*.txt.notify
    ▼
nexus_notify 读存档 → POST 企微群机器人 → 删 .notify
```

### 3.2 短信

```text
系统收件箱 content://sms/inbox（带 sub_id）
    ▼
nexus_notify
    │ isub：sub_id → 卡槽（本机常见 slot0↔subId2）
    │ sims[]：显示运营商/本机号
    │ notify_sms_cursor：去重水位
    ▼
同一企微 Webhook
```

### 3.3 配置控制面

```text
Chrome → 127.0.0.1:8787 (nexus_webui)
    ▼
config.json
    ├─ sims[].policy     → callpolicy 热读
    ├─ notify.*          → notify 热读（勿误关 enabled）
    └─ llm / stt / tts   → 保存时可能重启 ai_call（± engine）
                           只改策略/通知 → 不重启通话栈
```

---

## 4. 关键文件

| 路径 | 作用 |
|------|------|
| `/data/vendor/ai_hook/pcm.sock` | HAL→ai_call 实时音频 |
| `/data/vendor/ai_hook/tx_inject.pcm` | TTS→对方 |
| `/data/vendor/ai_hook/calls/call_*.txt` | 通话文字存档 |
| `/data/vendor/ai_hook/calls/*.notify` | 待推送标记 |
| `/data/adb/nexus/config.json` | 配置 |
| `/data/adb/nexus/run/engine.sock` | ai_call↔engine |
| `/data/adb/nexus/run/notify_sms_cursor` | 短信水位 |
| `/data/vendor/ai_hook/*.log` | 各进程日志 |

---

## 5. 三个 Magisk 模块怎么分

| 模块 | 内容 |
|------|------|
| `nexus_audio_hook` | 只负责「听得到 / 说得出」的 HAL 能力 |
| `nexus_models` | STT/TTS 模型文件 |
| `nexus_runtime` | 上表全部 Go 进程 + 开机脚本 + WebUI |

打包说明：[`magisk_modules/README.md`](../magisk_modules/README.md)。

设计稿：WebUI / 双卡策略 / 企微通知见 `docs/superpowers/specs/2026-07-19-*.md`。

**仍 TODO：**
- **AI 接听静麦保 TX**（kona：勿用系统静音 / 勿清 `TX_AIF1_CAP Mixer DEC*`；需另标定麦增益；详见 architecture plan Deferred TODO）
- 通话语音 mix 存档
