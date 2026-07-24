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
