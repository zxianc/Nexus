package com.nexus.tim.protocol

/** Shared IPC endpoints between tim_bridge and tim_hook. */
object TimIpc {
    /**
     * Loopback TCP for Hook ↔ Bridge control plane.
     * Abstract UDS is SELinux-denied for TIM; filesystem LocalSocket has no public accept API.
     */
    const val TCP_HOST = "127.0.0.1"
    const val TCP_PORT = 18788
}
