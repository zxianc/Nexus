# 通话旁路延迟优化 — 设计说明（终态）

**日期：** 2026-07-25  
**状态：** Archived（已实现）  
**归档位置：** `docs/superpowers/archive/2026-07-25-call-pipeline-latency/`  
**落地提交：** `ff3248c`

## 1. 目标

在 DeepSeek 已用 `v4-flash` + thinking off + stream 的前提下，砍本机串行死时间，并能量化各阶段耗时。

本轮只做三点：

1. 分阶段耗时埋点（`NexusPipeline`）
2. TTS/注入与 LLM SSE 解耦（`TtsSpeakQueue`）
3. VAD 尾静音 `silenceEndMs`：500 → **300**

明确不做：barge-in、流式 TTS、echo cooldown 调参、增益调整。

## 2. 终态流水线

```text
PCM_DL → EnergyVad(silenceEndMs=300)
      → aiExecutor: ASR → DeepSeek SSE → SentenceBuf
      → TtsSpeakQueue.offer(sentence)     # 非阻塞
      → NexusTtsSpeak 线程: synthesize → injectTts
```

- `aiBusy`：ASR + 读完 SSE + `awaitIdle`（本轮句子播完）后释放  
- echo guard（`beginTtsGuard` / cooldown 1.5s）语义不变  
- TTS 仍整句离线合成（流式另开）

## 3. 埋点格式

```text
NexusPipeline turn vad_emit=+0 asr_done=+86 llm_first=+462 llm_done=+281 tts_idle=+3147 total=3976 ...
```

| 字段 | 含义 |
|------|------|
| `asr_done` | VAD 出句 → ASR 完成 |
| `llm_first` | ASR 完成 → SSE 首个 content delta |
| `llm_done` | 首包 → SSE 结束（句已入队，不堵在整段 TTS） |
| `tts_idle` | SSE 结束 → 队列排空 |
| `total` | 本轮到播完 |

查看：`adb logcat -s NexusPipeline:I NexusBypass:I`

## 4. 真机结论（2026-07-25 一次通话）

- ASR ≈ **70–110ms**（非瓶颈）  
- LLM 首包 ≈ **0.45–0.7s**  
- 队列解耦生效：`llm_done` ≪ `tts_idle`（多句时）  
- 整轮大头仍在 **TTS 单句合成（约 0.4–1.2s/句）**  
- 对方停说 → 听到首句：约 VAD300 + ASR100 + 首包500 + 首句TTS ≈ **1.5–2s**

后续若再压开口延迟：优先更短首句 / 流式 TTS，而非换云端模型。

## 5. 回滚要点

- `silenceEndMs` 改回 500（或调 350）  
- `onAssistantSentence` 改回同步 `synthesize`+`injectTts`  
- 去掉 `TtsSpeakQueue` / `PipelineTiming` 接线  
