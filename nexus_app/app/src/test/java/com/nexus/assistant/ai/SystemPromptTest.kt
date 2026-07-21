package com.nexus.assistant.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SystemPromptTest {
    @Test
    fun expandReplacesNowPlaceholder() {
        val cal =
            Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
                set(2026, Calendar.JULY, 21, 15, 30, 0)
            }
        val out = SystemPrompt.expand("时间：{{NOW}}", cal.time)
        assertTrue(out.contains("2026年7月21日"))
        assertTrue(out.contains("15:30"))
        assertTrue(out.contains("工作日") || out.contains("休息日"))
    }
}
