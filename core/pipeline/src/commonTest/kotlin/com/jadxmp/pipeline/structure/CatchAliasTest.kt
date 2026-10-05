package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.pipeline.support.FakeCatchHandler
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.FakeTryBlock
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertSame

class CatchAliasTest {
    @Test
    fun crossBlockRethrowUsesOriginalCatchBinding() {
        val method = TestPipeline.buildMethod(FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = FakeMethodRef("Lexample/Calls;", "read", "V", emptyList())),
                Insn(Opcode.RETURN_VOID, 1),
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(0)),
                Insn(Opcode.MOVE_OBJECT, 3, intArrayOf(1, 0)),
                Insn(Opcode.GOTO, 4, target = 5),
                Insn(Opcode.THROW, 5, intArrayOf(1)),
            ),
            tries = listOf(FakeTryBlock(0, 0, FakeCatchHandler(emptyList(), emptyList(), 2))),
        ))
        TestPipeline.full(method)
        OutOfSsa(method).run()
        ExpressionShaping(method).run()
        val instructions = method.blocks.flatMap { it.instructions }
        val caught = instructions.single { it.opcode == IrOpcode.MOVE_EXCEPTION }.result!!.ssaValue
        val thrown = instructions.single { it.opcode == IrOpcode.THROW }.getArg(0) as RegisterOperand
        assertSame(caught, thrown.ssaValue, "an immutable catch alias must not erase precise Java rethrow typing")
    }
}
