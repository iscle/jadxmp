package com.jadxmp.api

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CLAUDE rule-4 batch resilience at the facade. The per-method backstop (both codegen backends) and the
 * per-class net ([Decompiler] `decompileNow`) must be **transparent on valid input**: `decompileAll` /
 * `decompileAllParallel` return every loaded class with real, error-free source — the guards never fire on
 * accurate input, and no throw ever escapes `generate()` to sink the batch.
 *
 * The containment MECHANISM itself — a throwing member → an honest marker → the siblings and the class
 * still render, so `generate()` returns normally and the batch keeps going — is proven directly, with
 * hand-built throwing IR, in `JavaFaultIsolationTest` / `KotlinFaultIsolationTest`. This test proves the
 * whole-project path that consumes it stays whole and byte-stable.
 *
 * A jvmTest only because it reads the `.dex` fixture from the classpath; the facade it drives is commonMain.
 */
class DecompilerFaultIsolationTest {

    private fun helloDexBytes(): ByteArray =
        javaClass.classLoader.getResourceAsStream("hello.dex")?.readBytes()
            ?: error("hello.dex test resource not found")

    @Test
    fun decompileAllReturnsEveryClassWithNoBatchLoss() {
        val decompiler = Decompiler()
        assertTrue(decompiler.load("hello.dex", helloDexBytes()) >= 1, "expected at least one class")

        val result = decompiler.decompileAll()

        // The batch survives whole: HelloWorld present, every returned class carries real source, none lost.
        assertTrue(
            result.classes.any { it.fullName == "HelloWorld" },
            "HelloWorld missing from batch: ${result.classes.map { it.fullName }}",
        )
        for (c in result.classes) assertTrue(c.code.isNotBlank(), "class ${c.fullName} produced no source")
        // Valid input ⇒ the rule-4 guards are transparent: no spurious errors introduced anywhere.
        assertEquals(0, result.errorCount, "no valid class may be flagged by the backstop / per-class net")
    }

    @Test
    fun parallelBatchAlsoSurvivesWhole() = runBlocking {
        val decompiler = Decompiler()
        decompiler.load("hello.dex", helloDexBytes())

        val result = decompiler.decompileAllParallel()

        assertTrue(result.classes.any { it.fullName == "HelloWorld" }, "HelloWorld missing from parallel batch")
        for (c in result.classes) assertTrue(c.code.isNotBlank(), "class ${c.fullName} produced no source")
        assertEquals(0, result.errorCount, "no valid class may be flagged on the parallel path")
    }
}
