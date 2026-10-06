package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import com.jadxmp.api.plugin.InputPlugin
import com.jadxmp.api.plugin.PluginRegistry
import com.jadxmp.api.plugin.PassPlugin
import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.pipeline.pass.RootPass
import com.jadxmp.pipeline.pass.PassContext
import com.jadxmp.input.AnnotationData
import com.jadxmp.input.ClassData
import com.jadxmp.input.CodeReader
import com.jadxmp.input.FieldData
import com.jadxmp.input.ListCodeLoader
import com.jadxmp.input.MethodData
import com.jadxmp.input.MethodRef
import java.lang.reflect.InvocationTargetException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MissingBodySemanticsTest {
    @Test fun failedMethodsAndConstructorsCompileButAlwaysThrowInBothLanguages() {
        for (kotlin in listOf(false, true)) {
            val result = decompile(kotlin, initializer = false)
            assertEquals(3, result.errorCount)
            val generated = result.classes.single()
            assertEquals(3, generated.code.split("JADXMP ERROR: failed to load method body: missing fixture body").size - 1)
            withCompiledClass(DecompiledClass(generated.fullName, generated.code), kotlin) { cls ->
                val target = if (kotlin) cls.getField("Companion").get(null) else null
                val owner = target?.javaClass ?: cls
                for (method in listOf("brokenInt", "brokenVoid")) {
                    val failure = assertThrows(InvocationTargetException::class.java) { owner.getMethod(method).invoke(target) }
                    assertInstanceOf(UnsupportedOperationException::class.java, failure.cause)
                    assertEquals("Method body unavailable", failure.cause!!.message)
                }
                val failure = assertThrows(InvocationTargetException::class.java) { cls.getConstructor().newInstance() }
                assertInstanceOf(UnsupportedOperationException::class.java, failure.cause)
            }
        }
    }

    @Test fun failedStaticInitializerRemainsVisibleAndPreventsSuccessfulInitialization() {
        for (kotlin in listOf(false, true)) {
            val result = decompile(kotlin, initializer = true)
            assertEquals(1, result.errorCount)
            val generated = result.classes.single()
            assertTrue(generated.code.contains("JADXMP ERROR: failed to load method body: missing fixture body"))
            withCompiledClass(DecompiledClass(generated.fullName, generated.code), kotlin) { cls ->
                val failure = assertThrows(ExceptionInInitializerError::class.java) {
                    Class.forName(cls.name, true, cls.classLoader)
                }
                assertInstanceOf(UnsupportedOperationException::class.java, failure.cause)
            }
        }
    }

    @Test fun javaExceptionFallbackResistsNameCollisions() = verifyNameCollisions(false)
    @Test fun kotlinExceptionFallbackResistsNameCollisions() = verifyNameCollisions(true)

    private fun verifyNameCollisions(kotlin: Boolean) {
        for (name in listOf("java", "UnsupportedOperationException", "JvmUnsupportedOperationException")) {
            val result = decompile(kotlin, initializer = false, collisionName = name)
            assertEquals(3, result.errorCount)
            val generated = result.classes.single()
            withCompiledClass(DecompiledClass(generated.fullName, generated.code), kotlin) { cls ->
                val target = if (kotlin) cls.getField("Companion").get(null) else null
                val owner = target?.javaClass ?: cls
                val types = Array(3) { Int::class.javaPrimitiveType!! }
                val failure = assertThrows(InvocationTargetException::class.java) {
                    owner.getMethod("brokenInt", *types).invoke(target, 1, 2, 3)
                }
                assertInstanceOf(UnsupportedOperationException::class.java, failure.cause)
            }
        }
    }

    private fun decompile(kotlin: Boolean, initializer: Boolean, collisionName: String? = null): com.jadxmp.api.DecompilationResult {
        val className = collisionName ?: if (initializer) "FailedInitializer" else "FailedBodies"
        val owner = "L$className;"
        fun method(name: String, result: String, flags: Int): MethodData = object : MethodData {
            override val ref = object : MethodRef {
                override val declaringClassType = owner
                override val name = name
                override val returnType = result
                override val parameterTypes = if (collisionName != null && name != "<init>") listOf("I", "I", "I") else emptyList<String>()
            }
            override val accessFlags = flags
            override val annotations = emptyList<AnnotationData>()
            override val parameterAnnotations = emptyList<List<AnnotationData>>()
            override val codeReader: CodeReader get() = error("missing fixture body")
        }
        val declaration = object : ClassData {
            override val type = owner
            override val accessFlags = 0x0011
            override val superType = "Ljava/lang/Object;"
            override val interfaces = emptyList<String>()
            override val sourceFile: String? = null
            override val fields = emptyList<FieldData>()
            override val annotations = emptyList<AnnotationData>()
            override val inputFileName = "failed-body-fixture"
            override val methods = if (initializer) listOf(method("<clinit>", "V", 8)) else listOf(
                method("<init>", "V", 1), method("brokenVoid", "V", 9), method("brokenInt", "I", 9),
            )
            override fun disassemble() = "body unavailable"
        }
        val plugin = object : InputPlugin {
            override val id = "failed-body-fixture"
            override fun tryLoad(name: String, bytes: ByteArray) = ListCodeLoader(listOf(declaration))
        }
        var parameterPassRan = false
        val parameterNames = object : PassPlugin {
            override val id = "collision-parameter-names"
            override fun rootPasses() = listOf(object : RootPass {
                override val name = "collision-parameter-names"
                override fun run(root: IrRoot, context: PassContext) {
                    parameterPassRan = true
                    for (cls in root.classes) for (method in cls.methods) if (method.argTypes.size == 3) {
                        method[CodegenKeys.PARAM_NAMES] = listOf("java", "UnsupportedOperationException", "JvmUnsupportedOperationException")
                    }
                }
            })
        }
        val engine = Decompiler(DecompilerArgs(
            outputFormat = if (kotlin) OutputFormat.KOTLIN else OutputFormat.JAVA,
            registry = PluginRegistry(listOf(plugin), listOf(parameterNames)),
        ))
        assertEquals(1, engine.load("fixture", byteArrayOf()))
        assertTrue(parameterPassRan, "Collision parameter injection must run")
        return engine.decompileAll()
    }
}
