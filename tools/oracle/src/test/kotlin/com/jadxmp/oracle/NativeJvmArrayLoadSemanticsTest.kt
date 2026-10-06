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

class NativeJvmArrayLoadSemanticsTest {
    @Test fun nativeArrayLoadsPreserveJavaValuesAndExceptions() = verify(false)
    @Test fun nativeArrayLoadsPreserveKotlinValuesAndExceptions() = verify(true)

    private fun verify(kotlin: Boolean) {
        val directory = Files.createTempDirectory("jadxmp-native-array-load").toFile()
        try {
            val source = directory.resolve("NativeArrayLoad.java").apply { writeText("""
                public interface NativeArrayLoad {
                    static int ints(int[] a, int i) { return a[i]; }
                    static boolean booleans(boolean[] a, int i) { return a[i]; }
                    static int bytes(byte[] a, int i) { return a[i]; }
                    static int chars(char[] a, int i) { return a[i]; }
                    static int shorts(short[] a, int i) { return a[i]; }
                    static long longs(long[] a, int i) { return a[i]; }
                    static float floats(float[] a, int i) { return a[i]; }
                    static double doubles(double[] a, int i) { return a[i]; }
                    static Object objects(Object[] a, int i) { return a[i]; }
                    static String strings(String[] a, int i) { return a[i]; }
                    static int[] nested(int[][] a, int i) { return a[i]; }
                    static int deep(int[][] a, int i) { return a[i][0]; }
                    static int byteArithmetic(byte[] a, int i) { int x = a[i]; return (x + 1) * x; }
                    static int charArithmetic(char[] a, int i) { return a[i] + 1; }
                    static int shortArithmetic(short[] a, int i) { return a[i] + 1; }
                    static int byteIncrement(byte[] a, int i) { int x = a[i]; x++; return x; }
                    static int charIncrement(char[] a, int i) { int x = a[i]; x++; return x; }
                    static int shortIncrement(short[] a, int i) { int x = a[i]; x++; return x; }
                    static int booleanBranch(boolean[] a, int i) { return a[i] ? 17 : -9; }
                    static long byteLong(byte[] a, int i) { return a[i]; }
                    static float charFloat(char[] a, int i) { return a[i]; }
                    static double shortDouble(short[] a, int i) { return a[i]; }
                    static int choose(boolean flag, byte[] a) { return (flag ? a : null)[0]; }
                    static int before(int[] a, int d) { return a[12 / d]; }
                    static int nestedIndex(int[] a, int[] indexes) { return a[indexes[0]]; }
                    static int nullNestedIndex(int[] indexes) { int[] a = null; return a[indexes[0]]; }
                    static int after(int[] a, int d) { int x = a[0]; return x + 12 / d; }
                    static int indexChanges(int[] a, int i) { return a[i++] + i; }
                    static int ignored(int[] a, int i) { int unused = a[i]; return 7; }
                    static int constantNull(int d) { int[] a = null; return a[12 / d]; }
                    static String nullReference() { String[] a = null; return a[0]; }
                    static String nullBranch(boolean flag, String[] b) { String[] a = null; return flag ? a[0] : b[0]; }
                    static int numericBooleanArithmetic(byte[] a) { return a[0] + 1; }
                    static int numericBooleanMask(byte[] a) { return a[0] & 2; }
                    static int numericBooleanOr(byte[] a) { return a[0] | 2; }
                    static int numericBooleanXor(byte[] a) { return a[0] ^ 2; }
                    static int numericBooleanNegate(byte[] a) { return -a[0]; }
                    static int numericBooleanShift(byte[] a) { return a[0] << 3; }
                    static int numericBooleanUnsigned(byte[] a) { return a[0] >>> 1; }
                    static long numericBooleanWide(byte[] a) { return a[0]; }
                    static boolean numericBoolean(boolean[] a) { return a[0]; }
                    static long nullWide() { long[] a = null; return a[0]; }
                    static int loop(int[] a, int count) { int x = 0; while (count > 0) { x += a[--count]; } return x; }
                }
            """.trimIndent()) }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-g", "-d", directory.path, source.path))
            val classFile = directory.resolve("NativeArrayLoad.class")
            val bytes = classFile.readBytes()
            // JVMS baload produces computational int. Javac cannot spell a direct boolean→int
            // return, so change only this same-length descriptor, then execute the JVM original.
            val from = "([Z)Z".encodeToByteArray()
            val positions = (0..bytes.size - from.size).filter { start -> from.indices.all { bytes[start + it] == from[it] } }
            assertEquals(1, positions.size)
            bytes[positions.single() + from.lastIndex] = 'I'.code.toByte()
            for (descriptor in listOf("([B)I", "([B)J")) {
                val byteGetter = descriptor.encodeToByteArray()
                val bytePositions = (0..bytes.size - byteGetter.size).filter { start -> byteGetter.indices.all { bytes[start + it] == byteGetter[it] } }
                assertEquals(1, bytePositions.size)
                bytes[bytePositions.single() + 2] = 'Z'.code.toByte()
            }
            classFile.writeBytes(bytes)
            val engine = Decompiler(DecompilerArgs(outputFormat = if (kotlin) OutputFormat.KOTLIN else OutputFormat.JAVA))
            assertEquals(1, engine.load("NativeArrayLoad.class", bytes))
            val result = engine.decompileAll()
            assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
            val generated = result.classes.single().let { DecompiledClass(it.fullName, it.code) }
            val reference = ReferenceDecompiler().decompile("NativeArrayLoad.class", bytes)
            assertEquals(0, reference.reportedErrors)
            val integer = Int::class.javaPrimitiveType!!
            val boolean = Boolean::class.javaPrimitiveType!!
            val identity = Any()
            val samples = listOf(
                "ints" to intArrayOf(Int.MIN_VALUE, 0, Int.MAX_VALUE), "booleans" to booleanArrayOf(false, true),
                "bytes" to byteArrayOf(-128, -1, 0, 127), "chars" to charArrayOf('\u0000', '\u8000', '\uffff'),
                "shorts" to shortArrayOf(-32768, -1, 0, 32767), "longs" to longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE),
                "floats" to floatArrayOf(-0.0f, 0f, Float.fromBits(0x7fc12345), Float.fromBits(0xffc45678.toInt()), Float.POSITIVE_INFINITY),
                "doubles" to doubleArrayOf(-0.0, 0.0, Double.fromBits(0xfff8123456789abcUL.toLong()), Double.fromBits(0x7ff8abcdef012345), Double.NEGATIVE_INFINITY),
                "objects" to arrayOf(null, identity, "x"), "strings" to arrayOf(null, "literal", String(charArrayOf('x'))),
                "nested" to arrayOf(null, intArrayOf(19), intArrayOf()), "deep" to arrayOf(null, intArrayOf(19), intArrayOf()),
                "byteArithmetic" to byteArrayOf(-128, -1, 0, 127), "charArithmetic" to charArrayOf('\uffff'),
                "shortArithmetic" to shortArrayOf(-32768, 32767), "booleanBranch" to booleanArrayOf(false, true),
                "byteIncrement" to byteArrayOf(-128, 127), "charIncrement" to charArrayOf('\uffff'),
                "shortIncrement" to shortArrayOf(-32768, 32767),
                "byteLong" to byteArrayOf(-128, 127), "charFloat" to charArrayOf('\uffff'), "shortDouble" to shortArrayOf(-32768),
            )
            val cases = buildList {
                for ((name, array) in samples) {
                    val length = java.lang.reflect.Array.getLength(array)
                    for (value in listOf(array, null)) for (index in -1..length) {
                        add(Case(name, listOf(array.javaClass, integer), listOf(value, index)))
                    }
                }
                for (array in listOf(intArrayOf(2, 5, 9), intArrayOf(), null)) {
                    for (divisor in listOf(0, 12, -1)) for (name in listOf("before", "after")) {
                        add(Case(name, listOf(IntArray::class.java, integer), listOf(array, divisor)))
                    }
                    for (index in listOf(-1, 0, 2, 3)) for (name in listOf("ignored", "indexChanges", "loop")) {
                        add(Case(name, listOf(IntArray::class.java, integer), listOf(array, index)))
                    }
                }
                for (array in listOf(byteArrayOf(-128), null)) for (flag in listOf(false, true)) {
                    add(Case("choose", listOf(boolean, ByteArray::class.java), listOf(flag, array)))
                }
                for (array in listOf(intArrayOf(19), null)) for (indices in listOf(intArrayOf(0), intArrayOf(1), intArrayOf(), null)) {
                    add(Case("nestedIndex", listOf(IntArray::class.java, IntArray::class.java), listOf(array, indices)))
                    add(Case("nullNestedIndex", listOf(IntArray::class.java), listOf(indices)))
                }
                for (d in listOf(0, 1)) add(Case("constantNull", listOf(integer), listOf(d)))
                add(Case("nullReference", emptyList(), emptyList()))
                add(Case("nullWide", emptyList(), emptyList()))
                for (a in listOf(booleanArrayOf(false), booleanArrayOf(true), booleanArrayOf(), null)) {
                    add(Case("numericBoolean", listOf(BooleanArray::class.java), listOf(a)))
                    for (name in listOf("numericBooleanArithmetic", "numericBooleanMask", "numericBooleanOr", "numericBooleanXor",
                        "numericBooleanNegate", "numericBooleanShift", "numericBooleanUnsigned", "numericBooleanWide")) {
                        add(Case(name, listOf(BooleanArray::class.java), listOf(a)))
                    }
                }
                for (flag in listOf(false, true)) for (a in listOf(arrayOf("alive"), null)) {
                    add(Case("nullBranch", listOf(boolean, Array<String>::class.java), listOf(flag, a)))
                }
            }
            URLClassLoader(arrayOf(directory.toURI().toURL())).use { originalLoader ->
                val original = originalLoader.loadClass("NativeArrayLoad")
                for ((output, isKotlin) in listOf(generated to kotlin, reference.classes.single() to false)) {
                    withCompiledClass(output, isKotlin) { cls ->
                        val target = if (isKotlin) cls.getField("Companion").get(null) else null
                        val owner = target?.javaClass ?: cls
                        for (case in cases) {
                            val expected = outcome(original, null, case)
                            val actual = outcome(owner, target, case)
                            val message = "${case.name}: ${case.arguments}\n${output.source}"
                            if (case.name in setOf("objects", "strings", "nested") && expected !is Failure) {
                                assertSame(expected, actual, message)
                            } else assertEquals(expected, actual, message)
                        }
                    }
                }
            }
        } finally { directory.deleteRecursively() }
    }

    private fun outcome(owner: Class<*>, target: Any?, case: Case): Any? = try {
        when (val value = owner.getMethod(case.name, *case.types.toTypedArray()).invoke(target, *case.arguments.toTypedArray())) {
            is Float -> value.toRawBits()
            is Double -> value.toRawBits()
            else -> value
        }
    } catch (error: InvocationTargetException) { Failure(error.targetException.javaClass.name) }
    private data class Failure(val type: String)
    private data class Case(val name: String, val types: List<Class<*>>, val arguments: List<Any?>)
}
