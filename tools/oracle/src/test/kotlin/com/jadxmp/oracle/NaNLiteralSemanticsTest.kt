package com.jadxmp.oracle

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class NaNLiteralSemanticsTest {
    @Test fun javaQuietNaNPayloadsRoundTrip() = verify(false)
    @Test fun kotlinQuietNaNPayloadsRoundTrip() = verify(true)

    @Test fun javaReconstructionOwnersResistClassMemberAndParameterShadowing() {
        for (name in listOf("Float", "Double", "java", "RawNaN")) verify(false, name)
    }

    @Test fun javaInheritedWrapperTypesCannotCaptureReconstruction() = verify(false, "Child")

    @Test fun javaPackageSiblingCannotCaptureWrapperPackage() = verify(false, "SiblingUser")

    @Test fun incompatibleWrapperAndPackageShadowsRemainExplicitDiagnostics() {
        for (name in listOf("Float", "Double")) verify(false, name, packageSibling = true)
    }

    @Test fun importedWrapperAndPackageShadowsRemainDiagnosed() = verify(false, "ImportedUser")

    private fun verify(kotlin: Boolean, collisionName: String? = null, packageSibling: Boolean = false) {
        // Quiet NaNs are observable via raw-bit APIs without arithmetic. Signalling-NaN
        // execution is host-dependent; common source tests cover their exact input bits.
        val floats = listOf(0x7fc00001, 0x7fffffff, 0xffc00000.toInt(), 0xffc12345.toInt())
        val doubles = listOf(0x7ff8000000000001L, 0x7fffffffffffffffL,
            0xfff8000000000000UL.toLong(), 0xfff923456789abcdUL.toLong())
        val helpers = if (collisionName == "Child") java.nio.file.Files.createTempDirectory("nan-inherited-types").toFile().also { dir ->
            val source = dir.resolve("Base.java")
            source.writeText("package fixtures; public class Base { public static class Float {} public static class Double {} }")
            assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", dir.absolutePath, source.absolutePath))
        } else null
        val file = File.createTempFile("RawNaN", ".smali")
        file.writeText(buildString {
            appendLine(".class public Lfixtures/${collisionName ?: "RawNaN"};")
            appendLine(if (helpers != null) ".super Lfixtures/Base;" else ".super Ljava/lang/Object;")
            if (collisionName != null) for (field in listOf("Float", "Double", "java")) appendLine(".field public static $field:I")
            for ((i, bits) in floats.withIndex()) {
                appendLine(".method public static f$i(${if (collisionName == "ImportedUser") "Lexternal/java;Lexternal/Float;Lexternal/Double;" else if (collisionName != null) "III" else ""})F")
                appendLine(".registers ${if (collisionName != null) 4 else 1}")
                appendLine("const v0, 0x${bits.toUInt().toString(16)}")
                appendLine("return v0")
                appendLine(".end method")
            }
            for ((i, bits) in doubles.withIndex()) {
                appendLine(".method public static d$i(${if (collisionName == "ImportedUser") "Lexternal/java;Lexternal/Float;Lexternal/Double;" else if (collisionName != null) "III" else ""})D")
                appendLine(".registers ${if (collisionName != null) 5 else 2}")
                appendLine("const-wide v0, 0x${bits.toULong().toString(16)}L")
                appendLine("return-wide v0")
                appendLine(".end method")
            }
        })
        val sibling = if (collisionName == "SiblingUser" || packageSibling) File.createTempFile("SiblingJava", ".smali").also {
            it.writeText(".class public Lfixtures/java;\n.super Ljava/lang/Object;\n")
        } else null
        try {
            val dex = SmaliAssembler.assemble(listOfNotNull(file, sibling)).dex!!
            val result = if (collisionName != null) {
                val pass = object : com.jadxmp.api.plugin.PassPlugin {
                    override val id = "nan-shadow-parameters"
                    override fun rootPasses() = listOf(object : com.jadxmp.pipeline.pass.RootPass {
                        override val name = "nan-shadow-parameters"
                        override fun run(root: com.jadxmp.ir.node.IrRoot, context: com.jadxmp.pipeline.pass.PassContext) {
                            for (cls in root.classes) for (method in cls.methods) {
                                method[com.jadxmp.codegen.CodegenKeys.PARAM_NAMES] = listOf("Float", "Double", "java")
                            }
                        }
                    })
                }
                val engine = com.jadxmp.api.Decompiler(com.jadxmp.api.DecompilerArgs(
                    registry = com.jadxmp.api.plugin.PluginRegistry(com.jadxmp.api.plugin.PluginRegistry.default().inputPlugins, listOf(pass))))
                engine.load("nan.dex", dex)
                val output = engine.decompileAll()
                DecompilationResult("nan.dex", output.classes.map { DecompiledClass(it.fullName, it.code) }, output.errorCount)
            } else if (kotlin) KotlinJadxmpDecompiler().decompileKotlin("nan.dex", dex)
                else JadxmpDecompiler().decompile("nan.dex", dex)
            if (packageSibling || collisionName == "ImportedUser") {
                assertEquals(1, result.reportedErrors)
                val source = result.classes.single { it.simpleName == collisionName }.source
                val blockedWrapper = if (collisionName == "ImportedUser") "Float" else collisionName
                org.junit.jupiter.api.Assertions.assertTrue(source.contains("JADXMP ERROR: cannot resolve NaN helper owner java.lang.$blockedWrapper"), source)
                if (collisionName == "ImportedUser") {
                    org.junit.jupiter.api.Assertions.assertTrue(source.contains("import external.java;"), source)
                    org.junit.jupiter.api.Assertions.assertTrue(source.contains("import external.Float;"), source)
                }
                val payloads = if (blockedWrapper == "Float") floats.map { it.toLong() } else doubles
                for (bits in payloads) org.junit.jupiter.api.Assertions.assertTrue(source.contains(bits.toString()), source)
                org.junit.jupiter.api.Assertions.assertFalse(AccuracySignals.recompiles(result.classes).success)
                return
            }
            assertEquals(0, result.reportedErrors)
            withCompiledClasses(result.classes, kotlin, listOfNotNull(helpers)) { loader ->
                val cls = loader.loadClass("fixtures.${collisionName ?: "RawNaN"}")
                val receiver = if (kotlin) cls.getField("Companion").get(null) else null
                val owner = receiver?.javaClass ?: cls
                val parameterTypes = if (collisionName != null) Array(3) { Int::class.javaPrimitiveType!! } else emptyArray()
                val arguments = if (collisionName != null) arrayOf<Any>(1, 2, 3) else emptyArray()
                floats.forEachIndexed { i, bits -> assertEquals(bits, (owner.getMethod("f$i", *parameterTypes).invoke(receiver, *arguments) as Float).toRawBits()) }
                doubles.forEachIndexed { i, bits -> assertEquals(bits, (owner.getMethod("d$i", *parameterTypes).invoke(receiver, *arguments) as Double).toRawBits()) }
            }
        } finally { file.delete(); sibling?.delete(); helpers?.deleteRecursively() }
    }
}
