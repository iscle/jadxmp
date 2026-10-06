package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import com.jadxmp.api.plugin.InputPlugin
import com.jadxmp.api.plugin.PluginRegistry
import com.jadxmp.input.jvm.JvmInput
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeJvmInputSemanticsTest {
    @Test fun nativeClassToJavaSourceMatchesOriginalJvmExecution() = verify(false)
    @Test fun nativeClassToKotlinSourceMatchesOriginalJvmExecution() = verify(true)

    @Test fun defaultFacadeLoadsNativeJavaInputWithoutCustomPlugins() = verify(false, useDefaultRegistry = true)
    @Test fun defaultFacadeLoadsNativeKotlinInputWithoutCustomPlugins() = verify(true, useDefaultRegistry = true)

    private fun verify(kotlin: Boolean, useDefaultRegistry: Boolean = false) = withOriginal("NativePrimitives", """
        public interface NativePrimitives {
            static int snapshot(int value) { return value++; }
            static long mixed(int a, long b, int c) { return b + a - c; }
            static int minimum(int a, int b) { return a < b ? a : b; }
            static int loop(int value) { int sum = 0; while (value > 0) sum += value--; return sum; }
            static long wideJoin(int x, long a, long b) { long result; if (x < 0) result = a; else result = b; return result + 1; }
            static int floating(double a, double b) { return a < b ? -1 : a > b ? 1 : a == b ? 0 : 7; }
            static double divide(double a, double b) { return a / b; }
            static double zero() { return -0.0; }
            static int dense(int x) { switch (x) { case 1: return -3; case 2: return 5; case 3: return 9; default: return 11; } }
            static int sparse(int x) { switch (x) { case -100: return 4; case 1000: return 8; default: return 13; } }
        }
    """.trimIndent()) { original, bytes ->
        val result = decompile("NativePrimitives.class", bytes, kotlin, useDefaultRegistry)
        assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
        val generated = result.classes.single()
        withCompiledClass(DecompiledClass(generated.fullName, generated.code), kotlin) { cls ->
            val target = if (kotlin) cls.getField("Companion").get(null) else null
            val owner = target?.javaClass ?: cls
            val cases = listOf(
                "snapshot" to listOf<Any>(Int.MAX_VALUE),
                "mixed" to listOf<Any>(Int.MAX_VALUE, Long.MIN_VALUE, Int.MIN_VALUE),
                "minimum" to listOf<Any>(Int.MAX_VALUE, Int.MIN_VALUE), "minimum" to listOf<Any>(-7, 9),
                "loop" to listOf<Any>(0), "loop" to listOf<Any>(20),
                "wideJoin" to listOf<Any>(-1, Long.MAX_VALUE, Long.MIN_VALUE),
                "wideJoin" to listOf<Any>(1, Long.MAX_VALUE, Long.MIN_VALUE),
                "floating" to listOf<Any>(Double.NaN, 1.0), "floating" to listOf<Any>(1.0, Double.NaN),
                "floating" to listOf<Any>(-0.0, 0.0), "floating" to listOf<Any>(Double.NEGATIVE_INFINITY, 2.0),
                "floating" to listOf<Any>(3.0, 2.0), "divide" to listOf<Any>(1.0, -0.0),
                "zero" to emptyList<Any>(), "dense" to listOf<Any>(1), "dense" to listOf<Any>(2),
                "dense" to listOf<Any>(3), "dense" to listOf<Any>(0),
                "sparse" to listOf<Any>(-100), "sparse" to listOf<Any>(1000), "sparse" to listOf<Any>(0),
            )
            for ((name, arguments) in cases) {
                val types = arguments.map(::primitiveType).toTypedArray()
                val expected = original.getMethod(name, *types).invoke(null, *arguments.toTypedArray())
                val actual = owner.getMethod(name, *types).invoke(target, *arguments.toTypedArray())
                assertEquals(bits(expected), bits(actual), "$name$arguments\n${generated.code}")
            }
        }
    }

    @Test fun unsupportedNativeMethodDoesNotDiscardItsPrimitiveSibling() = withOriginal("NativePartial", """
        public class NativePartial {
            public static int supported(int value) { return value + 1; }
            public static int unsupported(int value) { return Math.abs(value); }
        }
    """.trimIndent()) { _, bytes ->
        for (kotlin in listOf(false, true)) {
            val result = decompile("NativePartial.class", bytes, kotlin)
            assertTrue(result.errorCount >= 2, "constructor and invocation must stay diagnosed")
            val generated = result.classes.single()
            assertTrue(generated.code.contains("unsupported"), generated.code)
            withCompiledClass(DecompiledClass(generated.fullName, generated.code), kotlin) { cls ->
                val target = if (kotlin) cls.getField("Companion").get(null) else null
                val owner = target?.javaClass ?: cls
                assertEquals(Int.MIN_VALUE, owner.getMethod("supported", Int::class.javaPrimitiveType)
                    .invoke(target, Int.MAX_VALUE))
            }
        }
    }

    private fun decompile(name: String, bytes: ByteArray, kotlin: Boolean, useDefaultRegistry: Boolean = false): com.jadxmp.api.DecompilationResult {
        val plugin = object : InputPlugin {
            override val id = "native-jvm-test"
            override fun tryLoad(name: String, bytes: ByteArray) = JvmInput.loadClass(name, bytes)
        }
        val engine = Decompiler(DecompilerArgs(
            outputFormat = if (kotlin) OutputFormat.KOTLIN else OutputFormat.JAVA,
            registry = if (useDefaultRegistry) PluginRegistry.default() else PluginRegistry(listOf(plugin)),
        ))
        assertEquals(1, engine.load(name, bytes))
        return engine.decompileAll()
    }

    private fun primitiveType(value: Any): Class<*> = when (value) {
        is Int -> Int::class.javaPrimitiveType!!
        is Long -> Long::class.javaPrimitiveType!!
        is Double -> Double::class.javaPrimitiveType!!
        else -> error("unexpected argument $value")
    }
    private fun bits(value: Any?): Any? = if (value is Double) value.toBits() else value

    private fun withOriginal(name: String, source: String, action: (Class<*>, ByteArray) -> Unit) {
        val directory = Files.createTempDirectory("jadxmp-native-input").toFile()
        try {
            val input = directory.resolve("$name.java").apply { writeText(source) }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-g", "-d", directory.path, input.path))
            URLClassLoader(arrayOf(directory.toURI().toURL())).use { loader ->
                action(loader.loadClass(name), directory.resolve("$name.class").readBytes())
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
