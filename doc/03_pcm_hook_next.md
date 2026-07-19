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
- **AI 模式：**  HAL **只采 DL** → STT →LLM → TTS → **1.E 注入**；**现行文本存档**（挂断写摘要+对话）；语音 `mix(DL,TTS)` **TODO 延期**。
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

### ✅ STT → LLM → TTS 闭环 — 已完成（2026-07-19）

- [x] **Echo（无 LLM）：** `-echo-tts`；通话听验已通过
- [x] **常驻引擎（方案 B）：** `nexus_engine` + `-backend engine`；跨通话常驻
- [x] **DeepSeek 流式（方案 A）：** `-llm`；STT → SSE → 切句 TTS→TX；**通话听验通过**
- [x] **通内上下文：** `CallSession`；挂断清空；默认最多 24 条非 system（`LLM_MAX_MSGS`，防费用/延迟）
- [x] **文本存档：** 挂断后落盘对话全文 + DeepSeek 摘要 → `/data/vendor/ai_hook/calls/call_*.txt`
- [ ] **TODO（延期）** 语音存档 `mix(DL, TTS)`（需按 TX 播放时间轴对齐，防错位）
- [ ] **TODO（延期）** 企微推送 + 短信转发（**同一后续里程碑一起做**：摘要/全文推送）
- [ ] （可选）静音麦但仍允许 AI TX
- [ ] （可选）开机自启 `ai_call` / 业务 Magisk 模块

**注意：** 通话「静音」会挡上行（含 TTS）；测试勿静音。Key 放设备 `…/deepseek.key`，勿进 git。

### TODO — Magisk 三模块（`nexus_audio_hook` / `nexus_runtime` / `nexus_models`）

与 HAL 注入 **解耦**，程序与模型 **双包**：

| 模块 | 职责 |
|------|------|
| **`nexus_audio_hook`** | 仅 HAL+UDS+TX（原 `ai_audio_hook`，v2.2 更名；so/逻辑未改） |
| **`nexus_runtime`** | `ai_call`、`nexus_engine`、lib、配置、自启 — 见 [`magisk_modules/`](../magisk_modules/README.md) |
| **`nexus_models`** | SenseVoice / VITS — 同上（与 runtime 分 zip） |

运行时配置：`/data/adb/nexus/env.sh` + `secrets/deepseek.key`（`config.json` 预留给设置 UI）。  
原则：ASR/TTS/Go 只消费 `pcm.sock`；inject **不**进 runtime/models。

### 定稿 — 采集 vs 业务（2026-07-19）

- **`nexus_audio_hook`（HAL）：** 通话接通就采 DL → 落盘 + UDS；**不做**按卡/模式开关采集，**暂不考虑**为省电关旁路。
- **业务层（Go / 策略服务）：** 决定用不用：AI 处理 / 丢弃 / 不启 STT；双卡策略在业务层，**不**进 HAL。
- 以后若真要省电，再加可选「整段旁路总开关」作优化，不作为默认设计。

### 定稿 — 短信转发 + 企微推送（未做，捆绑后续）

- **不要**做进 `nexus_audio_hook`（音频注入与短信无关）。
- **另做**短信侧能力（独立 Magisk 模块或独立组件）：只负责收/窥短信事件并交给用户态。
- **Go 统一配置与编排**（哪张卡转发、通话摘要/全文推企微等）；与 HAL/STT 解耦。
- **企微推送**与短信出站 **同一里程碑一起做**（现已先做通话文本落盘，推送后接）。
- 可与「业务侧资产模块」同仓不同模块——**均不与 HAL 耦合**。

