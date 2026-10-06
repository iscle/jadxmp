package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeJvmFieldSemanticsTest {
    @Test fun declaredMutableFieldsExecuteAsJava() = verify(false)
    @Test fun declaredMutableFieldsExecuteAsKotlin() = verify(true)

    private fun verify(kotlin: Boolean) {
        val types = listOf("boolean", "byte", "char", "short", "int", "long", "float", "double", "Object", "String", "int[]")
        val source = DecompiledClass("NativeFields", buildString {
            append("public class NativeFields {\n")
            for ((index, type) in types.withIndex()) {
                append("public $type f$index; public static $type s$index;\n")
                append("public static $type get$index(NativeFields receiver){return receiver.f$index;}\n")
                append("public static void put$index(NativeFields receiver, $type value){receiver.f$index=value;}\n")
                append("public static $type sget$index(){return s$index;}\n")
                append("public static void sput$index($type value){s$index=value;}\n")
            }
            append("public volatile int observed; public static volatile long observedStatic;\n")
            append("public static int initialized=6; static {initialized=initialized+2;observedStatic=initialized;}\n")
            append("public static int initial(){return initialized;} public static long initWitness(){return observedStatic;}\n")
            append("public static int readVolatile(NativeFields receiver){return receiver.observed;}\n")
            append("public static void writeVolatile(NativeFields receiver,int v){receiver.observed=v;}\n")
            append("public static long staticVolatile(long v){observedStatic=v;return observedStatic;}\n")
            append("public static void order(NativeFields receiver,int[] effects){receiver.f4=effects[0]++;}\n")
            append("public static void divide(NativeFields receiver,int[] effects,int divisor){receiver.f4=effects[0]++/divisor;}\n")
            append("public static void knownNull(int[] effects){NativeFields receiver=null;receiver.f4=effects[0]++;}\n")
            append("public static int loop(NativeFields receiver,int n){while(n-->0){receiver.f4++;}return receiver.f4;}\n")
            append("public static int unused(NativeFields receiver){int ignored=receiver.f4;return 7;}\n")
            append("public static int numeric(NativeFields receiver){return -receiver.f1+(receiver.f2>>2)+(receiver.f3^4);}\n")
            append("}\n")
        })
        JavaCompilation.compile(listOf(source), release = 17).use { original ->
            assertTrue(original.result.success, original.result.diagnostics.toString())
            val bytes = original.output.resolve("NativeFields.class").readBytes()
            val engine = Decompiler(DecompilerArgs(outputFormat = if (kotlin) OutputFormat.KOTLIN else OutputFormat.JAVA))
            assertEquals(1, engine.load("NativeFields.class", bytes))
            val result = engine.decompileAll()
            // Constructors remain a separately diagnosed native-input limitation. Tests allocate
            // default-state receivers without running either constructor; no constructor parity claim.
            assertEquals(1, result.errorCount, result.classes.joinToString { it.code })
            val candidate = result.classes.single().let { DecompiledClass(it.fullName, it.code) }
            assertTrue(candidate.source.contains("constructor lowering is not supported"), candidate.source)
            val reference = ReferenceDecompiler().decompile("NativeFields.class", bytes)
            assertEquals(0, reference.reportedErrors)
            URLClassLoader(arrayOf(original.output.toURI().toURL())).use { loader ->
                val originalClass = loader.loadClass("NativeFields")
                val originalReceiver = allocate(originalClass)
                val values = listOf(true, (-127).toByte(), '\uffff', (-32000).toShort(), Int.MIN_VALUE,
                    Long.MIN_VALUE, Float.fromBits(0xffc12345.toInt()), Double.fromBits(0x7ff8abcdef012345), Any(), "text", intArrayOf(7))
                for ((output, isKotlin) in listOf(candidate to kotlin, reference.classes.single() to false)) {
                    withCompiledClass(output, isKotlin) { cls ->
                        val companion = if (isKotlin) cls.getField("Companion").get(null) else null
                        val owner = companion?.javaClass ?: cls
                        val receiver = allocate(cls)
                        assertEquals(8, invoke(owner, companion, "initial", emptyArray()))
                        assertEquals(8L, invoke(owner, companion, "initWitness", emptyArray()))
                        for (index in types.indices) {
                            val originalField = originalClass.getDeclaredField("f$index")
                            val field = cls.getDeclaredField("f$index").apply { isAccessible = true }
                            assertEquals(originalField.type.name, field.type.name)
                            // Public mutable fields in this complete ordinary-class scope now retain
                            // their raw ABI; a separate unchanged Java client pins field linkage too.
                            assertTrue(Modifier.isPublic(originalField.modifiers))
                            assertTrue(Modifier.isPublic(field.modifiers))
                            val value = values[index]
                            invoke(originalClass, null, "put$index", arrayOf(originalClass, originalField.type), originalReceiver, value)
                            invoke(owner, companion, "put$index", arrayOf(cls, field.type), receiver, value)
                            assertValue(value, invoke(owner, companion, "get$index", arrayOf(cls), receiver))
                            assertValue(invoke(originalClass, null, "get$index", arrayOf(originalClass), originalReceiver), field.get(receiver))
                            invoke(originalClass, null, "sput$index", arrayOf(originalField.type), value)
                            invoke(owner, companion, "sput$index", arrayOf(field.type), value)
                            assertValue(invoke(originalClass, null, "sget$index", emptyArray()), invoke(owner, companion, "sget$index", emptyArray()))
                            assertEquals(Failure("java.lang.NullPointerException"), invoke(owner, companion, "get$index", arrayOf(cls), null))
                            assertEquals(Failure("java.lang.NullPointerException"), invoke(owner, companion, "put$index", arrayOf(cls, field.type), null, value))
                        }
                        for (index in 8..10) {
                            val field = cls.getDeclaredField("f$index")
                            invoke(originalClass, null, "put$index", arrayOf(originalClass, field.type), originalReceiver, null)
                            invoke(owner, companion, "put$index", arrayOf(cls, field.type), receiver, null)
                            assertNull(invoke(originalClass, null, "get$index", arrayOf(originalClass), originalReceiver))
                            assertNull(invoke(owner, companion, "get$index", arrayOf(cls), receiver))
                            invoke(owner, companion, "sput$index", arrayOf(field.type), null)
                            assertNull(invoke(owner, companion, "sget$index", emptyArray()))
                        }
                        val knownNullEffects = intArrayOf(12)
                        assertEquals(Failure("java.lang.NullPointerException"), invoke(owner, companion, "knownNull", arrayOf(IntArray::class.java), knownNullEffects))
                        assertEquals(13, knownNullEffects[0])
                        assertEquals(Failure("java.lang.NullPointerException"), invoke(owner, companion, "knownNull", arrayOf(IntArray::class.java), null))
                        for (name in listOf("observed", "observedStatic")) assertTrue(Modifier.isVolatile(cls.getDeclaredField(name).modifiers))
                        invoke(owner, companion, "writeVolatile", arrayOf(cls, Int::class.javaPrimitiveType!!), receiver, 19)
                        assertEquals(19, invoke(owner, companion, "readVolatile", arrayOf(cls), receiver))
                        assertEquals(Long.MIN_VALUE, invoke(owner, companion, "staticVolatile", arrayOf(Long::class.javaPrimitiveType!!), Long.MIN_VALUE))
                        val integer = Int::class.javaPrimitiveType!!
                        for (name in listOf("order", "divide")) for (nullReceiver in listOf(false, true)) for (divisor in listOf(0, 2)) {
                            val before = intArrayOf(12); val after = intArrayOf(12)
                            val expectedArgs = if (name == "order") arrayOf(if (nullReceiver) null else originalReceiver, before) else arrayOf(if (nullReceiver) null else originalReceiver, before, divisor)
                            val actualArgs = if (name == "order") arrayOf(if (nullReceiver) null else receiver, after) else arrayOf(if (nullReceiver) null else receiver, after, divisor)
                            val expectedTypes = if (name == "order") arrayOf(originalClass, IntArray::class.java) else arrayOf(originalClass, IntArray::class.java, integer)
                            val actualTypes = if (name == "order") arrayOf(cls, IntArray::class.java) else arrayOf(cls, IntArray::class.java, integer)
                            assertEquals(invoke(originalClass, null, name, expectedTypes, *expectedArgs), invoke(owner, companion, name, actualTypes, *actualArgs), output.source)
                            assertArrayEquals(before, after)
                        }
                        for (count in listOf(-1, 0, 3)) {
                            invoke(originalClass, null, "put4", arrayOf(originalClass, integer), originalReceiver, 0)
                            invoke(owner, companion, "put4", arrayOf(cls, integer), receiver, 0)
                            assertEquals(invoke(originalClass, null, "loop", arrayOf(originalClass, integer), originalReceiver, count),
                                invoke(owner, companion, "loop", arrayOf(cls, integer), receiver, count))
                        }
                        assertEquals(Failure("java.lang.NullPointerException"), invoke(owner, companion, "unused", arrayOf(cls), null))
                        assertEquals(invoke(originalClass, null, "numeric", arrayOf(originalClass), originalReceiver), invoke(owner, companion, "numeric", arrayOf(cls), receiver))
                    }
                }
            }
        }
    }

    private fun allocate(type: Class<*>): Any {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (field.get(null) as sun.misc.Unsafe).allocateInstance(type)
    }
    private fun assertValue(expected: Any?, actual: Any?) {
        when (expected) {
            is Float -> assertEquals(expected.toRawBits(), (actual as Float).toRawBits())
            is Double -> assertEquals(expected.toRawBits(), (actual as Double).toRawBits())
            is Number, is Boolean, is Char -> assertEquals(expected, actual)
            else -> assertSame(expected, actual)
        }
    }
    private fun invoke(owner: Class<*>, target: Any?, name: String, types: Array<Class<*>>, vararg args: Any?): Any? = try {
        owner.getMethod(name, *types).invoke(target, *args)
    } catch (e: InvocationTargetException) { Failure(e.targetException.javaClass.name) }
    private data class Failure(val name: String)
}
