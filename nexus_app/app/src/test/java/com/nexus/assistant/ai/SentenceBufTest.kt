package com.nexus.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceBufTest {
    @Test
    fun emitsOnChinesePunctuation() {
        val out = ArrayList<String>()
        val buf = SentenceBuf { out.add(it) }
        buf.push("你好。世界！")
        assertEquals(listOf("你好。", "世界！"), out)
    }

    @Test
    fun flushEmitsRemainder() {
        val out = ArrayList<String>()
        val buf = SentenceBuf { out.add(it) }
        buf.push("还没结束")
        assertEquals(emptyList<String>(), out)
        buf.flush()
        assertEquals(listOf("还没结束"), out)
    }

    @Test
    fun skipsPunctuationOnly() {
        val out = ArrayList<String>()
        val buf = SentenceBuf { out.add(it) }
        buf.push("。。。")
        buf.flush()
        assertEquals(emptyList<String>(), out)
    }
}
