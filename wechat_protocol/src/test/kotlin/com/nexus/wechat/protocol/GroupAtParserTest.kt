package com.nexus.wechat.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAtParserTest {
    @Test
    fun empty_noAts() {
        val r = GroupAtParser.parse(msgSource = null, lvbuffer = null, selfId = "wxid_me")
        assertTrue(r.ats.isEmpty())
        assertFalse(r.atMe)
        assertFalse(r.atAll)
    }

    @Test
    fun msgsource_atuserlist_single() {
        val src = "<msgsource><atuserlist>wxid_a</atuserlist></msgsource>"
        val r = GroupAtParser.parse(msgSource = src, lvbuffer = null, selfId = "wxid_me")
        assertEquals(listOf("wxid_a"), r.ats)
        assertFalse(r.atMe)
        assertFalse(r.atAll)
    }

    @Test
    fun msgsource_at_me() {
        val src = "<msgsource><atuserlist>wxid_a,wxid_me</atuserlist></msgsource>"
        val r = GroupAtParser.parse(msgSource = src, lvbuffer = null, selfId = "wxid_me")
        assertEquals(listOf("wxid_a", "wxid_me"), r.ats)
        assertTrue(r.atMe)
    }

    @Test
    fun notify_all() {
        val src = "<msgsource><atuserlist>notify@all</atuserlist></msgsource>"
        val r = GroupAtParser.parse(msgSource = src, lvbuffer = null, selfId = "wxid_me")
        assertEquals(listOf("notify@all"), r.ats)
        assertTrue(r.atAll)
        assertTrue(r.atMe)
    }

    @Test
    fun lvbuffer_utf8_contains_atuserlist() {
        val blob = "<msgsource><atuserlist>wxid_me,wxid_b</atuserlist></msgsource>".toByteArray()
        val r = GroupAtParser.parse(msgSource = null, lvbuffer = blob, selfId = "wxid_me")
        assertTrue(r.atMe)
        assertEquals(listOf("wxid_me", "wxid_b"), r.ats)
    }

    @Test
    fun nested_xml_prefix() {
        val src = "<?xml version=\"1.0\"?><root><msgsource><atuserlist><![CDATA[wxid_me]]></atuserlist></msgsource></root>"
        val r = GroupAtParser.parse(msgSource = src, lvbuffer = null, selfId = "wxid_me")
        assertTrue(r.atMe)
        assertEquals(listOf("wxid_me"), r.ats)
    }
}
