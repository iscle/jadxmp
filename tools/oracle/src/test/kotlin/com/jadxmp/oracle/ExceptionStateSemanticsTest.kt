package com.jadxmp.oracle

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExceptionStateSemanticsTest {
    @Test
    fun javaExceptionsObserveOnlyCompletedAssignments() = verify(kotlin = false)

    @Test
    fun kotlinExceptionsObserveOnlyCompletedAssignments() = verify(kotlin = true)

    private fun verify(kotlin: Boolean) {
        val fixture = File(requireNotNull(javaClass.getResource("/fixtures/ExceptionState.smali")).toURI())
        val assembly = SmaliAssembler.assemble(fixture)
        assertTrue(assembly.ok, assembly.error)
        val hooks = File(ExceptionStateHooks::class.java.protectionDomain.codeSource.location.toURI())
        val result = if (kotlin) KotlinJadxmpDecompiler().decompileKotlin(fixture.name, assembly.dex!!)
            else JadxmpDecompiler().decompile(fixture.name, assembly.dex!!)
        assertEquals(0, result.reportedErrors, result.classes.joinToString { it.source })
        withCompiledClass(result.classes.single(), kotlin, listOf(hooks)) { cls ->
            val target = if (kotlin) cls.getField("Companion").get(null) else null
            val owner = target?.javaClass ?: cls
            fun call(name: String, type: Class<*>, arg: Any?): Any? = owner.getMethod(name, type).invoke(target, arg)
            val intType = Int::class.javaPrimitiveType!!
            ExceptionStateHooks.trace.clear()
            assertEquals(1, call("afterCall", intType, 1))
            assertEquals(listOf(1), ExceptionStateHooks.trace)
            assertEquals(2, call("afterCall", intType, 0))
            for (mode in listOf(0, 1, 2)) {
                assertEquals(if (mode == 1) 2 else 1, call("protectedReturn", intType, mode))
                assertEquals(if (mode == 1) 2 else 1, call("protectedBranchReturn", intType, mode))
            }
            assertEquals(7, call("arrayLength", IntArray::class.java, null))
            assertEquals(3, call("arrayLength", IntArray::class.java, intArrayOf(1, 2, 3)))
            val original = Any()
            assertSame(original, call("cast", Any::class.java, original))
            val string = "retained"
            assertSame(string, call("cast", Any::class.java, string))
            assertEquals(9, call("callResult", intType, 1))
            assertEquals(42, call("callResult", intType, 0))
            assertEquals(9L, call("wide", LongArray::class.java, null))
            assertEquals(9L, call("wide", LongArray::class.java, longArrayOf()))
            assertEquals(1234567890123L, call("wide", LongArray::class.java, longArrayOf(1234567890123L)))
            for (mode in listOf(0, 1, 2, 3)) {
                ExceptionStateHooks.trace.clear()
                assertEquals(if (mode == 0) 3 else mode, call("loop", intType, mode))
                assertEquals((1..(if (mode == 0) 3 else mode)).toList(), ExceptionStateHooks.trace)
            }
            val wideParameter = owner.getMethod("wideParameter", Long::class.javaPrimitiveType, intType)
            assertEquals(1234567890123L, wideParameter.invoke(target, 1234567890123L, 1))
            assertEquals(2469135780246L, wideParameter.invoke(target, 1234567890123L, 0))
            val instance = cls.getConstructor().newInstance()
            assertEquals(9, cls.getMethod("instance", intType).invoke(instance, 1))
            assertEquals(42, cls.getMethod("instance", intType).invoke(instance, 0))
            assertEquals(10, call("nested", intType, 0))
            assertEquals(30, call("nested", intType, 1))
            assertEquals(20, call("nested", intType, -1))
        }
    }
}
