# 个人 AI 通信助理 (AI-Call-Agent) 技术落地白皮书

**版本:** v1.4（Zygisk 注入已验证，进入 Hook 点选型）  
**日期:** 2026-07-18  
**作者:** Developer  
**目标环境:** 备用 Android 机（实装验证：OnePlus 8T / 骁龙 865，Magisk + Zygisk）

**相关文档：**

- 进展与操作验证（本文配套）：[`doc/02_zygisk_inject_progress.md`](02_zygisk_inject_progress.md)
- 旧 LD_PRELOAD 失败复盘：[`doc/Magisk_Injection_Log.md`](Magisk_Injection_Log.md)
- 模块侧简版说明：[`zygisk_module/doc/README.md`](../zygisk_module/doc/README.md)

---

## 1. 系统架构概述

本项目旨在打造一个完全运行在备用安卓机上的「AI 电话接听与短信摘要」中枢。

核心设计理念：**底层劫持音频、端侧极致处理、云端高智商推理、微信无缝触达**。

系统分为三大核心模块：

1. **AudioHook 模块（C++ / Magisk Zygisk）**  
   动态库 `libai_hook.so`，由 Zygisk companion / `service.sh` 通过 **ptrace + remote dlopen** 注入 `/system/bin/audioserver`。负责电话信令级双向 PCM 拦截与注入。  
   **当前：进程注入已在真机验证通过；函数级 Hook 尚未开始。**

2. **AI 调度守护进程（Go / Termux）**  
   接管 AudioHook 音频，调度本地 STT/TTS，与云端 DeepSeek 流式交互。

3. **微信推送层（企业微信 API）**  
   通话结束后推送摘要卡片到主力个人微信。

---

## 2. 核心技术栈与选型

| 类别 | 选型 | 备注 |
|------|------|------|
| Root / 模块框架 | Magisk 27.0+，**必须开启 Zygisk** | 不用换 `audioserver` 二进制 |
| 进程注入 | Zygisk companion + ptrace remote `dlopen`，`service.sh` 兜底 | 载荷路径：`/system/lib64/libai_hook.so` |
| Inline Hook | [Dobby](https://github.com/jmpews/Dobby) | **待接入**（注入完成后再做） |
| 守护进程 | Golang 1.22+（`linux/arm64`） | Termux / 自启 |
| STT | Sherpa-ONNX + SenseVoice-Small | 待阶段二 |
| TTS | Sherpa-ONNX + VITS/Piper 中文 | 待阶段二 |
| LLM | DeepSeek API（`stream: true`） | 待阶段三 |
| 触达 | 企业微信自建应用 | 待阶段四 |

### 2.1 已废弃方案（勿再走）

- Overlay 替换 `/system/bin/audioserver` + Shell/`LD_PRELOAD`
- `service.sh` 里 `killall audioserver` 后期望环境变量继承
- 向 `audioserver` `dlopen(/data/local/tmp/libai_hook.so)`（SELinux 拒绝）

---

## 3. 实施路线与当前进度

### 阶段一：Native 进入 audioserver 并劫持通话 PCM — **进行中**

#### 1.A 注入投递（已完成 ✅）

**目标：** `libai_hook.so` 稳定出现在 `audioserver` 的内存映射中。

**机制：**

1. Magisk 模块提供 `system/lib64/libai_hook.so`（Overlay）与 `zygisk/arm64-v8a.so`。
2. `preServerSpecialize` → `connectCompanion()`。
3. companion / `service.sh` 对进程名 `audioserver` 执行 ptrace remote `dlopen("/system/lib64/libai_hook.so")`。
4. 库内 `__attribute__((constructor))` 起线程（避免阻塞 audioserver 主循环）。

**操作（开发机）：**

```bat
cd zygisk_module
build.bat
adb push ai_audio_hook_zygisk.zip /sdcard/Download/
```

Magisk 安装 zip → 确认 Zygisk 开启 → 重启。

**验证（判定以 maps 为准）：**

```powershell
adb shell "su -c 'grep libai_hook /proc/\$(pidof audioserver)/maps'"
adb shell "su -c 'ls -l /system/lib64/libai_hook.so'"
```

成功样例：maps 中出现带 `r-xp` 的 `/system/lib64/libai_hook.so`。

手动补注入：

```powershell
adb shell "su -c '/data/adb/modules/ai_audio_hook/bin/inject audioserver /system/lib64/libai_hook.so'"
```

完整步骤与排障见 [`02_zygisk_inject_progress.md`](02_zygisk_inject_progress.md)。

#### 1.B 定位 Hook 点并拦截 PCM（下一步 ⏳）

**目标：** 在通话场景可靠拿到「对方下行」PCM，并能覆盖「本机上行」PCM。

**原则：**

- 不要先假设一定是 `AudioRecord::read` / `AudioTrack::write`；高通通话路径常走 AudioFlinger / HAL / voice 专用链路。
- Hook 层级尽量避开错误的 AEC 位置，减少回声（见风险表）。
- 先做「只读日志 / 计数」证明挂上符号，再做 PCM 替换。

**建议验证顺序：**

1. 接入 Dobby，Hook 一个无害、易确认的符号，logcat 可见。
2. 通话中对比 maps / 符号，锁定实际读写函数。
3. 实现下行 capture → UDS；上行 replace ← UDS。

#### 1.C 与 Go 的 IPC（未开始）

- 计划：Unix Domain Socket（如 `/dev/socket/ai_audio_sock` 或模块私有路径，注意 SELinux）。
- C++：Push 下行 PCM，Pull 上行 TTS PCM（注意时钟与阻塞策略）。

---

### 阶段二：本地 Go 控制中枢 — 未开始

1. 电话状态：`dumpsys telephony.registry` / `PHONE_STATE`；自动接听（如 `input keyevent 5`）。
2. 集成 Sherpa-ONNX（STT SenseVoice-Small + TTS）。
3. 事件循环：连 UDS，读对方音频 → STT；写 TTS PCM → Hook。

---

### 阶段三：DeepSeek 流式对话 — 未开始

1. VAD + 打断（清空发送缓冲）。
2. `stream: true` 请求 DeepSeek。
3. 按标点切句边合成边注入，压低首包延迟。

---

### 阶段四：挂断与企微推送 — 未开始

1. 挂断条件与物理挂断。
2. Transcript 总结为结构化信息。
3. 企业微信 TextCard 推送到主力微信。

---

## 4. 关键风险与调优

| 风险点 | 影响 | 应对 |
|--------|------|------|
| 厂商 Overlay / 解压卡死 | 装模块失败 | 手动组装 `/data/adb/modules/ai_audio_hook`；ZIP 用正斜杠打包 |
| SELinux 拒绝 tmp 路径 | dlopen 返回 0 | 只用 `/system/lib64/libai_hook.so` |
| 错误 remote call / 符号基址 | mmap 失败或进程挂起 | 使用现行 `inject.cpp`（dlsym 重定位 + LR=0）；避免错误 RET gadget |
| AEC / Hook 点层级错误 | 回声、无声 | 对照 AOSP/厂商库选点；分场景实测 |
| 电池发热 | 硬件安全 | ACC 限充、关无用同步、必要时假电池供电 |
| 休眠杀后台 | Go 守护退出 | 电池白名单 + Wakelock |

---

## 5. 目录与产物索引

| 路径 | 说明 |
|------|------|
| `zygisk_module/` | **现行主线** 源码与 `build.bat` |
| `zygisk_module/ai_audio_hook_zygisk.zip` | 安装包 |
| `magisk_module/` | 旧实验代码，非主注入路径 |
| `doc/02_zygisk_inject_progress.md` | 阶段一进展、操作、验证（详） |

---

*v1.4：阶段一「注入」闭环已在真机确认；下一迭代聚焦 Dobby 与通话 PCM Hook 点。*
