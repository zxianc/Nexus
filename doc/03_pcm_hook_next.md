# 下一里程碑

## ✅ 1.C 通话 PCM（混合）— 已完成（2026-07-19）

- incall-rec UL+DL + `pcm_read` d23 @ 48kHz；听验通过（L≡R）

## ✅ 1.D UDS → Go — 已完成（2026-07-19）

- HAL **listen** `/data/vendor/ai_hook/pcm.sock`；`pcm_recv` **connect**
- 协议：`APCM` + raw s16le；真机 `uds == dumped`

过程：`[dev_journal.md](dev_journal.md)`

---

## 音频方案（定稿）

- **全 AI / 全人互斥**，无同时回复。
- **AI 模式：**  HAL **只采 DL** → STT →LLM → TTS → **1.E 注入**；存档 = Go `mix(DL, TTS)`。
- **人模式听验：** 可继续混合 UL+DL 落盘。
- **不做** 硬件双路 incall-rec 并行。

---

## ⏳ 当前要实现

### ✅ 1.D′ HAL DL-only 推流 — 已完成（2026-07-19）

- `INCALL_REC_DOWNLINK` + 仅 `VOC_REC_DL`
- UDS `kind=DL`；`uds==dumped`
- **听验通过：仅对方声**



### ✅ 1.F Go 本地 STT — 已完成（2026-07-19 sherpa 听验）

- 包：[`daemon/ai_call`](../daemon/ai_call/README.md)
- DL UDS → VAD → mock / sherpa SenseVoice → `stt.log`；真机已出中文
- 资产：`/data/local/tmp/nexus_stt/`（勿进 git）

### ✅ VAD 调优（方案 A）— 已完成（2026-07-19）

- STT 后过滤无实质字符 → `DROP`（不写 `stt.log`）
- `MinSpeechMs` 300→500；拨测：真句进 log，纯 `。` 被丢

### ✅ 模块重装 / 开机自动注入 — 已完成（2026-07-19）

- zip **v2.1**：装 Magisk → 重启 → HAL maps 有 `libai_hook` + `pcm.sock`
- Magisk 可能报缺 `zygisk/armeabi-v7a.so`（无害）；主线是 `service.sh`+`inject32`
- **`ai_call` 仍手动**（未进模块自启）

### 1.E incall-music TX — ⏳

- TTS PCM 写入通话上行，对面可听
- `mix(DL,TTS)` / TTS / DeepSeek：仍未做

