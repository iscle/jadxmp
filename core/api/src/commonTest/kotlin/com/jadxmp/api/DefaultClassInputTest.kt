package com.jadxmp.api

import com.jadxmp.api.plugin.PluginRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultClassInputTest {
    @Test fun defaultFacadeRecognizesClassContentRegardlessOfName() {
        for (name in listOf("Empty.class", "upload.bin", "misnamed.jar")) {
            val engine = Decompiler()
            assertEquals(1, engine.load(name, emptyInterface))
            assertNotNull(engine.classInfo("sample.Empty"))
            val result = engine.decompileAll()
            assertEquals(0, result.errorCount)
            assertTrue(result.classes.single().code.contains("interface Empty"))
        }
    }

    @Test fun classSuffixAloneDoesNotClaimUnrelatedBytes() {
        val registry = PluginRegistry.default()
        assertNull(registry.load("Unknown.class", byteArrayOf(1, 2, 3, 4)))
        assertNull(registry.load("Empty.class", byteArrayOf()))
    }

    @Test fun recognizedTruncatedClassReportsFailureAndCanReload() {
        val engine = Decompiler()
        assertEquals(1, engine.load("Empty.class", emptyInterface))
        assertEquals(0, engine.load("broken.class", emptyInterface.copyOf(7)))
        assertTrue(engine.diagnostics.any { "failed to load" in it })
        assertNull(engine.classInfo("sample.Empty"))
        assertEquals(1, engine.load("Empty.class", emptyInterface))
        assertTrue(engine.diagnostics.isEmpty())
    }

    // javac --release 17 -g:none: package sample; public interface Empty {}
    private val emptyInterface = (
        "cafebabe0000003d000507000201000c73616d706c652f456d707479" +
        "0700040100106a6176612f6c616e672f4f626a6563740601000100030000000000000000"
    ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
