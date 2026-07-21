package com.nexus.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


class CallSessionTest {
    @Test
    fun messagesIncludeSystemAndHistory() {
        val s = CallSession(maxMessages = 4)
        s.appendUser("你好")
        s.appendAssistant("您好")
        val msgs = s.messages("系统")
        assertEquals("system", msgs[0].role)
        assertTrue(msgs[0].content.startsWith("系统"))
        assertTrue(msgs[0].content.contains("当前时间："))
        assertEquals("user", msgs[1].role)
        assertEquals("assistant", msgs[2].role)
    }

    @Test
    fun trimsToMaxMessages() {
        val s = CallSession(maxMessages = 2)
        s.appendUser("a")
        s.appendAssistant("b")
        s.appendUser("c")
        val msgs = s.messages("sys").filter { it.role != "system" }
        assertEquals(2, msgs.size)
        assertEquals("b", msgs[0].content)
        assertEquals("c", msgs[1].content)
    }

    @Test
    fun resetBumpsGeneration() {
        val s = CallSession()
        val g0 = s.generation
        s.appendUser("hi")
        s.reset()
        assertTrue(s.generation > g0)
        assertEquals(1, s.messages("sys").size) // only system
        assertFalse(s.appendAssistantGen(g0, "stale"))
    }
}
