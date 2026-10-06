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

class NativeJvmArrayStoreSemanticsTest {
    @Test fun nativeStoresExecuteAsJava() = verify(false)
    @Test fun nativeStoresExecuteAsKotlin() = verify(true)

    private fun verify(kotlin: Boolean) {
        val directory = Files.createTempDirectory("jadxmp-native-array-store").toFile()
        try {
            val source = directory.resolve("NativeArrayStore.java").apply { writeText("""
                public interface NativeArrayStore {
                    static void ints(int[] a, int i, int v) { a[i] = v; }
                    static void longs(long[] a, int i, long v) { a[i] = v; }
                    static void floats(float[] a, int i, float v) { a[i] = v; }
                    static void doubles(double[] a, int i, double v) { a[i] = v; }
                    static void bytes(byte[] a, int i, int v) { a[i] = (byte) v; }
                    static void chars(char[] a, int i, int v) { a[i] = (char) v; }
                    static void shorts(short[] a, int i, int v) { a[i] = (short) v; }
                    static void booleans(boolean[] a, int i, boolean v) { a[i] = v; }
                    static void objects(Object[] a, int i, Object v) { a[i] = v; }
                    static void strings(String[] a, int i, String v) { a[i] = v; }
                    static void casted(String[] a, int i, Object v) { a[i] = (String) v; }
                    static void nested(int[][] a, int i, int[] v) { a[i] = v; }
                    static void order(int[] a, int[] effects) { a[effects[0]++] = effects[1]++; }
                    static void nullOrder(int[] effects) { int[] a = null; a[effects[0]++] = effects[1]++; }
                    static void primitiveValueThrow(int[] a, int index, int divisor) { a[index] = 12 / divisor; }
                    static void referenceOrder(Object[] a, int[] effects, Object[] values) { a[effects[0]++] = values[effects[1]++]; }
                    static int indexChanges(int[] a, int i) { a[i++] = i; return i; }
                    static int assignment(byte[] a, int value) { return a[0] = (byte) value; }
                    static void loop(int[] a, int count) { while (count > 0) { a[--count] = count; } }
                    static void nullBranch(boolean flag, int[] target, int[] effects) { int[] absent = null; if (flag) { absent[0] = effects[0]++; } else { target[0] = effects[0]++; } }
                }
            """.trimIndent()) }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-g", "-d", directory.path, source.path))
            val bytes = directory.resolve("NativeArrayStore.class").readBytes()
            val engine = Decompiler(DecompilerArgs(outputFormat = if (kotlin) OutputFormat.KOTLIN else OutputFormat.JAVA))
            assertEquals(1, engine.load("NativeArrayStore.class", bytes))
            val result = engine.decompileAll()
            assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
            val generated = result.classes.single().let { DecompiledClass(it.fullName, it.code) }
            val reference = ReferenceDecompiler().decompile("NativeArrayStore.class", bytes)
            assertEquals(0, reference.reportedErrors)
            val integer = Int::class.javaPrimitiveType!!
            val boolean = Boolean::class.javaPrimitiveType!!
            val identity = Any()
            val row = intArrayOf(19)
            val cases = buildList {
                fun primitive(name: String, arrayType: Class<*>, valueType: Class<*>, values: List<Any>, factory: () -> Any) {
                    for (value in values) for (index in listOf(-1, 0, 1, 2)) for (nullArray in listOf(false, true)) {
                        add(Case(name, listOf(arrayType, integer, valueType)) { listOf(if (nullArray) null else factory(), index, value) })
                    }
                }
                val integers = listOf(Int.MIN_VALUE, -65537, -32769, -129, -1, 0, 1, 2, 3, 127, 128, 255, 256, 32768, 65535, Int.MAX_VALUE)
                primitive("ints", IntArray::class.java, integer, integers) { intArrayOf(9, 9) }
                primitive("bytes", ByteArray::class.java, integer, integers) { byteArrayOf(9, 9) }
                primitive("chars", CharArray::class.java, integer, integers) { charArrayOf('x', 'x') }
                primitive("shorts", ShortArray::class.java, integer, integers) { shortArrayOf(9, 9) }
                primitive("booleans", BooleanArray::class.java, boolean, listOf(false, true)) { booleanArrayOf(true, true) }
                primitive("longs", LongArray::class.java, Long::class.javaPrimitiveType!!,
                    listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) { longArrayOf(9, 9) }
                primitive("floats", FloatArray::class.java, Float::class.javaPrimitiveType!!,
                    listOf(-0.0f, 0f, Float.MIN_VALUE, Float.POSITIVE_INFINITY, Float.fromBits(0xffc12345.toInt()), Float.fromBits(0x7fc45678))) { floatArrayOf(9f, 9f) }
                primitive("doubles", DoubleArray::class.java, Double::class.javaPrimitiveType!!,
                    listOf(-0.0, 0.0, Double.MIN_VALUE, Double.NEGATIVE_INFINITY,
                        Double.fromBits(0xfff8123456789abcUL.toLong()), Double.fromBits(0x7ff8abcdef012345))) { doubleArrayOf(9.0, 9.0) }
                for (value in listOf(null, "text", identity, row)) for (index in listOf(-1, 0, 1)) {
                    for (arrayKind in 0..2) add(Case("objects", listOf(Array<Any>::class.java, integer, Any::class.java)) {
                        listOf(when (arrayKind) { 0 -> arrayOfNulls<Any>(1); 1 -> arrayOfNulls<String>(1); else -> null }, index, value)
                    })
                    add(Case("casted", listOf(Array<String>::class.java, integer, Any::class.java)) { listOf(arrayOfNulls<String>(1), index, value) })
                    add(Case("casted", listOf(Array<String>::class.java, integer, Any::class.java)) { listOf(null, index, value) })
                }
                for (value in listOf(null, "literal")) for (index in listOf(-1, 0, 1)) {
                    add(Case("strings", listOf(Array<String>::class.java, integer, String::class.java)) { listOf(arrayOfNulls<String>(1), index, value) })
                }
                for (value in listOf(null, row)) for (index in listOf(-1, 0, 1)) {
                    add(Case("nested", listOf(Array<IntArray>::class.java, integer, IntArray::class.java)) { listOf(arrayOfNulls<IntArray>(1), index, value) })
                }
                for (nullArray in listOf(false, true)) for (length in 0..2) for (index in listOf(-1, 0, 2)) {
                    for (name in listOf("order", "referenceOrder")) {
                        val types = if (name == "order") listOf(IntArray::class.java, IntArray::class.java)
                            else listOf(Array<Any>::class.java, IntArray::class.java, Array<Any>::class.java)
                        add(Case(name, types) {
                            val effects = IntArray(length) { if (it == 0) index else 0 }
                            if (name == "order") listOf(if (nullArray) null else intArrayOf(9), effects)
                            else listOf(if (nullArray) null else arrayOfNulls<String>(1), effects, arrayOf(identity))
                        })
                    }
                    add(Case("nullOrder", listOf(IntArray::class.java)) { listOf(IntArray(length) { if (it == 0) index else 0 }) })
                    for (divisor in listOf(0, 3)) add(Case("primitiveValueThrow", listOf(IntArray::class.java, integer, integer)) {
                        listOf(if (nullArray) null else intArrayOf(9), index, divisor)
                    })
                }
                for (flag in listOf(false, true)) add(Case("nullBranch", listOf(boolean, IntArray::class.java, IntArray::class.java)) {
                    listOf(flag, intArrayOf(9), intArrayOf(7))
                })
                for (value in integers) add(Case("assignment", listOf(ByteArray::class.java, integer)) { listOf(byteArrayOf(9), value) })
                for (i in listOf(-1, 0, 1, 3)) for (name in listOf("indexChanges", "loop")) {
                    add(Case(name, listOf(IntArray::class.java, integer)) { listOf(intArrayOf(9, 9, 9), i) })
                }
            }
            URLClassLoader(arrayOf(directory.toURI().toURL())).use { originalLoader ->
                val original = originalLoader.loadClass("NativeArrayStore")
                for ((output, isKotlin) in listOf(generated to kotlin, reference.classes.single() to false)) {
                    withCompiledClass(output, isKotlin) { cls ->
                        val target = if (isKotlin) cls.getField("Companion").get(null) else null
                        val owner = target?.javaClass ?: cls
                        for (case in cases) assertEquals(outcome(original, null, case), outcome(owner, target, case), "${case.name}\n${output.source}")
                    }
                }
            }
        } finally { directory.deleteRecursively() }
    }

    private fun outcome(owner: Class<*>, target: Any?, case: Case): Outcome {
        val arguments = case.arguments()
        val value = try { owner.getMethod(case.name, *case.types.toTypedArray()).invoke(target, *arguments.toTypedArray()) }
        catch (error: InvocationTargetException) { Failure(error.targetException.javaClass.name) }
        return Outcome(value, arguments.map(::snapshot))
    }
    private fun snapshot(value: Any?): Any? = when (value) {
        is ByteArray -> value.toList(); is BooleanArray -> value.toList(); is CharArray -> value.toList()
        is ShortArray -> value.toList(); is IntArray -> value.toList(); is LongArray -> value.toList()
        is FloatArray -> value.map { it.toRawBits() }; is DoubleArray -> value.map { it.toRawBits() }
        // Reference elements deliberately remain the same objects, including nested array identities.
        is Array<*> -> value.toList()
        else -> value
    }
    private data class Outcome(val returned: Any?, val state: List<Any?>)
    private data class Failure(val type: String)
    private data class Case(val name: String, val types: List<Class<*>>, val arguments: () -> List<Any?>)
}
