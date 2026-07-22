# 如何替换 STT / TTS 模型

Nexus Phone 用 **sherpa-onnx** 做离线识别与合成。本文说明：**从哪下载、下载哪一套、要哪些文件、怎么装进手机**。

当前引擎能力：

| 用途 | 引擎类型 | 推荐包（默认目录名） |
|------|----------|----------------------|
| **STT（听）** | SenseVoice（Offline） | `sense-voice` |
| **TTS（说）** | VITS 中文多说话人 | **`vits-zh-hf-fanchen-C`**（默认） |

其它 sherpa 模型（Whisper、Piper、Kokoro 等）**不能**直接当本 App 的 STT/TTS 用；必须与现有封装匹配：**SenseVoice + VITS（带 lexicon）**。

---

## 1. 从哪里下载（推荐）

**首选：k2-fsa 官方 GitHub Releases**（稳定、整包、带 sidecar / FST）。

| 模型 | Release 页 | 直接下载 |
|------|------------|----------|
| STT SenseVoice int8 | [asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) | [sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2) |
| **TTS fanchen-C（推荐）** | [tts-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models) | [vits-zh-hf-fanchen-C.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-fanchen-C.tar.bz2) |
| TTS VITS zh-ll（旧默认） | 同上 | [sherpa-onnx-vits-zh-ll.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-vits-zh-ll.tar.bz2) |

官方说明：

- SenseVoice：[Pretrained SenseVoice](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)
- VITS（含 fanchen）：[VITS pretrained models](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html)

**备选：Hugging Face**（GitHub 慢时用；国内可试 [hf-mirror.com](https://hf-mirror.com)）

| 模型 | 仓库 |
|------|------|
| STT SenseVoice | [`csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09`](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09) |
| **TTS fanchen-C** | [`csukuangfj/vits-zh-hf-fanchen-C`](https://huggingface.co/csukuangfj/vits-zh-hf-fanchen-C) |

Hugging Face 需至少下载（放同一目录）：

- `vits-zh-hf-fanchen-C.onnx`
- `tokens.txt`
- `lexicon.txt`
- 建议再下：`phone.fst`、`date.fst`、`number.fst`（GitHub 整包里有；HF 上不一定齐全时可从 GitHub 压缩包取）

镜像示例（PowerShell）：

```powershell
$dir = "$env:USERPROFILE\Downloads\vits-zh-hf-fanchen-C"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$base = "https://hf-mirror.com/csukuangfj/vits-zh-hf-fanchen-C/resolve/main"
foreach ($f in @("vits-zh-hf-fanchen-C.onnx","tokens.txt","lexicon.txt")) {
  curl.exe -L -o (Join-Path $dir $f) "$base/$f"
}
```

> 体积大约：STT int8 ~230MB；fanchen-C onnx ~116MB / 整包 tar.bz2 ~114MB。GitHub `.tar.bz2` 整包更省事（含 FST）。
>
> 国内 GitHub 直连失败时，可试加速镜像（示例）：
> `https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-fanchen-C.tar.bz2`

---

## 2. 下载哪一种

### 2.1 STT：选 **int8** SenseVoice（推荐）

| 选项 | 文件名特征 | 建议 |
|------|------------|------|
| **int8（推荐）** | `…-int8-…` / `model.int8.onnx` | 体积小、手机更快，App 默认优先找 int8 |
| float / 非 int8 | `model.onnx`（无 int8） | 可用，更大更慢 |

语言：选带 **zh** 的多语包即可，例如：

`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`

覆盖中/英/日/韩/粤。

### 2.2 TTS：选 **fanchen-C**（当前推荐）

| 选项 | 说明 |
|------|------|
| **`vits-zh-hf-fanchen-C`（推荐）** | 中文、**187 个 Speaker（ID 0～186）**；听感通常比 zh-ll 更丰富；采样率 16k |
| `sherpa-onnx-vits-zh-ll` | 旧默认；约 5 个音色，仍兼容（默认目录回退会找） |
| `vits-melo-tts-zh_en` / `fanchen-wnj` 等 | 同属 VITS + lexicon 时可试；Speaker 数量以该包为准 |
| Piper / Kokoro / Matcha | **本版代码未接**，不能只换文件 |

设置里 **TTS Speaker ID** 用滚轮选 **0～200**，弹窗内可改试听文案（默认「你好，这是音色测试。」）并点「试听音色」。fanchen-C 有效音色大约 0～186。

---

## 3. 解压后必须有哪些文件

把压缩包解压到电脑任意文件夹。**sidecar 必须与 `.onnx` 在同一目录**（App 导入时按「同目录」拷贝）。

### 3.1 STT（SenseVoice）

| 文件 | 必需 | 说明 |
|------|------|------|
| `model.int8.onnx` 或 `model.onnx` | ✅ | 主权重；优先 int8 |
| `tokens.txt` | ✅ | 词表，缺则导入失败 / 无法识别 |

其它（`README.md`、`test_wavs/`、`LICENSE`）可忽略，不必拷到手机。

### 3.2 TTS（fanchen-C）

| 文件 | 必需 | 说明 |
|------|------|------|
| `vits-zh-hf-fanchen-C.onnx`（或任意 `.onnx`） | ✅ | 主权重；官方包文件名不是 `model.onnx` 也没关系 |
| `tokens.txt` | ✅ | |
| `lexicon.txt` | ✅ | 中文发音词典 |
| `phone.fst` / `date.fst` / `number.fst` | 可选 | 有则更好（电话号、日期、数字读法）；App 会尽量一并导入 |

`dict/` 等其它目录本版导入逻辑**不会**自动拷。

解压后建议整理成两个文件夹，例如手机 Download 里：

```text
Download/
├── sense-voice/
│   ├── model.int8.onnx
│   └── tokens.txt
└── vits-zh-hf-fanchen-C/
    ├── vits-zh-hf-fanchen-C.onnx
    ├── tokens.txt
    ├── lexicon.txt
    ├── phone.fst      # 可选
    ├── date.fst
    └── number.fst
```

---

## 4. 安装到手机（推荐：App 内选择）

模型最终在 **App 私有目录**，普通文件管理器看不到；请用设置页导入。

### 步骤

1. 用数据线 / 云盘 / 局域网，把上面两个文件夹拷到手机可读位置（如 `Download/`）。
2. 打开 **Nexus Phone** → **设置** → **Nexus / AI**（拨号盘也可进 Nexus 页）。
3. **选择 STT 模型**：选中文件夹里的 **`.onnx`**（不是 tokens）。
4. **选择 TTS 模型**：选 **`vits-zh-hf-fanchen-C.onnx`**。
5. 成功后设置页会显示已选路径；失败时常见提示是「同目录缺少 tokens.txt / lexicon.txt」——把 sidecar 与 onnx 放同一层再选一次。
6. （可选）**TTS Speaker ID**：填 0～186，听感不对就换几个试（如 14、100）。
7. 打一通测试电话（SIM 策略为 AI、旁路正常）验证听/说。

导入后文件落在：

| 类型 | 私有路径（示意） |
|------|------------------|
| STT | `/data/data/org.fossify.nexus.phone/files/imported/stt/` |
| TTS | `/data/data/org.fossify.nexus.phone/files/imported/tts/` |

配置里会记住所选 `.onnx` 路径。覆盖安装一般保留；**卸载会清空**。

---

## 5. 安装到手机（可选：adb / 默认目录）

不经过「选择模型」时，也可放到默认目录，App 会自动查找：

| 默认目录 | 找什么 |
|----------|--------|
| `files/models/sense-voice/` | 优先 `model.int8.onnx`，否则 `model.onnx`，再否则任意 `.onnx`；同目录需 `tokens.txt` |
| `files/models/vits-zh-hf-fanchen-C/` | 任意 `.onnx`（如 `vits-zh-hf-fanchen-C.onnx`）；同目录需 `tokens.txt`、`lexicon.txt` |
| `files/models/vits-zh-ll/` | 旧 TTS 回退目录 |

正式包（非 debuggable）一般 **不能** `adb run-as`。可用其一：

- **仍推荐** App 内选择（最省事）
- root / Magisk 文件管理器拷到上述路径
- debug 包：`adb shell run-as org.fossify.nexus.phone.debug` 再 `cp`

电脑侧示例：

```powershell
adb push vits-zh-hf-fanchen-C /sdcard/Download/vits-zh-hf-fanchen-C
# 然后在 App 设置里「选择 TTS」指向 Download 里的 .onnx
```

有 root 时也可直接覆盖 App 私有目录（包名正式包）：

```powershell
adb push vits-zh-hf-fanchen-C /sdcard/Download/vits-zh-hf-fanchen-C
adb shell "su -c 'rm -rf /data/data/org.fossify.nexus.phone/files/models/vits-zh-hf-fanchen-C; mkdir -p /data/data/org.fossify.nexus.phone/files/models/vits-zh-hf-fanchen-C; cp -r /sdcard/Download/vits-zh-hf-fanchen-C/* /data/data/org.fossify.nexus.phone/files/models/vits-zh-hf-fanchen-C/; chown -R $(stat -c %u:%g /data/data/org.fossify.nexus.phone) /data/data/org.fossify.nexus.phone/files/models/vits-zh-hf-fanchen-C'"
```

若配置里曾保存过旧的 `tts_model_path`（指向 `vits-zh-ll`），请在设置里重新「选择 TTS」，或清掉该路径，才会走新默认目录。

---

## 6. 电脑端下载与解压示例

**Windows（PowerShell）**——GitHub 整包：

```powershell
cd $env:USERPROFILE\Downloads

# STT
curl.exe -L -O https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
tar -xvf sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2

# TTS（推荐 fanchen-C）
curl.exe -L -O https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-fanchen-C.tar.bz2
tar -xvf vits-zh-hf-fanchen-C.tar.bz2
```

解压后目录名一般为 `vits-zh-hf-fanchen-C/`，拷到手机 `Download`，再进 App 选择对应 `.onnx`。

无 `tar` 时可用 [7-Zip](https://www.7-zip.org/) 解压 `.tar.bz2`。GitHub 失败时改用上文 **Hugging Face / hf-mirror**。

---

## 7. 常见问题

| 现象 | 处理 |
|------|------|
| 导入提示缺少 `tokens.txt` / `lexicon.txt` | sidecar 与 `.onnx` 必须同文件夹；用系统文件选择器选的是 onnx，App 会找「同目录」兄弟文件 |
| 识别一直空 / 报模型未就绪 | 看设置里 STT 是否已选成功；确认是 SenseVoice 的 onnx，不是别的 ASR |
| 能听不能说 / TTS 无声 | 确认 TTS 三件套齐全；换 Speaker ID；确认 HAL 旁路与 AI 策略已开 |
| 换模型后仍像旧声线 | 确认选的是新 onnx；必要时清 App 数据后重新导入（会丢配置） |
| Speaker 听起来不对 | fanchen-C 换 0～186；旧 zh-ll 有效范围大约只有 0～4 |
| 卸载重装没声音 | 私有模型已删，需重新导入 |

---

## 8. 相关路径速查

| 内容 | 位置 |
|------|------|
| applicationId | `org.fossify.nexus.phone`（debug 带 `.debug`） |
| 配置 | App 私有 `shared_prefs/nexus_config.xml` |
| 默认模型根 | `files/models/`（TTS 默认子目录 `vits-zh-hf-fanchen-C`） |
| UI 导入 | `files/imported/stt`、`files/imported/tts` |
| 框架总览 | [`00_framework_overview.md`](00_framework_overview.md) |
| 编译安装 | [`../nexus_phone/README.md`](../nexus_phone/README.md) |
