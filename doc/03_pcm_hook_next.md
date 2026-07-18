# 下一里程碑

## ✅ 1.C 通话 PCM — 已完成（2026-07-19）

- 路径：incall-rec MultiMedia9 + `VOC_REC_UL/DL` + `pcm_read` d23 @ 48kHz  
- 落盘：`/data/vendor/ai_hook/ai_incall.pcm`  
- **听验通过：** 双向通话混合录音；L≡R  

过程：[`dev_journal.md`](dev_journal.md)

---

## ⏳ 1.D UDS → Go（当前）

- HAL 侧：通话中把 PCM 帧写入 Unix Domain Socket（替代/并行落盘）  
- Go 侧：Termux/守护进程 `recv` → 缓冲 → 后续 STT  
- SELinux：`hal_audio_default` 对 UDS 的 create/connect/write  

## 可选并行：1.E incall-music TX

- 目标：TTS 写入通话上行，对面可听  
- 入口：`platform_start_incall_music_usecase` / `incall-music-uplink` mixer  
