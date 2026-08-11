package com.nexus.tim.bridge.state

data class MeInfo(
    val userId: String = "",
    val nick: String = "",
)

data class ContactInfo(
    val userId: String,
    val display: String,
)

data class ChatInfo(
    val chatId: String,
    val title: String,
    val isGroup: Boolean,
)

data class BridgeState(
    val supportedVersion: String,
    @Volatile var hookConnected: Boolean = false,
    @Volatile var loggedIn: Boolean = false,
    @Volatile var timVersion: String? = null,
    @Volatile var recvHook: Boolean = false,
    @Volatile var me: MeInfo = MeInfo(),
    /** Full friends from HELLO. */
    @Volatile var contacts: List<ContactInfo> = emptyList(),
    /** Full groups from HELLO. */
    @Volatile var groups: List<ChatInfo> = emptyList(),
) {
    companion object {
        const val DEFAULT_SUPPORTED_VERSION = "4.1.0"
    }
}
