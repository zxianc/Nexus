package com.nexus.wechat.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAtSendTest {
    @Test
    fun normalize_trimsDedupesAndKeepsNotifyAll() {
        val ats = GroupAtSend.normalizeAts(
            listOf("  wxid_a ", "wxid_b", "wxid_a", "", "notify@all"),
        )
        assertEquals(listOf("wxid_a", "wxid_b", "notify@all"), ats)
    }

    @Test
    fun atuserlistCsv_joinsWithComma() {
        assertEquals(
            "wxid_a,wxid_b",
            GroupAtSend.atuserlistCsv(listOf("wxid_a", "wxid_b")),
        )
        assertEquals("notify@all", GroupAtSend.atuserlistCsv(listOf("notify@all")))
        assertEquals("", GroupAtSend.atuserlistCsv(emptyList()))
    }

    @Test
    fun atuserlistCdata_wrapsCsv() {
        assertEquals(
            "<![CDATA[wxid_a,notify@all]]>",
            GroupAtSend.atuserlistCdata("wxid_a,notify@all"),
        )
    }

    @Test
    fun formatContent_prependsAtDisplayWithFourPerEmSpace() {
        val text = GroupAtSend.formatContent(
            text = "hello",
            displays = listOf("哈喽", "所有人"),
        )
        assertEquals("@哈喽\u2005@所有人\u2005hello", text)
    }

    @Test
    fun formatContent_skipsWhenAlreadyPrefixed() {
        val existing = "@哈喽\u2005hello"
        val text = GroupAtSend.formatContent(
            text = existing,
            displays = listOf("哈喽"),
        )
        assertEquals(existing, text)
    }

    @Test
    fun displayForAt_notifyAllIsEveryone() {
        assertEquals("所有人", GroupAtSend.displayForAt("notify@all") { "ignored" })
        assertEquals("Alice", GroupAtSend.displayForAt("wxid_a") { "Alice" })
    }

    @Test
    fun build_emptyAtsLeavesText() {
        val built = GroupAtSend.build(text = "plain", ats = emptyList()) { it }
        assertEquals("plain", built.content)
        assertEquals("", built.atuserlist)
        assertFalse(built.hasAts)
        assertTrue(built.mentions.isEmpty())
    }

    @Test
    fun build_withAtsProducesCsvMentionsAndCdata() {
        val built = GroupAtSend.build(
            text = "hi",
            ats = listOf("wxid_a", "notify@all"),
        ) { id -> if (id == "wxid_a") "哈喽" else id }
        assertTrue(built.hasAts)
        assertEquals("wxid_a,notify@all", built.atuserlist)
        assertEquals("<![CDATA[wxid_a,notify@all]]>", built.atuserlistCdata)
        assertEquals("@哈喽\u2005@所有人\u2005hi", built.content)
        assertEquals(
            listOf(
                GroupAtSend.Mention("wxid_a", "哈喽"),
                GroupAtSend.Mention("notify@all", "所有人"),
            ),
            built.mentions,
        )
    }
}
