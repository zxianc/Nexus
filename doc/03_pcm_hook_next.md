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

### ✅ 1.E incall-music TX + 本地 TTS — 已完成（2026-07-19 通话听验）

- 路径：MultiMedia9 + **pcm OUT d23**；格式 **48k mono s16le**；默认静音保活
- **模型：** `sherpa-onnx-vits-zh-ll`；`ai_call -say` → `tx_inject.pcm`；播完 unlink（每句重写）
- **听验：** 对面女声「你好能听到嘛」；音色=模型/`-tts-sid`，换模型可换声
- **下一：** 闭环 STT→LLM→TTS；`mix(DL,TTS)` / DeepSeek 仍未做

### ⏳ STT → LLM → TTS 闭环

- [x] **Echo（无 LLM）：** `-echo-tts`；通话听验已通过（CLI 路径 ~3–4s）
- [x] **常驻引擎（方案 B）：** `nexus_engine` + `-backend engine`；**跨通话常驻**，模型一次加载；echo 听验通过（勿开通话静音，静音会挡上行含 TTS）
- [x] **DeepSeek 流式（方案 A）：** `-llm`；STT → SSE → 切句 TTS→TX（待通话听验）
- [ ] 存档 `mix(DL, TTS)`
- [ ] （可选）静音麦但仍允许 AI TX

### TODO — 独立 Magisk 模块：业务侧资产（未做，不急）

与 HAL 注入模块 **`ai_audio_hook` 解耦**，另做模块（暂名 `nexus_runtime` / `nexus_stt`）管理用户态资产，建议根目录例如 `/data/adb/nexus_stt/`（或模块自有 `files/`）：

- [ ] `sherpa-onnx-offline` + `libonnxruntime.so` + SenseVoice 模型
- [ ] Go：`ai_call`（及后续 daemon）
- [ ] （后续）**TTS** 引擎/模型与其它业务数据
- [ ] （可选）开机自启业务进程；**不**碰 inject / UDS server / HAL so

原则：驱动+UDS 仍只由 `ai_audio_hook` 负责；ASR/TTS/Go 只消费 `pcm.sock`，资产生命周期单独版本化。

### 定稿 — 采集 vs 业务（2026-07-19）

- **`ai_audio_hook`（HAL）：** 通话接通就采 DL → 落盘 + UDS；**不做**按卡/模式开关采集，**暂不考虑**为省电关旁路。
- **业务层（Go / 策略服务）：** 决定用不用：AI 处理 / 丢弃 / 不启 STT；双卡（卡1 自动接听 AI、卡2 拒接或放行人工）也在业务层，**不**进 HAL。
- 以后若真要省电，再加可选「整段旁路总开关」作优化，不作为默认设计。

### 定稿 — 短信转发（未做）

- **不要**做进 `ai_audio_hook`（音频注入与短信无关）。
- **另做**短信侧能力（独立 Magisk 模块或独立组件）：只负责收/窥短信事件并交给用户态。
- **Go 统一配置与编排**（哪张卡转发、摘要/推企微等）；各模块解耦，只通过约定 IPC/文件/ socket 对接业务 daemon。
- 可与「业务侧资产模块」同仓不同模块，或短信单独模块——**均不与 HAL 耦合**。

