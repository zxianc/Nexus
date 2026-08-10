package com.nexus.tim.bridge.state

data class MeInfo(
    val userId: String = "",
    val nick: String = "",
)

data class BridgeState(
    val supportedVersion: String,
    @Volatile var hookConnected: Boolean = false,
    @Volatile var loggedIn: Boolean = false,
    @Volatile var timVersion: String? = null,
    @Volatile var recvHook: Boolean = false,
    @Volatile var me: MeInfo = MeInfo(),
) {
    companion object {
        const val DEFAULT_SUPPORTED_VERSION = "4.1.0"
    }
}
