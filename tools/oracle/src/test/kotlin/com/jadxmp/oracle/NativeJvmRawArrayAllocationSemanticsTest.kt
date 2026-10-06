package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import java.lang.reflect.InvocationTargetException
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeJvmRawArrayAllocationSemanticsTest {
    @Test fun discardedAllocationsStillThrowAndDuplicatedSizeStaysIndependent() {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(V17, ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT, "RawAllocation", null, "java/lang/Object", null)
        for ((name, reference) in listOf("primitive" to false, "reference" to true, "duplicate" to false)) {
            writer.visitMethod(ACC_PUBLIC or ACC_STATIC, name, "(I)I", null, null).apply {
                visitCode(); visitVarInsn(ILOAD, 0)
                if (name == "duplicate") visitInsn(DUP)
                if (reference) visitTypeInsn(ANEWARRAY, "java/lang/String") else visitIntInsn(NEWARRAY, T_INT)
                visitInsn(POP)
                if (name != "duplicate") visitIntInsn(BIPUSH, 7)
                visitInsn(IRETURN); visitMaxs(0, 0); visitEnd()
            }
        }
        writer.visitEnd()
        val bytes = writer.toByteArray()
        val original = object : ClassLoader(javaClass.classLoader) {
            fun original() = defineClass("RawAllocation", bytes, 0, bytes.size)
        }.original()
        val reference = ReferenceDecompiler().decompile("RawAllocation.class", bytes)
        assertEquals(0, reference.reportedErrors)
        val outputs = OutputFormat.entries.map { format ->
            val engine = Decompiler(DecompilerArgs(outputFormat = format))
            assertEquals(1, engine.load("RawAllocation.class", bytes))
            val result = engine.decompileAll()
            assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
            result.classes.single().let { DecompiledClass(it.fullName, it.code) } to (format == OutputFormat.KOTLIN)
        } + (reference.classes.single() to false)
        for ((output, kotlin) in outputs) withCompiledClass(output, kotlin) { cls ->
            val target = if (kotlin) cls.getField("Companion").get(null) else null
            val owner = target?.javaClass ?: cls
            for (name in listOf("primitive", "reference", "duplicate")) for (n in listOf(-2, -1, 0, 1, 4)) {
                val expected = invoke(original, null, name, n)
                if (n < 0) assertEquals("java.lang.NegativeArraySizeException", expected)
                else assertEquals(if (name == "duplicate") n else 7, expected)
                assertEquals(expected, invoke(owner, target, name, n), "$name($n)\n${output.source}")
            }
        }
    }
    private fun invoke(owner: Class<*>, target: Any?, name: String, n: Int): Any? = try {
        owner.getMethod(name, Int::class.javaPrimitiveType!!).invoke(target, n)
    } catch (e: InvocationTargetException) { e.targetException.javaClass.name }
}
