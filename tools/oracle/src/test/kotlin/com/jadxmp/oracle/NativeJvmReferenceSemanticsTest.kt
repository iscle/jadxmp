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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeJvmReferenceSemanticsTest {
    @Test fun nativeReferencesCompileAndExecuteAsJava() = verify(false)
    @Test fun nativeReferencesCompileAndExecuteAsKotlin() = verify(true)

    private fun verify(kotlin: Boolean) {
        val directory = Files.createTempDirectory("jadxmp-native-references").toFile()
        try {
            val source = directory.resolve("NativeReferences.java").apply { writeText("""
                public interface NativeReferences {
                    static Object identity(Object value) { return value; }
                    static Object snapshot(Object value, Object replacement) { Object old = value; value = replacement; return old; }
                    static String nullable(boolean flag, String value) { return flag ? value : null; }
                    static Object choose(boolean flag, String text, int[] array) { return flag ? text : array; }
                    static Object loop(Object value, int count) { while (count > 0) { if (count == 2) value = null; count--; } return value; }
                    static boolean same(Object a, Object b) { return a == b; }
                    static boolean different(Object a, Object b) { return a != b; }
                    static boolean absent(Object value) { return value == null; }
                    static boolean present(Object value) { return value != null; }
                    static int[] array(int[] value) { return value; }
                }
            """.trimIndent()) }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-g", "-d", directory.path, source.path))
            val bytes = directory.resolve("NativeReferences.class").readBytes()
            val plugin = object : InputPlugin {
                override val id = "native-reference-test"
                override fun tryLoad(name: String, bytes: ByteArray) = JvmInput.loadClass(name, bytes)
            }
            val engine = Decompiler(DecompilerArgs(outputFormat = if (kotlin) OutputFormat.KOTLIN else OutputFormat.JAVA,
                registry = PluginRegistry(listOf(plugin))))
            assertEquals(1, engine.load("NativeReferences.class", bytes))
            val result = engine.decompileAll()
            assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
            val generated = result.classes.single().let { DecompiledClass(it.fullName, it.code) }
            val reference = ReferenceDecompiler().decompile("NativeReferences.class", bytes)
            assertEquals(0, reference.reportedErrors)
            val a = String(charArrayOf('x'))
            val b = String(charArrayOf('x'))
            assertNotSame(a, b)
            val array = intArrayOf(1, 2)
            val objectType = Any::class.java
            val boolType = Boolean::class.javaPrimitiveType!!
            val cases = buildList {
                for (value in listOf(null, a, array)) {
                    add(Case("identity", listOf(objectType), listOf(value)))
                    add(Case("snapshot", listOf(objectType, objectType), listOf(value, b)))
                    add(Case("absent", listOf(objectType), listOf(value)))
                    add(Case("present", listOf(objectType), listOf(value)))
                    for (count in listOf(0, 1, 2, 7)) add(Case("loop", listOf(objectType, Int::class.javaPrimitiveType!!), listOf(value, count)))
                }
                for (left in listOf(null, a, b)) for (right in listOf(null, a, b)) {
                    for (name in listOf("same", "different")) add(Case(name, listOf(objectType, objectType), listOf(left, right)))
                }
                for (flag in listOf(false, true)) {
                    for (value in listOf(null, a)) add(Case("nullable", listOf(boolType, String::class.java), listOf(flag, value)))
                    add(Case("choose", listOf(boolType, String::class.java, IntArray::class.java), listOf(flag, a, array)))
                }
                add(Case("array", listOf(IntArray::class.java), listOf(array)))
                add(Case("array", listOf(IntArray::class.java), listOf(null)))
            }
            URLClassLoader(arrayOf(directory.toURI().toURL())).use { originalLoader ->
                val original = originalLoader.loadClass("NativeReferences")
                for ((output, isKotlin) in listOf(generated to kotlin, reference.classes.single() to false)) {
                    withCompiledClass(output, isKotlin) { cls ->
                        val target = if (isKotlin) cls.getField("Companion").get(null) else null
                        val owner = target?.javaClass ?: cls
                        for (case in cases) {
                            val signature = original.getMethod(case.name, *case.types.toTypedArray())
                            val expected = signature.invoke(null, *case.arguments.toTypedArray())
                            val actual = owner.getMethod(case.name, *case.types.toTypedArray()).invoke(target, *case.arguments.toTypedArray())
                            if (signature.returnType.isPrimitive) assertEquals(expected, actual, "${case.name}: ${output.source}")
                            else assertSame(expected, actual, "${case.name}: ${output.source}")
                        }
                    }
                }
            }
        } finally { directory.deleteRecursively() }
    }

    private data class Case(val name: String, val types: List<Class<*>>, val arguments: List<Any?>)
}
