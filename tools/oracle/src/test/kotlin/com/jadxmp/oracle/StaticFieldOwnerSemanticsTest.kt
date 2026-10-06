package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** DEX frontend control: owner disambiguation belongs to both source backends. */
class StaticFieldOwnerSemanticsTest {
    @Test fun enumResidualInitializationStillExecutesWithTypedQualifier() {
        val source = DecompiledClass("fieldowner.Mode", """
            package fieldowner;
            public enum Mode {
                ON;
                public static int COUNT=42;
                public static boolean check(){return COUNT==42 && ON!=null;}
            }
        """.trimIndent())
        val fixture = JavaCheckFixture(listOf(source), source.fullName)
        val dex = JavaFixtureCompiler.dex(fixture)
        val output = JadxmpDecompiler().decompile("EnumStaticFieldOwner", dex)
        assertEquals(0, output.reportedErrors, output.classes.joinToString { it.source })
        assertEquals(ExecuteCheckResult.Evaluated(true), AccuracySignals.executeCheck(output.classes, fixture))
    }

    @Test fun mutableStaticFieldReadWriteAndInitializationSurviveFieldNameCapture() {
        val source = DecompiledClass("fieldowner.i", """
            package fieldowner;
            public class i {
                public static int i=2;
                public static int value;
                static { value=i+3; }
                public static int read(int n){return value+n;}
                public static void write(int n){value=n;}
                public static boolean check(){
                    if(value!=5) return false;
                    value=7;
                    return value==7 && i==2;
                }
            }
        """.trimIndent())
        val fixture = JavaCheckFixture(listOf(source), source.fullName)
        val dex = JavaFixtureCompiler.dex(fixture)
        for (kotlin in listOf(false, true)) {
            val output = if (kotlin) KotlinJadxmpDecompiler().decompileKotlin("StaticFieldOwner", dex) else JadxmpDecompiler().decompile("StaticFieldOwner", dex)
            assertEquals(0, output.reportedErrors, output.classes.joinToString { it.source })
            withCompiledClass(output.classes.single(), kotlin) { cls ->
                val target = if (kotlin) cls.getField("Companion").get(null) else null
                assertEquals(true, (target?.javaClass ?: cls).getMethod("check").invoke(target), output.classes.single().source)
            }
        }
    }
}
