# AI 响铃预热 + 延迟接听 — 设计说明

**日期：** 2026-07-25  
**状态：** Implemented

## 1. 目标

AI 策略来电时，在响铃窗口内预热 App 侧 ASR/TTS（及 LLM client / 网络绑定），按可配置延迟再接听，避免 ACTIVE 后冷加载拖慢首句响应。

HAL / UDS / PCM 仍只在 ACTIVE 后 `ACTION_START` 启动。

## 2. 时序

```text
RINGING + AnswerAi
  → BypassCommands.warmAi()          # ACTION_WARM：ensureAiLoaded + sync ensureLoaded，不开 UDS
  → postDelayed(answer, delayMs)     # 可配置；DISCONNECTED/IDLE 取消
ACTIVE
  → BypassCommands.startSession()    # 复用已热引擎 → pcm.sock → greeting/VAD
```

Fallback（`AiAnswerReceiver`）同样：warm → delayed `acceptRingingCall` → 800ms 后再 `startSession`（若 InCall 未接手）。

## 3. 配置

| 字段 | 默认 | 范围 | UI |
|------|------|------|-----|
| `ai_answer_delay_ms` | **3000** | 1000–5000 | 设置页 NumberPicker，单位秒 1–5 |

读写走 `ConfigRepository` SharedPreferences。

## 4. 边界

| 层 | 职责 |
|----|------|
| `CallService` / `CallPolicyBindings` / `AiAnswerReceiver` | 延迟 `answer`、取消任务 |
| `NexusBypassService` | `ACTION_WARM` 预热；`ACTION_START` 复用；`ACTION_END` 释放 |
| HAL / `AudioPipeline` | 不变 |

## 5. 生命周期

- 响铃中对方挂断 → `cancelScheduledAnswer` + `endSession`，禁止再 answer
- 预热失败 → 打日志，仍按时接听，走现有懒加载兜底
- `ensureLoaded` / `ensureAiLoaded` 必须幂等
- greeting 注入仍只在 ACTIVE + UL 就绪后

## 6. 日志

- `warm_ms=…`（预热耗时）
- 延迟前后可见 answer / acceptRingingCall

## 7. 明确不做

改 HAL、帧协议、流式 TTS、LLM 超时策略、响铃阶段开 PCM。
