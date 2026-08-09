package com.nexus.wechat.bridge.state

data class BridgeState(
    val supportedVersion: String,
    @Volatile var hookConnected: Boolean = false,
    @Volatile var loggedIn: Boolean = false,
    @Volatile var wechatVersion: String? = null,
) {
    companion object {
        /** Must match wechat_hook SupportedWeChat.VERSION_NAME. */
        const val DEFAULT_SUPPORTED_VERSION = "8.0.76"
    }
}
