# ai_call — 本地 STT 守护进程（1.F）

HAL DL UDS → 能量 VAD 切句 → **mock** 或 **sherpa-onnx SenseVoice** → `/data/vendor/ai_hook/stt.log`

约定：**STT/TTS 本地**；**LLM = DeepSeek 云端**（`-llm` 流式切句 TTS）。

## 编译（Windows → Android arm64）

```bat
cd daemon\ai_call
set GOOS=linux
set GOARCH=arm64
set CGO_ENABLED=0
go test ./...
go build -o ai_call_arm64 .
adb push ai_call_arm64 /data/local/tmp/ai_call
```

## 真机：mock 冒烟（无需模型）

前提：HAL 已注入且 `pcm.sock` 在听；可先停旧 `pcm_recv`。

```bash
adb shell 'su -c "pkill -9 pcm_recv; pkill -9 ai_call; chmod 755 /data/local/tmp/ai_call; touch /data/vendor/ai_hook/stt.log; chmod 666 /data/vendor/ai_hook/stt.log; STT_BACKEND=mock nohup /data/local/tmp/ai_call -backend mock -dump /data/vendor/ai_hook/uds_dl.pcm >>/data/vendor/ai_hook/ai_call.log 2>&1 &"'
```

通话后：

```bash
adb shell 'su -c "cat /data/vendor/ai_hook/stt.log; tail -n 40 /data/vendor/ai_hook/ai_call.log"'
```

应看到 `[mock] samples=... dur_ms=...`。

## 真机：sherpa SenseVoice

### 1) 模型（勿提交 git）

```text
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
```

解压后保证目录内有：

- `model.int8.onnx`
- `tokens.txt`

推到设备：

```bash
adb shell 'su -c "mkdir -p /data/local/tmp/nexus_stt/sense-voice /data/local/tmp/nexus_stt/tmp"'
# 将解压内容 push 到 /data/local/tmp/nexus_stt/sense-voice/
```

### 2) `sherpa-onnx-offline` arm64 可执行文件

**现行：本机 NDK 手动交叉编译**（非 Linux aarch64 预编译包）。完整步骤见  
[`doc/05_sherpa_android_build.md`](../../doc/05_sherpa_android_build.md)，脚本在 `scripts/`。

推到：

```text
/data/local/tmp/nexus_stt/sherpa-onnx-offline
/data/local/tmp/nexus_stt/libonnxruntime.so
```

`chmod 755`；启动时必须 `LD_LIBRARY_PATH=/data/local/tmp/nexus_stt`。

### 3) 启动

```bash
adb shell 'su -c "pkill -9 ai_call; export STT_BACKEND=sherpa STT_BIN=/data/local/tmp/nexus_stt/sherpa-onnx-offline STT_MODEL_DIR=/data/local/tmp/nexus_stt/sense-voice LD_LIBRARY_PATH=/data/local/tmp/nexus_stt; nohup /data/local/tmp/ai_call -backend sherpa >>/data/vendor/ai_hook/ai_call.log 2>&1 &"'
```

对方说话后 `stt.log` 应出现中文 `text=`。

**真机已验（2026-07-19）：** DL 通话可出「喂。」「外卖…」等。

**VAD 方案 A：** 无字母/数字的 STT 结果 `DROP`（不写 `stt.log`）；`MinSpeechMs=500`。

## 真机：`-say` 本地 TTS → TX

资产：`sherpa-onnx-offline-tts`、`libonnxruntime.so`、`vits-zh-ll/`（见 `doc/05_sherpa_android_build.md`）。

通话中执行（中文请用脚本文件，避免 adb/PowerShell 编码问题）：

```bash
# /data/local/tmp/say_test.sh
export LD_LIBRARY_PATH=/data/local/tmp/nexus_stt
/data/local/tmp/ai_call -say "你好，对面能听到吗？"
```

写出 `/data/vendor/ai_hook/tx_inject.pcm`（16k→48k mono）；播完 HAL unlink。

### Echo：STT 出字后自动播回（无 LLM）

**推荐（常驻引擎）：** `nexus_engine` 一次加载 SenseVoice+VITS，`ai_call -backend engine`。  
原理与协议见 [`../nexus_engine/README.md`](../nexus_engine/README.md)。

```bash
# 或 sh /data/local/tmp/start_echo.sh
export LD_LIBRARY_PATH=/data/local/tmp/nexus_stt ECHO_TTS=1
nohup /data/local/tmp/ai_call -backend engine -echo-tts >>/data/vendor/ai_hook/ai_call.log 2>&1 &
```

回退 CLI（每句 exec）：`-backend sherpa`。对方说一句 → 同文 TTS→TX。

### LLM：STT → DeepSeek 流式 → 逐句 TTS→TX

```bash
# 密钥：一行 sk-... 写入设备（勿提交 git）
adb shell 'su -c "printf \"%s\" \"sk-YOUR_KEY\" >/data/local/tmp/nexus_stt/deepseek.key; chmod 600 /data/local/tmp/nexus_stt/deepseek.key"'

# 或 sh /data/local/tmp/start_llm.sh
export LD_LIBRARY_PATH=/data/local/tmp/nexus_stt LLM=1
nohup /data/local/tmp/ai_call -backend engine -llm >>/data/vendor/ai_hook/ai_call.log 2>&1 &
```

流式：SSE 收字 → 按 `。！？.!?\n` 切句 → 逐句 TTS；句间等待 HAL 播完（避免覆盖 `tx_inject` 队列）。`-llm` 优先于 `-echo-tts`。
**上下文：** 同一通电话内累积对话历史发给模型；挂断/新 stream 清空；默认最多 24 条（`LLM_MAX_MSGS`）。
**存档：** 挂断后写 `/data/vendor/ai_hook/calls/call_*.txt`（摘要 + 全文）。企微/短信推送后续再做。
**打断：** 默认关（`LLM_BARGE_IN=0` / `-llm-barge-in=false`）。开启后仅 TTS 播放中可打断（短静音切 TX 并重开）；思考中或开关关闭时新真句只排队为下一轮。启动前默认防抖 600ms（`LLM_REPLY_DEBOUNCE_MS`）。
**配置：** 优先读 `/data/adb/nexus/config.json`（`NEXUS_CONFIG`）；本机 WebUI `http://127.0.0.1:8787`。flag/env 仍可覆盖。  
**音色：** `tts.sid` / `-tts-sid`，当前模型 **0～4**（见 `magisk_modules/nexus_models/models/vits-zh-ll/README.md`）。  
**日志时区：** Go 静态二进制嵌入 `time/tzdata`；`service.sh` / `restart_callstack.sh` 设置 `TZ`（默认跟 `persist.sys.timezone`）。

## Flag / 环境变量

| Flag | Env | 默认 |
|------|-----|------|
| `-backend` | `STT_BACKEND` | `mock`（`sherpa`\|`engine`） |
| `-engine-bin` | `ENGINE_BIN` | `…/nexus_engine` |
| `-engine-sock` | `ENGINE_SOCK` | `…/engine.sock` |
| `-stt-bin` | `STT_BIN` | `/data/local/tmp/nexus_stt/sherpa-onnx-offline` |
| `-model-dir` | `STT_MODEL_DIR` | `/data/local/tmp/nexus_stt/sense-voice` |
| `-stt-log` | `STT_LOG` | `/data/vendor/ai_hook/stt.log` |
| `-sock` | `PCM_SOCK` | `/data/vendor/ai_hook/pcm.sock` |
| `-lang` | `STT_LANG` | `auto`（SenseVoice：auto/zh/en/…） |
| `-say` | — | 非空则 TTS→TX 后退出 |
| `-echo-tts` | `ECHO_TTS` | `false`；STT OK 后同文播 TX |
| `-llm` | `LLM` | `false`；STT→DeepSeek→切句 TTS→TX |
| `-llm-barge-in` | `LLM_BARGE_IN` | `false`；TTS 播放中允许语音打断 |
| `-llm-key` | `DEEPSEEK_API_KEY` | 空则读 key 文件 |
| `-llm-key-file` | `DEEPSEEK_KEY_FILE` | `…/deepseek.key` |
| `-llm-model` | `DEEPSEEK_MODEL` | `deepseek-v4-flash` |
| `-llm-max-msgs` | `LLM_MAX_MSGS` | `24`（单通历史条数，不含 system） |
| `-archive-dir` | `CALL_ARCHIVE_DIR` | `…/ai_hook/calls` |
| `-llm-base` | `DEEPSEEK_BASE` | `https://api.deepseek.com` |
| `-tts-bin` | `TTS_BIN` | `…/sherpa-onnx-offline-tts` |
| `-tts-model` | `TTS_MODEL_DIR` | `…/vits-zh-ll` |
| `-tts-sid` | （或 `config.json` `tts.sid`） | `0`；**vits-zh-ll 合法 0～4**（5 说话人） |
| `-tx` | `TX_INJECT` | `…/tx_inject.pcm` |

## 与 pcm_recv

`pcm_recv` 仍可单独 dump；正式 STT 用本 `ai_call`（勿同时抢读同一 UDS client——HAL 只 accept 一个活跃 client，后连会顶掉前者）。
