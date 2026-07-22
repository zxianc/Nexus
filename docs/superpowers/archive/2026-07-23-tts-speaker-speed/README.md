# 归档：TTS Speaker / 语速选择与试听（2026-07-23）

**归档日期：** 2026-07-23  
**状态：** Archived（已落地；遇问题再修）  
**范围：** Nexus Phone 设置页 — TTS 音色 ID、语速、试听

| 文件 | 说明 |
|------|------|
| [`design.md`](design.md) | 终态设计（含语速与试听弹窗联调） |

**原 specs 入口（勿再迭代）：** 原 `docs/superpowers/specs/2026-07-23-tts-speaker-preview-design.md` 已并入本目录。

**相关实现（现行代码）：**

| 项 | 路径 |
|----|------|
| 设置页 | `nexus_phone/.../nexus/ui/NexusSettingsActivity.kt` |
| 试听弹窗布局 | `nexus_phone/.../res/layout/dialog_nexus_tts_speaker.xml` |
| 试听播放 | `nexus_phone/.../nexus/ai/TtsPreviewPlayer.kt` |
| TTS 引擎 | `nexus_phone/.../nexus/ai/SherpaTts.kt` |
| 配置 | `NexusConfig.ttsSpeakerId` / `ttsSpeed`；`ConfigRepository` |
| Release 混淆 | `nexus_phone/app/proguard-rules.pro`（keep `com.k2fsa.sherpa.onnx.**`） |
