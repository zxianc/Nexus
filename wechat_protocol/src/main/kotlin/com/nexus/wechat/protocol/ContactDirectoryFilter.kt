package com.nexus.wechat.protocol

/** Username filters for address-book style lists (not recent chats). */
object ContactDirectoryFilter {
    private val SYSTEM_IDS = setOf(
        "filehelper", "weixin", "newsapp", "fmessage", "medianote", "floatbottle",
        "qmessage", "qqmail", "qqfriend", "officialaccounts", "service_officialaccounts",
        "notifymessage", "schedule_message", "opencustomerservicemsg",
        "appbrandcustomerservicemsg", "appbrand_notify_message",
    )

    fun isPrivateFriendCandidate(username: String): Boolean {
        val id = username.trim()
        if (id.isEmpty()) return false
        if (id.contains('@')) return false
        if (id in SYSTEM_IDS) return false
        if (id.startsWith("gh_") || id.startsWith("fake_")) return false
        // Built-in plugins: shakeapp, voipapp, feedsapp, …
        if (id.endsWith("app") || id.endsWith("plugin")) return false
        return true
    }

    fun isChatroomId(username: String): Boolean = username.trim().endsWith("@chatroom")
}
