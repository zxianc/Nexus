package com.nexus.wechat.bridge.state

data class BridgeState(
    val supportedVersion: String,
    @Volatile var hookConnected: Boolean = false,
    @Volatile var loggedIn: Boolean = false,
    @Volatile var wechatVersion: String? = null,
) {
    companion object {
        /** Filled when a WeChat APK is pinned; mismatch gates sends. */
        const val DEFAULT_SUPPORTED_VERSION = "UNPINNED"
    }
}
