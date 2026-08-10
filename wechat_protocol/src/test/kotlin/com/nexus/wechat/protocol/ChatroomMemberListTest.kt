package com.nexus.wechat.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatroomMemberListTest {
    @Test
    fun empty_returnsEmpty() {
        assertTrue(ChatroomMemberList.parse(null).isEmpty())
        assertTrue(ChatroomMemberList.parse("").isEmpty())
        assertTrue(ChatroomMemberList.parse("   ").isEmpty())
    }

    @Test
    fun semicolonSeparated() {
        assertEquals(
            listOf("wxid_a", "wxid_b", "wxid_c"),
            ChatroomMemberList.parse("wxid_a;wxid_b;wxid_c"),
        )
    }

    @Test
    fun trailingAndDuplicateSeparators() {
        assertEquals(
            listOf("wxid_a", "wxid_b"),
            ChatroomMemberList.parse("wxid_a;;wxid_b;"),
        )
    }

    @Test
    fun commaFallback() {
        assertEquals(
            listOf("wxid_a", "wxid_b"),
            ChatroomMemberList.parse("wxid_a,wxid_b"),
        )
    }
}
