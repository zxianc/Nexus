# 归档：通话旁路延迟优化（2026-07-25）

**归档日期：** 2026-07-25  
**状态：** Archived（已落地；真机已用 `NexusPipeline` 验证）  
**范围：** VAD 尾静音、分阶段耗时埋点、TTS 与 LLM SSE 解耦  
**提交：** `ff3248c`

| 文件 | 说明 |
|------|------|
| [`design.md`](design.md) | 终态设计与真机结论 |
| [`plan.md`](plan.md) | 原实现计划（已执行） |

**原 plans 入口（勿再迭代）：** `docs/superpowers/plans/2026-07-25-call-pipeline-latency.md` 已改为指向本目录的 stub。

**相关实现（现行代码）：**

| 项 | 路径 |
|----|------|
| 耗时埋点 | `nexus_phone/.../nexus/ai/PipelineTiming.kt`（log tag `NexusPipeline`） |
| TTS 队列 | `nexus_phone/.../nexus/service/TtsSpeakQueue.kt` |
| 旁路接线 | `nexus_phone/.../nexus/service/NexusBypassService.kt` |
| SSE 首包钩子 | `DeepSeekClient.chatStream(..., onFirstDelta)` / `CallSessionController.onLlmFirstDelta` |
| VAD | `EnergyVad.Config.silenceEndMs = 300` |
