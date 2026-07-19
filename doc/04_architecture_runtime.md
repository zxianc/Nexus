# Nexus 现行实现：技术方案 / 数据流 / 线程模型

**日期：** 2026-07-19  
**对应进度：** 1.A～1.F、DeepSeek 闭环 + 通内上下文 + **文本存档落盘 ✅**；**语音 mix / 企微+短信 TODO 延期**
**目标机：** OnePlus 8T / 骁龙 865 / LineageOS + Magisk Zygisk  
**模块版本：** `nexus_audio_hook` **v2.2**（versionCode=4；原 `ai_audio_hook`）

**相关文档：** [`plan.md`](plan.md)（总方案）· [`dev_journal.md`](dev_journal.md)（过程）· [`03_pcm_hook_next.md`](03_pcm_hook_next.md)（下一里程碑）· [`daemon/ai_call/README.md`](../daemon/ai_call/README.md)

---

## 1. 技术方案一句话

在 **32-bit `android.hardware.audio.service`** 里用 Dobby 钩住高通 voice usecase，通话时打开 **incall-rec DL-only**（`pcm_open(0,23)`），把对方下行 PCM **落盘 + Unix Domain Socket 推给 Go**；Go 进程 `ai_call` 做重采样、能量 VAD、本地 SenseVoice STT，写出转写日志。

**不做：** 在 `audioserver` 抓通话 PCM（通话段 AF standby）；硬件双路 incall-rec。  
**已做 TX：** `ai_call -say` / echo → VITS → `tx_inject.pcm` → incall-music。  
**常驻引擎：** `nexus_engine`（SenseVoice+VITS 同进程）← UDS ← `ai_call -backend engine`。  
**LLM：** DeepSeek 云端 SSE（`-llm`）；通内 `CallSession`；挂断写文本档案（摘要+对话）到 `calls/`。  
**打断：** `LLM_BARGE_IN`（默认关）；开则仅 TTS 播放中可 barge-in，思考中新句排队。详见 [`daemon/ai_call/README.md`](../daemon/ai_call/README.md)。
**TODO 延期：** 语音 `mix(DL,TTS)`；企微推送与短信转发同一里程碑。

**职责定稿（2026-07-19）：**

- **HAL（`nexus_audio_hook`）：** 只管采集——通话接通就 DL → 落盘 + UDS；**不**按卡/模式动态关采集；**暂不考虑**省电而关旁路。
- **业务层（Go / 以后策略服务）：** 决定用不用（STT/AI / 丢弃）；双卡自动接听/拒接/放行人工也在业务层，不进 HAL。

---

## 2. 各部分作用（谁干什么）

按「为什么需要它」说明；细节实现见后文。

### 2.1 分层总览

| 层 | 是什么 | 核心作用 | 没有它会怎样 |
|----|--------|----------|--------------|
| **Magisk 模块** | 安装包 + boot 脚本 | 给 HAL 权限、目录、并把 `libai_hook.so` 打进进程 | 无法 Hook / 无法写 vendor 路径 / UDS 被 SELinux 拦 |
| **HAL 进程** | 系统自带的 `android.hardware.audio.service` | 真正碰到高通通话音频管线的地方 | 通话 PCM 不在 audioserver，别处抓不到 |
| **libai_hook.so** | 我们注入的 C++ 载荷 | 通话时打开 incall-rec DL，旁路出 PCM | 没有「对方声音」原始流 |
| **pcm.sock (UDS)** | HAL↔Go 的管道 | 实时把 PCM 送给用户态守护进程 | Go 读不到流（仍可只靠落盘文件，但不实时） |
| **ai_call** | Go 守护进程 | 切句 + 本地识别 + 写转写 | 只有 PCM，没有文字 |
| **sherpa + 模型** | 离线 ASR 引擎 | 把一句 16k PCM 变成中文文本 | 只能 mock，不能出真字 |
| **pcm_recv** | 早期调试工具 | 只验证 UDS 字节是否完整 | 正式链路用 `ai_call`，二者不要同时连 |

### 2.2 Magisk 模块各脚本

| 部分 | 作用 | 数据/产物 |
|------|------|-----------|
| `customize.sh` | 安装时 chmod 注入器、脚本 | 无音频数据 |
| `post-fs-data.sh` | 开机很早：补 sepolicy、建 `/data/vendor/ai_hook`、预建 pcm 文件、`chcon` | 准备**空目录/空文件**，供 HAL 写 |
| `sepolicy.rule` | 持久规则：`execmem`、写 `vendor_data_file`、HAL↔shell/magisk 的 unix_stream | 权限，不经手 PCM |
| `service.sh` | boot 完成后反复 `inject32`，把 so 载入 HAL | 不经手 PCM；成功标志是 maps 里有 `libai_hook` |
| `inject32` | ptrace + remote `dlopen` | 把 so 路径塞进目标进程 |
| `vendor/lib/libai_hook.so` | 实际 Hook 逻辑 | 见下节 |

### 2.3 HAL 载荷内部模块

| 部分 | 作用 | 输入 | 输出 |
|------|------|------|------|
| Dobby Hook（voice/platform） | 感知「通话开始/结束」、缓存 platform/adev/vsid | 系统原函数调用 | 触发 Round-7 / cleanup |
| `uds_server_thread` | 常驻监听，接受一个 Go client | 无音频；只管理连接 | `g_uds_client` fd |
| `round7_incall_thread` | 打开 DL 录音设备并读环 | 硬件/驱动侧通话下行混音 | **PCM 字节流** → 文件 + UDS |
| tinyalsa 计数 Hook | 观测用，**不参与**数据面 | 其它 pcm_read/write | 仅 log |

**方向约定（重要）：**

- **DL（Downlink）** = 对方 → 本机（现行 STT 只采这个）
- **UL（Uplink）** = 本机话筒 → 对方（现行故意关掉 VOC_REC_UL）
- 数据从 **内核/驱动 PCM 设备** 流出 → 我们的读环 → **文件 / Socket**；**不**改写通话本身听到的声音（TX 注入是以后 1.E）

### 2.4 Go `ai_call` 内部模块

| 部分 | 作用 | 输入 | 输出 |
|------|------|------|------|
| `dialUDS` / 重连循环 | 挂上 HAL 的 sock；断了再连 | sock 路径 | `net.Conn` |
| `readAPCMHeader` | 读流格式元数据 | 16 字节头 | rate/ch/bits/kind |
| 可选 `-dump` | 对照调试：原样存 UDS PCM | 48k stereo 裸流 | `uds_dl.pcm` 等 |
| `stereoS16ToMono16k` | STT 要 16k mono | 48k stereo | 16k mono PCM |
| `EnergyVAD` | 把连续流切成「一句话」 | 16k mono | `Utterance[]` |
| `uttCh` | 缓冲待识别句，避免堵读流 | Utterance | 送给 worker |
| `sttWorker` + Backend | 识别；mock 或 sherpa | 一句 PCM | 字符串 |
| `hasSpeechText` | 丢掉纯标点噪声结果 | 字符串 | 写或不写 `stt.log` |

### 2.5 外部 ASR 资产

| 部分 | 作用 |
|------|------|
| `sherpa-onnx-offline` | 命令行识别器（SenseVoice） |
| `model.int8.onnx` + `tokens.txt` | 模型与词表 |
| `libonnxruntime.so` | 运行时动态库；须 `LD_LIBRARY_PATH` |
| `tmp/` | 每句临时 `.wav`，识别完删 |

### 2.6 结构关系（简图）

```
┌─ Magisk ──────────────────────────────────────────────────┐
│  权限 + 目录 + inject32                                     │
└────────────────────────┬───────────────────────────────────┘
                         │ 注入 so（无 PCM）
                         ▼
┌─ HAL + libai_hook ─────────────────────────────────────────┐
│  感知通话 → 开 DL 录音 → 读 PCM                              │
│       ├─→ ai_dl.pcm（落盘备份/听验）                         │
│       └─→ pcm.sock（实时给 Go）                              │
└────────────────────────┬───────────────────────────────────┘
                         │ APCM + 48k stereo PCM
                         ▼
┌─ ai_call ──────────────────────────────────────────────────┐
│  收流 → 16k mono → 切句 →（队列）→ STT → 过滤 → stt.log     │
└────────────────────────┬───────────────────────────────────┘
                         │ 每句一次子进程
                         ▼
┌─ sherpa-onnx-offline + SenseVoice 模型 ────────────────────┐
│  wav in → 中文 text out                                     │
└────────────────────────────────────────────────────────────┘
```

---

## 3. 数据流向（从哪里来、到哪里去）

### 3.1 一图看懂（现行已跑通）

```
[对方手机]
    │ 蜂窝/VoLTE 等（系统链路，我们不碰）
    ▼
[本机调制解调 / 高通语音通路]
    │ 下行语音（DL）
    ▼
[audio.primary.kona + tinyalsa]
    │ MultiMedia9 + VOC_REC_DL=1
    │ pcm 设备 card0 device23
    ▼
[round7_incall_thread: pcm_read]          ← 我们 Hook 后主动打开的读环
    │
    ├──► /data/vendor/ai_hook/ai_dl.pcm   ← 旁路 A：落盘（听验/对照）
    │
    └──► Unix Socket pcm.sock             ← 旁路 B：实时推流
              │  先发 16B APCM(kind=DL)
              │  再发 raw s16le 48k/2ch
              ▼
         [ai_call 已 connect]
              │
              ├──► (可选) uds_dl.pcm      ← 与 sock 字节一致，调试用
              │
              ▼ stereo→mono + 48k→16k
         [EnergyVAD 切句]
              │  Utterance (16k mono)
              ▼
         [uttCh 队列]
              │
              ▼
         [sherpa / mock]
              │  text
              ▼
         hasSpeechText？
           ├─ 否 → ai_call.log 里 DROP（不进 stt.log）
           └─ 是 → /data/vendor/ai_hook/stt.log
```

**箭头方向总结：**

| 流向 | 内容 | 终点用途 |
|------|------|----------|
| 对方 → 驱动 → `pcm_read` | 通话下行 PCM | 原始音频源 |
| `pcm_read` → `ai_dl.pcm` | 同 PCM 写文件 | 人工听验、对齐字节数 |
| `pcm_read` → `pcm.sock` → `ai_call` | 同 PCM 过 UDS | 实时 STT 输入 |
| `ai_call` → 16k mono 句 | 切好的短 PCM | STT 引擎输入 |
| STT → `stt.log` | 中文句子 | 同时作为 LLM user 输入 |

**当前没有的回流（故图上没有）：**

- TTS PCM → 通话上行（1.E）
- 企微出站 + 短信转发（捆绑后续）
- 语音 `mix(DL, TTS)` 存档（延期）

### 3.2 按阶段：通话建立时 HAL 里发生什么

1. `platform_start_voice_call` → 缓存 `platform` / `vsid`（默认 `0x10C01000`）
2. `voice_start_call` → 缓存 `adev`
3. **`voice_start_usecase`** → `g_in_voice=true` → 启动 **`round7_incall_thread`**
4. 线程内（约 400ms 后）：
   - `platform_set_incall_recording_session_id(..., **INCALL_REC_DOWNLINK=1**)`
   - Mixer：`VOC_REC_UL=0`，`VOC_REC_DL=1`（只要对方声）
   - `pcm_open(0, 23, PCM_IN)` → **48 kHz / 2ch / s16le**
5. 循环 `pcm_read`：
   - → 写 **`ai_dl.pcm`**
   - → UDS：**APCM 头（每连接一次）+ raw PCM**（`kind=DL=1`）

### 3.3 挂断时数据停在哪

`voice_stop_usecase` → `g_in_voice=false` → 读环退出 → mixer cleanup → `pcm_close` / 关 dump。  
UDS 上停止写；`ai_call` 读到 EOF/错误 → `Flush` 可能吐出最后半句 → 再 `dial` 等下一通。  
**sock 文件仍在**（`uds_server_thread` 继续 listen）；停的是「这一通的 PCM」。

### 3.4 Go 侧逐步变换（格式怎么变）

| 步骤 | 数据形态 | 说明 |
|------|----------|------|
| UDS 到达 | 48kHz · 立体声 · s16le · kind=DL | 与 `ai_dl.pcm` 同源 |
| 重采样后 | 16kHz · 单声道 · s16le | 取左声道；STT 标准输入 |
| VAD 后 | 若干 `Utterance`（约 0.5s～8s） | 一句一段 PCM + PeakRMS |
| STT 后 | UTF-8 文本 | mock 占位或 SenseVoice 真字 |
| 过滤后 | 写入 `stt.log` 或 DROP | 纯 `。` 等不落盘 |

```
pcm.sock
  → readAPCMHeader
  → Read 循环（可选 -dump）
  → stereoS16ToMono16k
  → EnergyVAD.Push → Utterance
  → uttCh
  → Transcribe (mock | sherpa)
  → hasSpeechText？ → stt.log / DROP
```

### 3.5 APCM 协议（16 字节 LE）

| 偏移 | 字段 | 现行值 |
|------|------|--------|
| 0–3 | magic | `0x4D435041`（`'APCM'`） |
| 4–7 | sample rate | 48000 |
| 8–9 | channels | 2 |
| 10–11 | bits | 16 |
| 12–13 | kind | `0=mixed` / **`1=DL`** / `2=UL` |
| 14–15 | pad | 0 |

之后为 interleaved s16le 裸流。实现：HAL `uds_send_hdr_if_needed`；Go `readAPCMHeader`。

### 3.6 路径清单：文件里分别流着什么

| 路径 | 流向角色 | 内容 |
|------|----------|------|
| `/data/vendor/ai_hook/pcm.sock` | HAL → Go 的实时通道 | APCM + 48k stereo PCM |
| `/data/vendor/ai_hook/ai_dl.pcm` | HAL 旁路落盘 | 与 UDS 同源的 DL PCM（无头） |
| `/data/vendor/ai_hook/uds_dl.pcm` | Go 可选 dump | 应与 sock 载荷一致（无头或含头取决于实现；现行 `-dump` 为纯 PCM 帧） |
| `/data/vendor/ai_hook/stt.log` | STT 成功真句 | 文本行（给人对读 / 以后给 LLM） |
| `/data/vendor/ai_hook/ai_call.log` | 进程 stderr/stdout | 连接、recv、OK/DROP/ERR |
| `/data/local/tmp/nexus_stt/` | 只读资产 + 临时 wav | 模型与 CLI，**不是**通话主数据通道 |

### 3.7 与「目标全 AI 链路」对照（避免混淆）

| 段 | 现行 | 目标（未做） |
|----|------|--------------|
| 对方声 → STT | ✅ DL → ai_call → stt.log | 同左 |
| 文字 → 思考 | ✅ DeepSeek 流式 | 通内上下文；跨通话不记 |
| 回复声 → 对方 | ✅ STT→LLM→TTS→TX | `-llm`；勿静音 |
| 存档「对方+AI」文本 | ✅ 挂断落盘 | `/data/vendor/ai_hook/calls/` |
| 存档「对方+AI」语音 | ❌ TODO 延期 | Go `mix(DL, TTS)` 按播放时间轴 |
| 企微 / 短信 | ❌ TODO 捆绑后续 | 先落盘 |

---

## 4. 线程 / 协程模型与维护

系统里有两套并发：**HAL 进程内的 pthread**，以及 **`ai_call` 进程内的 goroutine**。互不 join 跨进程；靠 socket 与文件衔接。

### 4.1 HAL：`libai_hook.so` 内 pthread

全部 **`pthread_create` + `pthread_detach`**，**无 `pthread_join`**。

```
constructor on_load
    └── main_thread（detach，进程内只跑一次）
            usleep(2s) → install_hooks() → start_uds_server()
                                              └── uds_server_thread（detach，常驻）

voice_start_usecase
    └── round7_incall_thread（detach，每通通话新建）
```

| 线程入口 | 生命周期 | 职责 | 如何维护 / 退出 |
|----------|----------|------|-----------------|
| **`main_thread`** | so 加载后一次 | 延迟 2s 装 Hook，再起 UDS | 函数 return 即结束；避免 dlopen 竞态 |
| **`uds_server_thread`** | 随 HAL 进程常驻 | `bind/listen` `pcm.sock`；`accept` 后 CAS 换 `g_uds_client`，**关旧 client** | 仅 accept 致命错误才 break；HAL 死则整线程没；需 reinject |
| **`round7_incall_thread`** | 每通一次 | DL incall-rec 读环 + dump + UDS 写 | `g_in_voice=false` 或 `pcm_read` 失败 → cleanup 退出；下一通再 `start_round7()` |

**全局状态（示意）：**

- `g_in_voice`：usecase start/stop 翻转，驱动读环
- `g_uds_client` / `g_uds_hdr_sent`：单活跃 client；新连接顶掉旧连接
- `g_platform` / `g_adev` / `g_vsid`：建链参数

**注意：** 无读环互斥；设计依赖「先 stop 清 `g_in_voice`，再 start」。HAL 崩溃或被杀后，依赖 `service.sh` 或手动 `inject32` 再注入。

### 4.2 Go：`ai_call` 内 goroutine

```
main（主 goroutine）
  ├── go sttWorker          ← 常驻，串行消费 uttCh
  ├── go (空：等 ctx.Done)  ← 几乎无逻辑
  └── for { dial → runStream → reconnect }
         └── sherpa 子进程（按句，CommandContext，非 goroutine）
```

| 角色 | 函数 | 职责 | 维护方式 |
|------|------|------|----------|
| **主循环** | `main` | `dialUDS` → `runStream` → close → 再连 | dial 失败 sleep 500ms；断线 sleep 200ms；读超时 2s 不拆连接 |
| **流处理** | `runStream` | 读 PCM、重采样、VAD、`enqueue` | 挂断/对端关 socket → Flush 尾句 → 返回主循环 |
| **STT worker** | `sttWorker` | 串行 `Transcribe` + 写日志 | 进程退出时 `close(uttCh)` + `WaitGroup` 排空队列 |
| **sherpa 子进程** | `Sherpa.Transcribe` | 每句一次 CLI | `context.WithTimeout(60s)`；超时/取消杀子进程 |

**队列：** `uttCh` 默认容量 **2**，`enqueue` **非阻塞**；满则丢句并打 `stt queue full`（sherpa 慢时可能丢）。

**与 pcm_recv：** HAL 只保留 **一个** UDS client；后连顶掉先连。跑 STT 时不要同时开 `pcm_recv`。

### 4.3 Boot / 注入侧「线程」维护（shell，非 pthread）

| 脚本 | 时机 | 做什么 |
|------|------|--------|
| `post-fs-data.sh` | early boot | live sepolicy；建 `/data/vendor/ai_hook`；预 touch PCM；`chcon` |
| `sepolicy.rule` | Magisk 持久 | execmem、vendor_data 写、UDS HAL↔magisk/shell |
| `service.sh` | `boot_completed` + 8s | 最多 12 次、间隔 3s：`inject32` HAL；maps 有 `libai_hook` 则成功 |

**`ai_call` / `nexus_engine`：** 正式路径由 **`nexus_runtime` `service.sh` 开机拉起**；调试仍可用 `/data/local/tmp` + `LD_LIBRARY_PATH`。

### 4.4 一张总图：谁在跑

```
开机
  → Magisk post-fs-data / service.sh
  → HAL 进程被 inject → main_thread → hooks + uds_server_thread 常驻

人工启动 ai_call
  → sttWorker 常驻 + 主循环 dial 挂在 pcm.sock

来电 / 进通话
  → voice_start_usecase → round7_incall_thread
  → PCM → dump + UDS → ai_call VAD → sherpa 子进程（多句串行）
  → stt.log

挂断
  → voice_stop_usecase → round7 退出
  → ai_call stream end → Flush → 再 dial 等下一通
```

---

## 5. Hook 符号一览（HAL）

| 库 | 符号 | 作用 |
|----|------|------|
| `audio.primary.kona.so` | `voice_start_usecase` | **启动** Round-7 |
| 同上 | `voice_stop_usecase` | **停止** + mixer cleanup |
| 同上 | `voice_start_call` / `platform_start_voice_call` | 缓存 adev / platform / vsid |
| 同上 | `platform_set_incall_recording_session_id` 等 | 日志观察 |
| `libtinyalsa.so` | `pcm_read` / `pcm_write` | 仅计数（数据面不用） |

Round-7 实际读写：对 tinyalsa / primary 的 **`dlsym` 直调**（`pcm_open`/`pcm_read`/`mixer_*`），不用 Dobby maps 解析（防 SIGBUS）。

---

## 6. VAD 与 STT 过滤（现行默认）

**能量 VAD（`DefaultVADConfig`）：**

| 参数 | 值 | 含义 |
|------|-----|------|
| FrameMs | 20 | 分析帧 |
| SpeechRMS | 400 | 进入说话 |
| SilenceRMS | 250 | 静音累计 |
| MinSpeechMs | **500** | 过短丢弃 |
| SilenceEndMs | 500 | 静音切句 |
| MaxSpeechMs | 8000 | 强制切长句 |
| PreRollMs | 200 | 起说前缓冲 |

**方案 A：** `hasSpeechText` — 结果中无 Unicode 字母/数字（含 CJK）则 **DROP**，不写 `stt.log`（避免纯 `。`）。

**Sherpa：** SenseVoice int8；`--sense-voice-use-itn=1`；语言默认 `zh`。

---

## 7. 真机启动（摘要）

```bash
# HAL：模块安装后 reboot，或手动 inject32（见 zygisk_module/doc）

# STT（须 LD_LIBRARY_PATH）
adb shell 'su -c "pkill -9 pcm_recv; pkill -9 ai_call; \
  export STT_BACKEND=sherpa LD_LIBRARY_PATH=/data/local/tmp/nexus_stt \
    STT_BIN=/data/local/tmp/nexus_stt/sherpa-onnx-offline \
    STT_MODEL_DIR=/data/local/tmp/nexus_stt/sense-voice; \
  nohup /data/local/tmp/ai_call -backend sherpa \
    >>/data/vendor/ai_hook/ai_call.log 2>&1 &"'
```

验收：通话后 `stt.log` 有中文；`ai_call.log` 可有 `DROP`。

---

## 8. 未实现（避免误解）

| 项 | 状态 |
|----|------|
| 1.E incall-music **TX** + 本地 VITS TTS | ✅（手动 `-say`；每句重写 inject） |
| 常驻 `nexus_engine` + echo | ✅（`-backend engine`） |
| DeepSeek LLM / STT→LLM→TTS 自动闭环 | ✅（通内 session，默认 24 条上限） |
| 通话文本存档 + 摘要落盘 | ✅ `calls/call_*.txt` |
| 语音打断（barge-in） | ✅ 开关 `LLM_BARGE_IN`（默认关）；仅 TTS 中打断 |
| `mix(DL, TTS)` 语音存档 | TODO 延期（时间轴对齐） |
| 企微推送 + 短信转发 | TODO 捆绑后续 |
| Magisk 开机自启 `ai_call` + `nexus_engine` | ✅ `nexus_runtime` `service.sh` |
| 本机配置 WebUI | ✅ `nexus_webui` → `http://127.0.0.1:8787` |
| 双卡来电策略 | ✅ `nexus_callpolicy`（`human` / `ai` / `reject`） |

目标音频方案（全 AI）：`DL → STT → LLM → TTS → 1.E 注入`；**现行文本存档**；语音 mix / 企微+短信见 TODO。见 [`plan.md`](plan.md)。

---

## 9. 源码索引

| 文件 | 内容 |
|------|------|
| `zygisk_module/cpp/audio_hook_hal.cpp` | HAL Hook、UDS server、Round-7 |
| `zygisk_module/service.sh` / `post-fs-data.sh` / `sepolicy.rule` | 注入与权限 |
| `daemon/ai_call/main.go` | 重连、runStream、sttWorker、`replyScheduler`（防抖/打断开关） |
| `daemon/ai_call/txinject.go` | `tx_inject.pcm` 写入 / barge-in 短静音 |
| `daemon/ai_call/vad.go` / `textutil.go` | VAD、空识别过滤 |
| `daemon/ai_call/uds.go` / `pcmutil.go` | APCM、重采样 |
| `daemon/ai_call/llm/` | DeepSeek、CallSession、挂断存档 |
| `daemon/ai_call/stt/sherpa.go` | SenseVoice CLI |
| `daemon/nexus_callpolicy/` | 双卡来电策略（registry 检测 + Answer/Reject） |
| `daemon/nexus_webui/` | 本机配置页 + SIM 只读发现 |
| `daemon/nexuscfg/` | `config.json` 读写 / sims policy |
| `magisk_modules/nexus_runtime/` | 开机自启 + `env.sh` |

---

*本文描述「现行已跑通」实现；过程踩坑见 `dev_journal.md`，路线图以 `plan.md` 为准。*
