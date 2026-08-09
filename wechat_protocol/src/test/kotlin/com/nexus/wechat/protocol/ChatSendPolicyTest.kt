package com.nexus.wechat.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSendPolicyTest {
    @Test
    fun filehelper_alwaysAllowed() {
        assertNull(ChatSendPolicy.validate("filehelper", contact = null, chatroomKnown = false))
    }

    @Test
    fun blank_rejected() {
        assertEquals("missing_fields", ChatSendPolicy.validate("", contact = null, chatroomKnown = false))
    }

    @Test
    fun strangerSuffix_rejected() {
        assertEquals(
            "unknown_chat",
            ChatSendPolicy.validate("xxx@stranger", contact = null, chatroomKnown = false),
        )
    }

    @Test
    fun unknownPersonal_rejected() {
        assertEquals(
            "unknown_chat",
            ChatSendPolicy.validate("wxid_not_exist", contact = null, chatroomKnown = false),
        )
    }

    @Test
    fun personalNotFriend_rejected() {
        assertEquals(
            "not_friend",
            ChatSendPolicy.validate(
                "wxid_a",
                contact = ChatSendPolicy.ContactRow(type = 0, deleteFlag = 0),
                chatroomKnown = false,
            ),
        )
    }

    @Test
    fun personalFriend_allowed() {
        assertNull(
            ChatSendPolicy.validate(
                "wxid_a",
                contact = ChatSendPolicy.ContactRow(type = 3, deleteFlag = 0),
                chatroomKnown = false,
            ),
        )
    }

    @Test
    fun deletedFriend_rejected() {
        assertEquals(
            "not_friend",
            ChatSendPolicy.validate(
                "wxid_a",
                contact = ChatSendPolicy.ContactRow(type = 3, deleteFlag = 1),
                chatroomKnown = false,
            ),
        )
    }

    @Test
    fun chatroomKnown_allowed() {
        assertNull(
            ChatSendPolicy.validate(
                "123@chatroom",
                contact = null,
                chatroomKnown = true,
            ),
        )
    }

    @Test
    fun chatroomUnknown_rejected() {
        assertEquals(
            "unknown_chat",
            ChatSendPolicy.validate(
                "999@chatroom",
                contact = null,
                chatroomKnown = false,
            ),
        )
    }

    @Test
    fun officialAccountInContacts_allowed() {
        assertNull(
            ChatSendPolicy.validate(
                "gh_abc",
                contact = ChatSendPolicy.ContactRow(type = 3, deleteFlag = 0),
                chatroomKnown = false,
            ),
        )
    }
}
