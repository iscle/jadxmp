package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import java.lang.reflect.InvocationTargetException
import org.junit.jupiter.api.Test

class UpstreamPrimitiveArraySemanticsTest {
    @Test fun originalNegativeDoubleArrayCheckSurvivesBothBackends() {
        val reference = Corpus.root().parentFile.resolve("reference/jadx")
        PinnedReference.verify(reference)
        val source = reference.resolve("jadx-core/src/test/java/jadx/tests/integration/arith/TestPrimitivesNegate.java").readText()
        val sample = UpstreamJavaExtractor.extract(source).sample!!
        assertEquals(OriginalFixtureStatus.CHECK_PASSED, UpstreamJavaInventory.validate(sample).status)
        val fixture = JavaCheckFixture(listOf(sample.source), sample.checkClass, UpstreamJavaInventory.fixtureClasspath)
        val dex = JavaFixtureCompiler.dex(fixture)
        val oracle = ReferenceDecompiler().decompile("negate.dex", dex)
        assertEquals(ExecuteCheckResult.Evaluated(true), AccuracySignals.executeCheck(oracle.classes, fixture))
        val java = JadxmpDecompiler().decompile("negate.dex", dex)
        val kotlin = KotlinJadxmpDecompiler().decompileKotlin("negate.dex", dex)
        assertTrue(AccuracySignals.noErrors(java, ErrorMarkers.JADXMP))
        assertEquals(ExecuteCheckResult.Evaluated(true), AccuracySignals.executeCheck(java.classes, fixture))
        assertTrue(AccuracySignals.noErrors(kotlin, ErrorMarkers.JADXMP))
        assertEquals(ExecuteCheckResult.Evaluated(true), KotlinAccuracySignals.executeCheck(kotlin.classes, fixture))
    }

    @Test fun primitiveArrayStoresRetainIeeeBitsAndReferenceStoreExceptions() {
        val doubles = listOf(-20.0, -0.0, Double.MIN_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN)
        val doubleLiterals = listOf("-20.0", "-0.0", "Double.MIN_VALUE", "Double.POSITIVE_INFINITY", "Double.NEGATIVE_INFINITY", "Double.NaN")
        val floats = listOf(-20.0f, -0.0f, Float.MIN_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN)
        val floatLiterals = listOf("-20.0f", "-0.0f", "Float.MIN_VALUE", "Float.POSITIVE_INFINITY", "Float.NEGATIVE_INFINITY", "Float.NaN")
        val name = "fixtures.ArrayStores"
        val source = DecompiledClass(name, buildString {
            appendLine("package fixtures; public class ArrayStores {")
            doubleLiterals.forEachIndexed { i, literal -> appendLine("public void d$i(double[] a, int i) { a[i] = $literal; }") }
            floatLiterals.forEachIndexed { i, literal -> appendLine("public void f$i(float[] a, int i) { a[i] = $literal; }") }
            appendLine("public void integer(int[] a) { a[0] = -1046478848; }")
            appendLine("public void wide(long[] a) { a[0] = -4597049319638433792L; }")
            appendLine("public void reference(Object[] a, Object v) { a[0] = v; }")
            appendLine("}")
        })
        val dex = JavaFixtureCompiler.dex(JavaCheckFixture(listOf(source), name))
        val reference = ReferenceDecompiler().decompile("arrays.dex", dex)
        val java = JadxmpDecompiler().decompile("arrays.dex", dex)
        val kotlin = KotlinJadxmpDecompiler().decompileKotlin("arrays.dex", dex)
        for ((classes, isKotlin) in listOf(listOf(source) to false, reference.classes to false, java.classes to false, kotlin.classes to true)) {
            withCompiledClasses(classes, isKotlin) { loader ->
                val cls = loader.loadClass(name)
                val instance = cls.getConstructor().newInstance()
                doubles.forEachIndexed { i, value ->
                    val array = doubleArrayOf(1.0)
                    val method = cls.getMethod("d$i", DoubleArray::class.java, Int::class.javaPrimitiveType)
                    method.invoke(instance, array, 0)
                    assertEquals(value.toRawBits(), array[0].toRawBits(), "double $i, kotlin=$isKotlin")
                    val nullFailure = assertThrows(InvocationTargetException::class.java) { method.invoke(instance, null, 0) }
                    assertTrue(nullFailure.cause is NullPointerException)
                    val boundsFailure = assertThrows(InvocationTargetException::class.java) { method.invoke(instance, array, 1) }
                    assertTrue(boundsFailure.cause is ArrayIndexOutOfBoundsException)
                }
                floats.forEachIndexed { i, value ->
                    val array = floatArrayOf(1.0f)
                    cls.getMethod("f$i", FloatArray::class.java, Int::class.javaPrimitiveType).invoke(instance, array, 0)
                    assertEquals(value.toRawBits(), array[0].toRawBits(), "float $i, kotlin=$isKotlin")
                }
                val integers = intArrayOf(0)
                cls.getMethod("integer", IntArray::class.java).invoke(instance, integers)
                assertEquals(-1046478848, integers[0])
                val longs = longArrayOf(0)
                cls.getMethod("wide", LongArray::class.java).invoke(instance, longs)
                assertEquals(-4597049319638433792L, longs[0])
                val method = cls.getMethod("reference", Array<Any>::class.java, Any::class.java)
                val failure = assertThrows(InvocationTargetException::class.java) { method.invoke(instance, arrayOf("a"), Any()) }
                assertTrue(failure.cause is ArrayStoreException, failure.cause.toString())
            }
        }
        assertEquals(0, java.reportedErrors)
        assertEquals(0, kotlin.reportedErrors)
    }

}
