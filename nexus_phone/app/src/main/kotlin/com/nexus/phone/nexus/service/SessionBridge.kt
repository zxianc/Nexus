package com.nexus.phone.nexus.service

import com.nexus.phone.nexus.audio.AudioPipeline
import com.nexus.phone.nexus.protocol.ApcmHeader
import com.nexus.phone.nexus.protocol.PcmFrame

enum class SessionState {
    Idle,
    Connecting,
    Streaming,
}

/**
 * Pure session state + DL fan-out (JVM-testable). Socket I/O stays in [NexusBypassService].
 */
class SessionBridge(
    val pipeline: AudioPipeline = AudioPipeline(),
) {
    @Volatile
    var state: SessionState = SessionState.Idle
        private set

    @Volatile
    var header: ApcmHeader? = null
        private set

    fun onConnecting() {
        state = SessionState.Connecting
        header = null
        pipeline.reset()
    }

    fun onStreaming(hdr: ApcmHeader) {
        header = hdr
        pipeline.configure(hdr)
        state = SessionState.Streaming
    }

    fun onFrames(frames: List<PcmFrame>) {
        for (frame in frames) {
            if (frame is PcmFrame.PcmDl) {
                pipeline.offerDl(frame.pcm)
            }
        }
    }

    fun onIdle() {
        if (state == SessionState.Streaming) {
            pipeline.flush()
        }
        state = SessionState.Idle
        header = null
    }
}
