package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.IrAttrs
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

class RegionMakerSharedHandlerTest {
    @Test
    fun catchEntryCanStartItsOwnProtectedBody() {
        val sink = FakeMethodRef("Lexample/Calls;", "sink", "V", emptyList())
        val method = TestPipeline.buildMethod(FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink),
                Insn(Opcode.RETURN_VOID, 1),
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(0)),
                Insn(Opcode.INVOKE_STATIC, 3, methodRef = sink),
                Insn(Opcode.RETURN_VOID, 4),
                Insn(Opcode.MOVE_EXCEPTION, 5, intArrayOf(0)),
                Insn(Opcode.RETURN_VOID, 6),
            ),
            tries = listOf(
                FakeTryBlock(0, 0, FakeCatchHandler(listOf("Ljava/lang/RuntimeException;"), listOf(2), -1)),
                FakeTryBlock(2, 3, FakeCatchHandler(emptyList(), emptyList(), 5)),
            ),
        ))
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], method[IrAttrs.ERROR]?.message)
        assertFalse(method.contains(AttrFlag.HAS_ERROR))
    }

    @Test
    fun unusedCatchBinderDoesNotPreventSharedHandlerDuplication() {
        val sink = FakeMethodRef("Lexample/Calls;", "sink", "V", emptyList())
        val handler = FakeCatchHandler(listOf("Ljava/lang/RuntimeException;"), listOf(4), -1)
        val method = TestPipeline.buildMethod(FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink),
                Insn(Opcode.INVOKE_STATIC, 1, methodRef = sink), // unprotected call separates the ranges
                Insn(Opcode.INVOKE_STATIC, 2, methodRef = sink),
                Insn(Opcode.RETURN_VOID, 3),
                Insn(Opcode.MOVE_EXCEPTION, 4, intArrayOf(0)),
                Insn(Opcode.RETURN_VOID, 5),
            ),
            tries = listOf(FakeTryBlock(0, 0, handler), FakeTryBlock(2, 2, handler)),
        ))
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], method[IrAttrs.ERROR]?.message)
        assertFalse(method.contains(AttrFlag.HAS_ERROR))
    }
}
