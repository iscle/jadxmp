package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnreachableCatchTest {
    @Test
    fun nestedResourceHandlersPreserveReachableFlow() {
        val fixture = Corpus.smaliDir().resolve("trycatch/TestUnreachableCatch.smali")
        val assembled = SmaliAssembler.assemble(fixture)
        assertTrue(assembled.ok, assembled.error)
        val engine = Decompiler()
        engine.load(fixture.name, assembled.dex!!)
        val result = engine.decompileAll()
        assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
        val classpath = AndroidSdk.recompileClasspath()
        assertTrue(classpath.isNotEmpty(), "The Android SDK is required to validate this regression")
        val recompiled = AccuracySignals.recompiles(result.classes.map { DecompiledClass(it.fullName, it.code) }, classpath)
        assertTrue(recompiled.success, recompiled.diagnostics.joinToString("\n") + "\n" + result.classes.joinToString { it.code })
    }
}
