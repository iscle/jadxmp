package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException

class KotlinNullContextSemanticsTest {
    @Test
    fun nullArgumentsSelectExactJvmOverloads(@TempDir dir: File) {
        val helper = DecompiledClass("testing.NullOverloads", """
            package testing;
            public class NullOverloads {
                public static int choose(String value) { return value == null ? 1 : -1; }
                public static int choose(CharSequence value) { return value == null ? 2 : -1; }
                public static int choose(int[] value) { return value == null ? 3 : -1; }
                public static int choose(Object value) { return value == null ? 4 : -1; }
            }
        """.trimIndent())
        val fixture = dir.resolve("NullArguments.smali")
        fixture.writeText(buildString {
            appendLine(".class public LNullArguments;")
            appendLine(".super Ljava/lang/Object;")
            for ((index, descriptor) in listOf("Ljava/lang/String;", "Ljava/lang/CharSequence;", "[I", "Ljava/lang/Object;").withIndex()) {
                appendLine("""
                    .method public static choose$index()I
                        .locals 1
                        const/4 v0, 0x0
                        invoke-static {v0}, Ltesting/NullOverloads;->choose($descriptor)I
                        move-result v0
                        return v0
                    .end method
                """.trimIndent())
            }
        })
        JavaCompilation.compile(listOf(helper)).use { dependency ->
            assertTrue(dependency.result.success, dependency.result.diagnostics.toString())
            withCompiledClass(decompile(fixture), kotlin = true, additionalClasspath = listOf(dependency.output)) { cls ->
                val target = cls.getField("Companion").get(null)
                for (index in 0..3) assertEquals(index + 1, target.javaClass.getMethod("choose$index").invoke(target))
            }
        }
    }

    @Test
    fun throwNullRaisesNpeAndThrowValueKeepsIdentity(@TempDir dir: File) {
        val fixture = dir.resolve("ThrowNull.smali")
        fixture.writeText("""
            .class public LThrowNull;
            .super Ljava/lang/Object;
            .method public static nullFailure()V
                .locals 1
                const/4 v0, 0x0
                throw v0
            .end method
            .method public static failure(Ljava/lang/Throwable;)V
                .locals 0
                throw p0
            .end method
        """.trimIndent())
        withCompiledClass(decompile(fixture), kotlin = true) { cls ->
            val target = cls.getField("Companion").get(null)
            val thrown = runCatching { target.javaClass.getMethod("nullFailure").invoke(target) }.exceptionOrNull()
            assertTrue(thrown is InvocationTargetException && thrown.cause is NullPointerException)
            val original = IllegalStateException("original")
            val same = runCatching { target.javaClass.getMethod("failure", Throwable::class.java).invoke(target, original) }.exceptionOrNull()
            assertTrue(same is InvocationTargetException)
            assertSame(original, same!!.cause)
        }
    }

    @Test
    fun parcelableNullCorpusSelectsDeclaredOverload() {
        val result = KotlinAccuracySignals.recompiles(
            listOf(decompile(Corpus.smaliDir().resolve("invoke/TestCastInOverloadedInvoke2.smali"))),
            AndroidSdk.recompileClasspath(),
        )
        assertTrue(result.success, result.errors.toString())
    }

    private fun decompile(smali: File): DecompiledClass {
        val assembled = SmaliAssembler.assemble(smali)
        assertTrue(assembled.ok, assembled.error)
        val result = KotlinJadxmpDecompiler().decompileKotlin(smali.name, assembled.dex!!)
        assertEquals(0, result.reportedErrors, result.classes.joinToString { it.source })
        return result.classes.single()
    }
}
