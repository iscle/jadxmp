package com.jadxmp.input.dex

import com.jadxmp.input.ClassData
import com.jadxmp.input.IndexType
import com.jadxmp.input.MethodData
import com.jadxmp.input.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end round-trip of the real `hello.dex` fixture (copied from the jadx corpus). Read from the
 * shared commonTest resources; asserts the parser recovers the expected class, methods, and body.
 */
class HelloDexTest {

    private fun loadHelloDex(): List<ClassData> {
        val bytes = javaClass.classLoader.getResourceAsStream("hello.dex")
            ?.readBytes()
            ?: error("hello.dex test resource not found")
        return DexInput.load("hello.dex", bytes).classes
    }

    @Test
    fun parsesHelloWorldClass() {
        val classes = loadHelloDex()
        val helloWorld = classes.single { it.type == "LHelloWorld;" }
        assertEquals("Ljava/lang/Object;", helloWorld.superType)
        assertTrue(helloWorld.interfaces.isEmpty())
        assertEquals("HelloWorld.java", helloWorld.sourceFile)

        val methodNames = helloWorld.methods.map { it.ref.name }.toSet()
        assertTrue("<init>" in methodNames, "expected constructor, got $methodNames")
        assertTrue("main" in methodNames, "expected main, got $methodNames")
    }

    @Test
    fun mainHasExpectedSignatureAndBody() {
        val helloWorld = loadHelloDex().single { it.type == "LHelloWorld;" }
        val main = helloWorld.methods.single { it.ref.name == "main" }
        assertEquals("V", main.ref.returnType)
        assertEquals(listOf("[Ljava/lang/String;"), main.ref.parameterTypes)

        val (opcodes, strings) = collectBody(main)
        assertTrue("Hello, World!" in strings, "expected the greeting string literal, got $strings")
        assertTrue(opcodes.any { it.name.startsWith("INVOKE") }, "expected an invoke in main")
        assertTrue(Opcode.RETURN_VOID in opcodes, "expected return-void in main")
    }

    @Test
    fun disassemblesToSmali() {
        val helloWorld = loadHelloDex().single { it.type == "LHelloWorld;" }
        val smali = helloWorld.disassemble()
        assertTrue(smali.contains(".class LHelloWorld;"), smali)
        assertTrue(smali.contains(".super Ljava/lang/Object;"), smali)
        // Concrete method block: signature + register directive + real instructions.
        assertTrue(smali.contains(".method public static main([Ljava/lang/String;)V"), smali)
        assertTrue(smali.contains("    .registers 3"), smali)
        assertTrue(smali.contains("sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;"), smali)
        assertTrue(smali.contains("const-string v1, \"Hello, World!\""), smali)
        assertTrue(
            smali.contains("invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V"),
            smali,
        )
        assertTrue(smali.contains("    return-void"), smali)
        assertTrue(smali.contains(".end method"), smali)
    }

    private fun collectBody(method: MethodData): Pair<List<Opcode>, List<String>> {
        val code = method.codeReader
        assertNotNull(code)
        val opcodes = mutableListOf<Opcode>()
        val strings = mutableListOf<String>()
        code.visitInstructions { insn ->
            insn.decode()
            opcodes.add(insn.opcode)
            if (insn.indexType == IndexType.STRING_REF) strings.add(insn.indexAsString())
        }
        return opcodes to strings
    }
}
