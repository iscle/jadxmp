package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException

class KotlinJvmExpressionSemanticsTest {
    @Test
    fun charSequenceProjectionKeepsInterfaceDispatch(@TempDir dir: File) {
        val helper = DecompiledClass("testing.ShadowSequence", """
            package testing;
            public class ShadowSequence implements CharSequence {
                public int length() { return 1; }
                public char charAt(int index) { return 'a'; }
                public char get(int index) { return 'b'; }
                public CharSequence subSequence(int start, int end) { return this; }
            }
        """.trimIndent())
        val smali = dir.resolve("InterfaceDispatch.smali")
        smali.writeText("""
            .class public LInterfaceDispatch;
            .super Ljava/lang/Object;
            .method public static character(Ltesting/ShadowSequence;I)C
                .locals 1
                invoke-interface {p0, p1}, Ljava/lang/CharSequence;->charAt(I)C
                move-result v0
                return v0
            .end method
        """.trimIndent())
        JavaCompilation.compile(listOf(helper)).use { dependency ->
            assertTrue(dependency.result.success, dependency.result.diagnostics.toString())
            withCompiledClass(decompile(smali), kotlin = true, additionalClasspath = listOf(dependency.output)) { cls ->
                val target = cls.getField("Companion").get(null)
                val receiver = cls.classLoader.loadClass(helper.fullName).getConstructor().newInstance()
                val method = target.javaClass.getMethod("character", receiver.javaClass, Int::class.javaPrimitiveType)
                assertEquals('a', method.invoke(target, receiver, 0), "invoke-interface must not bind the unrelated concrete get(Int)")
            }
        }
    }

    @Test
    fun stringCorpusCompilesAndPreservesDigitWrites() {
        val source = decompile(Corpus.smaliDir().resolve("variables/TestVariables7.smali"))
        withCompiledClass(source, kotlin = true) { cls ->
            val instance = cls.getConstructor().newInstance()
            val method = cls.getMethod("test", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            for (value in listOf(Int.MIN_VALUE, -1, 0, 1, 7, 8, 255, Int.MAX_VALUE)) {
                val actual = ByteArray(20) { 127 }
                val expected = actual.copyOf()
                val octal = Integer.toOctalString(value)
                octal.forEachIndexed { index, c -> expected[16 - octal.length + index] = c.code.toByte() }
                assertEquals(octal.sumOf { it.code - '0'.code }, method.invoke(instance, actual, 16, value))
                assertArrayEquals(expected, actual, "octal writes for $value")
            }
        }
    }

    @Test
    fun getClassCorpusCompilesWithoutRecursiveEqualsFastPath() {
        val source = decompile(Corpus.smaliDir().resolve("conditions/TestTernaryInIf2.smali"))
        withCompiledClass(source, kotlin = true) { cls ->
            val instance = cls.getConstructor().newInstance()
            assertEquals(true, cls.getMethod("equals", Any::class.java).invoke(instance, instance))
            assertEquals(false, cls.getMethod("equals", Any::class.java).invoke(instance, null))
            assertEquals(false, cls.getMethod("equals", Any::class.java).invoke(instance, "different class"))
        }
    }

    @Test
    fun exactJvmCallsAndReferenceIdentityExecute(@TempDir dir: File) {
        val fixture = dir.resolve("JvmExpressions.smali")
        fixture.writeText("""
            .class public LJvmExpressions;
            .super Ljava/lang/Object;
            .field public javaClass:Ljava/lang/String;
            .method public typeSelf()Ljava/lang/Class;
                .locals 1
                invoke-virtual {p0}, LJvmExpressions;->getClass()Ljava/lang/Class;
                move-result-object v0
                return-object v0
            .end method
            .method public static type(Ljava/lang/Object;)Ljava/lang/Class;
                .locals 1
                invoke-virtual {p0}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
                move-result-object v0
                return-object v0
            .end method
            .method public static length(Ljava/lang/String;)I
                .locals 1
                invoke-virtual {p0}, Ljava/lang/String;->length()I
                move-result v0
                return v0
            .end method
            .method public static sequenceLength(Ljava/lang/CharSequence;)I
                .locals 1
                invoke-interface {p0}, Ljava/lang/CharSequence;->length()I
                move-result v0
                return v0
            .end method
            .method public static character(Ljava/lang/String;I)C
                .locals 1
                invoke-virtual {p0, p1}, Ljava/lang/String;->charAt(I)C
                move-result v0
                return v0
            .end method
            .method public static sequenceCharacter(Ljava/lang/CharSequence;I)C
                .locals 1
                invoke-interface {p0, p1}, Ljava/lang/CharSequence;->charAt(I)C
                move-result v0
                return v0
            .end method
            .method public static same(Ljava/lang/Object;Ljava/lang/Object;)Z
                .locals 1
                if-ne p0, p1, :different
                const/4 v0, 0x1
                return v0
                :different
                const/4 v0, 0x0
                return v0
            .end method
            .method public static different(Ljava/lang/Object;Ljava/lang/Object;)Z
                .locals 1
                if-eq p0, p1, :same
                const/4 v0, 0x1
                return v0
                :same
                const/4 v0, 0x0
                return v0
            .end method
            .method public static booleanByte(ZB)Z
                .locals 1
                if-ne p0, p1, :different
                const/4 v0, 0x1
                return v0
                :different
                const/4 v0, 0x0
                return v0
            .end method
            .method public static characterShort(CS)Z
                .locals 1
                if-eq p0, p1, :same
                const/4 v0, 0x1
                return v0
                :same
                const/4 v0, 0x0
                return v0
            .end method
        """.trimIndent())
        withCompiledClass(decompile(fixture), kotlin = true) { cls ->
            val target = cls.getField("Companion").get(null)
            val methods = target.javaClass
            assertEquals(cls, cls.getMethod("typeSelf").invoke(cls.getConstructor().newInstance()))
            val text = "a\uD83D\uDE00z"
            var lengthReads = 0
            var characterReads = 0
            val sequence = object : CharSequence {
                override val length: Int get() { lengthReads++; return text.length }
                override fun get(index: Int): Char { characterReads++; return text[index] }
                override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)
            }
            assertEquals(String::class.java, methods.getMethod("type", Any::class.java).invoke(target, text))
            assertEquals(Int::class.javaObjectType, methods.getMethod("type", Any::class.java).invoke(target, 42))
            val shadowed = object { val javaClass: String = "not a class" }
            assertEquals(shadowed::class.java, methods.getMethod("type", Any::class.java).invoke(target, shadowed))
            assertEquals(4, methods.getMethod("length", String::class.java).invoke(target, text))
            assertEquals(4, methods.getMethod("sequenceLength", CharSequence::class.java).invoke(target, sequence))
            for ((name, type, value) in listOf(
                Triple("character", String::class.java, text),
                Triple("sequenceCharacter", CharSequence::class.java, sequence),
            )) {
                val method = methods.getMethod(name, type, Int::class.javaPrimitiveType)
                for (index in text.indices) assertEquals(text[index], method.invoke(target, value, index))
                for (index in listOf(-1, text.length)) {
                    val thrown = runCatching { method.invoke(target, value, index) }.exceptionOrNull()
                    assertTrue(thrown is InvocationTargetException && thrown.cause is IndexOutOfBoundsException)
                }
            }
            assertEquals(1, lengthReads, "property mapping must dispatch exactly once")
            assertEquals(text.length + 2, characterReads, "index mapping must dispatch once even when throwing")
            val first = String(charArrayOf('a', 'b'))
            val second = String(charArrayOf('a', 'b'))
            for ((name, sameResult) in listOf("same" to true, "different" to false)) {
                val method = methods.getMethod(name, Any::class.java, Any::class.java)
                assertEquals(sameResult, method.invoke(target, first, first))
                assertEquals(!sameResult, method.invoke(target, first, second), "equal content must not imply equal references")
            }
            val booleanByte = methods.getMethod("booleanByte", Boolean::class.javaPrimitiveType, Byte::class.javaPrimitiveType)
            for (flag in listOf(false, true)) for (number in listOf<Byte>(-128, -1, 0, 1, 2, 127)) {
                assertEquals((if (flag) 1 else 0) == number.toInt(), booleanByte.invoke(target, flag, number))
            }
            val characterShort = methods.getMethod("characterShort", Char::class.javaPrimitiveType, Short::class.javaPrimitiveType)
            for (character in listOf('\u0000', '\u0001', '\u1234', '\uffff')) {
                for (number in listOf<Short>(Short.MIN_VALUE, -1, 0, 1, 0x34, 0x1234, Short.MAX_VALUE)) {
                    assertEquals(character.code != number.toInt(), characterShort.invoke(target, character, number))
                }
            }
        }
    }

    private fun decompile(smali: File): DecompiledClass {
        val assembled = SmaliAssembler.assemble(smali)
        assertTrue(assembled.ok, assembled.error)
        val result = KotlinJadxmpDecompiler().decompileKotlin(smali.name, assembled.dex!!)
        assertEquals(0, result.reportedErrors, result.classes.joinToString { it.source })
        return result.classes.single()
    }
}
