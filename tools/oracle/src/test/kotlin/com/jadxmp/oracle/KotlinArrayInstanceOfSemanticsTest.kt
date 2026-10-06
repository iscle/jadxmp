package com.jadxmp.oracle

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KotlinArrayInstanceOfSemanticsTest {
    @Test fun foldedArrayTestOperandExecutesExactlyOnceIncludingNull() {
        val directory = Files.createTempDirectory("jadxmp-array-instanceof").toFile()
        try {
            val source = DecompiledClass("ArrayEffects", """
                public class ArrayEffects {
                    public static int calls;
                    public static Object value(Object input) { calls++; return input; }
                }
            """.trimIndent())
            JavaCompilation.compile(listOf(source), release = 17).use { helper ->
                assertTrue(helper.result.success, helper.result.diagnostics.toString())
                val smali = directory.resolve("ArrayProbe.smali").apply { writeText("""
                    .class public LArrayProbe;
                    .super Ljava/lang/Object;
                    .method public static test(Ljava/lang/Object;)Z
                        .locals 1
                        invoke-static {p0}, LArrayEffects;->value(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v0
                        instance-of v0, v0, [Ljava/lang/String;
                        return v0
                    .end method
                """.trimIndent()) }
                val assembly = SmaliAssembler.assemble(smali)
                assertTrue(assembly.ok, assembly.error)
                val result = KotlinJadxmpDecompiler().decompileKotlin(smali.name, assembly.dex!!)
                assertEquals(0, result.reportedErrors, result.classes.joinToString { it.source })
                withCompiledClass(result.classes.single(), true, listOf(helper.output)) { cls ->
                    val effects = cls.classLoader.loadClass("ArrayEffects")
                    val calls = effects.getField("calls")
                    val target = cls.getField("Companion").get(null)
                    val method = target.javaClass.getMethod("test", Any::class.java)
                    for (value in listOf(null, "text", intArrayOf(1), arrayOf(Any()), arrayOf("text", null))) {
                        calls.setInt(null, 0)
                        assertEquals(value is Array<*> && value.javaClass.componentType == String::class.java, method.invoke(target, value))
                        assertEquals(1, calls.getInt(null), result.classes.single().source)
                    }
                }
            }
        } finally { directory.deleteRecursively() }
    }
}
