package com.jadxmp.oracle

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.codegen.kotlin.KotlinCodeGenerator
import com.jadxmp.codegen.kotlin.KotlinCodegenKeys
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.node.LocalVar
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException

class KotlinArrayNullabilitySemanticsTest {
    @Test
    fun arrayDereferencesPreserveEvaluationAndJvmExceptionOrder(@TempDir dir: File) {
        val helper = DecompiledClass("testing.ArrayEffects", """
            package testing;
            public class ArrayEffects {
                public static String trace = "";
                public static int fail;
                private static void receiverEffect() {
                    trace += "R";
                    if (fail == 1) throw new IllegalArgumentException("receiver");
                }
                public static int[] receiverInt(int[] value) { receiverEffect(); return value; }
                public static Object[] receiverRef(Object[] value) { receiverEffect(); return value; }
                public static int index(int value) {
                    trace += "I";
                    if (fail == 2) throw new IllegalStateException("index");
                    return value;
                }
                public static void valueEffect() {
                    trace += "V";
                    if (fail == 3) throw new UnsupportedOperationException("value");
                }
                public static int valueInt(int value) { valueEffect(); return value; }
                public static Object valueRef(Object value) { valueEffect(); return value; }
                public static int read(int[] array, int index) { return receiverInt(array)[index(index)]; }
                public static void writeInt(int[] array, int index, int value) {
                    receiverInt(array)[index(index)] = valueInt(value);
                }
                public static void writeRef(Object[] array, int index, Object value) {
                    receiverRef(array)[index(index)] = valueRef(value);
                }
                public static void writeNull(Object[] array, int index) {
                    Object[] receiver = receiverRef(array);
                    int position = index(index);
                    valueEffect();
                    receiver[position] = null;
                }
            }
        """.trimIndent())
        val fixture = dir.resolve("ArrayDereferences.smali")
        fixture.writeText("""
            .class public LArrayDereferences;
            .super Ljava/lang/Object;
            .method public static read([II)I
                .locals 3
                invoke-static {p0}, Ltesting/ArrayEffects;->receiverInt([I)[I
                move-result-object v0
                invoke-static {p1}, Ltesting/ArrayEffects;->index(I)I
                move-result v1
                aget v2, v0, v1
                return v2
            .end method
            .method public static writeInt([III)V
                .locals 3
                .param p0, "receiver"
                .param p1, "argument"
                .param p2, "argument2"
                invoke-static {p0}, Ltesting/ArrayEffects;->receiverInt([I)[I
                move-result-object v0
                invoke-static {p1}, Ltesting/ArrayEffects;->index(I)I
                move-result v1
                invoke-static {p2}, Ltesting/ArrayEffects;->valueInt(I)I
                move-result v2
                aput v2, v0, v1
                return-void
            .end method
            .method public static writeRef([Ljava/lang/Object;ILjava/lang/Object;)V
                .locals 3
                invoke-static {p0}, Ltesting/ArrayEffects;->receiverRef([Ljava/lang/Object;)[Ljava/lang/Object;
                move-result-object v0
                invoke-static {p1}, Ltesting/ArrayEffects;->index(I)I
                move-result v1
                invoke-static {p2}, Ltesting/ArrayEffects;->valueRef(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v2
                aput-object v2, v0, v1
                return-void
            .end method
            .method public static writeNull([Ljava/lang/Object;I)V
                .locals 3
                invoke-static {p0}, Ltesting/ArrayEffects;->receiverRef([Ljava/lang/Object;)[Ljava/lang/Object;
                move-result-object v0
                invoke-static {p1}, Ltesting/ArrayEffects;->index(I)I
                move-result v1
                invoke-static {}, Ltesting/ArrayEffects;->valueEffect()V
                const/4 v2, 0x0
                aput-object v2, v0, v1
                return-void
            .end method
        """.trimIndent())
        JavaCompilation.compile(listOf(helper)).use { dependency ->
            assertTrue(dependency.result.success, dependency.result.diagnostics.toString())
            withCompiledClass(decompile(fixture), kotlin = true, additionalClasspath = listOf(dependency.output)) { cls ->
                val target = cls.getField("Companion").get(null)
                val original = cls.classLoader.loadClass(helper.fullName)
                val intType = Int::class.javaPrimitiveType!!
                val objectArray = arrayOfNulls<Any>(0).javaClass
                fun verify(name: String, types: List<Class<*>>, args: List<Any?>, fail: Int) {
                    fun observe(owner: Class<*>, receiver: Any?): List<Any?> {
                        val actualArgs = args.toMutableList()
                        actualArgs[0] = when (val array = args[0]) {
                            is IntArray -> array.copyOf()
                            is Array<*> -> array.clone()
                            else -> null
                        }
                        original.getField("trace").set(null, "")
                        original.getField("fail").setInt(null, fail)
                        var failure: Class<*>? = null
                        val value = try { owner.getMethod(name, *types.toTypedArray()).invoke(receiver, *actualArgs.toTypedArray()) }
                            catch (exception: InvocationTargetException) { failure = exception.cause!!.javaClass; null }
                        val contents = when (val array = actualArgs[0]) {
                            is IntArray -> array.toList()
                            is Array<*> -> array.toList()
                            else -> null
                        }
                        return listOf(value, failure, original.getField("trace").get(null), contents)
                    }
                    assertEquals(observe(original, null), observe(target.javaClass, target), "$name index=${args[1]} fail=$fail")
                }
                for (fail in 0..3) for (index in listOf(-1, 0, 2)) {
                    for (array in listOf(null, intArrayOf(11, 12))) {
                        verify("read", listOf(IntArray::class.java, intType), listOf(array, index), fail)
                        verify("writeInt", listOf(IntArray::class.java, intType, intType), listOf(array, index, 42), fail)
                    }
                    for (array in listOf(null, arrayOfNulls<Any>(2), arrayOf("a", "b"))) {
                        for (value in listOf("text", 42)) {
                            verify("writeRef", listOf(objectArray, intType, Any::class.java), listOf(array, index, value), fail)
                        }
                        verify("writeNull", listOf(objectArray, intType), listOf(array, index), fail)
                    }
                }
            }
        }
    }

    @Test
    fun nestedArraysElementsAndCloneKeepNullAndRuntimeComponentType(@TempDir dir: File) {
        val fixture = dir.resolve("ArrayValues.smali")
        fixture.writeText("""
            .class public LArrayValues;
            .super Ljava/lang/Object;
            .field public values:[I
            .field public static counter:I
            .method public static nullRead()I
                .locals 3
                const/4 v0, 0x0
                const/4 v1, 0x0
                aget v2, v0, v1
                return v2
            .end method
            .method public static nullCastLength()I
                .locals 2
                const/4 v0, 0x0
                check-cast v0, [I
                const/4 v1, 0x1
                sput v1, LArrayValues;->counter:I
                array-length v1, v0
                return v1
            .end method
            .method public static index(I)I
                .locals 1
                const/4 v0, 0x1
                sput v0, LArrayValues;->counter:I
                return p0
            .end method
            .method public static character([Ljava/lang/CharSequence;I)C
                .locals 2
                const/4 v1, 0x0
                aget-object v0, p0, v1
                invoke-static {p1}, LArrayValues;->index(I)I
                move-result v1
                invoke-interface {v0, v1}, Ljava/lang/CharSequence;->charAt(I)C
                move-result v1
                return v1
            .end method
            .method public static nested([[I)I
                .locals 2
                const/4 v0, 0x0
                aget-object v1, p0, v0
                array-length v0, v1
                return v0
            .end method
            .method public static element([Ljava/lang/String;I)Ljava/lang/String;
                .locals 1
                aget-object v0, p0, p1
                return-object v0
            .end method
            .method public static copy([Ljava/lang/Object;)[Ljava/lang/Object;
                .locals 1
                invoke-virtual {p0}, [Ljava/lang/Object;->clone()Ljava/lang/Object;
                move-result-object v0
                check-cast v0, [Ljava/lang/Object;
                return-object v0
            .end method
        """.trimIndent())
        withCompiledClass(decompile(fixture), kotlin = true) { cls ->
            val target = cls.getField("Companion").get(null)
            for (name in listOf("nullRead", "nullCastLength")) {
                val failure = runCatching { target.javaClass.getMethod(name).invoke(target) }.exceptionOrNull()
                assertTrue(failure is InvocationTargetException && failure.cause is NullPointerException, "$name: $failure")
            }
            assertEquals(1, target.javaClass.getMethod("getCounter").invoke(target), "check-cast null must not fail before the following field write")
            val character = target.javaClass.getMethod("character", arrayOfNulls<CharSequence>(0).javaClass, Int::class.javaPrimitiveType)
            for (values in listOf(null, arrayOfNulls<CharSequence>(1))) {
                target.javaClass.getMethod("setCounter", Int::class.javaPrimitiveType).invoke(target, 0)
                val failure = runCatching { character.invoke(target, values, 0) }.exceptionOrNull()
                assertTrue(failure is InvocationTargetException && failure.cause is NullPointerException)
                assertEquals(if (values == null) 0 else 1, target.javaClass.getMethod("getCounter").invoke(target))
            }
            assertEquals('b', character.invoke(target, arrayOf<CharSequence>("abc"), 1))
            val nested = target.javaClass.getMethod("nested", arrayOfNulls<IntArray>(0).javaClass)
            for (value in listOf(null, arrayOfNulls<IntArray>(1))) {
                val failure = runCatching { nested.invoke(target, value) }.exceptionOrNull()
                assertTrue(failure is InvocationTargetException && failure.cause is NullPointerException)
            }
            assertEquals(3, nested.invoke(target, arrayOf(intArrayOf(1, 2, 3))))
            val elements = arrayOf<String?>(null, "value")
            val element = target.javaClass.getMethod("element", elements.javaClass, Int::class.javaPrimitiveType)
            assertEquals(null, element.invoke(target, elements, 0))
            assertEquals("value", element.invoke(target, elements, 1))
            val original = arrayOf("one", "two")
            val copy = target.javaClass.getMethod("copy", arrayOfNulls<Any>(0).javaClass)
            val actual = copy.invoke(target, original) as Array<*>
            assertEquals(original.javaClass, actual.javaClass)
            assertNotSame(original, actual)
            assertSame(original[0], actual[0])
            val nullClone = runCatching { copy.invoke(target, null) }.exceptionOrNull()
            assertTrue(nullClone is InvocationTargetException && nullClone.cause is NullPointerException)
            val instance = cls.getConstructor().newInstance()
            assertEquals(null, cls.getMethod("getValues").invoke(instance))
            val values = intArrayOf(1, 2)
            cls.getMethod("setValues", IntArray::class.java).invoke(instance, values)
            assertSame(values, cls.getMethod("getValues").invoke(instance))
            cls.getMethod("setValues", IntArray::class.java).invoke(instance, null)
            assertEquals(null, cls.getMethod("getValues").invoke(instance))
        }
    }

    @Test
    fun nullableArrayOverridesKeepJavaDescriptorsAndIdentity() {
        val helper = DecompiledClass("testing.ArrayCallback", "package testing; public interface ArrayCallback { int[] transform(int[] values); }")
        val root = IrRoot()
        val cls = IrClass(root, "ArrayImplementation", 1, interfaces = listOf(IrType.objectType(helper.fullName)))
        root.addClass(cls)
        val type = IrType.array(IrType.INT)
        val method = IrMethod(cls, "transform", type, listOf(type), 1)
        cls.methods.add(method)
        method[CodegenKeys.PARAM_NAMES] = listOf("values")
        method[KotlinCodegenKeys.IS_OVERRIDE] = true
        val local = LocalVar().apply { name = "values"; this.type = type; add(AttrFlag.METHOD_ARGUMENT) }
        val ssa = SsaValue(1, 0, RegisterOperand(1, type)).apply { local.addSsaValue(this); add(AttrFlag.METHOD_ARGUMENT) }
        val parameter = RegisterOperand(1, type).also { it.ssaValue = ssa }
        method.blocks.add(BasicBlock(0).apply { instructions.add(Instruction(IrOpcode.RETURN, args = listOf(parameter))) })
        val source = DecompiledClass(cls.fullName, KotlinCodeGenerator().generate(cls).code)
        JavaCompilation.compile(listOf(helper)).use { dependency ->
            assertTrue(dependency.result.success, dependency.result.diagnostics.toString())
            withCompiledClass(source, kotlin = true, additionalClasspath = listOf(dependency.output)) { generated ->
                val instance = generated.getConstructor().newInstance()
                val transform = generated.getMethod("transform", IntArray::class.java)
                assertEquals(IntArray::class.java, transform.returnType)
                assertEquals(null, transform.invoke(instance, null))
                val values = intArrayOf(1, 2)
                assertSame(values, transform.invoke(instance, values))
            }
        }
    }

    private fun decompile(smali: File): DecompiledClass {
        val assembly = SmaliAssembler.assemble(smali)
        assertTrue(assembly.ok, assembly.error)
        val result = KotlinJadxmpDecompiler().decompileKotlin(smali.name, assembly.dex!!)
        assertEquals(0, result.reportedErrors, result.classes.joinToString { it.source })
        return result.classes.single()
    }
}
