# 个人 AI 通信助理 (AI-Call-Agent) 技术落地白皮书

**版本:** v1.20（三模块命名：nexus_audio_hook + nexus_runtime + nexus_models）  
**日期:** 2026-07-19  
**作者:** Developer  
**目标环境:** 备用 Android 机（实装：OnePlus 8T / 骁龙 865 / LineageOS 23.2，Magisk + Zygisk）

**相关文档：**

- 过程日志（**增量追加**）：[`doc/dev_journal.md`](dev_journal.md)
- 下一里程碑清单：[`doc/03_pcm_hook_next.md`](03_pcm_hook_next.md)
- **现行实现（数据流 / 线程）：** [`doc/04_architecture_runtime.md`](04_architecture_runtime.md)
- **sherpa NDK 手编：** [`doc/05_sherpa_android_build.md`](05_sherpa_android_build.md)
- 注入/Dobby 详记：[`doc/02_zygisk_inject_progress.md`](02_zygisk_inject_progress.md)
- 旧 LD_PRELOAD 复盘：[`doc/Magisk_Injection_Log.md`](Magisk_Injection_Log.md)
- Magisk 业务模块说明：[`magisk_modules/README.md`](../magisk_modules/README.md)
- 模块手册：[`zygisk_module/doc/README.md`](../zygisk_module/doc/README.md)

---

## 1. 系统架构概述

本项目旨在打造完全运行在备用安卓机上的「AI 电话接听与短信摘要」中枢。

核心设计：**底层劫持音频、端侧极致处理、云端高智商推理、微信无缝触达**。

### 1.1 接听模式（互斥）

| 模式 | 行为 |
|------|------|
| **全 AI 接听** | 对面只与 AI 对话；本机主人不插话 |
| **全人接听** | 本机主人接听；不做实时 STT/TTS |

不同时出现「AI 与人一起回复」。

### 1.2 音频数据流（定稿）

**全 AI 接听：**

1. **HAL 只采 DL**（`INCALL_REC_DOWNLINK` + `VOC_REC_DL`）→ UDS → Go  
2. **STT** 只吃 DL（对方语音）→ LLM → **TTS**  
3. **TTS** 经 **incall-music uplink（1.E）** 注入，对面可听  
4. **存档听效果：** Go 内 **软件混合 `DL + TTS PCM`** 落盘（不依赖第二路硬件 incall-rec）

**全人接听（听验/存档）：** 可继续用已验证的 **UL+DL 混合** incall-rec 落盘。

**不采用：** 硬件并行开两路 incall-rec（DL + 混合）——同一 MultiMedia9/会话上不可靠，不作为前置依赖。

### 1.3 组件

1. **AudioHook（C++ / Magisk Zygisk）** — 模块 ID **`nexus_audio_hook`**（原 `ai_audio_hook`）  
   主线：**32-bit HAL** → `android.hardware.audio.service`。  
   **已完成：** 混合听验 + UDS + **DL-only** + **1.E TX**。  

2. **AI 调度守护进程（Go）** — 现行 `/data/local/tmp` 调试可用；将迁入 **`nexus_runtime`**（程序）+ **`nexus_models`**（模型可选包）  
3. **微信推送层（企业微信 API）** — **TODO，与短信转发同一后续里程碑**（现仅文本落盘）  

---

## 2. 核心技术栈与选型

| 类别 | 选型 | 备注 |
|------|------|------|
| Root / 模块 | Magisk 27+，**开启 Zygisk** | |
| 进程注入 | companion + ptrace `dlopen`，`service.sh` 兜底 | HAL：`/vendor/lib/libai_hook.so`（32） |
| Inline Hook | Dobby；HAL 侧 `dlsym` 取址（勿用 Dobby maps 解析） | `execmem`：audioserver + `hal_audio_default` |
| 通话 PCM（AI） | incall-rec **DL-only** → UDS | STT 输入；勿用混合当唯一 STT 源 |
| 通话 PCM（人/听验） | incall-rec **UL+DL**（已验证） | 48kHz s16le；L≡R 混合 |
| 存档（AI 模式） | **现行：文本**（对话+摘要落盘）；语音 `mix(DL,TTS)` **TODO 延期** | mix 需按 TX 播放时间轴对齐 |
| TX 注入 | incall-music uplink（1.E） | 对面听见 TTS |
| SELinux | `sepolicy.rule`：execmem + `vendor_data_file` + UDS | Magisk 语法无冒号、不用 `self` |
| STT | **本地** sherpa-onnx SenseVoice（`ai_call`） | mock 可验管线 |
| TTS | **本地 VITS**（`vits-zh-ll` ✅） | 经 1.E `tx_inject.pcm`；换模型/`sid` 换声 |
| LLM | **DeepSeek 云端**（`-llm` 流式切句 + 通内 session） | 默认 `deepseek-v4-flash`；打断见 `LLM_BARGE_IN`（默认关） |
| 企微 / 短信 | **TODO 捆绑后续** | 先落盘；推送与短信一起做 |

### 2.1 已废弃 / 已排除

- Overlay 换 `audioserver` + `LD_PRELOAD`
- `dlopen(/data/local/tmp/...)`；载荷放 `/system/lib`（linker namespace 拒绝）
- audioserver datapath / MonoPipe 当通话 PCM（通话段 AF standby）
- 仅 Hook tinyalsa / compress_voip（响铃有、通话中段无）
- 硬件双路 incall-rec 并行作存档（不可靠，改软件合成）

---

## 3. 实施路线与当前进度

### 阶段一：Native 劫持通话 PCM — **1.A～1.F、VAD(A)、1.E+TTS、DeepSeek 闭环 ✅**

#### 1.A 注入投递 — ✅

#### 1.B Dobby 探测 Hook — ✅（audioserver `openat`）

#### 1.C 定位通话 PCM 并拦截 — ✅（2026-07-19 听验通过）

混合路径已验证：`platform_set_incall_recording_session_id(vsid, UL+DL)` + `VOC_REC_UL/DL` + `pcm_open(0,23)` → `/data/vendor/ai_hook/ai_incall.pcm`。

#### 1.D UDS ↔ Go — ✅（2026-07-19）

HAL `bind/listen` → Go `pcm_recv` `connect`；`APCM` + s16le；真机 `uds == dumped`。

#### 1.D′ DL-only 推流 — ✅（2026-07-19 听验通过）

`INCALL_REC_DOWNLINK` + `VOC_REC_DL`；UDS `kind=DL`；用户确认录音仅对方声。

#### 1.E incall-music TX + 本地 TTS — ✅（2026-07-19 通话听验）

- 注入点 + **按需 `tx_inject.pcm`**（48k mono）；播完 unlink，**每句需重新写入**
- `sherpa-onnx-offline-tts` + **VITS `sherpa-onnx-vits-zh-ll`**；`ai_call -say` → TX
- 对面听到女声；音色由模型/`-tts-sid` 决定，换模型可换声
- 软件合成存档：未做；**DeepSeek 流式闭环：通话听验 ✅**（含通内上下文）

#### 1.G DeepSeek 流式 + 通内上下文 — ✅（2026-07-19）

- `ai_call -llm`：STT → SSE → 标点切句 TTS→TX；模型默认 `deepseek-v4-flash`（thinking 关闭）
- Android：自定义 DNS + 系统 CA（`/system/etc/security/cacerts`）
- **通内记忆：** `llm.CallSession` 累积 user/assistant；挂断/新 UDS stream `Reset`
- **历史上限：** 默认最近 **24** 条非 system（`-llm-max-msgs` / `LLM_MAX_MSGS`）——防 token/延迟膨胀；短通话通常触达不到；**不是**跨通话长期记忆
- **文本存档：** 挂断 → 等 in-flight 结束 → 全文+DeepSeek 摘要 → `/data/vendor/ai_hook/calls/call_*.txt`
- **TODO 延期：** 语音 `mix(DL,TTS)`；**企微推送 + 短信**同一里程碑再做

#### 1.F Go：本地 STT — ✅（2026-07-19 sherpa 真机听验）

- `daemon/ai_call`：DL → VAD → mock|sherpa SenseVoice → `stt.log`
- 真机出字；VAD 方案 A 已过滤纯标点

#### 可移植 / 开机自恢复 — ✅（2026-07-19 模块 v2.1 重装）

- Magisk 装 zip + 重启 → `service.sh` 自动 `inject32` HAL；maps + `pcm.sock` OK
- `ai_call` **未**自启（仍手动）；Zygisk 缺 `armeabi-v7a.so` 仅告警、不影响主线

#### TODO（进行中定稿）：业务侧双 Magisk 包 + Hook 更名

**三模块分工（互不塞职责）：**

| 模块 ID | 内容 | 状态 |
|---------|------|------|
| **`nexus_audio_hook`** | HAL 注入 / UDS / DL / TX（原 `ai_audio_hook`，仅更名） | ✅ 改名 v2.2 |
| **`nexus_runtime`** | `ai_call`、`nexus_engine`、ORT so、配置目录、开机自启 | ✅ 骨架 `magisk_modules/nexus_runtime`（填 bin 后打包） |
| **`nexus_models`** | SenseVoice + VITS 等大模型（与程序版本解耦） | ✅ 骨架 `magisk_modules/nexus_models`（填模型后打包） |

- 可写配置/密钥建议：`/data/adb/nexus/`（`config.json`、`secrets/`），**不**写进 module 只读树  
- 装新 Hook 前请卸载旧 ID `ai_audio_hook`（Magisk 按 id 并存）  
- HAL/UDS/inject **不**并入 runtime/models；详见 [`03_pcm_hook_next.md`](03_pcm_hook_next.md)

#### 定稿：HAL 只采集，用不用交给业务层

- HAL：接通即 DL 旁路（UDS+落盘）；不按卡关采集；**暂不考虑省电关旁路**
- Go/策略：是否 STT/AI、双卡接听策略等；见 [`03_pcm_hook_next.md`](03_pcm_hook_next.md)

#### 定稿 / TODO：短信转发（未做）

- **不**进 `nexus_audio_hook`；另模块/组件负责短信，**Go 统一配置与转发编排**，与 HAL/STT 解耦

---

### 阶段二～四

完整 daemon 多会话、企微+短信出站、语音 `mix(DL,TTS)` — **TODO 延期**。DeepSeek 闭环 + 文本存档已在 `ai_call -llm`。


---

## 4. 关键风险

| 风险 | 应对 |
|------|------|
| 缺 `execmem` | DobbyCodePatch SIGSEGV；必须带正确 sepolicy |
| HAL 落盘 EACCES | 写 `vendor_data_file` + live/模块 sepolicy；预建文件 |
| PowerShell 解析 `$(pidof)` | 外层单引号包住 `adb shell '...'` |
| 录音≠注入 | TX 需 incall-music，勿与 VOC_REC 混淆 |
| TTS 回灌进 DL | 评估 AEC/门控；存档用软件 mix，STT 仍只信 DL |
| Magisk `zygisk/armeabi-v7a.so` missing | 主线靠 `service.sh`；可后补 32-bit companion 或去掉 unused zygisk |

---

## 5. 目录索引

| 路径 | 说明 |
|------|------|
| `zygisk_module/` | **现行主线**（含 `audio_hook_hal.cpp`） |
| `daemon/ai_call/` | **本地 STT 守护进程**（mock / sherpa SenseVoice） |
| `magisk_module/` | 旧实验 + 内嵌 Dobby 源码 |
| `doc/02_zygisk_inject_progress.md` | 注入操作详版 |
| `doc/03_pcm_hook_next.md` | 当前里程碑 |
| `doc/04_architecture_runtime.md` | 现行方案 / 数据流 / 线程模型 |
| `doc/05_sherpa_android_build.md` | sherpa-onnx-offline Android NDK 构建 |

---

*v1.20：Hook 更名 nexus_audio_hook；runtime+models 双包骨架。*
