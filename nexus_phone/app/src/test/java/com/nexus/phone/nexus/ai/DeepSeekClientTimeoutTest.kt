package com.nexus.phone.nexus.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekClientTimeoutTest {
    @Test
    fun readTimeoutIsShortEnoughNotToBlockWholeCall() {
        // Call turns are often <20s; 60s read timeout left aiBusy stuck on hung streams.
        assertTrue(DeepSeekClient.DEFAULT_READ_TIMEOUT_MS <= 15_000)
        assertTrue(DeepSeekClient.DEFAULT_READ_TIMEOUT_MS >= 5_000)
    }

    @Test
    fun connectTimeoutStaysTight() {
        assertEquals(8_000, DeepSeekClient.DEFAULT_CONNECT_TIMEOUT_MS)
    }
}
