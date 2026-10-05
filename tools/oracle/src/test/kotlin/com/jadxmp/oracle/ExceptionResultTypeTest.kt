package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExceptionResultTypeTest {
    @Test
    fun resultCommitRetainsCursorTypeAcrossCleanupPhi() {
        val fixture = Corpus.smaliDir().resolve("types/TestTypeResolver17.smali")
        val assembly = SmaliAssembler.assemble(fixture)
        assertTrue(assembly.ok, assembly.error)
        val result = JadxmpDecompiler().decompile(fixture.name, assembly.dex!!)
        assertEquals(0, result.reportedErrors, result.classes.joinToString { it.source })
        val classpath = AndroidSdk.recompileClasspath()
        assertTrue(classpath.isNotEmpty(), "Android SDK required for the Cursor regression")
        val compilation = AccuracySignals.recompiles(result.classes, classpath)
        assertTrue(compilation.success, compilation.diagnostics.joinToString("\n") + "\n" + result.classes.joinToString { it.source })
    }
}
