package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import java.lang.reflect.InvocationTargetException
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeJvmRawFieldSemanticsTest {
    @Test fun rawStoresKeepOriginalDuplicatedIntAndBooleanComputationalValues() {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(V17, ACC_PUBLIC, "RawFields", null, "java/lang/Object", null)
        for (type in listOf("B", "C", "S", "Z")) for (static in listOf(false, true)) {
            val name = (if (static) "s" else "f") + type
            writer.visitField(ACC_PUBLIC or (if (static) ACC_STATIC else 0), name, type, null, null).visitEnd()
            writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "put$name", if (static) "(I)I" else "(LRawFields;I)I", null, null).apply {
                visitCode()
                if (!static) visitVarInsn(ALOAD, 0)
                visitVarInsn(ILOAD, if (static) 0 else 1)
                visitInsn(if (static) DUP else DUP_X1)
                visitFieldInsn(if (static) PUTSTATIC else PUTFIELD, "RawFields", name, type)
                visitInsn(IRETURN); visitMaxs(0, 0); visitEnd()
            }
            writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "get$name", if (static) "()I" else "(LRawFields;)I", null, null).apply {
                visitCode()
                if (!static) visitVarInsn(ALOAD, 0)
                visitFieldInsn(if (static) GETSTATIC else GETFIELD, "RawFields", name, type)
                visitInsn(ICONST_2); visitInsn(IMUL); visitInsn(IRETURN); visitMaxs(0, 0); visitEnd()
            }
        }
        writer.visitField(ACC_PUBLIC, "wide", "J", null, null).visitEnd()
        writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "putWide", "(LRawFields;J)J", null, null).apply {
            visitCode(); visitVarInsn(ALOAD, 0); visitVarInsn(LLOAD, 1); visitInsn(DUP2_X1)
            visitFieldInsn(PUTFIELD, "RawFields", "wide", "J"); visitInsn(LRETURN); visitMaxs(0, 0); visitEnd()
        }
        writer.visitEnd()
        val bytes = writer.toByteArray()
        val original = object : ClassLoader(javaClass.classLoader) {
            fun original() = defineClass("RawFields", bytes, 0, bytes.size)
        }.original()
        val originalReceiver = allocate(original)
        val reference = ReferenceDecompiler().decompile("RawFields.class", bytes)
        assertEquals(0, reference.reportedErrors)
        // Original-pin raw narrowing may not be source-representable. Record its actual compiler
        // result separately below; original verified execution is always the semantic baseline.
        val referenceCompilation = AccuracySignals.recompiles(reference.classes)
        val outputs = OutputFormat.entries.map { format ->
            val engine = Decompiler(DecompilerArgs(outputFormat = format))
            assertEquals(1, engine.load("RawFields.class", bytes))
            val result = engine.decompileAll()
            assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
            result.classes.single().let { DecompiledClass(it.fullName, it.code) } to (format == OutputFormat.KOTLIN)
        }
        val integer = Int::class.javaPrimitiveType!!
        for ((output, kotlin) in outputs) withCompiledClass(output, kotlin) { cls ->
            val companion = if (kotlin) cls.getField("Companion").get(null) else null
            val owner = companion?.javaClass ?: cls
            val receiver = allocate(cls)
            for (type in listOf("B", "C", "S", "Z")) for (static in listOf(false, true)) {
                val name = (if (static) "s" else "f") + type
                val expectedField = original.getDeclaredField(name)
                val actualField = cls.getDeclaredField(name).apply { isAccessible = true }
                for (number in listOf(Int.MIN_VALUE, -65537, -32769, -129, -3, -2, -1, 0, 1, 2, 3, 127, 128, 255, 256, 32768, 65535, Int.MAX_VALUE)) {
                    val expected = if (static) invoke(original, null, "put$name", arrayOf(integer), number)
                        else invoke(original, null, "put$name", arrayOf(original, integer), originalReceiver, number)
                    val actual = if (static) invoke(owner, companion, "put$name", arrayOf(integer), number)
                        else invoke(owner, companion, "put$name", arrayOf(cls, integer), receiver, number)
                    assertEquals(number, expected)
                    assertEquals(expected, actual, output.source)
                    assertEquals(expectedField.get(if (static) null else originalReceiver), actualField.get(if (static) null else receiver))
                    val expectedRead = if (static) invoke(original, null, "get$name", emptyArray()) else invoke(original, null, "get$name", arrayOf(original), originalReceiver)
                    val actualRead = if (static) invoke(owner, companion, "get$name", emptyArray()) else invoke(owner, companion, "get$name", arrayOf(cls), receiver)
                    assertEquals(expectedRead, actualRead, output.source)
                }
                if (!static) assertEquals("java.lang.NullPointerException", invoke(owner, companion, "put$name", arrayOf(cls, integer), null, 2))
            }
            for (value in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
                assertEquals(value, invoke(owner, companion, "putWide", arrayOf(cls, Long::class.javaPrimitiveType!!), receiver, value))
                assertEquals(value, cls.getDeclaredField("wide").apply { isAccessible = true }.get(receiver))
            }
        }
        assertFalse(referenceCompilation.success, reference.classes.joinToString { it.source })
        val diagnostics = referenceCompilation.diagnostics.joinToString("\n")
        for (fragment in listOf("possible lossy conversion from int to byte", "possible lossy conversion from int to char", "possible lossy conversion from int to short", "int cannot be converted to boolean")) {
            assertTrue(diagnostics.contains(fragment), diagnostics)
        }
    }

    private fun allocate(type: Class<*>): Any {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (field.get(null) as sun.misc.Unsafe).allocateInstance(type)
    }
    private fun invoke(owner: Class<*>, target: Any?, name: String, types: Array<Class<*>>, vararg args: Any?): Any? = try {
        owner.getMethod(name, *types).invoke(target, *args)
    } catch (e: InvocationTargetException) { e.targetException.javaClass.name }
}
