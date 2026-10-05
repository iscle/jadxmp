package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Compile AND execute both generated languages; source acceptance misses NaN/zero miscompilation. */
class CompareSemanticsTest {
    @Test
    fun javaPreservesDexComparisonSemantics() = verify(kotlin = false)

    @Test
    fun kotlinPreservesDexComparisonSemantics() = verify(kotlin = true)

    private fun verify(kotlin: Boolean) {
        val fixture = Corpus.smaliDir().resolve("arith/CompareSemantics.smali")
        val assembled = SmaliAssembler.assemble(fixture)
        assertTrue(assembled.ok, assembled.error)
        val result = if (kotlin) {
            KotlinJadxmpDecompiler().decompileKotlin(fixture.name, assembled.dex!!)
        } else {
            JadxmpDecompiler().decompile(fixture.name, assembled.dex!!)
        }
        assertEquals(0, result.reportedErrors, result.classes.joinToString { it.source })
        val source = result.classes.single()
        withCompiledClass(source, kotlin) { outerClass ->
            val target = if (kotlin) outerClass.getField("Companion").get(null) else null
            val cls = target?.javaClass ?: outerClass
            val floatValues = listOf(Float.NEGATIVE_INFINITY, -1f, -0f, 0f, 1f, Float.POSITIVE_INFINITY, Float.NaN)
            val doubleValues = listOf(Double.NEGATIVE_INFINITY, -1.0, -0.0, 0.0, 1.0, Double.POSITIVE_INFINITY, Double.NaN)
            for ((name, bias) in listOf("lowFloat" to -1, "highFloat" to 1)) {
                val method = cls.getMethod(name, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                for (a in floatValues) for (b in floatValues) {
                    val expected = when { a > b -> 1; a == b -> 0; a < b -> -1; else -> bias }
                    assertEquals(expected, method.invoke(target, a, b), "$name($a, $b)\n${source.source}")
                }
            }
            for ((name, bias) in listOf("lowDouble" to -1, "highDouble" to 1)) {
                val method = cls.getMethod(name, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
                for (a in doubleValues) for (b in doubleValues) {
                    val expected = when { a > b -> 1; a == b -> 0; a < b -> -1; else -> bias }
                    assertEquals(expected, method.invoke(target, a, b), "$name($a, $b)\n${source.source}")
                }
            }
            val longMethod = cls.getMethod("compareLong", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            for (a in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
                for (b in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
                    assertEquals(a.compareTo(b), longMethod.invoke(target, a, b), "compareLong($a, $b)")
                }
            }
            assertEquals(4, cls.getMethod("nested", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .invoke(target, Float.NaN, 1f), "comparison expression precedence")
            assertEquals(-1, cls.getMethod("ordered").invoke(target), "left operand must execute first")
            // Kotlin exposes mutable properties through accessors, Java exposes the actual field.
            val count = if (kotlin) {
                cls.getMethod("getCounter").invoke(target)
            } else cls.getField("counter").get(null)
            assertEquals(2, count, "operands must be evaluated exactly once")
        }
    }

}
