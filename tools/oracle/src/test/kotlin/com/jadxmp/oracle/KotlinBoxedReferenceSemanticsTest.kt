package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException

class KotlinBoxedReferenceSemanticsTest {
    private data class Wrapper(val name: String, val descriptor: String, val primitive: Class<*>, val value: Any, val accessor: String)
    private val wrappers = listOf(
        Wrapper("Boolean", "Z", Boolean::class.javaPrimitiveType!!, true, "booleanValue"),
        Wrapper("Byte", "B", Byte::class.javaPrimitiveType!!, 123.toByte(), "byteValue"),
        Wrapper("Short", "S", Short::class.javaPrimitiveType!!, 20000.toShort(), "shortValue"),
        Wrapper("Integer", "I", Int::class.javaPrimitiveType!!, 20000, "intValue"),
        Wrapper("Long", "J", Long::class.javaPrimitiveType!!, 1234567890123L, "longValue"),
        Wrapper("Float", "F", Float::class.javaPrimitiveType!!, -0.0f, "floatValue"),
        Wrapper("Double", "D", Double::class.javaPrimitiveType!!, Double.NaN, "doubleValue"),
        Wrapper("Character", "C", Char::class.javaPrimitiveType!!, '\u4567', "charValue"),
    )

    @Test
    fun constructorsUnboxingAndNullableJvmResultsKeepReferenceIdentity(@TempDir dir: File) {
        val helper = DecompiledClass("testing.BoxEffects", buildString {
            append("package testing; public class BoxEffects { public static Object last;\n")
            for (wrapper in wrappers) {
                append("public static ${wrapper.name} field${wrapper.name}; public ${wrapper.name} value${wrapper.name};\n")
                append("public static ${wrapper.name}[] keepArray${wrapper.name}(${wrapper.name}[] value) { last=value; return value; }\n")
                append("public static ${wrapper.name} keep${wrapper.name}(${wrapper.name} value) { last=value; return value; }\n")
                append("public static String choose${wrapper.name}(${wrapper.name} value) { return \"reference\"; }\n")
                append("public static String choose${wrapper.name}(${wrapper.primitive.name} value) { return \"primitive\"; }\n")
            }
            append("}")
        })
        JavaCompilation.compile(listOf(helper)).use { dependency ->
            assertTrue(dependency.result.success, dependency.result.diagnostics.toString())
            for (wrapper in wrappers) {
                val boxDescriptor = "Ljava/lang/${wrapper.name};"
                val wide = wrapper.descriptor in listOf("J", "D")
                val arguments = if (wide) "p0, p1" else "p0"
                val constructorArguments = if (wide) "v0, p0, p1" else "v0, p0"
                val resultMove = if (wide) "move-result-wide" else "move-result"
                val resultReturn = if (wide) "return-wide" else "return"
                val fixture = dir.resolve("Box${wrapper.name}.smali")
                fixture.writeText("""
                    .class public LBox${wrapper.name};
                    .super Ljava/lang/Object;
                    .field public value:$boxDescriptor
                    .method public constructor <init>($boxDescriptor)V
                        .locals 0
                        invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                        iput-object p1, p0, LBox${wrapper.name};->value:$boxDescriptor
                        return-void
                    .end method
                    .method public static holder($boxDescriptor)$boxDescriptor
                        .locals 1
                        new-instance v0, LBox${wrapper.name};
                        invoke-direct {v0, p0}, LBox${wrapper.name};-><init>($boxDescriptor)V
                        iget-object v0, v0, LBox${wrapper.name};->value:$boxDescriptor
                        return-object v0
                    .end method
                    .method public static allocate(${wrapper.descriptor})$boxDescriptor
                        .locals 1
                        new-instance v0, $boxDescriptor
                        invoke-direct {$constructorArguments}, $boxDescriptor-><init>(${wrapper.descriptor})V
                        return-object v0
                    .end method
                    .method public static forwardAllocation(${wrapper.descriptor})$boxDescriptor
                        .locals 1
                        invoke-static {$arguments}, LBox${wrapper.name};->allocate(${wrapper.descriptor})$boxDescriptor
                        move-result-object v0
                        return-object v0
                    .end method
                    .method public static echo($boxDescriptor)$boxDescriptor
                        .locals 0
                        .param p0, "Jvm${wrapper.name}"
                        return-object p0
                    .end method
                    .method public static external($boxDescriptor)$boxDescriptor
                        .locals 1
                        invoke-static {p0}, Ltesting/BoxEffects;->keep${wrapper.name}($boxDescriptor)$boxDescriptor
                        move-result-object v0
                        return-object v0
                    .end method
                    .method public static forward($boxDescriptor)$boxDescriptor
                        .locals 1
                        invoke-static {p0}, LBox${wrapper.name};->echo($boxDescriptor)$boxDescriptor
                        move-result-object v0
                        return-object v0
                    .end method
                    .method public static staticField($boxDescriptor)$boxDescriptor
                        .locals 1
                        sput-object p0, Ltesting/BoxEffects;->field${wrapper.name}:$boxDescriptor
                        sget-object v0, Ltesting/BoxEffects;->field${wrapper.name}:$boxDescriptor
                        return-object v0
                    .end method
                    .method public static instanceField(Ltesting/BoxEffects;$boxDescriptor)$boxDescriptor
                        .locals 1
                        iput-object p1, p0, Ltesting/BoxEffects;->value${wrapper.name}:$boxDescriptor
                        iget-object v0, p0, Ltesting/BoxEffects;->value${wrapper.name}:$boxDescriptor
                        return-object v0
                    .end method
                    .method public static externalArray([$boxDescriptor)[$boxDescriptor
                        .locals 1
                        invoke-static {p0}, Ltesting/BoxEffects;->keepArray${wrapper.name}([$boxDescriptor)[$boxDescriptor
                        move-result-object v0
                        return-object v0
                    .end method
                    .method public static factory(${wrapper.descriptor})$boxDescriptor
                        .locals 1
                        invoke-static {$arguments}, $boxDescriptor->valueOf(${wrapper.descriptor})$boxDescriptor
                        move-result-object v0
                        return-object v0
                    .end method
                    .method public static unbox($boxDescriptor)${wrapper.descriptor}
                        .locals 2
                        invoke-virtual {p0}, $boxDescriptor->${wrapper.accessor}()${wrapper.descriptor}
                        $resultMove v0
                        $resultReturn v0
                    .end method
                    .method public static primitive(${wrapper.descriptor})${wrapper.descriptor}
                        .locals 0
                        $resultReturn p0
                    .end method
                    .method public static cast(Ljava/lang/Object;)$boxDescriptor
                        .locals 0
                        check-cast p0, $boxDescriptor
                        return-object p0
                    .end method
                    .method public static overloadReference($boxDescriptor)Ljava/lang/String;
                        .locals 1
                        invoke-static {p0}, Ltesting/BoxEffects;->choose${wrapper.name}($boxDescriptor)Ljava/lang/String;
                        move-result-object v0
                        return-object v0
                    .end method
                    .method public static overloadPrimitive(${wrapper.descriptor})Ljava/lang/String;
                        .locals 1
                        invoke-static {$arguments}, Ltesting/BoxEffects;->choose${wrapper.name}(${wrapper.descriptor})Ljava/lang/String;
                        move-result-object v0
                        return-object v0
                    .end method
                """.trimIndent())
                if (wrapper.name != "Character") fixture.appendText("\n" + """
                    .method public static fromString(Ljava/lang/String;)$boxDescriptor
                        .locals 1
                        new-instance v0, $boxDescriptor
                        invoke-direct {v0, p0}, $boxDescriptor-><init>(Ljava/lang/String;)V
                        return-object v0
                    .end method
                """.trimIndent())
                val constantBits = when (wrapper.name) {
                    "Long", "Double" -> Long.MIN_VALUE
                    "Float" -> 0x7fc00001L
                    else -> null
                }
                if (constantBits != null) fixture.appendText("\n" + """
                    .method public static constant()${wrapper.descriptor}
                        .locals 2
                        const${if (wide) "-wide" else ""} v0, ${if (wide) "-0x8000000000000000L" else "0x7fc00001"}
                        $resultReturn v0
                    .end method
                """.trimIndent())
                val assembly = SmaliAssembler.assemble(fixture)
                assertTrue(assembly.ok, assembly.error)
                val result = KotlinJadxmpDecompiler().decompileKotlin(fixture.name, assembly.dex!!)
                assertEquals(0, result.reportedErrors, result.classes.joinToString { it.source })
                withCompiledClass(result.classes.single(), kotlin = true, additionalClasspath = listOf(dependency.output)) { generated ->
                    val target = generated.getField("Companion").get(null)
                    val methods = target.javaClass
                    val box = Class.forName("java.lang.${wrapper.name}")
                    val value = box.getConstructor(wrapper.primitive).newInstance(wrapper.value)
                    val allocate = methods.getMethod("allocate", wrapper.primitive)
                    assertEquals(box, allocate.returnType)
                    val first = allocate.invoke(target, wrapper.value)
                    val second = allocate.invoke(target, wrapper.value)
                    assertEquals(value, first)
                    assertNotSame(first, second, "${wrapper.name} constructor must allocate distinct references")
                    val forwardedAllocation = methods.getMethod("forwardAllocation", wrapper.primitive)
                    assertEquals(box, forwardedAllocation.returnType)
                    assertEquals(value, forwardedAllocation.invoke(target, wrapper.value))
                    assertNotSame(first, forwardedAllocation.invoke(target, wrapper.value))
                    val echo = methods.getMethod("echo", box)
                    val external = methods.getMethod("external", box)
                    assertEquals(box, echo.returnType)
                    assertEquals(box, external.returnType)
                    val effects = generated.classLoader.loadClass(helper.fullName)
                    val instance = effects.getConstructor().newInstance()
                    for (input in listOf(null, value)) {
                        assertSame(input, echo.invoke(target, input))
                        assertSame(input, methods.getMethod("holder", box).invoke(target, input))
                        assertSame(input, methods.getMethod("forward", box).invoke(target, input))
                        assertSame(input, methods.getMethod("staticField", box).invoke(target, input))
                        assertSame(input, effects.getField("field${wrapper.name}").get(null))
                        assertSame(input, methods.getMethod("instanceField", effects, box).invoke(target, instance, input))
                        assertSame(input, effects.getField("value${wrapper.name}").get(instance))
                        assertSame(input, external.invoke(target, input))
                        assertSame(input, effects.getField("last").get(null))
                        assertSame(input, methods.getMethod("cast", Any::class.java).invoke(target, input))
                        assertEquals("reference", methods.getMethod("overloadReference", box).invoke(target, input))
                    }
                    val array = java.lang.reflect.Array.newInstance(box, 2)
                    java.lang.reflect.Array.set(array, 1, value)
                    val arrayMethod = methods.getMethod("externalArray", array.javaClass)
                    assertEquals(array.javaClass, arrayMethod.returnType)
                    for (input in listOf(null, array)) {
                        assertSame(input, arrayMethod.invoke(target, input))
                        assertSame(input, effects.getField("last").get(null))
                    }
                    assertSame(value, java.lang.reflect.Array.get(array, 1))
                    assertEquals("primitive", methods.getMethod("overloadPrimitive", wrapper.primitive).invoke(target, wrapper.value))
                    if (constantBits != null) {
                        val value = methods.getMethod("constant").invoke(target)
                        val actualBits = when (value) {
                            is Float -> value.toRawBits().toLong()
                            is Double -> value.toRawBits()
                            else -> value
                        }
                        assertEquals(constantBits, actualBits)
                    }
                    val primitive = methods.getMethod("primitive", wrapper.primitive)
                    assertEquals(wrapper.primitive, primitive.returnType)
                    assertEquals(wrapper.value, primitive.invoke(target, wrapper.value))
                    val unbox = methods.getMethod("unbox", box)
                    assertEquals(wrapper.primitive, unbox.returnType)
                    assertEquals(wrapper.value, unbox.invoke(target, value))
                    val failure = org.junit.jupiter.api.Assertions.assertThrows(InvocationTargetException::class.java) {
                        unbox.invoke(target, null)
                    }
                    assertTrue(failure.cause is NullPointerException)
                    val wrongType = org.junit.jupiter.api.Assertions.assertThrows(InvocationTargetException::class.java) {
                        methods.getMethod("cast", Any::class.java).invoke(target, Any())
                    }
                    assertTrue(wrongType.cause is ClassCastException)
                    val factory = methods.getMethod("factory", wrapper.primitive)
                    assertEquals(box, factory.returnType)
                    assertEquals(value, factory.invoke(target, wrapper.value))
                    if (wrapper.name == "Boolean") assertSame(java.lang.Boolean.TRUE, factory.invoke(target, true))
                    if (wrapper.name != "Character") {
                        val fromString = methods.getMethod("fromString", String::class.java)
                        assertEquals(box, fromString.returnType)
                        val constructor = box.getConstructor(String::class.java)
                        fun outcome(action: () -> Any?): List<Any?> = try { listOf(action(), null) }
                            catch (exception: InvocationTargetException) { listOf(null, exception.cause!!.javaClass) }
                        for (text in listOf(null, "true", "TRUE", "test", "123", "-0.0", "NaN")) {
                            val expected = outcome { constructor.newInstance(text) }
                            val actual = outcome { fromString.invoke(target, text) }
                            assertEquals(expected, actual, "${wrapper.name}($text)")
                            if (actual[1] == null) assertNotSame(actual[0], fromString.invoke(target, text))
                        }
                    }
                }
            }
        }
    }
    @Test
    fun numberAccessorProjectionKeepsVirtualDispatch(@TempDir dir: File) {
        val values = listOf(
            Wrapper("Byte", "B", Byte::class.javaPrimitiveType!!, (-5).toByte(), "byteValue"),
            Wrapper("Short", "S", Short::class.javaPrimitiveType!!, 1111.toShort(), "shortValue"),
            Wrapper("Integer", "I", Int::class.javaPrimitiveType!!, 2222, "intValue"),
            Wrapper("Long", "J", Long::class.javaPrimitiveType!!, 3333L, "longValue"),
            Wrapper("Float", "F", Float::class.javaPrimitiveType!!, -0.0f, "floatValue"),
            Wrapper("Double", "D", Double::class.javaPrimitiveType!!, 4444.5, "doubleValue"),
        )
        val helper = DecompiledClass("testing.AccessorNumber", buildString {
            append("package testing; public class AccessorNumber extends Number { public static int calls;\n")
            for ((index, wrapper) in values.withIndex()) {
                val value = wrapper.value.toString() + when (wrapper.descriptor) { "F" -> "f"; "J" -> "L"; else -> "" }
                append("public ${wrapper.primitive.name} ${wrapper.accessor}() { calls = ${index + 1}; return $value; }\n")
            }
            append("}")
        })
        val fixture = dir.resolve("NumberAccessors.smali")
        fixture.writeText(buildString {
            append(".class public LNumberAccessors;\n.super Ljava/lang/Object;\n")
            for (wrapper in values) {
                val suffix = if (wrapper.descriptor in listOf("J", "D")) "-wide" else ""
                append("""
                    .method public static ${wrapper.accessor}(Ljava/lang/Number;)${wrapper.descriptor}
                        .locals 2
                        invoke-virtual {p0}, Ljava/lang/Number;->${wrapper.accessor}()${wrapper.descriptor}
                        move-result$suffix v0
                        return$suffix v0
                    .end method
                """.trimIndent() + "\n")
            }
        })
        JavaCompilation.compile(listOf(helper)).use { dependency ->
            assertTrue(dependency.result.success, dependency.result.diagnostics.toString())
            withCompiledClass(decompile(fixture), kotlin = true, additionalClasspath = listOf(dependency.output)) { generated ->
                val target = generated.getField("Companion").get(null)
                val numberType = generated.classLoader.loadClass(helper.fullName)
                val number = numberType.getConstructor().newInstance()
                for ((index, wrapper) in values.withIndex()) {
                    numberType.getField("calls").setInt(null, 0)
                    val method = target.javaClass.getMethod(wrapper.accessor, Number::class.java)
                    assertEquals(wrapper.primitive, method.returnType)
                    assertEquals(wrapper.value, method.invoke(target, number))
                    assertEquals(index + 1, numberType.getField("calls").getInt(null))
                    numberType.getField("calls").setInt(null, 0)
                    val failure = org.junit.jupiter.api.Assertions.assertThrows(InvocationTargetException::class.java) { method.invoke(target, null) }
                    assertTrue(failure.cause is NullPointerException)
                    assertEquals(0, numberType.getField("calls").getInt(null))
                }
            }
        }
    }

    @Test
    fun wrapperAndProjectionAliasesCannotCaptureReferencedDefaultPackageTypes(@TempDir dir: File) {
        val helpers = listOf(
            DecompiledClass("JvmBoolean", "public class JvmBoolean { public java.lang.Boolean call(java.lang.Boolean value) { return value; } }"),
            DecompiledClass("KotlinBoolean", "public class KotlinBoolean {}"),
        )
        val fixture = dir.resolve("BoxAliasCollision.smali")
        fixture.writeText("""
            .class public LBoxAliasCollision;
            .super Ljava/lang/Object;
            .field public JvmBoolean2:I
            .field public KotlinBoolean2:I
            .field public java:Ljava/lang/Object;
            .method public static call(LJvmBoolean;LKotlinBoolean;Ljava/lang/Boolean;)Ljava/lang/Boolean;
                .locals 1
                invoke-virtual {p0, p2}, LJvmBoolean;->call(Ljava/lang/Boolean;)Ljava/lang/Boolean;
                move-result-object v0
                return-object v0
            .end method
        """.trimIndent())
        JavaCompilation.compile(helpers).use { dependency ->
            assertTrue(dependency.result.success, dependency.result.diagnostics.toString())
            withCompiledClass(decompile(fixture), kotlin = true, additionalClasspath = listOf(dependency.output)) { generated ->
                val target = generated.getField("Companion").get(null)
                val receiverType = generated.classLoader.loadClass("JvmBoolean")
                val ignoredType = generated.classLoader.loadClass("KotlinBoolean")
                val boxType = Class.forName("java.lang.Boolean")
                val method = target.javaClass.getMethod("call", receiverType, ignoredType, boxType)
                assertEquals(boxType, method.returnType)
                val receiver = receiverType.getConstructor().newInstance()
                val fresh = boxType.getConstructor(Boolean::class.javaPrimitiveType!!).newInstance(true)
                for (value in listOf(null, fresh)) assertSame(value, method.invoke(target, receiver, null, value))
            }
        }
    }

    private fun decompile(fixture: File): DecompiledClass {
        val assembly = SmaliAssembler.assemble(fixture)
        assertTrue(assembly.ok, assembly.error)
        val result = KotlinJadxmpDecompiler().decompileKotlin(fixture.name, assembly.dex!!)
        assertEquals(0, result.reportedErrors, result.classes.joinToString { it.source })
        return result.classes.single()
    }

}
