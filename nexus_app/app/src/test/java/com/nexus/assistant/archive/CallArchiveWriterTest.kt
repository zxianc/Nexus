package com.nexus.assistant.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CallArchiveWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun write_roundTrip_metaAndTranscript_andNotifyQueue() {
        val root = tmp.newFolder("nexus_calls")
        val writer = CallArchiveWriter(root)
        val meta =
            CallMeta(
                slot = 0,
                number = "+8613900000000",
                policy = "ai",
                startedAtMs = 1_721_545_822_000L,
                endedAtMs = 1_721_545_900_000L,
                aiMode = true,
            )
        val lines =
            listOf(
                "用户: 你好，快递在哪",
                "助理: 请放驿站即可",
            )

        val result = writer.write(meta, lines, enqueueNotify = true)

        assertTrue(result.callDir.isDirectory)
        assertTrue(result.callDir.name.contains("slot0"))
        assertTrue(File(result.callDir, "audio").isDirectory)

        val metaJson = File(result.callDir, "meta.json").readText()
        assertTrue(metaJson.contains("\"slot\": 0") || metaJson.contains("\"slot\":0"))
        assertTrue(metaJson.contains("+8613900000000"))
        assertTrue(metaJson.contains("ai"))

        val transcript = File(result.callDir, "transcript.txt").readText()
        assertEquals(lines.joinToString("\n") + "\n", transcript)

        val queueDir = File(root, "notify_queue")
        assertTrue(queueDir.isDirectory)
        val jobs = queueDir.listFiles()?.filter { it.isFile } ?: emptyList()
        assertEquals(1, jobs.size)
        val job = jobs.first().readText()
        assertTrue(job.contains("快递") || job.contains("驿站"))
        assertTrue(job.contains(result.callDir.name))
    }

    @Test
    fun write_withoutNotify_skipsQueue() {
        val root = tmp.newFolder("nexus_calls2")
        val writer = CallArchiveWriter(root)
        val meta =
            CallMeta(
                slot = 1,
                number = "10086",
                policy = "human",
                startedAtMs = 1000L,
                endedAtMs = 2000L,
                aiMode = false,
            )
        writer.write(meta, emptyList(), enqueueNotify = false)
        val queue = File(root, "notify_queue")
        val jobs = queue.listFiles()?.filter { it.isFile } ?: emptyList()
        assertEquals(0, jobs.size)
    }
}
