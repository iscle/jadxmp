package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExternalArityRoundTripTest {
    private val profile by lazy { ClasspathMetadataProfile.load(UpstreamJavaInventory.fixtureClasspath) }

    @Test fun originalArrayReorderingAndFloatOffsetChecksExecuteWithExplicitClasspathMetadata() {
        val root = Corpus.root().parentFile.resolve("reference/jadx")
        PinnedReference.verify(root)
        assertTrue(profile.evidence.any { "sha256=" in it })
        assertEquals(1, profile.index.findClass("org.assertj.core.api.AbstractFloatAssert")!!.parameters.size)
        assertEquals(1, profile.index.findClass("org.assertj.core.data.Offset")!!.parameters.size)
        val baseline = UpstreamJavaRoundTrips()
        val withMetadata = UpstreamJavaRoundTrips(kotlin = KotlinJadxmpDecompiler(profile.index)::decompileKotlin)
        for (name in listOf("code/TestArrayAccessReorder.java", "others/TestFloatValue.java")) {
            val file = root.resolve("jadx-core/src/test/java/jadx/tests/integration/$name")
            val without = baseline.measure(name, file.readBytes())
            val measured = withMetadata.measure(name, file.readBytes())
            assertEquals("CHECK_PASSED", measured.originalStatus)
            assertFalse(without.kotlin!!.signals.recompiles, "Metadata-free baseline must remain independently visible: $without")
            for (output in listOf(measured.reference, measured.java, measured.kotlin)) {
                assertNotNull(output)
                assertTrue(output!!.signals.noErrors, output.diagnostics.toString())
                assertTrue(output.signals.recompiles, output.diagnostics.toString())
                assertEquals(true, output.signals.executesCheck, output.diagnostics.toString())
            }
        }
    }

    @Test fun suppliedMetadataCannotTurnFailedOriginalOrMissingRebuiltCheckIntoSuccess() {
        val withMetadata = UpstreamJavaRoundTrips(kotlin = KotlinJadxmpDecompiler(profile.index)::decompileKotlin)
        val failed = withMetadata.measure("Failure.java", fixture("public boolean check() { return false; }"))
        assertEquals("CHECK_FAILED", failed.originalStatus)
        assertNull(failed.kotlin)
        val missing = UpstreamJavaRoundTrips(kotlin = { name, bytes ->
            val original = KotlinJadxmpDecompiler(profile.index).decompileKotlin(name, bytes)
            original.copy(classes = listOf(DecompiledClass("fixtures.Fixture", "package fixtures; class Fixture { class TestCls }")))
        }).measure("Missing.java", fixture("public boolean check() { return true; }"))
        assertEquals("CHECK_PASSED", missing.originalStatus)
        assertTrue(missing.kotlin!!.signals.recompiles)
        assertEquals(false, missing.kotlin!!.signals.executesCheck)
    }

    private fun fixture(body: String) = """
        package fixtures;
        public class Fixture { public static class TestCls { $body } }
    """.trimIndent()
}
