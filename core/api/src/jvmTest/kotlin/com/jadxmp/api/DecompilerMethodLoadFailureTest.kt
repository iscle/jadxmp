package com.jadxmp.api

import com.jadxmp.api.plugin.InputPlugin
import com.jadxmp.api.plugin.PluginRegistry
import com.jadxmp.input.ClassData
import com.jadxmp.input.CodeReader
import com.jadxmp.input.ListCodeLoader
import com.jadxmp.input.MethodData
import com.jadxmp.input.MethodRef
import com.jadxmp.input.dex.DexInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DecompilerMethodLoadFailureTest {
    @Test fun aBrokenBodyRemainsVisibleWithoutLosingHealthyMethodsInEitherLanguage() {
        val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream("hello.dex")).use { it.readBytes() }
        for (format in OutputFormat.entries) {
            val original = DexInput.load("hello.dex", bytes)
            val classes = original.classes.map { source ->
                if (source.type != "LHelloWorld;") source else {
                    val template = source.methods.first()
                    val broken = object : MethodData by template {
                        override val ref = object : MethodRef by template.ref {
                            override val name = "brokenBody"
                            override val returnType = "V"
                            override val parameterTypes = emptyList<String>()
                        }
                        override val accessFlags = 0x0009 // public static
                        override val codeReader: CodeReader get() = throw IllegalArgumentException("invalid body payload")
                    }
                    object : ClassData by source {
                        override val methods = source.methods + broken
                    }
                }
            }
            val plugin = object : InputPlugin {
                override val id = "method-load-test"
                override fun tryLoad(name: String, bytes: ByteArray) = ListCodeLoader(classes)
            }
            val decompiler = Decompiler(DecompilerArgs(outputFormat = format, registry = PluginRegistry(listOf(plugin))))
            assertEquals(original.classes.size, decompiler.load("fixture", bytes))
            val result = assertNotNull(decompiler.decompileClass("HelloWorld"))
            assertTrue(result.code.contains("brokenBody"), result.code)
            assertTrue(result.code.contains("invalid body payload"), result.code)
            assertTrue(result.code.contains("JADXMP ERROR"), result.code)
            assertTrue(result.code.contains("println"), "healthy method disappeared:\n${result.code}")
            assertTrue(result.metadata.errorCount > 0)
        }
    }
}
