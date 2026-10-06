package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeJvmArrayAllocationSemanticsTest {
    @Test fun allocationsExecuteAsJava() = verify(false)
    @Test fun allocationsExecuteAsKotlin() = verify(true)

    private fun verify(kotlin: Boolean) {
        val property = "jadxmp.native.allocation.initialization.$kotlin"
        val sources = listOf(DecompiledClass("allocation.NativeAllocation", """
            package allocation;
            public interface NativeAllocation {
                static Object booleans(int n) { return new boolean[n]; }
                static Object bytes(int n) { return new byte[n]; }
                static Object chars(int n) { return new char[n]; }
                static Object shorts(int n) { return new short[n]; }
                static Object ints(int n) { return new int[n]; }
                static Object longs(int n) { return new long[n]; }
                static Object floats(int n) { return new float[n]; }
                static Object doubles(int n) { return new double[n]; }
                static Object strings(int n) { return new String[n]; }
                static Object interfaces(int n) { return new Runnable[n]; }
                static Object nestedPrimitives(int n) { return new int[n][]; }
                static Object nestedReferences(int n) { return new String[n][][]; }
                static Object order(int[] effect) { return new int[effect[0]++]; }
                static Object division(int n, int divisor) { return new String[n / divisor]; }
                static int unused(int n) { int[] ignored = new int[n]; return 7; }
                static int snapshot(int n) { int[] a = new int[n++]; return a.length + n; }
                static Object initialized(int n) { return new Target[n]; }
                static Object missing(int[] effects, int divisor) { return new Missing[effects[0]++ / divisor]; }
            }
        """.trimIndent()), DecompiledClass("allocation.Target", """
            package allocation; public class Target {
                static { System.setProperty("$property", "initialized"); }
            }
        """.trimIndent()), DecompiledClass("allocation.Missing", "package allocation; public class Missing {}"))
        JavaCompilation.compile(sources, release = 17).use { original ->
            assertTrue(original.result.success, original.result.diagnostics.toString())
            val bytes = original.output.resolve("allocation/NativeAllocation.class").readBytes()
            val engine = Decompiler(DecompilerArgs(outputFormat = if (kotlin) OutputFormat.KOTLIN else OutputFormat.JAVA))
            assertEquals(1, engine.load("NativeAllocation.class", bytes))
            val result = engine.decompileAll()
            assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
            val candidate = result.classes.single().let { DecompiledClass(it.fullName, it.code) }
            val reference = ReferenceDecompiler().decompile("NativeAllocation.class", bytes)
            assertEquals(0, reference.reportedErrors)
            URLClassLoader(arrayOf(original.output.toURI().toURL())).use { loader ->
                val baseline = loader.loadClass("allocation.NativeAllocation")
                for ((output, isKotlin) in listOf(candidate to kotlin, reference.classes.single() to false)) {
                    withCompiledClass(output, isKotlin, listOf(original.output)) { cls ->
                        val target = if (isKotlin) cls.getField("Companion").get(null) else null
                        val owner = target?.javaClass ?: cls
                        for (name in listOf("booleans", "bytes", "chars", "shorts", "ints", "longs", "floats", "doubles", "strings", "interfaces", "nestedPrimitives", "nestedReferences", "unused", "snapshot")) {
                            for (n in listOf(Int.MIN_VALUE, -2, -1, 0, 1, 4)) assertEquals(invoke(baseline, null, name, n), invoke(owner, target, name, n), "$name($n)\n${output.source}")
                        }
                        for (name in listOf("ints", "strings", "nestedPrimitives", "nestedReferences")) {
                            val method = owner.getMethod(name, Int::class.javaPrimitiveType!!)
                            assertNotSame(method.invoke(target, 0), method.invoke(target, 0), "$name must allocate fresh zero-length arrays")
                        }
                        for (n in listOf(-1, 0, 2)) for (divisor in listOf(0, 1, -1)) {
                            assertEquals(invoke(baseline, null, "division", n, divisor), invoke(owner, target, "division", n, divisor))
                        }
                        for (n in listOf(-1, 0, 2)) {
                            val before = intArrayOf(n); val after = intArrayOf(n)
                            assertEquals(invoke(baseline, null, "order", before), invoke(owner, target, "order", after))
                            assertArrayEquals(before, after)
                        }
                        assertEquals(Failure("java.lang.NullPointerException"), invoke(owner, target, "order", null))
                        initialization(owner, target, property)
                        missing(owner, target, original.output.resolve("allocation/Missing.class"))
                    }
                }
                initialization(baseline, null, property)
                missing(baseline, null, original.output.resolve("allocation/Missing.class"))
            }
        }
        System.clearProperty(property)
    }

    private fun initialization(owner: Class<*>, target: Any?, property: String) {
        System.clearProperty(property)
        assertEquals(listOf("[Lallocation.Target;", listOf(null)), invoke(owner, target, "initialized", 1))
        assertNull(System.getProperty(property), "array allocation initialized its component class")
        Class.forName("allocation.Target", true, owner.classLoader)
        assertEquals("initialized", System.getProperty(property))
    }

    private fun missing(owner: Class<*>, target: Any?, file: java.io.File) {
        val bytes = file.readBytes(); assertTrue(file.delete())
        try {
            for (n in listOf(-1, 0, 1)) {
                val effects = intArrayOf(n)
                assertEquals(Failure("java.lang.NoClassDefFoundError"), invoke(owner, target, "missing", effects, 1))
                assertEquals(n + 1, effects[0])
            }
            val effects = intArrayOf(-1)
            assertEquals(Failure("java.lang.ArithmeticException"), invoke(owner, target, "missing", effects, 0))
            assertEquals(0, effects[0])
            assertEquals(Failure("java.lang.NullPointerException"), invoke(owner, target, "missing", null, 1))
        } finally { file.writeBytes(bytes) }
    }

    private fun invoke(owner: Class<*>, target: Any?, name: String, vararg args: Any?): Any? {
        val types = args.map { if (it is Int) Int::class.javaPrimitiveType!! else IntArray::class.java }.toTypedArray()
        return try { snapshot(owner.getMethod(name, *types).invoke(target, *args)) }
        catch (e: InvocationTargetException) { Failure(e.targetException.javaClass.name) }
    }
    private fun snapshot(value: Any?): Any? {
        if (value == null || !value.javaClass.isArray) return value
        return listOf(value.javaClass.name, (0 until java.lang.reflect.Array.getLength(value)).map { snapshot(java.lang.reflect.Array.get(value, it)) })
    }
    private data class Failure(val name: String)
}
