package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import java.lang.reflect.InvocationTargetException
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Verifier-valid stores which source Java cannot spell without altering checks or narrowing. */
class NativeJvmRawArrayStoreSemanticsTest {
    @Test fun rawStoresKeepNarrowingLowBitsIdentityAndRuntimeArrayStoreChecks() {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(V17, ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT, "NativeRawArrayStore", null, "java/lang/Object", null)
        for ((type, opcode) in listOf("B" to BASTORE, "Z" to BASTORE, "C" to CASTORE, "S" to SASTORE)) {
            writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "store$type", "([$type" + "I)I", null, null).apply {
                visitCode(); visitVarInsn(ALOAD, 0); visitInsn(ICONST_0); visitVarInsn(ILOAD, 1)
                // Keep the original Int result while the consumed copy is narrowed by xastore.
                visitInsn(DUP_X2); visitInsn(opcode); visitInsn(IRETURN); visitMaxs(0, 0); visitEnd()
            }
        }
        for ((name, descriptor) in listOf("strings" to "[Ljava/lang/String;", "nested" to "[[I", "alias" to "[Ljava/lang/String;")) {
            writer.visitMethod(ACC_PUBLIC or ACC_STATIC, name, "($descriptor" + "ILjava/lang/Object;)Ljava/lang/Object;", null, null).apply {
                visitCode(); visitVarInsn(ALOAD, 0)
                if (name == "alias") visitInsn(DUP)
                visitVarInsn(ILOAD, 1); visitVarInsn(ALOAD, 2)
                if (name != "alias") visitInsn(DUP_X2)
                visitInsn(AASTORE); visitInsn(ARETURN); visitMaxs(0, 0); visitEnd()
            }
        }
        writer.visitEnd()
        val bytes = writer.toByteArray()
        val original = object : ClassLoader(javaClass.classLoader) {
            fun original() = defineClass("NativeRawArrayStore", bytes, 0, bytes.size)
        }.original()
        val reference = ReferenceDecompiler().decompile("NativeRawArrayStore.class", bytes)
        assertEquals(0, reference.reportedErrors)
        val referenceCompilation = AccuracySignals.recompiles(reference.classes)
        // Measured original-pin limitation, not runtime parity or an input-policy exemption:
        // these instructions verify and execute, but jadx omits required source conversions.
        assertFalse(referenceCompilation.success, reference.classes.joinToString { it.source })
        val referenceDiagnostics = referenceCompilation.diagnostics.joinToString("\n")
        for (message in listOf("possible lossy conversion from int to byte", "int cannot be converted to boolean",
            "possible lossy conversion from int to char", "possible lossy conversion from int to short",
            "java.lang.Object cannot be converted to java.lang.String", "java.lang.Object cannot be converted to int[]")) {
            assertTrue(referenceDiagnostics.contains(message), referenceDiagnostics)
        }
        val outputs = OutputFormat.entries.map { format ->
            val engine = Decompiler(DecompilerArgs(outputFormat = format))
            assertEquals(1, engine.load("NativeRawArrayStore.class", bytes))
            val result = engine.decompileAll()
            assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
            result.classes.single().let { DecompiledClass(it.fullName, it.code) } to (format == OutputFormat.KOTLIN)
        }
        val integer = Int::class.javaPrimitiveType!!
        val numbers = listOf(Int.MIN_VALUE, -65537, -32769, -129, -3, -2, -1, 0, 1, 2, 3, 127, 128, 255, 256, 32768, 65535, Int.MAX_VALUE)
        val primitiveFactories = listOf("B" to { byteArrayOf(9) }, "Z" to { booleanArrayOf(true) },
            "C" to { charArrayOf('x') }, "S" to { shortArrayOf(9) })
        val identity = Any()
        val row = intArrayOf(19)
        for ((output, kotlin) in outputs) withCompiledClass(output, kotlin) { cls ->
            val target = if (kotlin) cls.getField("Companion").get(null) else null
            val owner = target?.javaClass ?: cls
            for ((type, factory) in primitiveFactories) for (number in numbers) for (nullArray in listOf(false, true)) {
                val expectedArray = if (nullArray) null else factory()
                val actualArray = if (nullArray) null else factory()
                val types: Array<Class<*>> = arrayOf(factory().javaClass, integer)
                val expected = invoke(original, null, "store$type", types, listOf(expectedArray, number))
                val actual = invoke(owner, target, "store$type", types, listOf(actualArray, number))
                if (!nullArray) assertEquals(number, expected, "original JVM must retain the duplicated Int")
                assertEquals(expected, actual, "store$type($number)\n${output.source}")
                assertEquals(snapshot(expectedArray), snapshot(actualArray), "store$type($number)\n${output.source}")
            }
            for (name in listOf("strings", "nested", "alias")) for (value in listOf(null, "text", row, identity)) {
                for (index in listOf(-1, 0, 1)) for (nullArray in listOf(false, true)) {
                    fun array(): Array<*>? = if (nullArray) null else if (name == "nested") arrayOfNulls<IntArray>(1) else arrayOfNulls<String>(1)
                    val expectedArray = array()
                    val actualArray = array()
                    val arrayType = if (name == "nested") Array<IntArray>::class.java else Array<String>::class.java
                    val types: Array<Class<*>> = arrayOf(arrayType, integer, Any::class.java)
                    val expected = invoke(original, null, name, types, listOf(expectedArray, index, value))
                    val actual = invoke(owner, target, name, types, listOf(actualArray, index, value))
                    if (expected is Failure) assertEquals(expected, actual, "$name($index,$value)\n${output.source}")
                    else if (name == "alias") {
                        assertSame(expectedArray, expected, "original JVM must retain the array alias")
                        assertSame(actualArray, actual, output.source)
                    } else {
                        assertSame(value, expected, "original JVM must retain the duplicated reference")
                        assertSame(value, actual, output.source)
                    }
                    assertEquals(expectedArray?.toList(), actualArray?.toList(), output.source)
                }
            }
        }
    }

    private fun invoke(owner: Class<*>, target: Any?, name: String, types: Array<Class<*>>, args: List<Any?>): Any? = try {
        owner.getMethod(name, *types).invoke(target, *args.toTypedArray())
    } catch (error: InvocationTargetException) { Failure(error.targetException.javaClass.name) }
    private fun snapshot(value: Any?): Any? = when (value) {
        is ByteArray -> value.toList(); is BooleanArray -> value.toList(); is CharArray -> value.toList(); is ShortArray -> value.toList()
        else -> value
    }
    private data class Failure(val type: String)
}
