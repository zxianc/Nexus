package com.nexus.assistant.archive

import com.nexus.assistant.ai.ChatMessage
import com.nexus.assistant.notify.WebhookDeliveryResult
import com.nexus.assistant.notify.WebhookDeliveryStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CallArchiveWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun write_structuredCallJson_includesLocalAndPeer() {
        val root = tmp.newFolder("nexus_calls")
        val writer = CallArchiveWriter(root)
        val meta =
            CallMeta(
                slot = 0,
                peerNumber = "+8613900000000",
                localNumber = "13800138000",
                localLabel = "移动",
                localCarrier = "中国移动",
                policy = "ai",
                startedAtMs = 1_721_545_822_000L,
                endedAtMs = 1_721_545_900_000L,
                aiMode = true,
            )
        val turns =
            listOf(
                ChatMessage("user", "你好，快递在哪"),
                ChatMessage("assistant", "请放驿站即可"),
            )
        val notify =
            WebhookDeliveryResult(WebhookDeliveryStatus.FAILED, 3, "timeout")

        val result = writer.write(meta, turns, notify)

        assertTrue(result.callDir.isDirectory)
        val callJson = File(result.callDir, "call.json").readText()
        assertTrue(callJson.contains("peer_number"))
        assertTrue(callJson.contains("+8613900000000"))
        assertTrue(callJson.contains("13800138000"))
        assertTrue(callJson.contains("local_display"))

        val notifyText = CallArchiveWriter.buildNotifyText(meta, turns, result.callDir.name)
        assertTrue(notifyText.contains("对方:"))
        assertTrue(notifyText.contains("本机:"))
        assertTrue(notifyText.contains("13800138000"))

        assertFalse(File(root, "notify_queue").exists())
    }

    @Test
    fun write_skippedNotify_recordsStatus() {
        val root = tmp.newFolder("nexus_calls2")
        val writer = CallArchiveWriter(root)
        val meta =
            CallMeta(
                slot = 1,
                peerNumber = "10086",
                localNumber = "13900139000",
                policy = "human",
                startedAtMs = 1000L,
                endedAtMs = 2000L,
                aiMode = false,
            )
        val result =
            writer.write(
                meta,
                emptyList(),
                WebhookDeliveryResult(WebhookDeliveryStatus.SKIPPED, 0, null),
            )
        val json = result.callJson.readText()
        assertTrue(json.contains("skipped"))
        assertTrue(json.contains("13900139000"))
    }
}
