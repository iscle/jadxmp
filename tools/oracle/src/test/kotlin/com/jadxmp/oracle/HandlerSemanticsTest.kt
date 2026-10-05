package com.jadxmp.oracle

import java.io.File
import java.lang.reflect.InvocationTargetException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HandlerSemanticsTest {
    @Test
    fun splitHandlersKeepExceptionIdentitySuppressionAndExecutionOrder() {
        val fixture = File(requireNotNull(javaClass.getResource("/fixtures/HandlerSemantics.smali")).toURI())
        val assembled = SmaliAssembler.assemble(fixture)
        assertTrue(assembled.ok, assembled.error)
        val result = JadxmpDecompiler().decompile(fixture.name, assembled.dex!!)
        assertEquals(0, result.reportedErrors, result.classes.joinToString { it.source })
        val hookClasses = File(HandlerTestHooks::class.java.protectionDomain.codeSource.location.toURI())
        withCompiledClass(result.classes.single(), kotlin = false, additionalClasspath = listOf(hookClasses)) { cls ->
            val run = cls.getMethod("run", Int::class.javaPrimitiveType)
            for ((mode, trace) in mapOf(0 to listOf(1, 2, 3), 1 to listOf(1, 5), 2 to listOf(1, 2, 4, 5),
                3 to listOf(1, 2, 3, 5), 12 to listOf(1, 2, 4), 20 to listOf(1, 2, 4, 5), 21 to listOf(1, 2, 4))) {
                HandlerTestHooks.reset()
                val thrown = try { run.invoke(null, mode); null } catch (e: InvocationTargetException) { e.targetException }
                assertEquals(trace, HandlerTestHooks.trace, "mode=$mode")
                if (mode == 12 || mode == 21) assertSame(HandlerTestHooks.failures.first(), thrown)
                else assertNull(thrown, "mode=$mode")
                if (mode == 20 || mode == 21) {
                    assertEquals(1, HandlerTestHooks.failures.first().suppressed.size)
                    assertSame(HandlerTestHooks.failures[1], HandlerTestHooks.failures.first().suppressed.single())
                }
            }
            val nested = cls.getMethod("nested", Int::class.javaPrimitiveType)
            for ((mode, trace) in mapOf(0 to listOf(1), 1 to listOf(1, 2), 30 to listOf(1, 2, 3))) {
                HandlerTestHooks.reset()
                nested.invoke(null, mode)
                assertEquals(trace, HandlerTestHooks.trace, "protected handler-entry mode=$mode")
            }
            val shared = cls.getMethod("shared", Int::class.javaPrimitiveType)
            for ((mode, trace) in mapOf(0 to listOf(6, 7, 8), 6 to listOf(6, 9), 7 to listOf(6, 7), 8 to listOf(6, 7, 8, 9))) {
                HandlerTestHooks.reset()
                val thrown = try { shared.invoke(null, mode); null } catch (e: InvocationTargetException) { e.targetException }
                assertEquals(trace, HandlerTestHooks.trace, "unprotected-gap mode=$mode")
                if (mode == 7) assertSame(HandlerTestHooks.failures.single(), thrown, "the gap must remain outside both catches")
                else assertNull(thrown)
            }
        }
    }
}
