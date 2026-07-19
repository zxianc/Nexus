# ai_call — 本地 STT 守护进程（1.F）

HAL DL UDS → 能量 VAD 切句 → **mock** 或 **sherpa-onnx SenseVoice** → `/data/vendor/ai_hook/stt.log`

约定：**STT/TTS 本地**；**LLM 仅 DeepSeek**（本包不接 LLM/TTS）。

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

用官方 Android arm64 构建产物中的 `sherpa-onnx-offline`（见 [Build for Android](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html)），推到：

```text
/data/local/tmp/nexus_stt/sherpa-onnx-offline
```

并 `chmod 755`；若动态链接，把依赖 `.so` 放到同目录并设置 `LD_LIBRARY_PATH`。

### 3) 启动

```bash
adb shell 'su -c "pkill -9 ai_call; export STT_BACKEND=sherpa STT_BIN=/data/local/tmp/nexus_stt/sherpa-onnx-offline STT_MODEL_DIR=/data/local/tmp/nexus_stt/sense-voice LD_LIBRARY_PATH=/data/local/tmp/nexus_stt; nohup /data/local/tmp/ai_call -backend sherpa >>/data/vendor/ai_hook/ai_call.log 2>&1 &"'
```

对方说话后 `stt.log` 应出现中文 `text=`。

**真机已验（2026-07-19）：** DL 通话可出「喂。」「外卖…」等。

**VAD 方案 A：** 无字母/数字的 STT 结果 `DROP`（不写 `stt.log`）；`MinSpeechMs=500`。

## Flag / 环境变量

| Flag | Env | 默认 |
|------|-----|------|
| `-backend` | `STT_BACKEND` | `mock` |
| `-stt-bin` | `STT_BIN` | `/data/local/tmp/nexus_stt/sherpa-onnx-offline` |
| `-model-dir` | `STT_MODEL_DIR` | `/data/local/tmp/nexus_stt/sense-voice` |
| `-stt-log` | `STT_LOG` | `/data/vendor/ai_hook/stt.log` |
| `-sock` | `PCM_SOCK` | `/data/vendor/ai_hook/pcm.sock` |
| `-lang` | `STT_LANG` | `zh` |

## 与 pcm_recv

`pcm_recv` 仍可单独 dump；正式 STT 用本 `ai_call`（勿同时抢读同一 UDS client——HAL 只 accept 一个活跃 client，后连会顶掉前者）。
