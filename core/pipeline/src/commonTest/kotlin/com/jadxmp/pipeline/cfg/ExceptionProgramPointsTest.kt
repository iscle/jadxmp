package com.jadxmp.pipeline.cfg

import com.jadxmp.input.Opcode
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCatchHandler
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.FakeTryBlock
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ExceptionProgramPointsTest {
    @Test
    fun protectedReturnsKeepTheirNormalExit() {
        for (separateTarget in listOf(false, true)) {
            val instructions = buildList {
                if (separateTarget) add(Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 2))
                add(Insn(Opcode.INVOKE_STATIC, 1, methodRef = FakeMethodRef("Lexample/Hooks;", "run", "V", emptyList())))
                add(Insn(Opcode.RETURN_VOID, 2))
                add(Insn(Opcode.MOVE_EXCEPTION, 3, intArrayOf(0)))
                add(Insn(Opcode.RETURN_VOID, 4))
            }
            val method = TestPipeline.buildMethod(FakeCodeReader(1, instructions,
                tries = listOf(FakeTryBlock(if (separateTarget) 0 else 1, 2,
                    FakeCatchHandler(listOf("Ljava/lang/RuntimeException;"), listOf(3), -1)))))
            TestPipeline.dominators(method)
            val returning = TestPipeline.blockAt(method, 2)
            assertEquals(listOf(method.exitBlock), returning.successors, "protected return must reach exit, separate=$separateTarget")
            assertFalse(method[PipelineAttrs.EXCEPTION_EDGES].orEmpty().contains(
                (returning.id.toLong() shl 32) or method.exitBlock!!.id.toLong()))
        }
    }
    @Test
    fun protectedThrowKeepsOnlyExceptionalSuccessors() {
        val method = TestPipeline.buildMethod(FakeCodeReader(1, listOf(
            Insn(Opcode.THROW, 0, intArrayOf(0)),
            Insn(Opcode.MOVE_EXCEPTION, 1, intArrayOf(0)),
            Insn(Opcode.RETURN_VOID, 2),
        ), tries = listOf(FakeTryBlock(0, 0, FakeCatchHandler(emptyList(), emptyList(), 1)))))
        TestPipeline.dominators(method)
        val throwing = TestPipeline.blockAt(method, 0)
        assertEquals(listOf(TestPipeline.blockAt(method, 1)), throwing.successors)
        assertFalse(method.exitBlock in throwing.successors)
    }

}
