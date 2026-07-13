package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Proves the kotlinc recompile signal ([KotlinAccuracySignals]) is NOT vacuous: correct Kotlin compiles
 * clean, wrong Kotlin reports errors, and empty output fails. Each test tolerates a genuinely-missing
 * compiler by asserting the SKIP path (UNAVAILABLE) instead of a false pass — so the suite never turns a
 * broken environment into a green signal.
 */
class KotlinAccuracySignalsTest {

    private fun kt(fullName: String, source: String) = DecompiledClass(fullName, source.trimIndent())

    /** A trivially-correct .kt snippet must compile CLEAN (or SKIP if kotlinc is unavailable). */
    @Test
    fun correctKotlinCompilesClean() {
        val cls = kt(
            "a.Foo",
            """
            package a

            class Foo {
                fun answer(): Int = 42
                fun greet(name: String): String = "hi ${'$'}name"
            }
            """,
        )
        val result = KotlinAccuracySignals.recompiles(listOf(cls))
        if (result.skipped) {
            // Environment without an invokable kotlinc: assert an HONEST skip, never a fabricated pass.
            assertFalse(result.success, "UNAVAILABLE must not be scored as success")
            assertEquals(KotlinRecompileStatus.UNAVAILABLE, result.status)
            return
        }
        assertEquals(
            KotlinRecompileStatus.CLEAN,
            result.status,
            "correct Kotlin should compile clean; diagnostics=${result.errors + result.warnings}",
        )
        assertTrue(result.success)
    }

    /** Deliberately-wrong Kotlin must report ERRORS — the signal actually detects broken output. */
    @Test
    fun brokenKotlinReportsErrors() {
        val cls = kt(
            "a.Bar",
            """
            package a

            class Bar {
                // Type mismatch: a String where an Int is required — kotlinc must reject this.
                fun broken(): Int = "not an int"
            }
            """,
        )
        val result = KotlinAccuracySignals.recompiles(listOf(cls))
        if (result.skipped) {
            assertFalse(result.success)
            assertEquals(KotlinRecompileStatus.UNAVAILABLE, result.status)
            return
        }
        assertEquals(KotlinRecompileStatus.ERRORS, result.status, "type mismatch must be an error")
        assertFalse(result.success, "output with a compile error is not a recompile pass")
        assertTrue(result.errors.isNotEmpty(), "at least one error diagnostic must be captured")
    }

    /** Empty output fails (F2): a decompiler that produced zero classes did not cleanly decompile. */
    @Test
    fun emptyOutputFails() {
        val result = KotlinAccuracySignals.recompiles(emptyList())
        assertEquals(KotlinRecompileStatus.NO_OUTPUT, result.status)
        assertFalse(result.success, "zero classes is never a recompile pass")
    }

    /**
     * Comment-only source that "compiles" to zero .class files is a FALSE pass unless guarded (F1). It must
     * score NO_OUTPUT, not CLEAN. Skips honestly if kotlinc is unavailable.
     */
    @Test
    fun commentOnlySourceFailsF1() {
        val cls = kt("a.Empty", "package a\n// nothing here, no declarations\n")
        val result = KotlinAccuracySignals.recompiles(listOf(cls))
        assumeTrue(!result.skipped, "kotlinc unavailable — F1 guard covered by the unit assertion only")
        assertEquals(
            KotlinRecompileStatus.NO_OUTPUT,
            result.status,
            "comment-only source produces no a/Empty.class and must fail the F1 guard",
        )
        assertFalse(result.success)
    }

    /**
     * MUST-FIX 1 guard: the skip-guard maps ONLY compiler-absent failures to UNAVAILABLE. A
     * `NoClassDefFoundError`/`LinkageError` (embeddable jar missing) and a `ClassNotFoundException` are
     * SKIPs; any other Throwable — e.g. a kotlinc internal crash on pathological output — must NOT be
     * swallowed as UNAVAILABLE (that would hide a real "jadxmp emits uncompilable Kotlin" failure).
     */
    @Test
    fun skipGuardMapsOnlyCompilerAbsentToUnavailable() {
        // Compiler-absent / linkage cases → UNAVAILABLE (skip).
        val noClassDef = KotlinAccuracySignals.catchingUnavailable { throw NoClassDefFoundError("kotlinc absent") }
        assertEquals(KotlinRecompileStatus.UNAVAILABLE, noClassDef.status)
        assertTrue(noClassDef.skipped)

        val classNotFound = KotlinAccuracySignals.catchingUnavailable { throw ClassNotFoundException("K2JVMCompiler") }
        assertEquals(KotlinRecompileStatus.UNAVAILABLE, classNotFound.status)

        // A generic RuntimeException (stand-in for a compiler-internal crash) must PROPAGATE, never become a
        // silent UNAVAILABLE skip.
        val boom = assertThrows(IllegalStateException::class.java) {
            KotlinAccuracySignals.catchingUnavailable { throw IllegalStateException("kotlinc internal crash") }
        }
        assertEquals("kotlinc internal crash", boom.message)

        // An AssertionError (a non-Linkage Error, e.g. a compiler invariant tripping) must also propagate.
        assertThrows(AssertionError::class.java) {
            KotlinAccuracySignals.catchingUnavailable { throw AssertionError("compiler invariant") }
        }
    }

    /** Multiple classes referencing each other must be compiled together as one module and pass. */
    @Test
    fun crossReferencingClassesCompileTogether() {
        val a = kt("p.A", "package p\n\nclass A(val b: B) {\n    fun n(): Int = b.value\n}\n")
        val b = kt("p.B", "package p\n\nclass B {\n    val value: Int = 7\n}\n")
        val result = KotlinAccuracySignals.recompiles(listOf(a, b))
        assumeTrue(!result.skipped, "kotlinc unavailable")
        assertTrue(
            result.success,
            "sibling classes must compile in one unit; diagnostics=${result.errors + result.warnings}",
        )
    }
}
