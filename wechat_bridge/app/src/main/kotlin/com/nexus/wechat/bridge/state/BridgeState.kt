package com.nexus.wechat.bridge.state

data class MeInfo(
    val userId: String = "",
    val nick: String = "",
)

data class MemberInfo(
    val userId: String,
    val display: String,
)

data class ChatInfo(
    val chatId: String,
    val title: String,
    val isGroup: Boolean,
    val members: List<MemberInfo> = emptyList(),
)

data class BridgeState(
    val supportedVersion: String,
    @Volatile var hookConnected: Boolean = false,
    @Volatile var loggedIn: Boolean = false,
    @Volatile var wechatVersion: String? = null,
    @Volatile var me: MeInfo = MeInfo(),
    @Volatile var chats: List<ChatInfo> = emptyList(),
) {
    fun membersOf(chatId: String): List<MemberInfo> =
        chats.firstOrNull { it.chatId == chatId }?.members.orEmpty()

    companion object {
        /** Must match wechat_hook SupportedWeChat.VERSION_NAME. */
        const val DEFAULT_SUPPORTED_VERSION = "8.0.76"
    }
}
