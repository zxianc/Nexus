# 下一里程碑

## ✅ 1.C 通话 PCM — 已完成（2026-07-19）

- 路径：incall-rec MultiMedia9 + `VOC_REC_UL/DL` + `pcm_read` d23 @ 48kHz  
- 落盘：`/data/vendor/ai_hook/ai_incall.pcm`  
- **听验通过：** 双向通话混合录音；L≡R  

过程：[`dev_journal.md`](dev_journal.md)

---

## ⏳ 1.D UDS → Go（当前）

- Socket：`/data/vendor/ai_hook/pcm.sock`
- 协议：16 字节头 `APCM` + rate(u32) + ch(u16) + bits(u16) + padding，随后 raw s16le
- HAL：通话中 `connect` 并推送（无接收端则仅落盘）
- 接收端：`daemon/pcm_recv`（Go）

```bash
cd daemon/pcm_recv && go build -o pcm_recv .
adb push pcm_recv /data/local/tmp/
adb shell 'su -c "/data/local/tmp/pcm_recv -dump /data/vendor/ai_hook/uds_dump.pcm"'
```

## 可选并行：1.E incall-music TX

- 目标：TTS 写入通话上行，对面可听  
- 入口：`platform_start_incall_music_usecase` / `incall-music-uplink` mixer  
