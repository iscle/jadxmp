package com.jadxmp.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves [OutputFormat.KOTLIN] routing through the facade: the same `hello.dex` that renders to Java by
 * default renders to Kotlin when the arg is flipped, exercising the same input → IR → pipeline → out-of-SSA
 * bridge feed with only the leaf backend swapped ([com.jadxmp.codegen.kotlin.KotlinCodeGenerator]). Also
 * pins that the JAVA default path is unchanged (no regression from adding the second backend).
 *
 * A jvmTest only because it reads the `.dex` fixture from the classpath; the routed facade is all
 * `commonMain` and compiles for wasmJs (Kotlin output must work in-browser too).
 */
class DecompilerKotlinOutputTest {

    private fun helloDexBytes(): ByteArray =
        javaClass.classLoader.getResourceAsStream("hello.dex")?.readBytes()
            ?: error("hello.dex test resource not found")

    private fun decompile(format: OutputFormat): DecompiledClass {
        val decompiler = Decompiler(DecompilerArgs(outputFormat = format))
        val count = decompiler.load("hello.dex", helloDexBytes())
        assertTrue(count >= 1, "expected at least one class, got $count")
        return assertNotNull(
            decompiler.decompileClass("HelloWorld"),
            "HelloWorld should decompile; classes=${decompiler.classNames}",
        )
    }

    @Test
    fun kotlinFormatProducesKotlinSource() {
        val code = decompile(OutputFormat.KOTLIN).code
        // Kotlin-isms present…
        assertTrue(code.contains("class HelloWorld"), "expected a Kotlin class declaration:\n$code")
        assertTrue(code.contains("fun "), "expected a Kotlin `fun` (main):\n$code")
        // …and Java-isms absent: Kotlin has no `public class`, no `new` construction, no trailing `;`.
        assertTrue(!code.contains("public class"), "Kotlin output must not emit a Java `public class`:\n$code")
        assertTrue(!code.contains("new "), "Kotlin output must not emit Java `new` construction:\n$code")
    }

    @Test
    fun javaFormatIsUnchangedByAddingKotlinBackend() {
        val code = decompile(OutputFormat.JAVA).code
        // The default Java path stays Java: the branchy/φ-free hello.dex still renders the same Java-isms.
        assertTrue(code.contains("class HelloWorld"), "expected the Java class:\n$code")
        assertTrue(code.contains("super();"), "expected Java super-constructor call:\n$code")
        assertTrue(code.contains("\"Hello, World!\""), "expected the greeting literal:\n$code")
        assertTrue(code.contains("static void main("), "expected the Java main signature:\n$code")
    }

    @Test
    fun defaultFormatStaysJava() {
        // Adding OutputFormat.KOTLIN must not change the default — DecompilerArgs() is still Java.
        assertEquals(OutputFormat.JAVA, DecompilerArgs().outputFormat)
    }

    @Test
    fun perCallFormatOverrideRendersBothFromOneInstance() {
        // A SINGLE loaded Decompiler renders the same class as either format via the per-call override —
        // no reload, no second instance. This is the seam the UI's Java/Kotlin toggle drives.
        val decompiler = Decompiler() // default args → JAVA
        assertTrue(decompiler.load("hello.dex", helloDexBytes()) >= 1)

        val java = assertNotNull(decompiler.decompileClass("HelloWorld", OutputFormat.JAVA)).code
        val kotlin = assertNotNull(decompiler.decompileClass("HelloWorld", OutputFormat.KOTLIN)).code

        // Java-isms present in the Java render, absent from Kotlin.
        assertTrue(java.contains("static void main("), "Java render should have the Java main signature:\n$java")
        assertTrue(java.lines().any { it.trimEnd().endsWith(";") }, "Java render should have `;` line endings:\n$java")

        // Kotlin-isms present in the Kotlin render, and the Java-isms gone.
        assertTrue(kotlin.contains("fun "), "Kotlin render should have `fun`:\n$kotlin")
        assertTrue(!kotlin.contains("new "), "Kotlin render must not emit Java `new`:\n$kotlin")
        assertTrue(kotlin.lines().none { it.trimEnd().endsWith(";") }, "Kotlin render must not end lines with `;`:\n$kotlin")

        // The two renders genuinely differ (proves the override routed to a different backend).
        assertTrue(java != kotlin, "Java and Kotlin renders of the same class must differ")
    }

    @Test
    fun renderOrderDoesNotAffectOutputOrErrorCount() {
        // The MUST-FIX: codegen mutates SHARED error attrs (HAS_ERROR / IrAttrs.ERROR) on the IR. Without
        // the per-render baseline reset, rendering Kotlin first flags a node for a Kotlin-only gap, and a
        // later Java render on the same instance sees that stray flag and emits a spurious `// JADXMP
        // ERROR` — so the Java text (and errorCount) would depend on whether Kotlin ran first. Reproduced
        // on hello.dex: Java-only errorCount=0, Java-after-Kotlin errorCount=1 + a spurious marker.

        // Fresh single-format baselines (their IR was never touched by the other backend).
        val javaOnly = decompile(OutputFormat.JAVA)
        val kotlinOnly = decompile(OutputFormat.KOTLIN)

        // One instance rendering KOTLIN FIRST, then JAVA — the order that triggered the bug.
        val shared = Decompiler()
        assertTrue(shared.load("hello.dex", helloDexBytes()) >= 1)
        val kotlinFirst = assertNotNull(shared.decompileClass("HelloWorld", OutputFormat.KOTLIN))
        val javaAfterKotlin = assertNotNull(shared.decompileClass("HelloWorld", OutputFormat.JAVA))

        // Java-after-Kotlin is byte-identical to Java-only and carries the same error count — no leakage.
        assertEquals(
            javaOnly.code,
            javaAfterKotlin.code,
            "Java output must be independent of a prior Kotlin render on the same instance",
        )
        assertEquals(
            javaOnly.metadata.errorCount,
            javaAfterKotlin.metadata.errorCount,
            "Java errorCount must not rise from a prior Kotlin render (spurious-marker contamination)",
        )
        // No spurious marker leaked in (unless the clean Java-only render itself legitimately has one).
        assertEquals(
            javaOnly.code.contains("JADXMP ERROR"),
            javaAfterKotlin.code.contains("JADXMP ERROR"),
            "a `// JADXMP ERROR` must not appear only because Kotlin rendered first:\n${javaAfterKotlin.code}",
        )

        // Symmetric: Kotlin-first equals Kotlin-only (a prior Java render must not contaminate Kotlin either).
        assertEquals(kotlinOnly.code, kotlinFirst.code, "Kotlin output must be independent of render order")
        assertEquals(kotlinOnly.metadata.errorCount, kotlinFirst.metadata.errorCount)

        // And the omitted-format call still resolves to the instance default (JAVA), also uncontaminated.
        val default = assertNotNull(shared.decompileClass("HelloWorld"))
        assertEquals(javaOnly.code, default.code, "default (no-format) call is Java and equally uncontaminated")
    }
}
