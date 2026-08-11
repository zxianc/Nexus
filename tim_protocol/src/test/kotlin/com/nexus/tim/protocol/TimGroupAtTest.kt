package com.nexus.tim.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class TimGroupAtTest {
    @Test
    fun normalizeAts_trimsDedupesAndMapsNotifyAll() {
        val ats = TimGroupAt.normalizeAts(
            listOf("  95019432 ", "123456", "95019432", "", "NOTIFY@ALL", "notify@all"),
        )
        assertEquals(listOf("95019432", "123456", TimGroupAt.NOTIFY_ALL), ats)
    }

    @Test
    fun normalizeAts_emptyInput() {
        assertEquals(emptyList<String>(), TimGroupAt.normalizeAts(emptyList()))
        assertEquals(emptyList<String>(), TimGroupAt.normalizeAts(listOf("  ", "")))
    }
}
