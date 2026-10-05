package com.jadxmp.oracle

import java.io.File
import java.lang.reflect.InvocationTargetException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Effects deliberately outside protected blocks exercise expression motion, independently of SSA splitting. */
class ExpressionOrderSemanticsTest {
    @Test fun javaExpressionMotionPreservesExceptionsAndCalls() = verify(kotlin = false)
    @Test fun kotlinExpressionMotionPreservesExceptionsAndCalls() = verify(kotlin = true)

    private fun verify(kotlin: Boolean) {
        val fixture = File(requireNotNull(javaClass.getResource("/fixtures/ExpressionOrder.smali")).toURI())
        val dex = requireNotNull(SmaliAssembler.assemble(fixture).dex)
        val result = if (kotlin) KotlinJadxmpDecompiler().decompileKotlin(fixture.name, dex)
            else JadxmpDecompiler().decompile(fixture.name, dex)
        assertEquals(0, result.reportedErrors, result.classes.joinToString { it.source })
        val hooks = File(ExpressionOrderHooks::class.java.protectionDomain.codeSource.location.toURI())
        withCompiledClass(result.classes.single(), kotlin, listOf(hooks)) { cls ->
            val target = if (kotlin) cls.getField("Companion").get(null) else null
            val owner = target?.javaClass ?: cls
            fun call(name: String, type: Class<*>? = null, arg: Any? = null): Any? =
                if (type == null) owner.getMethod(name).invoke(target)
                else owner.getMethod(name, type).invoke(target, arg)
            try {
                ExpressionOrderHooks.reset()
                call("store")
                assertEquals(listOf(1, 2, 4), ExpressionOrderHooks.trace)
                assertEquals(10, ExpressionOrderHooks.targetArray!![0])
                ExpressionOrderHooks.reset(failFirst = true)
                val firstFailure = assertThrows(InvocationTargetException::class.java) { call("store") }
                assertInstanceOf(IllegalStateException::class.java, firstFailure.cause)
                assertEquals(listOf(1), ExpressionOrderHooks.trace)
                ExpressionOrderHooks.reset()
                ExpressionOrderHooks.targetArray = null
                val storeFailure = assertThrows(InvocationTargetException::class.java) { call("store") }
                assertInstanceOf(NullPointerException::class.java, storeFailure.cause)
                assertEquals(listOf(1, 2, 4), ExpressionOrderHooks.trace)
                for (name in listOf("divide", "remainder")) {
                    ExpressionOrderHooks.reset()
                    val failure = assertThrows(InvocationTargetException::class.java) {
                        call(name, Int::class.javaPrimitiveType, 0)
                    }
                    assertInstanceOf(ArithmeticException::class.java, failure.cause)
                    assertEquals(emptyList<Int>(), ExpressionOrderHooks.trace)
                    assertEquals(if (name == "divide") 1 else 5, call(name, Int::class.javaPrimitiveType, 7))
                    assertEquals(listOf(9), ExpressionOrderHooks.trace)
                }
                ExpressionOrderHooks.reset()
                val failure = assertThrows(InvocationTargetException::class.java) {
                    call("length", IntArray::class.java, null)
                }
                assertInstanceOf(NullPointerException::class.java, failure.cause)
                assertEquals(emptyList<Int>(), ExpressionOrderHooks.trace)
                assertEquals(3, call("length", IntArray::class.java, intArrayOf(1, 2, 3)))
                assertEquals(listOf(9), ExpressionOrderHooks.trace)
                for ((method, expected, trace) in listOf(
                    Triple("reversed", 2010, listOf(1, 2, 3)),
                    Triple("nested", 11, listOf(1, 9)),
                )) {
                    ExpressionOrderHooks.reset()
                    assertEquals(expected, call(method))
                    assertEquals(trace, ExpressionOrderHooks.trace)
                    ExpressionOrderHooks.reset(failFirst = true)
                    val thrown = assertThrows(InvocationTargetException::class.java) { call(method) }
                    assertInstanceOf(IllegalStateException::class.java, thrown.cause)
                    assertEquals(listOf(1), ExpressionOrderHooks.trace)
                }
            } finally {
                ExpressionOrderHooks.reset()
            }
        }
    }
}

object ExpressionOrderHooks {
    val trace = ArrayList<Int>()
    private var failFirst = false
    var targetArray: IntArray? = intArrayOf(0)
    fun reset(failFirst: Boolean = false) { trace.clear(); this.failFirst = failFirst; targetArray = intArrayOf(0) }
    @JvmStatic fun effect() { trace.add(9) }
    @JvmStatic fun first(): Int {
        trace.add(1)
        if (failFirst) throw IllegalStateException("first")
        return 10
    }
    @JvmStatic fun second(): Int { trace.add(2); return 20 }
    @JvmStatic fun array(): IntArray? { trace.add(2); return targetArray }
    @JvmStatic fun index(): Int { trace.add(4); return 0 }
    @JvmStatic fun combine(left: Int, right: Int): Int { trace.add(3); return left * 100 + right }
}
