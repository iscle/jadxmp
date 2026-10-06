package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeJvmArrayLengthSemanticsTest {
    @Test fun nativeArrayLengthPreservesJavaValuesAndExceptions() = verify(false)
    @Test fun nativeArrayLengthPreservesKotlinValuesAndExceptions() = verify(true)

    private fun verify(kotlin: Boolean) {
        val directory = Files.createTempDirectory("jadxmp-native-array-length").toFile()
        try {
            val source = directory.resolve("NativeArrayLength.java").apply { writeText("""
                public interface NativeArrayLength {
                    static int ints(int[] value) { return value.length; }
                    static int booleans(boolean[] value) { return value.length; }
                    static int bytes(byte[] value) { return value.length; }
                    static int chars(char[] value) { return value.length; }
                    static int shorts(short[] value) { return value.length; }
                    static int longs(long[] value) { return value.length; }
                    static int floats(float[] value) { return value.length; }
                    static int doubles(double[] value) { return value.length; }
                    static int objects(Object[] value) { return value.length; }
                    static int nested(int[][] value) { return value.length; }
                    static int choose(boolean flag, int[] value) { return (flag ? value : null).length; }
                    static int before(int[] value, int divisor) { int quotient = 12 / divisor; return quotient + value.length; }
                    static int after(int[] value, int divisor) { int length = value.length; return length + 12 / divisor; }
                    static int ignored(int[] value) { int unused = value.length; return 7; }
                    static int constantNull() { int[] value = null; return value.length; }
                    static int loop(int[] value, int count) { int result = 0; while (count > 0) { result += value.length; count--; } return result; }
                }
            """.trimIndent()) }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-g", "-d", directory.path, source.path))
            val bytes = directory.resolve("NativeArrayLength.class").readBytes()
            val engine = Decompiler(DecompilerArgs(outputFormat = if (kotlin) OutputFormat.KOTLIN else OutputFormat.JAVA))
            assertEquals(1, engine.load("NativeArrayLength.class", bytes))
            val result = engine.decompileAll()
            assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
            val generated = result.classes.single().let { DecompiledClass(it.fullName, it.code) }
            val reference = ReferenceDecompiler().decompile("NativeArrayLength.class", bytes)
            assertEquals(0, reference.reportedErrors)
            val integer = Int::class.javaPrimitiveType!!
            val boolean = Boolean::class.javaPrimitiveType!!
            val samples = listOf("ints" to intArrayOf(1, 2), "booleans" to booleanArrayOf(true),
                "bytes" to byteArrayOf(), "chars" to charArrayOf('a', 'b'), "shorts" to shortArrayOf(1),
                "longs" to longArrayOf(1, 2, 3), "floats" to floatArrayOf(0f), "doubles" to doubleArrayOf(0.0),
                "objects" to arrayOf<Any?>(null, "x"), "nested" to arrayOf(intArrayOf(), intArrayOf(1)))
            val cases = buildList {
                for ((name, array) in samples) for (value in listOf(array, null)) {
                    add(Case(name, listOf(array.javaClass), listOf(value)))
                }
                for (array in listOf(intArrayOf(1, 2), null)) {
                    for (divisor in listOf(0, 3)) for (name in listOf("before", "after")) {
                        add(Case(name, listOf(IntArray::class.java, integer), listOf(array, divisor)))
                    }
                    for (flag in listOf(false, true)) add(Case("choose", listOf(boolean, IntArray::class.java), listOf(flag, array)))
                    for (count in listOf(0, 1, 3)) add(Case("loop", listOf(IntArray::class.java, integer), listOf(array, count)))
                    add(Case("ignored", listOf(IntArray::class.java), listOf(array)))
                }
                add(Case("constantNull", emptyList(), emptyList()))
            }
            URLClassLoader(arrayOf(directory.toURI().toURL())).use { originalLoader ->
                val original = originalLoader.loadClass("NativeArrayLength")
                for ((output, isKotlin) in listOf(generated to kotlin, reference.classes.single() to false)) {
                    withCompiledClass(output, isKotlin) { cls ->
                        val target = if (isKotlin) cls.getField("Companion").get(null) else null
                        val owner = target?.javaClass ?: cls
                        for (case in cases) {
                            assertEquals(outcome(original, null, case), outcome(owner, target, case), "${case.name}: ${case.arguments}\n${output.source}")
                        }
                    }
                }
            }
        } finally { directory.deleteRecursively() }
    }

    private fun outcome(owner: Class<*>, target: Any?, case: Case): Any? = try {
        owner.getMethod(case.name, *case.types.toTypedArray()).invoke(target, *case.arguments.toTypedArray())
    } catch (error: InvocationTargetException) {
        error.targetException.javaClass.name
    }
    private data class Case(val name: String, val types: List<Class<*>>, val arguments: List<Any?>)
}
