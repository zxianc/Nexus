# 个人 AI 通信助理 (AI-Call-Agent) 技术落地白皮书

**版本:** v1.7（阶段 1.C 完成：通话 PCM 已真机落盘听验）  
**日期:** 2026-07-19  
**作者:** Developer  
**目标环境:** 备用 Android 机（实装：OnePlus 8T / 骁龙 865 / LineageOS 23.2，Magisk + Zygisk）

**相关文档：**

- 过程日志（**增量追加**）：[`doc/dev_journal.md`](dev_journal.md)
- 下一里程碑清单：[`doc/03_pcm_hook_next.md`](03_pcm_hook_next.md)
- 注入/Dobby 详记：[`doc/02_zygisk_inject_progress.md`](02_zygisk_inject_progress.md)
- 旧 LD_PRELOAD 复盘：[`doc/Magisk_Injection_Log.md`](Magisk_Injection_Log.md)
- 模块手册：[`zygisk_module/doc/README.md`](../zygisk_module/doc/README.md)

---

## 1. 系统架构概述

本项目旨在打造完全运行在备用安卓机上的「AI 电话接听与短信摘要」中枢。

核心设计：**底层劫持音频、端侧极致处理、云端高智商推理、微信无缝触达**。

1. **AudioHook（C++ / Magisk Zygisk）**  
   主线：**32-bit HAL** `/vendor/lib/libai_hook.so` → `android.hardware.audio.service`。  
   **当前：** 通话 PCM 经 **incall-rec（MultiMedia9 + VOC_REC_UL/DL）** 已真机采集并听验通过（上下行混合单轨）。  
   **说明：** 该点是**旁路录音**，不是 TX 注入；对面要听见需另开 incall-music uplink。

2. **AI 调度守护进程（Go / Termux）** — 下一步（1.D UDS）  
3. **微信推送层（企业微信 API）** — 未开始  

---

## 2. 核心技术栈与选型

| 类别 | 选型 | 备注 |
|------|------|------|
| Root / 模块 | Magisk 27+，**开启 Zygisk** | |
| 进程注入 | companion + ptrace `dlopen`，`service.sh` 兜底 | HAL：`/vendor/lib/libai_hook.so`（32） |
| Inline Hook | Dobby；HAL 侧 `dlsym` 取址（勿用 Dobby maps 解析） | `execmem`：audioserver + `hal_audio_default` |
| 通话 PCM | incall-rec：`platform_set_incall_recording_session_id` + mixer `VOC_REC_*` + `pcm_read` d23 | 48kHz s16le；L≡R 混合 |
| SELinux | `sepolicy.rule`：execmem + HAL 写 `vendor_data_file` | Magisk 语法无冒号、不用 `self` |
| 守护进程 / STT / TTS / LLM / 企微 | 同前，待后续阶段 | |

### 2.1 已废弃 / 已排除

- Overlay 换 `audioserver` + `LD_PRELOAD`
- `dlopen(/data/local/tmp/...)`；载荷放 `/system/lib`（linker namespace 拒绝）
- audioserver datapath / MonoPipe 当通话 PCM（通话段 AF standby）
- 仅 Hook tinyalsa / compress_voip（响铃有、通话中段无）

---

## 3. 实施路线与当前进度

### 阶段一：Native 劫持通话 PCM — **1.C 完成，进入 1.D**

#### 1.A 注入投递 — ✅

#### 1.B Dobby 探测 Hook — ✅（audioserver `openat`）

#### 1.C 定位通话 PCM 并拦截 — ✅（2026-07-19 听验通过）

**结论路径：**

1. `voice_start_call` / `voice_start_usecase(41)` 确认 CS/IMS voice  
2. `platform_set_incall_recording_session_id(vsid, UL+DL)`  
3. mixer：`MultiMedia9 Mixer VOC_REC_UL/DL = 1`  
4. `pcm_open(0, 23)` @ 48kHz stereo → `pcm_read` 全程  
5. 落盘 `/data/vendor/ai_hook/ai_incall.pcm`（需 sepolicy 写 `vendor_data_file`）

**听验：** 用户确认 wav 为双向通话录音；L/R 样本 **100% 相同**（混合单轨复制成 stereo）。

详见 [`03_pcm_hook_next.md`](03_pcm_hook_next.md)、[`dev_journal.md`](dev_journal.md)。

#### 1.D UDS ↔ Go — ⏳ 进行中

将 incall PCM 从 HAL 经 Unix Domain Socket 送给 Go 守护进程（STT 前级）。

#### 1.E（并行可选）incall-music TX 注入 — 未开始

对面听见 TTS：`USECASE_INCALL_MUSIC_UPLINK` / `platform_start_incall_music_usecase`。

---

### 阶段二～四

Go 守护进程、DeepSeek 流式、企微推送 — 均未开始（内容同前版规划）。

---

## 4. 关键风险

| 风险 | 应对 |
|------|------|
| 缺 `execmem` | DobbyCodePatch SIGSEGV；必须带正确 sepolicy |
| HAL 落盘 EACCES | 写 `vendor_data_file` + live/模块 sepolicy；预建文件 |
| PowerShell 解析 `$(pidof)` | 外层单引号包住 `adb shell '...'` |
| 录音≠注入 | TX 需 incall-music，勿与 VOC_REC 混淆 |

---

## 5. 目录索引

| 路径 | 说明 |
|------|------|
| `zygisk_module/` | **现行主线**（含 `audio_hook_hal.cpp`） |
| `magisk_module/` | 旧实验 + 内嵌 Dobby 源码 |
| `doc/02_zygisk_inject_progress.md` | 注入操作详版 |
| `doc/03_pcm_hook_next.md` | 1.C/1.D 里程碑 |

---

*v1.7：1.C incall-rec 通话 PCM 真机听验通过；下一目标 1.D UDS。*
