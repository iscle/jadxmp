package com.jadxmp.oracle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File

class ArrayConstantViewsSemanticsTest {
    @Test fun javaDoubleAndLong() = verify(wide = true, kotlin = false)
    @Test fun kotlinDoubleAndLong() = verify(wide = true, kotlin = true)
    @Test fun javaFloatAndInt() = verify(wide = false, kotlin = false)
    @Test fun kotlinFloatAndInt() = verify(wide = false, kotlin = true)

    private fun verify(wide: Boolean, kotlin: Boolean) {
        val file = File.createTempFile("ArrayConstantViews", ".smali")
        val bits = if (wide) 1.0.toBits() else 1.0f.toBits().toLong()
        val types = if (wide) "[D[JZ)J" else "[F[IZ)I"
        val suffix = if (wide) "-wide" else ""
        val constant = "const$suffix v0, 0x${bits.toString(16)}${if (wide) "L" else ""}"
        file.writeText(buildString {
            appendLine(".class public Lfixtures/ArrayConstantViews;")
            appendLine(".super Ljava/lang/Object;")
            for (reverse in listOf(false, true)) for (merge in listOf(false, true)) {
                appendLine(".method public static store${reverse}_${merge}($types")
                appendLine(".registers 8")
                if (merge) {
                    appendLine("if-eqz p2, :other")
                    appendLine(constant)
                    appendLine("goto :join")
                    appendLine(":other")
                    appendLine(constant)
                    appendLine(":join")
                } else appendLine(constant)
                appendLine("move$suffix v2, v0")
                appendLine("const/4 v4, 0x0")
                for (array in if (reverse) listOf("p1", "p0") else listOf("p0", "p1")) {
                    appendLine("aput$suffix v2, $array, v4")
                }
                appendLine("return$suffix v0")
                appendLine(".end method")
            }
        })
        try {
            val dex = SmaliAssembler.assemble(file).dex!!
            val output = if (kotlin) KotlinJadxmpDecompiler().decompileKotlin("constant-views.dex", dex)
                else JadxmpDecompiler().decompile("constant-views.dex", dex)
            assertEquals(0, output.reportedErrors)
            withCompiledClass(output.classes.single(), kotlin) { cls ->
                val receiver = if (kotlin) cls.getField("Companion").get(null) else null
                val owner = receiver?.javaClass ?: cls
                for (reverse in listOf(false, true)) for (merge in listOf(false, true)) for (branch in listOf(false, true)) {
                    if (wide) {
                        val floating = DoubleArray(1)
                        val integral = LongArray(1)
                        val result = owner.getMethod("store${reverse}_${merge}", DoubleArray::class.java,
                            LongArray::class.java, Boolean::class.javaPrimitiveType).invoke(receiver, floating, integral, branch)
                        assertEquals(bits, floating[0].toRawBits())
                        assertEquals(bits, integral[0])
                        assertEquals(bits, result)
                    } else {
                        val floating = FloatArray(1)
                        val integral = IntArray(1)
                        val result = owner.getMethod("store${reverse}_${merge}", FloatArray::class.java,
                            IntArray::class.java, Boolean::class.javaPrimitiveType).invoke(receiver, floating, integral, branch)
                        assertEquals(bits.toInt(), floating[0].toRawBits())
                        assertEquals(bits.toInt(), integral[0])
                        assertEquals(bits.toInt(), result)
                    }
                }
            }
        } finally { file.delete() }
    }

    @Test fun computedNumericConversionsRemainConversions() {
        val source = DecompiledClass("fixtures.ArrayConversions", """
            package fixtures;
            public class ArrayConversions {
                public static void store(double[] d, long[] l, long input) {
                    d[0] = (double) input;
                    l[0] = input;
                }
            }
        """.trimIndent())
        val dex = JavaFixtureCompiler.dex(JavaCheckFixture(listOf(source), source.fullName))
        for (kotlin in listOf(false, true)) {
            val output = if (kotlin) KotlinJadxmpDecompiler().decompileKotlin("conversion.dex", dex)
                else JadxmpDecompiler().decompile("conversion.dex", dex)
            assertEquals(0, output.reportedErrors)
            withCompiledClass(output.classes.single(), kotlin) { cls ->
                val receiver = if (kotlin) cls.getField("Companion").get(null) else null
                val owner = receiver?.javaClass ?: cls
                val floating = DoubleArray(1)
                val integral = LongArray(1)
                owner.getMethod("store", DoubleArray::class.java, LongArray::class.java,
                    Long::class.javaPrimitiveType).invoke(receiver, floating, integral, -9L)
                assertEquals(-9.0, floating[0])
                assertEquals(-9L, integral[0])
            }
        }
    }
}
