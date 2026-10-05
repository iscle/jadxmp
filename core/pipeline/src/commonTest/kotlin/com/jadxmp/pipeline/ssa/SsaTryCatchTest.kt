package com.jadxmp.pipeline.ssa

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeCatchHandler
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.FakeTryBlock
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exception handlers observe register state before the instruction that throws. */
class SsaTryCatchTest {

    @Test
    fun handlerReadsValueBeforeThrowingInstruction() {
        val bar = FakeMethodRef("Lcom/example/Foo;", "bar", "V", emptyList())
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 1), // v0 = 1
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = bar), // may throw
                Insn(Opcode.CONST, 2, intArrayOf(0), literal = 2), // v0 = 2
                Insn(Opcode.RETURN, 3, intArrayOf(0)), // normal path returns 2
                Insn(Opcode.MOVE_EXCEPTION, 4, intArrayOf(1)),
                Insn(Opcode.RETURN, 5, intArrayOf(0)), // handler returns v0
            ),
            tries = listOf(FakeTryBlock(0, 2, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(4), -1))),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.ssa(method)

        val handler = TestPipeline.blockAt(method, 5)
        val ret = handler.instructions.first { it.opcode == IrOpcode.RETURN && it.offset == 5 }
        val def = (ret.getArg(0) as RegisterOperand).ssaValue!!.assign.parent!!
        assertEquals(IrOpcode.CONST, def.opcode)
        assertEquals(1L, (def.getArg(0) as LiteralOperand).value)
    }
    @Test
    fun throwingResultDoesNotOverwritePreviousRegisterOnExceptionalEdge() {
        val reader = FakeCodeReader(3, listOf(
            Insn(Opcode.CONST, 0, intArrayOf(0), literal = 7),
            Insn(Opcode.ARRAY_LENGTH, 1, intArrayOf(0, 2)),
            Insn(Opcode.RETURN, 2, intArrayOf(0)),
            Insn(Opcode.MOVE_EXCEPTION, 3, intArrayOf(1)),
            Insn(Opcode.RETURN, 4, intArrayOf(0)),
        ), tries = listOf(FakeTryBlock(1, 1, FakeCatchHandler(listOf("Ljava/lang/NullPointerException;"), listOf(3), -1))))
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT, argTypes = listOf(IrType.array(IrType.INT)))
        TestPipeline.ssa(method)
        val caughtReturn = TestPipeline.blockAt(method, 4).instructions.single { it.offset == 4 }
        val before = (caughtReturn.getArg(0) as RegisterOperand).ssaValue!!.assign.parent!!
        assertEquals(IrOpcode.CONST, before.opcode)
        assertEquals(7L, (before.getArg(0) as LiteralOperand).value)
        val normalReturn = TestPipeline.blockAt(method, 2).instructions.single { it.offset == 2 }
        val normal = (normalReturn.getArg(0) as RegisterOperand).ssaValue!!.assign.parent!!
        val normalValue = if (normal.opcode == IrOpcode.MOVE)
            (normal.getArg(0) as RegisterOperand).ssaValue!!.assign.parent!! else normal
        assertEquals(IrOpcode.ARRAY_LENGTH, normalValue.opcode)
    }

    @Test
    fun unusedStringResolutionRemainsObservable() {
        val method = TestPipeline.buildMethod(FakeCodeReader(1, listOf(
            Insn(Opcode.CONST_STRING, 0, intArrayOf(0), stringValue = "unused"),
            Insn(Opcode.RETURN_VOID, 1),
        )))
        TestPipeline.ssa(method)
        assertEquals(1, method.blocks.flatMap { it.instructions }.count { it.opcode == IrOpcode.CONST_STRING })
    }

}
