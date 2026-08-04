package com.nexus.phone.nexus.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun emitsOnCommaForLowerLatency() {
        val out = ArrayList<String>()
        val buf = SentenceBuf { out.add(it) }
        buf.push("好的，请放门口。")
        assertEquals(listOf("好的，", "请放门口。"), out)
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

    @Test
    fun rejectsLatinNoiseLikeSenseVoiceFalsePositive() {
        assertFalse(SentenceBuf.hasSpeechRune("A."))
        assertFalse(SentenceBuf.hasSpeechRune("A"))
        assertFalse(SentenceBuf.hasSpeechRune("ok"))
        assertFalse(SentenceBuf.hasSpeechRune("123"))
        assertFalse(SentenceBuf.hasSpeechRune(""))
    }

    @Test
    fun acceptsChineseSpeech() {
        assertTrue(SentenceBuf.hasSpeechRune("你好"))
        assertTrue(SentenceBuf.hasSpeechRune("放门口"))
        assertTrue(SentenceBuf.hasSpeechRune("嗯。"))
    }
}
