package com.nexus.wechat.bridge.uds

import com.nexus.wechat.bridge.state.BridgeState
import com.nexus.wechat.bridge.store.EventStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HookSessionTest {
    @Test
    fun sendText_withoutHook_failsUnavailable() {
        val session = HookSession(BridgeState(supportedVersion = "x"), EventStore())
        val r = session.requestSendText("wxid_a", "hi", emptyList(), timeoutMs = 100)
        assertFalse(r.ok)
        assertEquals("hook_unavailable", r.error)
    }
}
