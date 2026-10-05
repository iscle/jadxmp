package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExecuteCheckTest {
    private fun source(checkBody: String, returnType: String = "void") = listOf(DecompiledClass(
        "Fixture", "public class Fixture { public $returnType check() { $checkBody } }",
    ))

    @Test
    fun passesOnlyWhenOriginalAndRebuildChecksPass() {
        val good = source("if (6 * 7 != 42) throw new AssertionError();")
        val bad = source("throw new AssertionError();")
        assertEquals(ExecuteCheckResult.Evaluated(true), AccuracySignals.executeCheck(good, JavaCheckFixture(good, "Fixture")))
        assertEquals(ExecuteCheckResult.Evaluated(false), AccuracySignals.executeCheck(bad, JavaCheckFixture(good, "Fixture")))
        assertEquals(ExecuteCheckResult.Evaluated(false), AccuracySignals.executeCheck(good, JavaCheckFixture(bad, "Fixture")))
    }

    @Test
    fun booleanChecksHonorReturnValue() {
        val good = source("return true;", "boolean")
        val bad = source("return false;", "boolean")
        assertEquals(ExecuteCheckResult.Evaluated(false), AccuracySignals.executeCheck(bad, JavaCheckFixture(good, "Fixture")))
    }

    @Test
    fun rebuiltMissingCheckOrCompilationFailureIsNotSkipped() {
        val good = source("")
        val noCheck = listOf(DecompiledClass("Fixture", "public class Fixture {}"))
        assertEquals(ExecuteCheckResult.NotEvaluated, AccuracySignals.executeCheck(good, JavaCheckFixture(noCheck, "Fixture")))
        assertEquals(ExecuteCheckResult.Evaluated(false), AccuracySignals.executeCheck(noCheck, JavaCheckFixture(good, "Fixture")))
        assertEquals(ExecuteCheckResult.Evaluated(false), AccuracySignals.executeCheck(emptyList(), JavaCheckFixture(good, "Fixture")))
    }

    @Test
    fun systemExitZeroAndInfiniteLoopsCannotProduceFalsePasses() {
        for (body in listOf("System.exit(0);", "while (true) {}")) {
            val good = source("")
            assertEquals(ExecuteCheckResult.Evaluated(false), AccuracySignals.executeCheck(source(body), JavaCheckFixture(good, "Fixture", timeoutMillis = 1_000)))
        }
    }

    @Test
    fun fixtureStaticStateDoesNotLeakBetweenExecutions() {
        val stateful = listOf(DecompiledClass("Fixture", """
            public class Fixture {
                private static int calls;
                public static void check() { if (++calls != 1) throw new AssertionError(); }
            }
        """.trimIndent()))
        assertEquals(ExecuteCheckResult.Evaluated(true), AccuracySignals.executeCheck(stateful, JavaCheckFixture(stateful, "Fixture")))
    }
    @Test
    fun kotlinExecutionDetectsMiscompilationAndMissingCheck() {
        val original = JavaCheckFixture(source("return true;", "boolean"), "Fixture")
        val good = listOf(DecompiledClass("Fixture", "class Fixture { fun check(): Boolean = true }"))
        val bad = listOf(DecompiledClass("Fixture", "class Fixture { fun check(): Boolean = false }"))
        val missing = listOf(DecompiledClass("Fixture", "class Fixture"))
        assertEquals(ExecuteCheckResult.Evaluated(true), KotlinAccuracySignals.executeCheck(good, original))
        assertEquals(ExecuteCheckResult.Evaluated(false), KotlinAccuracySignals.executeCheck(bad, original))
        assertEquals(ExecuteCheckResult.Evaluated(false), KotlinAccuracySignals.executeCheck(missing, original))
    }

    @Test
    fun kotlinCompanionCheckIsExecutedForStaticSourceMethods() {
        val java = listOf(DecompiledClass("Fixture", "public class Fixture { public static boolean check() { return true; } }"))
        val kotlin = listOf(DecompiledClass("Fixture", "class Fixture { companion object { fun check(): Boolean = true } }"))
        assertEquals(ExecuteCheckResult.Evaluated(true), KotlinAccuracySignals.executeCheck(kotlin, JavaCheckFixture(java, "Fixture")))
    }

    @Test
    fun malformedClassNamesFailCompilationWithoutWritingOutsideWorkspace() {
        for (name in listOf("../Escape", "/Escape", "a/../../Escape", "C:\\Escape", "a..Escape")) {
            assertEquals(false, AccuracySignals.recompiles(listOf(DecompiledClass(name, "class Escape {}"))).success)
        }
    }

    @Test
    fun legalPackageInfoAndSupplementaryUnicodeNamesStillCompile() {
        assertEquals(true, AccuracySignals.recompiles(listOf(DecompiledClass("p.package-info", "@Deprecated package p;"))).success)
        val name = "\uD801\uDC00Class"
        assertEquals(true, AccuracySignals.recompiles(listOf(DecompiledClass(name, "public class $name {}"))).success)
    }

}
