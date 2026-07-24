# Call pipeline latency: timing + TTS queue + VAD — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 降低 AI 通话「开口慢」体感：补充分阶段耗时日志、把 TTS/注入从 SSE 读线程解耦、把 VAD 尾静音从 500ms 收到约 300ms。

**Architecture:** 在 `NexusBypassService` 旁路路径上，`aiExecutor` 只做 ASR + DeepSeek SSE；句子文本进入 `TtsSpeakQueue`，由独立 `ttsExecutor` 串行合成并 `injectTts`。`EnergyVad` 默认 `silenceEndMs=300`。用 `PipelineTiming` 打出 VAD→ASR→TTFT→句切→TTS→首包注入的毫秒差，便于真机核对收益。

**Tech Stack:** Kotlin、JUnit4（`app/src/test`）、现有 `sherpa-onnx` / `NexusBypassService` / `EnergyVad`；不改 HAL、不改 DeepSeek 模型名。

**范围（仅这三点）：**

| # | 项 | 本计划 |
|---|-----|--------|
| 1 | 分阶段耗时埋点 | 做 |
| 2 | TTS/注入与 SSE 解耦 | 做 |
| 3 | VAD `silenceEndMs` → 300ms | 做 |
| — | barge-in、流式 TTS、cooldown 调参、增益 | **不做** |

## Global Constraints

- 包：`com.nexus.phone.nexus.*`；工程：`nexus_phone/`
- 分支：`feature/nexus-phone-fossify`（或当前开发分支）
- 不改 `zygisk_module/`；不换 DeepSeek 模型；不强制改 system prompt
- TTS 仍整句 `SherpaTts.synthesize`（流式合成另开计划）
- `injectTts` / echo guard（`beginTtsGuard` / `endTtsGuard` / `TTS_ECHO_COOLDOWN_MS`）语义保持
- Release R8：新建类若反射无关，无需额外 ProGuard
- 单测命令（Windows）：`cd nexus_phone` 后 `.\gradlew.bat :app:testCoreDebugUnitTest --tests <fqcn>`

---

## File map

| Path | Responsibility |
|------|----------------|
| `nexus/.../ai/PipelineTiming.kt` | 一轮 utterance 的阶段时间戳与 `Log.i` 汇总 |
| `nexus/.../service/TtsSpeakQueue.kt` | 句子队列 + 单消费者线程；cancel / awaitIdle |
| `nexus/.../service/NexusBypassService.kt` | 接线：埋点、队列、greeting/fallback 走队列 |
| `nexus/.../ai/DeepSeekClient.kt` | SSE 首 delta 回调 / 埋点钩子（可选经 `PipelineTiming`） |
| `nexus/.../ai/SherpaTts.kt` | synthesize 前后耗时可经外部传入，或仅由 Service 包一层 |
| `nexus/.../audio/EnergyVad.kt` | 默认 `silenceEndMs = 300` |
| `nexus/.../audio/EnergyVadTest.kt` | 静音结束阈值单测 |
| `nexus/.../service/TtsSpeakQueueTest.kt` | 队列顺序、cancel、awaitIdle 单测 |

```text
PCM_DL → EnergyVad(silenceEndMs=300)
      → aiExecutor: ASR → DeepSeek SSE → SentenceBuf
      → TtsSpeakQueue.offer(sentence)
      → ttsExecutor: synthesize → injectTts
```

---

### Task 1: `PipelineTiming` 埋点工具

**Files:**
- Create: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/ai/PipelineTiming.kt`
- Create: `nexus_phone/app/src/test/java/com/nexus/phone/nexus/ai/PipelineTimingTest.kt`

**Interfaces:**
- Consumes: `android.util.Log`（单测用可测的 wall-clock 注入）
- Produces: `class PipelineTiming(private val tag: String = "NexusPipeline", private val nowMs: () -> Long = { System.currentTimeMillis() })` with:
  - `fun mark(stage: String)`
  - `fun summary(extra: String = "")` → 打一条汇总 log，返回 `"stage=Δms,..."` 字符串供断言

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexus.phone.nexus.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTimingTest {
    @Test
    fun summary_includesStageDeltas() {
        var t = 1000L
        val timing = PipelineTiming(tag = "TestPipe", nowMs = { t })
        timing.mark("vad")
        t = 1300L
        timing.mark("asr_done")
        t = 1800L
        timing.mark("llm_first")
        val s = timing.summary("sid=1")
        assertTrue(s.contains("vad"))
        assertTrue(s.contains("asr_done=+300"))
        assertTrue(s.contains("llm_first=+500"))
        assertTrue(s.contains("sid=1"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
cd e:\workspace\Nexus\nexus_phone
.\gradlew.bat :app:testCoreDebugUnitTest --tests com.nexus.phone.nexus.ai.PipelineTimingTest
```

Expected: FAIL（类不存在）

- [ ] **Step 3: Minimal implementation**

```kotlin
package com.nexus.phone.nexus.ai

import android.util.Log

/**
 * One-turn call-pipeline stopwatch. Stages are ordered by [mark] call order.
 * Log line example:
 * `NexusPipeline turn vad=0 asr_done=+320 llm_first=+410 sent=+80 tts_done=+260 inject=+15 total=1085 sid=0`
 */
class PipelineTiming(
    private val tag: String = "NexusPipeline",
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val t0 = nowMs()
    private val marks = ArrayList<Pair<String, Long>>()

    fun mark(stage: String) {
        marks.add(stage to nowMs())
    }

    fun summary(extra: String = ""): String {
        val parts = ArrayList<String>()
        var prev = t0
        for ((name, at) in marks) {
            parts.add("$name=+${at - prev}")
            prev = at
        }
        val total = (marks.lastOrNull()?.second ?: t0) - t0
        val body =
            buildString {
                append("turn")
                if (parts.isNotEmpty()) {
                    append(' ')
                    append(parts.joinToString(" "))
                }
                append(" total=")
                append(total)
                if (extra.isNotEmpty()) {
                    append(' ')
                    append(extra)
                }
            }
        Log.i(tag, body)
        return body
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Same gradle command. Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/ai/PipelineTiming.kt \
  nexus_phone/app/src/test/java/com/nexus/phone/nexus/ai/PipelineTimingTest.kt
git commit -m "feat(nexus_phone): add PipelineTiming for call latency stages"
```

---

### Task 2: `TtsSpeakQueue`（SSE 与播报解耦的核心）

**Files:**
- Create: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/service/TtsSpeakQueue.kt`
- Create: `nexus_phone/app/src/test/java/com/nexus/phone/nexus/service/TtsSpeakQueueTest.kt`

**Interfaces:**
- Consumes: 无 Android Framework 硬依赖（便于 JVM 单测）；播报副作用由构造注入
- Produces:

```kotlin
class TtsSpeakQueue(
    private val speak: (text: String) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    fun start()
    fun offer(text: String)
    fun clear()
    /** Block until queue empty and no in-flight speak (or timeout). */
    fun awaitIdle(timeoutMs: Long = 120_000L): Boolean
    fun shutdown()
}
```

行为约定：

1. `start()` 启动**单**消费者线程；未 start 时 `offer` 丢弃并打 log（或抛错，二选一：**丢弃 + Log.w**）。
2. `offer` 非阻塞入队；消费者按 FIFO 调用 `speak(text)`（`speak` 内同步完成 synthesize+inject）。
3. `clear()` 清空等待队列；**不**打断正在执行的 `speak`（本计划不做 barge-in）。
4. `awaitIdle`：队列空且无 in-flight 时返回 true；超时 false。
5. `shutdown()`：clear + 停线程（interrupt + join 短超时）。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexus.phone.nexus.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TtsSpeakQueueTest {
    @Test
    fun offer_speaksInOrder_thenAwaitIdle() {
        val heard = CopyOnWriteArrayList<String>()
        val q =
            TtsSpeakQueue(
                speak = { text ->
                    Thread.sleep(20)
                    heard.add(text)
                },
            )
        q.start()
        q.offer("一")
        q.offer("二")
        assertTrue(q.awaitIdle(5_000))
        assertEquals(listOf("一", "二"), heard.toList())
        q.shutdown()
    }

    @Test
    fun clear_dropsPending_butKeepsInFlight() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val heard = CopyOnWriteArrayList<String>()
        val q =
            TtsSpeakQueue(
                speak = { text ->
                    started.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    heard.add(text)
                },
            )
        q.start()
        q.offer("A")
        assertTrue(started.await(2, TimeUnit.SECONDS))
        q.offer("B")
        q.clear()
        release.countDown()
        assertTrue(q.awaitIdle(5_000))
        assertEquals(listOf("A"), heard.toList())
        q.shutdown()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
.\gradlew.bat :app:testCoreDebugUnitTest --tests com.nexus.phone.nexus.service.TtsSpeakQueueTest
```

Expected: FAIL

- [ ] **Step 3: Minimal implementation**

实现要点（完整代码写入仓库时按此语义）：

```kotlin
package com.nexus.phone.nexus.service

import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TtsSpeakQueue(
    private val speak: (text: String) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    private val queue = LinkedBlockingQueue<String>()
    private val started = AtomicBoolean(false)
    private val shutdown = AtomicBoolean(false)
    private val inFlight = AtomicInteger(0)
    private val idleLock = Object()
    private var thread: Thread? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        shutdown.set(false)
        thread =
            Thread(
                {
                    while (!shutdown.get()) {
                        val text =
                            try {
                                queue.poll(100, TimeUnit.MILLISECONDS)
                            } catch (_: InterruptedException) {
                                break
                            } ?: continue
                        inFlight.incrementAndGet()
                        try {
                            speak(text)
                        } catch (t: Throwable) {
                            onError(t)
                        } finally {
                            inFlight.decrementAndGet()
                            synchronized(idleLock) { idleLock.notifyAll() }
                        }
                    }
                },
                "NexusTtsSpeak",
            ).also {
                it.isDaemon = true
                it.start()
            }
    }

    fun offer(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (!started.get() || shutdown.get()) {
            Log.w(TAG, "offer dropped (not started): ${t.take(24)}")
            return
        }
        queue.offer(t)
    }

    fun clear() {
        queue.clear()
    }

    fun awaitIdle(timeoutMs: Long = 120_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(idleLock) {
            while (true) {
                if (queue.isEmpty() && inFlight.get() == 0) return true
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                idleLock.wait(left.coerceAtMost(100))
            }
        }
    }

    fun shutdown() {
        shutdown.set(true)
        clear()
        thread?.interrupt()
        try {
            thread?.join(1_000)
        } catch (_: InterruptedException) {
        }
        thread = null
        started.set(false)
    }

    companion object {
        private const val TAG = "TtsSpeakQueue"
    }
}
```

注意：`awaitIdle` 循环里在 `queue`/`inFlight` 变化时依赖 `notifyAll`；`offer` 后也需 `synchronized(idleLock) { idleLock.notifyAll() }`，否则可能干等——实现时补上。

- [ ] **Step 4: Run tests — PASS**

- [ ] **Step 5: Commit**

```bash
git add nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/service/TtsSpeakQueue.kt \
  nexus_phone/app/src/test/java/com/nexus/phone/nexus/service/TtsSpeakQueueTest.kt
git commit -m "feat(nexus_phone): TtsSpeakQueue to decouple TTS from LLM SSE"
```

---

### Task 3: 接入 `NexusBypassService`（埋点 + 队列）

**Files:**
- Modify: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/service/NexusBypassService.kt`
- Modify: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/ai/DeepSeekClient.kt`（增加可选 `onFirstDelta: (() -> Unit)? = null`）
- Modify: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/ai/CallSessionController.kt`（把 `onFirstDelta` 传到 client，或由 Service 包一层）

**Interfaces:**
- Consumes: `PipelineTiming`（Task 1）、`TtsSpeakQueue`（Task 2）
- Produces: 行为变更——句回调只 `offer`；`chatStream` 返回后 `awaitIdle` 再清 `aiBusy`

- [ ] **Step 1: Extend `DeepSeekClient.chatStream` for first-delta hook**

```kotlin
fun chatStream(
    messages: List<ChatMessage>,
    onSentence: (String) -> Unit,
    onFirstDelta: (() -> Unit)? = null,
): String {
    // ...
    var first = true
    // inside delta loop, after extractDeltaContent:
    if (first) {
        first = false
        onFirstDelta?.invoke()
    }
    sentences.push(delta)
}
```

`CallSessionController.onUserUtterance` 增加可选参数或成员回调：

```kotlin
var onLlmFirstDelta: (() -> Unit)? = null

// in chatStream call:
c.chatStream(msgs, onSentence = { ... }, onFirstDelta = { onLlmFirstDelta?.invoke() })
```

- [ ] **Step 2: Wire queue + timing in `NexusBypassService`**

字段：

```kotlin
private var speakQueue: TtsSpeakQueue? = null
```

在 `ensureAiLoaded` / 会话开始（`runOneConnection` 里 `ensureAiLoaded` 之后）创建并 `start()`；在 `releaseAi` / `teardown` 里 `shutdown()`。

`speak` 闭包：

```kotlin
speak = { text ->
    val audio = tts?.synthesize(text, sid = currentTtsSpeakerId()) ?: return@TtsSpeakQueue
    val c = client ?: return@TtsSpeakQueue
    injectTts(c, audio.samples, audio.sampleRate)
}
```

创建 `CallSessionController` 时：

```kotlin
callLlm = CallSessionController(llmCfg, ds) { sentence ->
    speakQueue?.offer(sentence)
}
```

改写 `enqueueUtterance`：

```kotlin
aiExecutor.execute {
    val timing = PipelineTiming()
    timing.mark("vad_emit")
    try {
        // ... suppress / busy checks unchanged ...
        val text = asr?.transcribe(u.pcm16k).orEmpty().trim()
        timing.mark("asr_done")
        Log.i(TAG, "ASR text='$text'")
        // ... speech rune checks ...
        callLlm?.onLlmFirstDelta = { timing.mark("llm_first") }
        val full = llm.onUserUtterance(text)
        timing.mark("llm_done")
        val idle = speakQueue?.awaitIdle(120_000L) == true
        timing.mark("tts_idle")
        timing.summary(
            "asrChars=${text.length} replyChars=${full.length} idleOk=$idle",
        )
        if (full.isBlank()) fallbackListenRetry()
    } finally {
        callLlm?.onLlmFirstDelta = null
        aiBusy.set(false)
    }
}
```

在 `TtsSpeakQueue.speak` 内可再包局部计时（可选）：`Log.i(TAG, "tts_sentence ms=… chars=…")`。

greeting / `fallbackListenRetry`：改为 `speakQueue?.offer(...)` + 必要时 `awaitIdle`，**不要**再在 `aiExecutor` 上同步 `synthesize`（避免与队列抢同一 `SherpaTts` 锁死序）。`SherpaTts` 已有内部 `lock`，队列单线程消费即可保证串行。

`releaseAi`：

```kotlin
speakQueue?.shutdown()
speakQueue = null
```

- [ ] **Step 3: Compile**

```powershell
.\gradlew.bat :app:compileCoreDebugKotlin
```

Expected: SUCCESS

- [ ] **Step 4: Unit tests still green**

```powershell
.\gradlew.bat :app:testCoreDebugUnitTest --tests com.nexus.phone.nexus.ai.PipelineTimingTest
.\gradlew.bat :app:testCoreDebugUnitTest --tests com.nexus.phone.nexus.service.TtsSpeakQueueTest
```

- [ ] **Step 5: Commit**

```bash
git add nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/service/NexusBypassService.kt \
  nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/ai/DeepSeekClient.kt \
  nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/ai/CallSessionController.kt
git commit -m "feat(nexus_phone): async TTS queue and pipeline timing on call path"
```

---

### Task 4: VAD `silenceEndMs` 默认 300

**Files:**
- Modify: `nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/audio/EnergyVad.kt`
- Create: `nexus_phone/app/src/test/java/com/nexus/phone/nexus/audio/EnergyVadTest.kt`

**Interfaces:**
- Consumes: 现有 `EnergyVad.Config`
- Produces: 默认 `silenceEndMs = 300`（`minSpeechMs` 保持 500，本任务不改）

- [ ] **Step 1: Write failing test（用短 Config 加速）**

构造：`speechRms` 低阈值、帧 20ms。推入：足够长的「语音」帧（高幅值）→ 再推入静音帧。

- 配置 `silenceEndMs = 300` 时：静音约 300ms 后应得到 1 个 `Utterance`
- 若误用 500：同样 300ms 静音不应结束（对照断言）

```kotlin
package com.nexus.phone.nexus.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class EnergyVadTest {
    private fun toneFrame(samples: Int = 320, amp: Int = 5000): ShortArray {
        val out = ShortArray(samples)
        for (i in out.indices) {
            out[i] = (sin(i * 0.5) * amp).toInt().toShort()
        }
        return out
    }

    private fun silenceFrame(samples: Int = 320): ShortArray = ShortArray(samples)

    @Test
    fun silenceEnd_300ms_endsUtterance() {
        val vad =
            EnergyVad(
                EnergyVad.Config(
                    speechRms = 400.0,
                    silenceRms = 250.0,
                    minSpeechMs = 100,
                    silenceEndMs = 300,
                    maxSpeechMs = 8000,
                    preRollMs = 0,
                ),
            )
        // ~200ms speech
        repeat(10) { vad.push(toneFrame()) }
        // 200ms silence — should NOT end yet
        var utts = emptyList<Utterance>()
        repeat(10) { utts = utts + vad.push(silenceFrame()) }
        assertTrue(utts.isEmpty())
        // +100ms silence → total 300ms → end
        repeat(5) { utts = utts + vad.push(silenceFrame()) }
        assertEquals(1, utts.size)
    }
}
```

若默认 Config 已是 300，另加：

```kotlin
@Test
fun defaultSilenceEndMs_is300() {
    assertEquals(300, EnergyVad.Config().silenceEndMs)
}
```

- [ ] **Step 2: Run test — 在改默认前，`defaultSilenceEndMs_is300` 应 FAIL**

- [ ] **Step 3: Change default**

```kotlin
val silenceEndMs: Int = 300,
```

in `EnergyVad.Config`.

确认 `AudioPipeline` 使用默认 `EnergyVad()`、未硬编码 500。

- [ ] **Step 4: Run EnergyVadTest — PASS**

```powershell
.\gradlew.bat :app:testCoreDebugUnitTest --tests com.nexus.phone.nexus.audio.EnergyVadTest
```

- [ ] **Step 5: Commit**

```bash
git add nexus_phone/app/src/main/kotlin/com/nexus/phone/nexus/audio/EnergyVad.kt \
  nexus_phone/app/src/test/java/com/nexus/phone/nexus/audio/EnergyVadTest.kt
git commit -m "perf(nexus_phone): shorten VAD silenceEndMs default to 300ms"
```

---

### Task 5: 真机验收 + 文档一行

**Files:**
- Modify: `docs/superpowers/plans/2026-07-25-call-pipeline-latency.md`（本文件勾选）或在 `doc/00_framework_overview.md` 旁路一节加一句「延迟埋点 tag=`NexusPipeline`」——**仅一行**，避免大文档改动。

- [ ] **Step 1: 组装安装**

```powershell
cd e:\workspace\Nexus\nexus_phone
.\gradlew.bat :app:assembleCoreRelease
adb install -r app\build\outputs\apk\core\release\phone-*-core-release.apk
```

- [ ] **Step 2: 真机一轮 AI 来电**

```powershell
adb logcat -s NexusPipeline:I NexusBypass:I TtsSpeakQueue:I SherpaTts:I DeepSeekClient:I
```

验收：

1. 有 `NexusPipeline turn … asr_done=+… llm_first=+… tts_idle=+… total=…`
2. 多句回复时，`llm_done` 相对 `llm_first` 应明显短于「整段播完」时间（SSE 不再被整句 TTS 卡住）
3. 对方停顿后进 ASR 的等待体感短于改前（约少 ~200ms 静音税）
4. 无崩溃；echo guard 仍抑制 TTS 回灌（无「自己跟自己聊」）

- [ ] **Step 3: 若半句切断过多**  
  将默认改为 `350`（仍在 250–350 目标带内），补测后提交 `fix` commit。

- [ ] **Step 4: Commit docs（若有）并 push（仅当用户要求 push）

```bash
git add doc/00_framework_overview.md
git commit -m "docs: note NexusPipeline latency log tag for call bypass"
```

---

## Self-review

| 需求 | 对应 Task |
|------|-----------|
| 1 分阶段耗时 | Task 1 + Task 3 接线 |
| 2 TTS/注入与 SSE 解耦 | Task 2 + Task 3 |
| 3 VAD 尾静音缩短 | Task 4（默认 300） |
| 真机验证 | Task 5 |

无 TBD/占位；类型名 `PipelineTiming` / `TtsSpeakQueue` / `awaitIdle` 前后一致。未包含 barge-in、流式 TTS、cooldown——属范围外。

---

## 风险与回滚

| 风险 | 缓解 |
|------|------|
| 300ms 静音导致半句 ASR | Task 5 可调到 350；或临时改回 500 |
| 队列与 `aiBusy` 时序：LLM 结束但还在播 | `awaitIdle` 后再清 busy，避免叠轮 |
| `SherpaTts` 并发 | 仅 `ttsExecutor`/队列消费者调用 synthesize |
| greeting 与 utterance 抢队列 | 同队列 FIFO；会话开始 greeting 先 offer |

回滚：恢复 `silenceEndMs=500`；`onAssistantSentence` 改回同步 `synthesize`+`injectTts`；去掉 queue。
