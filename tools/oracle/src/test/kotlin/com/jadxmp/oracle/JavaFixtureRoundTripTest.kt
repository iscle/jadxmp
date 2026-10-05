package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JavaFixtureRoundTripTest {
    @Test
    fun compilesDexDecompilesRecompilesAndExecutesOriginalFixtures() {
        for (name in listOf("Arithmetic", "LoopSum", "FloatingComparisons")) {
            val source = DecompiledClass("semantics.$name", Corpus.root().resolve("java/semantics/$name.java").readText())
            val original = JavaCheckFixture(listOf(source), source.fullName)
            assertEquals(ExecuteCheckResult.Evaluated(true), AccuracySignals.executeCheck(original.classes, original), name)
            val dex = JavaFixtureCompiler.dex(original)
            for (decompiler in listOf(ReferenceDecompiler(), JadxmpDecompiler())) {
                val result = decompiler.decompile(name, dex)
                val score = SignalScore.of(result, decompiler.errorMarkers, original = original)
                assertTrue(score.noErrors && score.recompiles && score.executesCheck == true,
                    "$name/${decompiler.name}: $score\n${result.classes.joinToString { it.source }}")
            }
        }
    }
    @Test
    fun kotlinRoundTripsExecuteTheSameOriginalChecks() {
        for (name in listOf("Arithmetic", "LoopSum", "FloatingComparisons")) {
            val source = DecompiledClass("semantics.$name", Corpus.root().resolve("java/semantics/$name.java").readText())
            val original = JavaCheckFixture(listOf(source), source.fullName)
            val dex = JavaFixtureCompiler.dex(original)
            val result = KotlinJadxmpDecompiler().decompileKotlin(name, dex)
            val compilation = KotlinAccuracySignals.recompiles(result.classes)
            assertTrue(compilation.success, "$name: ${compilation.errors}\n${result.classes.joinToString { it.source }}")
            assertEquals(ExecuteCheckResult.Evaluated(true), KotlinAccuracySignals.executeCheck(result.classes, original),
                "$name: ${result.classes.joinToString { it.source }}")
        }
    }

}
