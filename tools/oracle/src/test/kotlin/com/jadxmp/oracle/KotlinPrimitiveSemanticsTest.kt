package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KotlinPrimitiveSemanticsTest {
    @Test
    fun longShiftsUseIntBooleanDistance(@TempDir dir: File) {
        val smali = dir.resolve("BooleanShifts.smali")
        smali.writeText(buildString {
            appendLine(".class public LBooleanShifts;")
            appendLine(".super Ljava/lang/Object;")
            for (op in listOf("shl", "shr", "ushr")) {
                appendLine(".method public static $op(JZ)J")
                appendLine("    .locals 2")
                appendLine("    $op-long v0, p0, p2")
                appendLine("    return-wide v0")
                appendLine(".end method")
            }
        })
        val source = decompile(smali)
        withCompiledClass(source, kotlin = true) { cls ->
            val companion = cls.getField("Companion").get(null)
            for (value in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
                for (flag in listOf(false, true)) {
                    val distance = if (flag) 1 else 0
                    val expected = mapOf("shl" to (value shl distance), "shr" to (value shr distance), "ushr" to (value ushr distance))
                    for ((name, result) in expected) {
                        val method = companion.javaClass.getMethod(name, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                        assertEquals(result, method.invoke(companion, value, flag), "$name($value, $flag)")
                    }
                }
            }
        }
    }

    @Test
    fun booleanConversionCorpusRecompiles() {
        val names = listOf("Byte", "Char", "Double", "Float", "Int", "Int2", "Long", "Short")
        for (name in names) {
            val source = decompile(Corpus.smaliDir().resolve("conditions/TestBooleanTo$name.smali"))
            val compiled = KotlinAccuracySignals.recompiles(listOf(source))
            assertTrue(compiled.success, "BooleanTo$name: ${compiled.errors}\n${source.source}")
        }
    }

    @Test
    fun booleanXorCorpusExecutes() {
        val source = decompile(Corpus.smaliDir().resolve("arith/TestXor.smali"))
        withCompiledClass(source, kotlin = true) { cls ->
            val instance = cls.getConstructor().newInstance()
            assertEquals(true, cls.getMethod("test").invoke(instance))
            assertEquals(false, cls.getMethod("test1").invoke(instance))
            assertEquals(true, cls.getMethod("test2").invoke(instance))
        }
    }

    @Test
    fun wrapperFactoriesPreserveBoxedSignaturesAndOverloadSelection(@TempDir dir: File) {
        val smali = dir.resolve("BoxedValues.smali")
        smali.writeText("""
            .class public LBoxedValues;
            .super Ljava/lang/Object;
            .method public static boxed(Z)Ljava/lang/Integer;
                .locals 1
                invoke-static {p0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                move-result-object v0
                return-object v0
            .end method
            .method public static character(Z)Ljava/lang/Character;
                .locals 1
                invoke-static {p0}, Ljava/lang/Character;->valueOf(C)Ljava/lang/Character;
                move-result-object v0
                return-object v0
            .end method
            .method public static choose(I)I
                .locals 1
                const/4 v0, 0x1
                return v0
            .end method
            .method public static choose(Ljava/lang/Integer;)I
                .locals 1
                const/4 v0, 0x2
                return v0
            .end method
            .method public static overload(Z)I
                .locals 1
                invoke-static {p0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                move-result-object v0
                invoke-static {v0}, LBoxedValues;->choose(Ljava/lang/Integer;)I
                move-result v0
                return v0
            .end method
        """.trimIndent())
        val source = decompile(smali)
        withCompiledClass(source, kotlin = true) { cls ->
            val companion = cls.getField("Companion").get(null)
            val methods = companion.javaClass
            val boxed = methods.getMethod("boxed", Boolean::class.javaPrimitiveType)
            val character = methods.getMethod("character", Boolean::class.javaPrimitiveType)
            assertEquals(Int::class.javaObjectType, boxed.returnType)
            assertEquals(Char::class.javaObjectType, character.returnType)
            for (value in listOf(false, true)) {
                val number = if (value) 1 else 0
                assertEquals(number, boxed.invoke(companion, value))
                assertEquals(number.toChar(), character.invoke(companion, value))
                assertEquals(2, methods.getMethod("overload", Boolean::class.javaPrimitiveType).invoke(companion, value))
            }
        }
    }

    @Test
    fun booleanConversionsExecuteForBothValuesAndEvaluateOperandOnce(@TempDir dir: File) {
        val conversions = listOf(
            Triple("byte", "B", "int-to-byte"),
            Triple("char", "C", "int-to-char"),
            Triple("short", "S", "int-to-short"),
            Triple("long", "J", "int-to-long"),
            Triple("float", "F", "int-to-float"),
            Triple("double", "D", "int-to-double"),
        )
        val smali = dir.resolve("BooleanConversions.smali")
        smali.writeText(buildString {
            appendLine(".class public LBooleanConversions;")
            appendLine(".super Ljava/lang/Object;")
            appendLine(".field public static counter:I")
            for ((name, descriptor, opcode) in conversions) {
                appendLine(".method public static $name(Z)$descriptor")
                appendLine("    .locals 2")
                appendLine("    $opcode v0, p0")
                appendLine("    ${if (descriptor == "J" || descriptor == "D") "return-wide" else "return"} v0")
                appendLine(".end method")
            }
            appendLine("""
                .method public static number(Z)I
                    .locals 0
                    return p0
                .end method
                .method public static next()Z
                    .locals 1
                    sget v0, LBooleanConversions;->counter:I
                    add-int/lit8 v0, v0, 0x1
                    sput v0, LBooleanConversions;->counter:I
                    const/4 v0, 0x1
                    return v0
                .end method
                .method public static ordered()I
                    .locals 1
                    invoke-static {}, LBooleanConversions;->next()Z
                    move-result v0
                    invoke-static {v0}, LBooleanConversions;->number(Z)I
                    move-result v0
                    return v0
                .end method
            """.trimIndent())
        })
        val source = decompile(smali)
        withCompiledClass(source, kotlin = true) { cls ->
            val companion = cls.getField("Companion").get(null)
            val methods = companion.javaClass
            for (value in listOf(false, true)) {
                val number = if (value) 1 else 0
                val expected = mapOf(
                    "byte" to number.toByte(), "char" to number.toChar(), "short" to number.toShort(),
                    "long" to number.toLong(), "float" to number.toFloat(), "double" to number.toDouble(),
                    "number" to number,
                )
                for ((name, result) in expected) {
                    assertEquals(result, methods.getMethod(name, Boolean::class.javaPrimitiveType).invoke(companion, value), name)
                }
            }
            assertEquals(1, methods.getMethod("ordered").invoke(companion))
            assertEquals(1, methods.getMethod("getCounter").invoke(companion), "boolean operand evaluated once")
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
