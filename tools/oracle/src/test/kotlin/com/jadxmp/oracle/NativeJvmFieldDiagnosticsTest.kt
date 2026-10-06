package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import java.net.URLClassLoader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeJvmFieldDiagnosticsTest {
    @Test fun rawConstantReadInitializesOriginalButUnsupportedSourceIsNeverReportedAsParity() {
        val property = "jadxmp.native.field.constant.init"
        JavaCompilation.compile(listOf(DecompiledClass("fieldconstant.ConstantOwner", """
            package fieldconstant; public class ConstantOwner {
                public static final int VALUE = 7;
                static { System.setProperty("$property", "initialized"); }
            }
        """.trimIndent())), release = 17).use { helper ->
            assertTrue(helper.result.success, helper.result.diagnostics.toString())
            val writer = writer("fieldconstant/ConstantCaller")
            read(writer, "read", "fieldconstant/ConstantOwner", "VALUE")
            healthy(writer)
            writer.visitEnd()
            val bytes = writer.toByteArray()
            helper.output.resolve("fieldconstant/ConstantCaller.class").writeBytes(bytes)
            System.clearProperty(property)
            URLClassLoader(arrayOf(helper.output.toURI().toURL())).use { original ->
                assertEquals(7, original.loadClass("fieldconstant.ConstantCaller").getMethod("read").invoke(null))
                assertEquals("initialized", System.getProperty(property), "original getstatic must initialize ConstantOwner")
            }
            val reference = ReferenceDecompiler().decompile("ConstantCaller.class", bytes)
            assertEquals(0, reference.reportedErrors)
            System.clearProperty(property)
            withCompiledClass(reference.classes.single(), false, listOf(helper.output)) { cls ->
                assertEquals(7, cls.getMethod("read").invoke(null))
                // Measured pinned-reference mismatch: recompilation inlines ConstantValue and
                // loses the original class initialization. This is not an execution parity pass.
                assertNull(System.getProperty(property))
            }
            for (format in OutputFormat.entries) {
                val engine = Decompiler(DecompilerArgs(outputFormat = format))
                assertEquals(1, engine.load("ConstantCaller.class", bytes))
                val result = engine.decompileAll()
                assertEquals(1, result.errorCount)
                val output = result.classes.single().let { DecompiledClass(it.fullName, it.code) }
                assertTrue(output.source.contains("external JVM field access requires declaration metadata"), output.source)
                withCompiledClass(output, format == OutputFormat.KOTLIN) { cls ->
                    val target = if (format == OutputFormat.KOTLIN) cls.getField("Companion").get(null) else null
                    val owner = target?.javaClass ?: cls
                    assertEquals(9, owner.getMethod("healthy").invoke(target))
                    val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { owner.getMethod("read").invoke(target) }
                    assertInstanceOf(UnsupportedOperationException::class.java, failure.targetException)
                }
            }
        }
        System.clearProperty(property)
    }

    @Test fun ownFinalAndConstantValueReadsDiagnoseWithoutDiscardingHealthyMutableReads() {
        val writer = writer("RestrictedFields")
        writer.visitField(ACC_PUBLIC or ACC_STATIC or ACC_FINAL, "finalValue", "I", null, 7).visitEnd()
        writer.visitField(ACC_PUBLIC or ACC_STATIC, "mutableConstant", "I", null, 3).visitEnd()
        writer.visitField(ACC_PUBLIC or ACC_STATIC, "ordinary", "I", null, null).visitEnd()
        read(writer, "readFinal", "RestrictedFields", "finalValue")
        read(writer, "readConstant", "RestrictedFields", "mutableConstant")
        read(writer, "readOrdinary", "RestrictedFields", "ordinary")
        healthy(writer); writer.visitEnd()
        val bytes = writer.toByteArray()
        for (format in OutputFormat.entries) {
            val engine = Decompiler(DecompilerArgs(outputFormat = format))
            assertEquals(1, engine.load("RestrictedFields.class", bytes))
            val result = engine.decompileAll()
            assertEquals(2, result.errorCount, result.classes.joinToString { it.code })
            val output = result.classes.single().let { DecompiledClass(it.fullName, it.code) }
            assertTrue(output.source.contains("final or ConstantValue JVM field access requires initialization-preserving source"), output.source)
            withCompiledClass(output, format == OutputFormat.KOTLIN) { cls ->
                val target = if (format == OutputFormat.KOTLIN) cls.getField("Companion").get(null) else null
                val owner = target?.javaClass ?: cls
                assertEquals(9, owner.getMethod("healthy").invoke(target))
                assertEquals(0, owner.getMethod("readOrdinary").invoke(target))
                assertTrue(java.lang.reflect.Modifier.isFinal(cls.getDeclaredField("finalValue").modifiers))
            }
        }
    }

    private fun writer(name: String) = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS).apply {
        visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null)
    }
    private fun read(writer: ClassWriter, name: String, owner: String, field: String) {
        writer.visitMethod(ACC_PUBLIC or ACC_STATIC, name, "()I", null, null).apply {
            visitCode(); visitFieldInsn(GETSTATIC, owner, field, "I"); visitInsn(IRETURN); visitMaxs(0, 0); visitEnd()
        }
    }
    private fun healthy(writer: ClassWriter) {
        writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "healthy", "()I", null, null).apply {
            visitCode(); visitIntInsn(BIPUSH, 9); visitInsn(IRETURN); visitMaxs(0, 0); visitEnd()
        }
    }
}
