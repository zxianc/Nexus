# 个人 AI 通信助理 (AI-Call-Agent) 技术落地白皮书

**版本:** v1.2 (修正 Native 注入方案)
**日期:** 2026-07-18
**作者:** Developer
**目标环境:** Android 16 (LineageOS 23.2), 骁龙 865, 已 Root (纯 Magisk 环境)

---

## 1. 系统架构概述

本项目旨在打造一个完全运行在备用安卓机上的“AI 电话接听与短信摘要”中枢。

核心设计理念：**“底层劫持音频、端侧极致处理、云端高智商推理、微信无缝触达”**。

系统分为三大核心模块：
1. **AudioHook 模块 (C++ / Magisk)**：一个纯 Native C++ 动态库。通过 Magisk 的挂载特性与 `LD_PRELOAD` 机制注入到系统的 `/system/bin/audioserver` 进程中。负责剥夺系统的麦克风控制权，实现电话信令级双向音频的无损拦截与注入。
2. **AI 调度守护进程 (Go / Termux)**：系统的中枢神经。负责接管 AudioHook 传来的音频，调度本地 STT/TTS 引擎，并与云端 DeepSeek API 进行流式交互。
3. **微信推送层 (企业微信 API)**：电话结束后，负责将 AI 总结的对话摘要以图文卡片的形式，推送到主力的个人微信中。

---

## 2. 核心技术栈与选型

*   **OS 层权限:** Magisk 27.0+ (无需开启 Zygisk)
*   **Native Hook 框架:** [Dobby](https://github.com/jmpews/Dobby) (强大的跨平台 Inline Hook 库)
*   **进程注入方案:** Magisk OverlayFS 挂载 + Wrapper 代理脚本 (`LD_PRELOAD`)
*   **守护进程语言:** Golang 1.22+ (`linux/arm64` 交叉编译)
*   **语音转文本 (STT):** [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx) + **SenseVoice-Small** (量化版) 
*   **文本转语音 (TTS):** [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx) + VITS/Piper 中文离线模型
*   **大语言模型 (LLM):** DeepSeek API (流式请求模式)
*   **终端触达:** 企业微信自建应用 Webhook

---

## 3. 详细实施步骤 (The Roadmap)

### 阶段一：基于 Magisk 与 LD_PRELOAD 的 Native 音频劫持 (The Hook)
*这是整个项目最困难的深水区，目标是接管电话的上下行音频流。*

1.  **编写 C++ Hook 动态库 (`libai_hook.so`)：** 
    *   使用 C++ 编写核心拦截逻辑，引入 Dobby 库。
    *   在库加载的初始化函数（`__attribute__((constructor))`）中启动 Hook 线程。
2.  **寻找 Hook 点与拦截：**
    *   **上行 (Mic注入)：** Hook Android HAL 层的 `AudioRecord::read` 或对应 HAL 接口。当系统试图读取物理麦克风数据发给基带时，拦截该请求，并用从 Go 进程接收到的 TTS PCM 数据进行覆盖。
    *   **下行 (Speaker拦截)：** Hook `AudioTrack::write` 或对应 HAL 接口，获取对方的说话声音（PCM 数据）。
3.  **构建 Magisk 挂载模块与代理注入：** 
    *   在模块安装脚本中，将 `libai_hook.so` 放置到 `/system/lib64/`。
    *   编写代理脚本（Wrapper），利用 Magisk 启动机制（如 `post-fs-data.sh`），将 `/system/bin/audioserver` 替换为我们的脚本。
    *   代理脚本内容设定环境变量 `export LD_PRELOAD=libai_hook.so`，然后拉起真实的 `audioserver` 二进制文件，从而将 C++ 代码强行注入。
4.  **建立本地通信 IPC：** 
    *   注入成功的 C++ 代码在 `audioserver` 进程内创建一个 Unix Domain Socket (UDS)（比如 `/dev/socket/ai_audio_sock`）。
    *   模块通过该 Socket，将对方声音实时 Push 给 Go 进程，并阻塞式 Pull Go 进程发来的注入音频。

### 阶段二：构建本地控制中枢 (The Go Daemon)
*将 Go 编译为可执行文件，通过 Termux 部署在手机本地后台长期运行。*

1.  **电话状态监控与控制：**
    *   通过 `su -c "dumpsys telephony.registry"` 或监听 Android 广播 (`android.intent.action.PHONE_STATE`) 获取来电状态。
    *   实现自动接听逻辑（例如，响铃 3 秒后执行模拟按键 `su -c "input keyevent 5"`）。
2.  **集成 Sherpa-ONNX (STT & TTS)：**
    *   使用 Sherpa-ONNX 提供的 Go 语言绑定 API，避免外部进程调用开销。
    *   加载 SenseVoice-Small 模型用于 STT，加载对应 VITS 模型用于 TTS。
3.  **音频调度循环 (The Event Loop)：**
    *   连接 C++ 模块创建的 `/dev/socket/ai_audio_sock`。
    *   **读循环 (听)：** 从 Socket 持续读取外卖员的音频，送入 SenseVoice-Small 引擎。
    *   **写循环 (说)：** 将 TTS 生成的 PCM 数据，按照信令要求的时钟速率，平滑写入 Socket 传给 Hook 模块。

### 阶段三：端云协同的 AI 对话编排 (DeepSeek Integration)
*让电话具备真正的智慧，实现真人级别的对话延迟。*

1.  **VAD 与打断机制 (Interruption Handling)：**
    *   利用 Sherpa-ONNX 内置的 VAD（静音检测）判断对方是否停止说话，触发大模型思考。
    *   若在 AI 发声期间检测到对方插嘴，Go 进程立刻清空发送 Buffer，中止 TTS 播放，重新进入监听状态。
2.  **流式思考 (LLM Streaming)：**
    *   将 STT 识别出的文本拼接到 Prompt 中，带上下文发送给 `https://api.deepseek.com/v1/chat/completions`。
    *   **必须开启 `stream: true`**。
3.  **边想边说 (Pipeline Execution)：**
    *   Go 进程流式读取 DeepSeek Chunk，按标点符号切分单句。
    *   切分后立刻送入本地 TTS 引擎，生成后马上注入电话，将首句延迟压缩到极致。

### 阶段四：通话终结与微信推流
*完美的收尾工作。*

1.  **结束条件判断：** 当 AI 输出特定挂断指令或对方挂断，Go 进程执行物理挂断逻辑。
2.  **总结对话：** 提取本次通话完整 Transcript，非流式请求 DeepSeek 提取结构化核心信息（如 JSON）。
3.  **企业微信触达：**
    *   将结果组装为企业微信 TextCard 格式。
    *   携带预先配置的 `CorpID` 和 `Secret` 换取 Token，发起 POST 请求推送到主力的个人微信。

---

## 4. 关键风险与调优策略

| 风险点 | 影响范围 | 应对策略 |
| :--- | :--- | :--- |
| **设备电池鼓包过热** | 硬件安全 | 1. 禁用云端同步等无用服务；2. 安装 `ACC` (Advanced Charging Controller) 模块将电量限制在 50%；3. 考虑拆卸电池改用稳压假电池直供。 |
| **AEC (回声消除) 崩溃** | 通话质量 | `LD_PRELOAD` Hook 的层级必须位于硬件 AEC 之下，否则对方会听到回声。需要通过反编译和阅读 Android AOSP 源码找准具体的 Hook 函数。 |
| **安卓休眠杀后台** | 系统稳定性 | 在 LineageOS 电池管理中，赋予 Termux 和 Go Daemon “无限制”权限，必要时开启 Wakelock 保持 CPU 运转。 |

---
*文档生成完毕。开始你的底层开发之旅吧！*