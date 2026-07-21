package com.nexus.assistant.service

import com.nexus.assistant.audio.AudioPipeline
import com.nexus.assistant.protocol.ApcmHeader
import com.nexus.assistant.protocol.PcmFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionBridgeTest {
    @Test
    fun lifecycle_idleToStreamingToIdle() {
        val bridge = SessionBridge(AudioPipeline())
        assertEquals(SessionState.Idle, bridge.state)

        bridge.onConnecting()
        assertEquals(SessionState.Connecting, bridge.state)

        bridge.onStreaming(ApcmHeader(48000, 2, 16, 1))
        assertEquals(SessionState.Streaming, bridge.state)
        assertEquals(48000, bridge.header!!.rate)

        bridge.onIdle()
        assertEquals(SessionState.Idle, bridge.state)
    }

    @Test
    fun onFrames_forwardsOnlyPcmDl() {
        val pipe = AudioPipeline()
        val bridge = SessionBridge(pipe)
        bridge.onStreaming(ApcmHeader(48000, 2, 16, 1))
        bridge.onFrames(
            listOf(
                PcmFrame.PcmDl(ByteArray(1920)),
                PcmFrame.CtrlMute(true),
                PcmFrame.PcmDl(ByteArray(960)),
            ),
        )
        assertEquals(2, pipe.totalDlFrames)
        assertEquals(2880L, pipe.totalDlBytes)
    }
}
