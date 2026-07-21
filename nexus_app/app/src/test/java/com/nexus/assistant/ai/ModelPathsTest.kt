package com.nexus.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelPathsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun findOnnx_prefersInt8() {
        val dir = tmp.newFolder("stt")
        File(dir, "model.onnx").writeText("a")
        File(dir, "model.int8.onnx").writeText("b")
        val found = ModelPaths.findOnnx(dir, preferInt8 = true)
        assertEquals("model.int8.onnx", found!!.name)
    }

    @Test
    fun findOnnx_acceptsCustomName() {
        val dir = tmp.newFolder("custom")
        File(dir, "my-sense.onnx").writeText("x")
        val found = ModelPaths.findOnnx(dir, preferInt8 = true)
        assertTrue(found!!.name.endsWith(".onnx"))
        assertEquals("my-sense.onnx", found.name)
    }
}
