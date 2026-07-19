# sherpa-onnx Android arm64 构建与部署手记

**日期：** 2026-07-19  
**结论：** 真机上的 `sherpa-onnx-offline` **是本机用 NDK 手动交叉编译的**（不是直接用官方 Linux aarch64 包；官方 Android 预编译包主要是 `.so`/APK 用途，CLI 需自己编）。  
**配套脚本：** [`daemon/ai_call/scripts/`](../daemon/ai_call/scripts/)  
**运行侧说明：** [`daemon/ai_call/README.md`](../daemon/ai_call/README.md)

---

## 1. 真机上最终用什么

| 路径（设备） | 来源 |
|--------------|------|
| `/data/local/tmp/nexus_stt/sherpa-onnx-offline` | **本机 NDK 编译**（sherpa-onnx **v1.13.4**，`arm64-v8a`，`ANDROID_PLATFORM=android-28`） |
| `/data/local/tmp/nexus_stt/libonnxruntime.so` | 编译时下载的 **onnxruntime-android 1.27.0**（`jni/arm64-v8a`），与二进制同目录 |
| `/data/local/tmp/nexus_stt/sense-voice/` | **官方模型包**解压（`model.int8.onnx` + `tokens.txt`） |

二进制特征（真机 `file` 曾显示）：ELF arm64、**Android 28**、NDK **r30**、动态链接 → 必须设  
`LD_LIBRARY_PATH=/data/local/tmp/nexus_stt`。

本地工作目录（**不进 git**，在 `.gitignore` 的 `tmp/` 下）：

```text
tmp/nexus_stt/
├── src/sherpa-onnx/          # git clone v1.13.4
│   └── build-android-arm64-v8a/
│       ├── install/bin/sherpa-onnx-offline
│       └── install/lib/libonnxruntime.so
├── sense-voice-int8.tar.bz2  # 模型归档
└── extract_*/                # 曾尝试的官方预编译解压（对照用）
```

---

## 2. 为什么要手动编 CLI

官方文档 [Build for Android](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html) 主线是编 **`.so` + APK**。  
Release 里虽有 `*-android*.tar.bz2` / Linux aarch64 包，但：

- **Linux aarch64** 二进制面向 glibc，**不能**直接在 Android 上跑；
- **Android 预编译包**往往是库/静态变体，不一定带可直接 `adb` 推的 `sherpa-onnx-offline` CLI，或 API/链接方式不合适；
- Nexus 需要的是：**可在 `su` shell 里 exec 的 SenseVoice 离线 CLI**，给 Go `ai_call` 按句调用。

因此本机走：**Windows + Android NDK + CMake/Ninja → 编 `sherpa-onnx-offline`**。

（模型仍用官方 release 下载，无需自训。）

---

## 3. 环境（本机已用）

| 项 | 路径 / 版本 |
|----|-------------|
| OS | Windows |
| NDK | `E:\android\SDK\ndk\30.0.15729638`（与模块 `build.bat` 同套） |
| CMake / Ninja | `E:\android\SDK\cmake\4.1.2\bin\` |
| sherpa-onnx | tag **v1.13.4** |
| onnxruntime | **1.27.0** android pack（csukuangfj/onnxruntime-libs） |
| ABI | `arm64-v8a` |
| min API | 最终 **android-28**（先试过 21，再按真机 API 重编） |

---

## 4. 实际操作步骤（可复现）

### 4.1 下载模型（及可选对照包）

```powershell
cd E:\workspace\Nexus
python daemon\ai_call\scripts\download_nexus_stt.py
```

会拉到 `tmp/nexus_stt/`：

- SenseVoice int8 模型包  
- （可选）linux-aarch64 / android-static 等，仅作对照，**最终 CLI 不用它们**

模型解压后保证有：

- `model.int8.onnx`
- `tokens.txt`

推到设备：

```powershell
adb shell "su -c 'mkdir -p /data/local/tmp/nexus_stt/sense-voice /data/local/tmp/nexus_stt/tmp'"
# 将上述两文件 adb push 到 /data/local/tmp/nexus_stt/sense-voice/
```

### 4.2 克隆源码 + 首次配置编译（API 21 脚手架）

脚本：[`daemon/ai_call/scripts/build_sherpa_android.ps1`](../daemon/ai_call/scripts/build_sherpa_android.ps1)

要点：

1. `git clone --depth 1 --branch v1.13.4` → `tmp/nexus_stt/src/sherpa-onnx`
2. 下载 `onnxruntime-android-1.27.0.zip`，设  
   `SHERPA_ONNXRUNTIME_LIB_DIR` / `SHERPA_ONNXRUNTIME_INCLUDE_DIR`
3. CMake：`ANDROID_ABI=arm64-v8a`，`BUILD_SHARED_LIBS=ON`，`SHERPA_ONNX_ENABLE_BINARY=ON`，关掉 TTS/Python/JNI/Tests 等
4. `ninja` + `cmake --install`，并把 `libonnxruntime.so` 拷进 `install/lib`

```powershell
powershell -ExecutionPolicy Bypass -File daemon\ai_call\scripts\build_sherpa_android.ps1
```

### 4.3 按真机 API 重编（现行采用）

脚本：[`daemon/ai_call/scripts/rebuild_sherpa_api28.ps1`](../daemon/ai_call/scripts/rebuild_sherpa_api28.ps1)

在已有 ORT 与源码基础上，把 **`ANDROID_PLATFORM` 改为 `android-28`**，只编：

```text
ninja sherpa-onnx-offline
```

产物：

```text
tmp/nexus_stt/src/sherpa-onnx/build-android-arm64-v8a/install/bin/sherpa-onnx-offline
tmp/nexus_stt/src/sherpa-onnx/build-android-arm64-v8a/install/lib/libonnxruntime.so
```

```powershell
powershell -ExecutionPolicy Bypass -File daemon\ai_call\scripts\rebuild_sherpa_api28.ps1
```

### 4.4 推到手机

```powershell
adb push tmp\nexus_stt\src\sherpa-onnx\build-android-arm64-v8a\install\bin\sherpa-onnx-offline /data/local/tmp/nexus_stt/sherpa-onnx-offline
adb push tmp\nexus_stt\src\sherpa-onnx\build-android-arm64-v8a\install\lib\libonnxruntime.so /data/local/tmp/nexus_stt/libonnxruntime.so
adb shell "su -c 'chmod 755 /data/local/tmp/nexus_stt/sherpa-onnx-offline'"
```

### 4.5 冒烟（不经 ai_call）

```powershell
adb shell "su -c 'LD_LIBRARY_PATH=/data/local/tmp/nexus_stt /data/local/tmp/nexus_stt/sherpa-onnx-offline --tokens=/data/local/tmp/nexus_stt/sense-voice/tokens.txt --sense-voice-model=/data/local/tmp/nexus_stt/sense-voice/model.int8.onnx --num-threads=2 --sense-voice-language=zh --debug=0 /data/local/tmp/nexus_stt/zh.wav'"
```

期望 stdout 含 JSON 行，例如 `"text":"开饭时间..."`。

再启 `ai_call -backend sherpa`（见 ai_call README）。

---

## 5. CMake 关键开关（摘要）

| 变量 | 值 | 原因 |
|------|-----|------|
| `ANDROID_ABI` | `arm64-v8a` | 与 `ai_call` 同为 64-bit |
| `ANDROID_PLATFORM` | `android-28` | 对齐真机 / linker |
| `BUILD_SHARED_LIBS` | `ON` | 与 `libonnxruntime.so` 动态链接 |
| `SHERPA_ONNX_ENABLE_BINARY` | `ON` | 产出 CLI |
| TTS / diarization / Python / JNI / Tests / PortAudio | `OFF` | 减小体积与依赖 |

---

## 6. 与官方「只下包」路径的对比

| 做法 | 结果 |
|------|------|
| 下 Linux aarch64 shared/static | **不能**当 Android 可执行文件用 |
| 下 `*-android*.tar.bz2` | 可作库/对照；本项目最终 CLI 仍自编 |
| **NDK 编 `sherpa-onnx-offline`** | ✅ 现行方案 |
| SenseVoice 模型 tar.bz2 | ✅ 直接下载解压，不自训 |

---

## 7. 注意

- 产物与模型体积大，**勿提交 git**（`tmp/`、设备 `/data/local/tmp/nexus_stt/`）。
- 脚本里 NDK/CMake 路径写死为本机 SDK；换机器请改脚本顶部变量。
- Magisk **`nexus_audio_hook` 不包含** sherpa；模型归 **`nexus_models`**，程序归 **`nexus_runtime`**（待做）。现行推 `/data/local/tmp/nexus_stt` 仅调试。
- **TODO（未做）：** 另做业务侧 Magisk 模块托管 CLI/模型/`ai_call`/后续 TTS，与 HAL 解耦——见 [`03_pcm_hook_next.md`](03_pcm_hook_next.md)。
- 升级 sherpa 版本时：改 clone 的 tag，并确认 ORT 版本与官方该 tag 兼容。

---

## 8. 常驻引擎 `nexus_engine`（方案 B）

**说明文档（原理 / 协议 / 构建）：** [`daemon/nexus_engine/README.md`](../daemon/nexus_engine/README.md)

源码：[`daemon/nexus_engine/main.cc`](../daemon/nexus_engine/main.cc)  
构建：`daemon/nexus_engine/build_api28.ps1`（链已有 sherpa 静态库；需 `-Wl,-z,max-page-size=16384` 避免 Bionic TLS 对齐错误）

- 启动加载 SenseVoice + VITS 各一次；UDS 行 JSON（`ping`/`stt`/`tts`）
- `ai_call -backend engine` 由 Go `engine.Supervisor` 拉起或复用
- 冷启动约 2–3s；常驻后单次 TTS 约 1s（日志 `tts id=… ms=`）

## 9. TTS CLI（`sherpa-onnx-offline-tts`）

在已有 STT 的 `build-android-arm64-v8a` 上开 TTS：

```powershell
# daemon/ai_call/scripts/build_sherpa_tts_api28.ps1
# 需 SHERRPA_ONNX_ENABLE_TTS=ON；复用同目录 ORT 1.27.0
```

**踩坑：** FetchContent 拉 `piper-phonemize-….zip` 易断。把 zip 放到  
`build-android-arm64-v8a/piper-phonemize-f3ff95afc03640bc1399e113e83361192a2fafb4.zip`  
（SHA256=`d9cca4e2…`）后重跑脚本即可本地命中。

模型：`sherpa-onnx-vits-zh-ll` → 设备 `/data/local/tmp/nexus_stt/vits-zh-ll/`。  
调用：`ai_call -say "…"`（见 `daemon/ai_call/README.md`）。

---

## 10. 索引

| 文件 | 说明 |
|------|------|
| `daemon/ai_call/scripts/build_sherpa_android.ps1` | 首次 clone + ORT + CMake（API 21 脚手架） |
| `daemon/ai_call/scripts/rebuild_sherpa_api28.ps1` | API 28 重编 STT CLI |
| `daemon/ai_call/scripts/build_sherpa_tts_api28.ps1` | API 28 编 TTS CLI |
| `daemon/nexus_engine/build_api28.ps1` | **现行** 常驻引擎 |
| `daemon/nexus_engine/README.md` | 引擎原理 / UDS 协议 / 与 CLI 区别 |
| `daemon/ai_call/engine/` | Go Supervisor / Client |
| `daemon/ai_call/stt/sherpa.go` / `tts/sherpa.go` | CLI 回退 |
| `daemon/ai_call/stt/engine.go` / `tts/engine.go` | 常驻引擎后端 |

*过程踩坑与听验见 [`dev_journal.md`](dev_journal.md) 1.F / TTS 条目。*
