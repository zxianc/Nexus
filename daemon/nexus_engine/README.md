# nexus_engine — 常驻 STT + TTS 引擎

Android arm64 上的长驻进程：启动时加载 **SenseVoice（STT）** 与 **VITS（TTS）** 各一次，经 **Unix Domain Socket** 用行 JSON 协议为 `ai_call` 服务。

**不是**把 `sherpa-onnx-offline` CLI 打包进去，而是自研 `main.cc`，用 NDK **链接 sherpa-onnx 静态库** + 动态 `libonnxruntime.so` 编出的独立可执行文件。

相关：构建手记 [`doc/05_sherpa_android_build.md`](../../doc/05_sherpa_android_build.md) §8 · Go 客户端 [`daemon/ai_call/engine/`](../ai_call/engine/) · Magisk [`magisk_modules/README.md`](../../magisk_modules/README.md)

---

## 1. 为什么需要

| 路径 | 每次 STT/TTS |
|------|----------------|
| CLI：`sherpa-onnx-offline*` | 进程启动 + **重新加载模型**（秒级） |
| **本引擎** | 模型常驻，请求 mainly 推理（常亚秒～约 1s） |

`ai_call -backend engine` 为现行默认；CLI 仍可作回退。

---

## 2. 在整条链路中的位置

```text
HAL (nexus_audio_hook) ──UDS pcm.sock──► ai_call
                                            │
                                            ├─ VAD / 编排 / DeepSeek / TX
                                            │
                                            └─ Unix engine.sock ──► nexus_engine
                                                                     STT + TTS
```

引擎 **不**负责采音、注入上行、LLM；只做人声识别与合成。

---

## 3. 启动参数

```text
nexus_engine \
  --sock=PATH \
  --stt-model-dir=DIR \
  --tts-model-dir=DIR \
  [--lang=auto] \
  [--threads=2]
```

| 参数 | 调试默认 | Magisk 典型 |
|------|----------|-------------|
| `--sock` | `/data/local/tmp/nexus_stt/engine.sock` | `/data/adb/nexus/run/engine.sock` |
| `--stt-model-dir` | `…/sense-voice` | `…/nexus_models/models/sense-voice` |
| `--tts-model-dir` | `…/vits-zh-ll` | `…/nexus_models/models/vits-zh-ll` |
| `--lang` | `auto` | SenseVoice 语言 |
| `--threads` | `2` | ONNX 线程 |

运行时需能加载同目录或 `LD_LIBRARY_PATH` 下的 **`libonnxruntime.so`**。  
就绪日志：`nexus_engine ready on …`。

---

## 4. 协议（UDS，一行一条 JSON）

客户端每请求 **connect → 写一行 → 读一行 → close**（Go：`engine.Client`）。服务端请求 **串行**（mutex）。

**请求**

```json
{"id":1,"op":"ping"}
{"id":2,"op":"stt","wav":"/path/to/16k_mono.wav"}
{"id":3,"op":"tts","text":"你好","wav":"/path/out.wav","sid":0}
```

| `op` | 输入 | 成功时主要字段 |
|------|------|----------------|
| `ping` | — | `ok=true` |
| `stt` | `wav` 路径（PCM wav） | `text`，`ms` |
| `tts` | `text`，输出 `wav` 路径，可选 `sid` | `wav`，`rate`，`ms` |

**响应示例**

```json
{"id":2,"ok":true,"text":"你好。","ms":80}
{"id":3,"ok":true,"wav":"/path/out.wav","rate":16000,"ms":900}
{"id":2,"ok":false,"err":"…"}
```

---

## 5. 构建（Windows → Android arm64）

前提：已按 `doc/05_sherpa_android_build.md` 编过 sherpa（含 TTS），静态库在  
`tmp/nexus_stt/src/sherpa-onnx/build-android-arm64-v8a/lib/`。

```powershell
cd daemon\nexus_engine
.\build_api28.ps1
```

- 产出：`…/build-android-arm64-v8a/install/bin/nexus_engine`
- 同时复制 `libonnxruntime.so` 到 `install/lib/`
- 链接需 **`-Wl,-z,max-page-size=16384`**，否则 Bionic 报 TLS segment underaligned

推机示例（调试树）：

```bash
adb push nexus_engine /data/local/tmp/nexus_stt/
adb push libonnxruntime.so /data/local/tmp/nexus_stt/
adb shell 'su -c "chmod 755 /data/local/tmp/nexus_stt/nexus_engine"'
```

Magisk：放入 `magisk_modules/nexus_runtime/bin/` 与 `lib/`，随 `nexus_runtime.zip` 安装。

---

## 6. 与 sherpa CLI 对照

| | `nexus_engine` | `sherpa-onnx-offline` / `-tts` |
|--|----------------|--------------------------------|
| 源码 | 本目录 `main.cc` | sherpa 官方 CLI 目标 |
| 链接 | 同一套 sherpa 静态库 + ORT | 同左（另一套可执行文件） |
| 生命周期 | 长驻，跨通话 | 每调用一次进程 |
| 谁调用 | `ai_call -backend engine` | `-backend sherpa` / `-say` CLI 路径 |

二者 **互不包含**；`nexus_runtime` 的 `bin/` 里可同时放引擎与 CLI（CLI 可选回退）。

---

## 7. 运维备忘

- **内存：** 双模型常驻，RSS 约数百 MB（机型 8GB 足够）
- **日志：** 调试常写 `/data/vendor/ai_hook/nexus_engine.log`；Magisk 见 `nexus_runtime` 的 `service.sh`
- **换语言/模型：** 改启动参数后需 **重启进程**（模型只在启动时加载）
- **Go 侧：** `engine.Supervisor` 负责拉起/复用；`stt.Engine` / `tts.Engine` 走本协议
