package com.nexus.wechat.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDirectoryFilterTest {
    @Test
    fun friendCandidates_keepNormalWxid() {
        assertTrue(ContactDirectoryFilter.isPrivateFriendCandidate("wxid_abc"))
        assertTrue(ContactDirectoryFilter.isPrivateFriendCandidate("name123"))
    }

    @Test
    fun friendCandidates_rejectRoomsAndNoise() {
        assertFalse(ContactDirectoryFilter.isPrivateFriendCandidate("123@chatroom"))
        assertFalse(ContactDirectoryFilter.isPrivateFriendCandidate("gh_official"))
        assertFalse(ContactDirectoryFilter.isPrivateFriendCandidate("filehelper"))
        assertFalse(ContactDirectoryFilter.isPrivateFriendCandidate("weixin"))
        assertFalse(ContactDirectoryFilter.isPrivateFriendCandidate("fake_x"))
        assertFalse(ContactDirectoryFilter.isPrivateFriendCandidate("shakeapp"))
        assertFalse(ContactDirectoryFilter.isPrivateFriendCandidate("officialaccounts"))
        assertFalse(ContactDirectoryFilter.isPrivateFriendCandidate(""))
    }

    @Test
    fun chatroomId() {
        assertTrue(ContactDirectoryFilter.isChatroomId("2618@chatroom"))
        assertFalse(ContactDirectoryFilter.isChatroomId("wxid_a"))
    }
}
