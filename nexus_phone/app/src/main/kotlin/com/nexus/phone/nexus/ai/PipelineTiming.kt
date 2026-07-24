package com.nexus.phone.nexus.ai

import android.util.Log

/**
 * One-turn call-pipeline stopwatch. Stages are ordered by [mark] call order.
 * Log line example:
 * `NexusPipeline turn vad=+0 asr_done=+320 llm_first=+410 total=1085 sid=0`
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
        try {
            Log.i(tag, body)
        } catch (_: Throwable) {
            // android.util.Log is a stub on plain JVM unit tests
        }
        return body
    }
}
