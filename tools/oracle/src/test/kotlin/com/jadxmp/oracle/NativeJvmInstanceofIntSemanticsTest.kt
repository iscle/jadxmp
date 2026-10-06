package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Direct verified JVM instructions exercise numeric uses which Java's Boolean syntax cannot spell. */
class NativeJvmInstanceofIntSemanticsTest {
    @Test fun instanceofComputationalIntegerExecutesThroughBothBackendsAndPinnedReference() {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(V17, ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT, "NativeInstanceofInt", null, "java/lang/Object", null)
        fun method(name: String, returnType: String = "I", operation: MethodVisitor.() -> Unit = {}) {
            writer.visitMethod(ACC_PUBLIC or ACC_STATIC, name, "(Ljava/lang/Object;)$returnType", null, null).apply {
                visitCode()
                visitVarInsn(ALOAD, 0)
                visitTypeInsn(INSTANCEOF, "java/lang/String")
                operation()
                visitInsn(if (returnType == "J") LRETURN else IRETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        method("value")
        method("add") { visitInsn(ICONST_2); visitInsn(IADD) }
        method("mask") { visitInsn(ICONST_2); visitInsn(IAND) }
        method("negate") { visitInsn(INEG) }
        method("shift") { visitInsn(ICONST_3); visitInsn(ISHL) }
        method("wide", "J") { visitInsn(I2L) }
        method("fused") { visitVarInsn(ALOAD, 0); visitTypeInsn(INSTANCEOF, "java/lang/Integer"); visitInsn(IADD) }
        method("branch") {
            val otherwise = Label()
            val end = Label()
            visitJumpInsn(IFEQ, otherwise)
            visitIntInsn(BIPUSH, 17)
            visitJumpInsn(GOTO, end)
            visitLabel(otherwise)
            visitIntInsn(BIPUSH, -9)
            visitLabel(end)
        }
        writer.visitEnd()
        val bytes = writer.toByteArray()
        val original = object : ClassLoader(javaClass.classLoader) {
            fun original() = defineClass("NativeInstanceofInt", bytes, 0, bytes.size)
        }.original()
        val reference = ReferenceDecompiler().decompile("NativeInstanceofInt.class", bytes)
        assertEquals(0, reference.reportedErrors)
        val outputs = OutputFormat.entries.map { format ->
            val engine = Decompiler(DecompilerArgs(outputFormat = format))
            assertEquals(1, engine.load("NativeInstanceofInt.class", bytes))
            val result = engine.decompileAll()
            assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
            result.classes.single().let { DecompiledClass(it.fullName, it.code) } to (format == OutputFormat.KOTLIN)
        } + (reference.classes.single() to false)
        for ((output, kotlin) in outputs) withCompiledClass(output, kotlin) { cls ->
            val target = if (kotlin) cls.getField("Companion").get(null) else null
            val owner = target?.javaClass ?: cls
            for (name in listOf("value", "add", "mask", "negate", "shift", "wide", "fused", "branch")) {
                for (value in listOf(null, "text", 7, Any(), intArrayOf(1))) {
                    assertEquals(original.getMethod(name, Any::class.java).invoke(null, value),
                        owner.getMethod(name, Any::class.java).invoke(target, value), "$name: $value\n${output.source}")
                }
            }
        }
    }
}
