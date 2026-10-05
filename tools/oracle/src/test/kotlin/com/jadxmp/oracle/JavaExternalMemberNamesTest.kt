package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JavaExternalMemberNamesTest {
    @Test
    fun threadYieldRemainsARealJdkCallAfterRoundTrip() {
        val source = DecompiledClass("semantics.ExternalCall", """
            package semantics;
            public class ExternalCall {
                public static boolean check() { Thread.yield(); return true; }
            }
        """.trimIndent())
        val original = JavaCheckFixture(listOf(source), source.fullName)
        val dex = JavaFixtureCompiler.dex(original)
        for (decompiler in listOf(ReferenceDecompiler(), JadxmpDecompiler())) {
            val output = decompiler.decompile("ExternalCall", dex)
            assertTrue(AccuracySignals.recompiles(output.classes).success, output.classes.joinToString { it.source })
            assertEquals(ExecuteCheckResult.Evaluated(true), AccuracySignals.executeCheck(output.classes, original), "${decompiler.name}: ${output.classes}")
        }
    }
}
