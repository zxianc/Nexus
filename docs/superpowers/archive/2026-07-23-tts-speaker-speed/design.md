# TTS Speaker / 语速选择与试听 — 设计说明（终态）

**日期：** 2026-07-23  
**状态：** Archived（已实现）  
**归档位置：** `docs/superpowers/archive/2026-07-23-tts-speaker-speed/`

## 1. 背景与目标

默认 TTS（`vits-zh-hf-fanchen-C`）多音色、默认语速偏慢。需要在设置里：

- 滚轮选择 Speaker ID（0–200）并试听
- 调节语速（0.5x～2.0x）并试听
- 试听文案可改（默认有文案，不持久化）

试听走扬声器 `AudioTrack`，**不**走通话 PCM 旁路。

## 2. 行为（终态）

### 2.1 设置列表

| 项 | 行为 |
|----|------|
| TTS Speaker ID | 打开试听弹窗（见下） |
| TTS 语速 | 单独 NumberPicker 弹窗，也可在试听弹窗内改 |

展示范围：Speaker `0–200`；语速 `0.5x～2.0x`（步长 0.1）。

### 2.2 试听弹窗（主入口）

点击「TTS Speaker ID」打开 `dialog_nexus_tts_speaker`：

1. **左** Speaker NumberPicker（0–200）  
2. **右** 语速 NumberPicker（0.5x～2.0x）  
3. 试听文案 `EditText`：默认「你好，这是音色测试。」（可改；不持久化）  
4. 「试听」：当前滚轮上的 Speaker + 语速合成，扬声器播放；再次试听先停后播  
5. 「确定」：同时保存 `tts_speaker_id` 与 `tts_speed`，并刷新设置页两行文案  
6. 「取消」/关闭：不保存；停止播放  

模型未就绪：toast，不崩溃。

### 2.3 语速语义

| UI | 内部 |
|----|------|
| 语速越大越快（建议 fanchen 试 1.2～1.5） | `lengthScale = 1 / speed`（加载 TTS 时写入 VITS config） |
| 默认 `1.0x` | `generate(..., speed=1.0)`；速率只由 `lengthScale` 表达 |

旁路服务（`NexusBypassService`）按配置重建 `SherpaTts` 时带上当前 `ttsSpeed`。

## 3. 实现要点

- `TtsPreviewPlayer.play(context, speakerId, text, speed)` → 临时 `SherpaTts` + `AudioTrack`
- `NexusConfig`：`tts_speaker_id`、`tts_speed`；`ConfigRepository` 读写并 clamp（`TTS_SPEED_MIN/MAX`）
- Release R8：必须 keep `com.k2fsa.sherpa.onnx.**`，否则 JNI `OfflineTts.newFromFile` 会因混淆闪退（`fid == null`）

## 4. 明确不做

- 试听文案不写入 prefs  
- 试听不注入通话上行 PCM  
- 不在本提案内换男声模型包（如 `fanchen-wnj`）

## 5. 验收（已通过）

1. 设置页可改 Speaker / 语速并持久化  
2. 试听弹窗内可同时调两者；试听音质与语速符合预期  
3. 确定后设置页两行同步；通话旁路使用新语速  
4. Release 包试听不因 sherpa JNI 混淆崩溃  
