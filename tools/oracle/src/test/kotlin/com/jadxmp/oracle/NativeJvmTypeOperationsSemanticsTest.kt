package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import com.jadxmp.api.plugin.InputPlugin
import com.jadxmp.api.plugin.PluginRegistry
import com.jadxmp.input.jvm.JvmInput
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeJvmTypeOperationsSemanticsTest {
    @Test fun castsTestsAndReferenceLiteralsExecuteAsJava() = verify(false)
    @Test fun castsTestsAndReferenceLiteralsExecuteAsKotlin() = verify(true)

    @Test fun integerConsumersRetainTheJvmInstanceofZeroOrOneValue() {
        val name = "nativeops.IntegerTypeTest"
        JavaCompilation.compile(listOf(DecompiledClass(name, """
            package nativeops;
            public interface IntegerTypeTest {
                static boolean value(Object input) { return input instanceof String; }
            }
        """.trimIndent())), release = 17).use { original ->
            assertTrue(original.result.success, original.result.diagnostics.toString())
            val classFile = original.output.resolve("nativeops/IntegerTypeTest.class")
            val bytes = classFile.readBytes()
            val descriptor = "(Ljava/lang/Object;)Z".encodeToByteArray()
            val offsets = (0..bytes.size - descriptor.size).filter { offset ->
                descriptor.indices.all { bytes[offset + it] == descriptor[it] }
            }
            assertEquals(1, offsets.size)
            // ireturn accepts the JVM int result directly. This verifier-valid descriptor change
            // exercises an integer consumer without javac's source-level boolean ternary bridge.
            bytes[offsets.single() + descriptor.lastIndex] = 'I'.code.toByte()
            classFile.writeBytes(bytes)
            val reference = ReferenceDecompiler().decompile("IntegerTypeTest.class", bytes)
            assertEquals(0, reference.reportedErrors)
            URLClassLoader(arrayOf(original.output.toURI().toURL())).use { loader ->
                val baseline = loader.loadClass(name).getMethod("value", Any::class.java)
                val plugin = object : InputPlugin {
                    override val id = "native-integer-type-test"
                    override fun tryLoad(name: String, bytes: ByteArray) = JvmInput.loadClass(name, bytes)
                }
                val outputs = OutputFormat.entries.map { format ->
                    val engine = Decompiler(DecompilerArgs(outputFormat = format, registry = PluginRegistry(listOf(plugin))))
                    assertEquals(1, engine.load("IntegerTypeTest.class", bytes))
                    val result = engine.decompileAll()
                    assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
                    result.classes.single().let { DecompiledClass(it.fullName, it.code) } to (format == OutputFormat.KOTLIN)
                } + (reference.classes.single() to false)
                for ((output, kotlin) in outputs) withCompiledClass(output, kotlin) { cls ->
                    val target = if (kotlin) cls.getField("Companion").get(null) else null
                    val method = (target?.javaClass ?: cls).getMethod("value", Any::class.java)
                    for (input in listOf(null, "text", Any(), intArrayOf(1))) {
                        assertEquals(baseline.invoke(null, input), method.invoke(target, input), output.source)
                    }
                }
            }
        }
    }

    private fun verify(kotlin: Boolean) {
        val property = "jadxmp.native.type.initialization.$kotlin"
        val sources = listOf(
            DecompiledClass("nativeops.NativeTypeOperations", """
                package nativeops;
                public interface NativeTypeOperations {
                    static String cast(Object value) { return (String) value; }
                    static String[] arrayCast(Object value) { return (String[]) value; }
                    static int[] primitiveArrayCast(Object value) { return (int[]) value; }
                    static String[][] nestedArrayCast(Object value) { return (String[][]) value; }
                    static Object nestedCast(Object value) { return (String[]) (Object[]) value; }
                    static int unusedCast(Object value) { String ignored = (String) value; return 7; }
                    static boolean isString(Object value) { return value instanceof String; }
                    static boolean isArray(Object value) { return value instanceof String[]; }
                    static boolean isPrimitiveArray(Object value) { return value instanceof int[]; }
                    static int number(Object value) { return (value instanceof String ? 1 : 0) + 3; }
                    static String literal() { return "native-intern-constant"; }
                    static Object literalObject() { return "native-intern-constant"; }
                    static boolean sameLiteral(Object value) { return value == "native-intern-constant"; }
                    static Class stringClass() { return String.class; }
                    static Class arrayClass() { return String[][].class; }
                    static Class primitiveArrayClass() { return int[].class; }
                    static Class targetClass() { return TypeTarget.class; }
                    static Class targetArrayClass() { return TypeTarget[].class; }
                    static Object missingCast(Object value) { return (MissingTarget[]) value; }
                    static boolean missing(Object value) { return value instanceof MissingTarget[]; }
                    static boolean missingAfterDivision(Object value, int divisor) {
                        int ignored = 1 / divisor;
                        return value instanceof MissingTarget[];
                    }
                    static TypeTarget targetCast(Object value) { return (TypeTarget) value; }
                    static boolean isTarget(Object value) { return value instanceof TypeTarget; }
                }
            """.trimIndent()),
            DecompiledClass("nativeops.MissingTarget", "package nativeops; public class MissingTarget {}"),
            DecompiledClass("nativeops.TypeTarget", """
                package nativeops;
                public class TypeTarget {
                    static { System.setProperty("$property", "initialized"); }
                }
            """.trimIndent()),
        )
        JavaCompilation.compile(sources, release = 17).use { original ->
            assertTrue(original.result.success, original.result.diagnostics.toString())
            val bytes = original.output.resolve("nativeops/NativeTypeOperations.class").readBytes()
            val plugin = object : InputPlugin {
                override val id = "native-type-operations"
                override fun tryLoad(name: String, bytes: ByteArray) = JvmInput.loadClass(name, bytes)
            }
            val engine = Decompiler(DecompilerArgs(outputFormat = if (kotlin) OutputFormat.KOTLIN else OutputFormat.JAVA,
                registry = PluginRegistry(listOf(plugin))))
            assertEquals(1, engine.load("NativeTypeOperations.class", bytes))
            val result = engine.decompileAll()
            assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
            val candidate = result.classes.single().let { DecompiledClass(it.fullName, it.code) }
            val reference = ReferenceDecompiler().decompile("NativeTypeOperations.class", bytes)
            assertEquals(0, reference.reportedErrors, reference.classes.joinToString { it.source })
            val text = String("native-intern-constant".toCharArray())
            val strings = arrayOf("a", null)
            val nested = arrayOf(strings)
            val values = listOf(null, text, Any(), 42, intArrayOf(1), strings, nested, arrayOf(Any()))
            URLClassLoader(arrayOf(original.output.toURI().toURL())).use { originalLoader ->
                val baseline = originalLoader.loadClass("nativeops.NativeTypeOperations")
                for ((outputIndex, variant) in listOf(candidate to kotlin, reference.classes.single() to false).withIndex()) {
                    val (output, isKotlin) = variant
                    withCompiledClass(output, isKotlin, listOf(original.output)) { cls ->
                        val target = if (isKotlin) cls.getField("Companion").get(null) else null
                        val owner = target?.javaClass ?: cls
                        for (name in listOf("cast", "arrayCast", "primitiveArrayCast", "nestedArrayCast", "nestedCast",
                            "unusedCast", "isString", "isArray", "isPrimitiveArray", "number", "sameLiteral")) {
                            val expectedMethod = baseline.getMethod(name, Any::class.java)
                            val actualMethod = owner.getMethod(name, Any::class.java)
                            for (value in values) {
                                val expected = outcome { expectedMethod.invoke(null, value) }
                                val actual = outcome { actualMethod.invoke(target, value) }
                                // The pinned reference drops this unused throwing checkcast. Keep its
                                // measured mismatch explicit; candidates must match original bytecode.
                                if (outputIndex == 1 && name == "unusedCast") {
                                    assertNull(actual.failure)
                                    assertEquals(7, actual.value)
                                    continue
                                }
                                assertEquals(expected.failure, actual.failure, "$name($value): ${output.source}")
                                if (expected.failure == null) {
                                    if (expectedMethod.returnType.isPrimitive) assertEquals(expected.value, actual.value, name)
                                    else assertSame(expected.value, actual.value, name)
                                }
                            }
                        }
                        for (name in listOf("literal", "literalObject", "stringClass", "arrayClass", "primitiveArrayClass")) {
                            assertSame(baseline.getMethod(name).invoke(null), owner.getMethod(name).invoke(target), name)
                        }
                        assertSame("native-intern-constant".intern(), owner.getMethod("literal").invoke(target))
                        assertEquals(false, owner.getMethod("sameLiteral", Any::class.java).invoke(target, text))
                        assertEquals(true, owner.getMethod("sameLiteral", Any::class.java).invoke(target, text.intern()))
                        checkInitialization(cls, owner, target, property)
                        checkMissingTarget(owner, target, original.output.resolve("nativeops/MissingTarget.class"))
                    }
                }
                checkInitialization(baseline, baseline, null, property)
                checkMissingTarget(baseline, null, original.output.resolve("nativeops/MissingTarget.class"))
            }
        }
        System.clearProperty(property)
    }

    private fun checkInitialization(cls: Class<*>, owner: Class<*>, target: Any?, property: String) {
        System.clearProperty(property)
        val targetClass = Class.forName("nativeops.TypeTarget", false, cls.classLoader)
        assertNull(owner.getMethod("targetCast", Any::class.java).invoke(target, null))
        assertEquals(false, owner.getMethod("isTarget", Any::class.java).invoke(target, null))
        assertEquals(ClassCastException::class.java,
            outcome { owner.getMethod("targetCast", Any::class.java).invoke(target, Any()) }.failure)
        assertSame(targetClass, owner.getMethod("targetClass").invoke(target))
        assertSame(targetClass, owner.getMethod("targetClass").invoke(target))
        assertSame(targetClass, (owner.getMethod("targetArrayClass").invoke(target) as Class<*>).componentType)
        assertNull(System.getProperty(property), "type operations initialized TypeTarget")
        val instance = targetClass.getConstructor().newInstance()
        assertEquals("initialized", System.getProperty(property))
        assertSame(instance, owner.getMethod("targetCast", Any::class.java).invoke(target, instance))
        assertEquals(true, owner.getMethod("isTarget", Any::class.java).invoke(target, instance))
    }

    private fun checkMissingTarget(owner: Class<*>, target: Any?, targetFile: java.io.File) {
        val bytes = targetFile.readBytes()
        assertTrue(targetFile.delete())
        try {
            val test = owner.getMethod("missing", Any::class.java)
            val cast = owner.getMethod("missingCast", Any::class.java)
            val prefixed = owner.getMethod("missingAfterDivision", Any::class.java, Int::class.javaPrimitiveType!!)
            // Null instanceof does not resolve its class operand. A reference-array class literal
            // must therefore occur only after the generated null branch, not as the call receiver.
            assertNull(cast.invoke(target, null))
            assertEquals(false, test.invoke(target, null))
            assertEquals(false, prefixed.invoke(target, null, 1))
            assertEquals(ArithmeticException::class.java, outcome { prefixed.invoke(target, Any(), 0) }.failure)
            assertEquals(NoClassDefFoundError::class.java, outcome { test.invoke(target, Any()) }.failure)
            assertEquals(NoClassDefFoundError::class.java, outcome { cast.invoke(target, Any()) }.failure)
        } finally {
            targetFile.writeBytes(bytes)
        }
    }

    private data class Outcome(val value: Any?, val failure: Class<*>?)
    private fun outcome(action: () -> Any?): Outcome = try {
        Outcome(action(), null)
    } catch (error: InvocationTargetException) {
        Outcome(null, error.targetException.javaClass)
    }
}
