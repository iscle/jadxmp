package com.jadxmp.oracle

import java.io.File
import java.lang.reflect.InvocationTargetException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NullMonitorSemanticsTest {
    @Test
    fun knownNullLockThrowsBeforeBodyAndPreservesPrefixEffects() {
        val fixture = File(requireNotNull(javaClass.getResource("/fixtures/NullMonitor.smali")).toURI())
        val assembled = SmaliAssembler.assemble(fixture)
        assertTrue(assembled.ok, assembled.error)
        val hooks = File(HandlerTestHooks::class.java.protectionDomain.codeSource.location.toURI())
        for (kotlin in listOf(false, true)) {
            val result = if (kotlin) KotlinJadxmpDecompiler().decompileKotlin(fixture.name, assembled.dex!!)!!
                else JadxmpDecompiler().decompile(fixture.name, assembled.dex!!)
            assertEquals(0, result.reportedErrors, result.classes.joinToString { it.source })
            withCompiledClass(result.classes.single(), kotlin = kotlin, additionalClasspath = listOf(hooks)) { cls ->
                HandlerTestHooks.reset()
                val target = if (kotlin) cls.getField("Companion").get(null) else null
                val owner = target?.javaClass ?: cls
                val failure = assertThrows(InvocationTargetException::class.java) { owner.getMethod("run").invoke(target) }
                assertInstanceOf(NullPointerException::class.java, failure.targetException)
                assertEquals(listOf(10), HandlerTestHooks.trace)
            }
        }
    }
}
