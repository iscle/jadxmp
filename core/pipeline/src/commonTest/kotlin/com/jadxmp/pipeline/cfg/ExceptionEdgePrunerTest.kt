package com.jadxmp.pipeline.cfg

import com.jadxmp.input.Opcode
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeCatchHandler
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.FakeTryBlock
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExceptionEdgePrunerTest {
    private fun retainsHandler(caught: String?, throwingCall: Boolean = false): Boolean {
        val body = if (throwingCall) {
            Insn(Opcode.INVOKE_STATIC, 0, methodRef = FakeMethodRef("Lexample/Calls;", "read", "V", emptyList()))
        } else Insn(Opcode.CONST_STRING, 0, intArrayOf(0), stringValue = "value")
        val handler = if (caught == null) FakeCatchHandler(emptyList(), emptyList(), 2)
            else FakeCatchHandler(listOf(caught), listOf(2), -1)
        val method = TestPipeline.buildMethod(FakeCodeReader(
            1,
            listOf(body, Insn(Opcode.RETURN_VOID, 1), Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(0)), Insn(Opcode.RETURN_VOID, 3)),
            tries = listOf(FakeTryBlock(0, 0, handler)),
        ), returnType = IrType.VOID)
        TestPipeline.cfg(method)
        val protected = TestPipeline.blockAt(method, 0)
        val catch = TestPipeline.blockAt(method, 2)
        ExceptionEdgePruner(method).run()
        return catch in protected.successors
    }

    @Test
    fun stringResolutionCannotRaiseCheckedIOException() {
        assertFalse(retainsHandler("Ljava/io/IOException;"))
    }

    @Test
    fun stringResolutionRetainsErrorsAndUnknownCatchTypes() {
        assertTrue(retainsHandler("Ljava/lang/OutOfMemoryError;"))
        assertTrue(retainsHandler("Ljava/lang/Throwable;"))
        assertTrue(retainsHandler("Lexample/UnresolvedException;"))
        assertTrue(retainsHandler(null))
    }

    @Test
    fun invocationStillReachesCheckedHandler() {
        assertTrue(retainsHandler("Ljava/io/IOException;", throwingCall = true))
    }
}
