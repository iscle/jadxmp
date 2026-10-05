package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException

class KotlinJvmStaticInvocationSemanticsTest {
    @Test
    fun stringValueOfKeepsPrimitiveAndReferenceOverloads(@TempDir dir: File) {
        val fixture = dir.resolve("StaticStrings.smali")
        fixture.writeText(buildString {
            appendLine(".class public LStaticStrings;")
            appendLine(".super Ljava/lang/Object;")
            for ((name, type) in listOf("boolean" to "Z", "character" to "C", "integer" to "I", "longValue" to "J", "floatValue" to "F", "doubleValue" to "D", "characters" to "[C", "objectValue" to "Ljava/lang/Object;")) {
                appendLine("""
                    .method public static $name($type)Ljava/lang/String;
                        .locals 1
                        invoke-static {p0${if (type == "J" || type == "D") ", p1" else ""}}, Ljava/lang/String;->valueOf($type)Ljava/lang/String;
                        move-result-object v0
                        return-object v0
                    .end method
                """.trimIndent())
            }
            appendLine("""
                .method public static arrayAsObject([C)Ljava/lang/String;
                    .locals 1
                    invoke-static {p0}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;
                    move-result-object v0
                    return-object v0
                .end method
                .method public static slice([CII)Ljava/lang/String;
                    .locals 1
                    invoke-static {p0, p1, p2}, Ljava/lang/String;->valueOf([CII)Ljava/lang/String;
                    move-result-object v0
                    return-object v0
                .end method
                .method public static nullObject()Ljava/lang/String;
                    .locals 1
                    const/4 v0, 0x0
                    invoke-static {v0}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;
                    move-result-object v0
                    return-object v0
                .end method
                .method public static nullCharacters()Ljava/lang/String;
                    .locals 1
                    const/4 v0, 0x0
                    invoke-static {v0}, Ljava/lang/String;->valueOf([C)Ljava/lang/String;
                    move-result-object v0
                    return-object v0
                .end method
            """.trimIndent())
        })
        withCompiledClass(decompile(fixture), kotlin = true) { cls ->
            val target = cls.getField("Companion").get(null)
            fun call(name: String, type: Class<*>, value: Any): Any? = target.javaClass.getMethod(name, type).invoke(target, value)
            for (value in listOf(false, true)) assertEquals(java.lang.String.valueOf(value), call("boolean", Boolean::class.javaPrimitiveType!!, value))
            for (value in listOf('\u0000', 'a', '\uffff')) assertEquals(java.lang.String.valueOf(value), call("character", Char::class.javaPrimitiveType!!, value))
            for (value in listOf(Int.MIN_VALUE, 0, Int.MAX_VALUE)) assertEquals(java.lang.String.valueOf(value), call("integer", Int::class.javaPrimitiveType!!, value))
            for (value in listOf(Long.MIN_VALUE, 0L, Long.MAX_VALUE)) assertEquals(java.lang.String.valueOf(value), call("longValue", Long::class.javaPrimitiveType!!, value))
            for (value in listOf(Float.NaN, Float.NEGATIVE_INFINITY, -0.0f, 1.25f)) assertEquals(java.lang.String.valueOf(value), call("floatValue", Float::class.javaPrimitiveType!!, value))
            for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.0, 1.25)) assertEquals(java.lang.String.valueOf(value), call("doubleValue", Double::class.javaPrimitiveType!!, value))
            val chars = charArrayOf('a', '\u0000', '\ud83d', '\ude00', 'z')
            assertEquals(String(chars), call("characters", CharArray::class.java, chars))
            assertEquals(chars.toString(), call("arrayAsObject", CharArray::class.java, chars), "Object overload must not stringify array contents")
            assertEquals(String(chars, 1, 3), target.javaClass.getMethod("slice", CharArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(target, chars, 1, 3))
            assertEquals("null", target.javaClass.getMethod("nullObject").invoke(target))
            val thrown = runCatching { target.javaClass.getMethod("nullCharacters").invoke(target) }.exceptionOrNull()
            assertTrue(thrown is InvocationTargetException && thrown.cause is NullPointerException)
            var calls = 0
            val objectValue = object { override fun toString(): String { calls++; return "side effect" } }
            assertEquals("side effect", call("objectValue", Any::class.java, objectValue))
            assertEquals(1, calls)
        }
    }

    @Test
    fun boxedClassPrimitiveReturningStaticsKeepJvmBehavior(@TempDir dir: File) {
        val fixture = dir.resolve("StaticParsers.smali")
        val signatures = listOf(
            Triple("Boolean", "parseBoolean", "Z"), Triple("Byte", "parseByte", "B"),
            Triple("Short", "parseShort", "S"), Triple("Integer", "parseInt", "I"),
            Triple("Long", "parseLong", "J"), Triple("Float", "parseFloat", "F"), Triple("Double", "parseDouble", "D"),
        )
        fixture.writeText(buildString {
            appendLine(".class public LStaticParsers;")
            appendLine(".super Ljava/lang/Object;")
            for ((owner, name, type) in signatures) {
                val wide = type == "J" || type == "D"
                appendLine("""
                    .method public static $name(Ljava/lang/String;)$type
                        .locals 2
                        invoke-static {p0}, Ljava/lang/$owner;->$name(Ljava/lang/String;)$type
                        move-result${if (wide) "-wide" else ""} v0
                        return${if (wide) "-wide" else ""} v0
                    .end method
                    .method public static ${name}Null()$type
                        .locals 2
                        const/4 v0, 0x0
                        invoke-static {v0}, Ljava/lang/$owner;->$name(Ljava/lang/String;)$type
                        move-result${if (wide) "-wide" else ""} v0
                        return${if (wide) "-wide" else ""} v0
                    .end method
                """.trimIndent())
            }
        })
        withCompiledClass(decompile(fixture), kotlin = true) { cls ->
            val target = cls.getField("Companion").get(null)
            for ((owner, name) in signatures) {
                val original = Class.forName("java.lang.$owner").getMethod(name, String::class.java)
                val generated = target.javaClass.getMethod(name, String::class.java)
                val expectedNull = runCatching { original.invoke(null, null) }
                val actualNull = runCatching { target.javaClass.getMethod("${name}Null").invoke(target) }
                if (expectedNull.isSuccess) assertEquals(expectedNull.getOrThrow(), actualNull.getOrThrow(), "$owner null")
                else assertEquals(expectedNull.exceptionOrNull()!!.cause!!::class, actualNull.exceptionOrNull()!!.cause!!::class, "$owner null")
                for (input in listOf("true", "FALSE", "-1", "42", "NaN", "not a number")) {
                    val expected = runCatching { original.invoke(null, input) }
                    val actual = runCatching { generated.invoke(target, input) }
                    if (expected.isSuccess) assertEquals(expected.getOrThrow(), actual.getOrThrow(), "$owner $input")
                    else assertEquals(expected.exceptionOrNull()!!.cause!!::class, actual.exceptionOrNull()!!.cause!!::class, "$owner $input")
                }
            }
        }
    }

    @Test
    fun staticAliasesCannotBeCapturedByJavaPropertyOrDeclarationNames(@TempDir dir: File) {
        val fixture = dir.resolve("JvmString.smali")
        fixture.writeText("""
            .class public LJvmString;
            .super Ljava/lang/Object;
            .field public java:I
            .field public JvmBoolean:I
            .field public JvmString2:I
            .field public dependency:Ltesting/JvmString4;
            .method public static text(Ljava/lang/Object;I)Ljava/lang/String;
                .locals 1
                .param p0, "java"
                .param p1, "JvmString3"
                invoke-static {p0}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;
                move-result-object v0
                return-object v0
            .end method
            .method public parse(Ljava/lang/String;)Z
                .locals 1
                .param p1, "text"
                invoke-static {p1}, Ljava/lang/Boolean;->parseBoolean(Ljava/lang/String;)Z
                move-result v0
                return v0
            .end method
        """.trimIndent())
        val importedType = DecompiledClass("testing.JvmString4", "package testing; public class JvmString4 {}")
        JavaCompilation.compile(listOf(importedType)).use { dependency ->
            assertTrue(dependency.result.success, dependency.result.diagnostics.toString())
            withCompiledClass(decompile(fixture), kotlin = true, additionalClasspath = listOf(dependency.output)) { cls ->
                val target = cls.getField("Companion").get(null)
                assertEquals("value", target.javaClass.getMethod("text", Any::class.java, Int::class.javaPrimitiveType).invoke(target, "value", 42))
                assertEquals(true, cls.getMethod("parse", String::class.java).invoke(cls.getConstructor().newInstance(), "true"))
            }
        }
    }

    @Test
    fun staticAliasesPreserveDefaultPackageReferenceDescriptors(@TempDir dir: File) {
        val helper = DecompiledClass("JvmString", "public class JvmString { public int value() { return 73; } }")
        val fixture = dir.resolve("DefaultPackageReference.smali")
        fixture.writeText("""
            .class public LDefaultPackageReference;
            .super Ljava/lang/Object;
            .field public dependency:LJvmString;
            .method public static text(LJvmString;)Ljava/lang/String;
                .locals 1
                invoke-virtual {p0}, LJvmString;->value()I
                move-result v0
                invoke-static {v0}, Ljava/lang/String;->valueOf(I)Ljava/lang/String;
                move-result-object v0
                return-object v0
            .end method
        """.trimIndent())
        JavaCompilation.compile(listOf(helper)).use { dependency ->
            assertTrue(dependency.result.success, dependency.result.diagnostics.toString())
            withCompiledClass(decompile(fixture), kotlin = true, additionalClasspath = listOf(dependency.output)) { cls ->
                val originalType = cls.classLoader.loadClass("JvmString")
                val target = cls.getField("Companion").get(null)
                val value = originalType.getConstructor().newInstance()
                assertEquals(originalType, cls.getDeclaredField("dependency").type)
                assertEquals("73", target.javaClass.getMethod("text", originalType).invoke(target, value))
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
