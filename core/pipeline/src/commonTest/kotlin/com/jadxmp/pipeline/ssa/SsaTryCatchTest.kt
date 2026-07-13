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

/**
 * Documents the **known** try-catch SSA imprecision (see the `TODO(before inlining)` in [SsaBuilder]).
 * A register reassigned inside a protected block, with a throwing instruction between the two assigns,
 * makes the handler read the *later* SSA version though runtime would see the earlier one. This is
 * harmless in Phase 2 (all versions collapse to one source variable) but must be fixed before any pass
 * trusts SSA def-identity. This test pins the current behaviour so a future fix visibly changes it.
 */
class SsaTryCatchTest {

    @Test
    fun handlerReadsLastAssignWithinProtectedBlock() {
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
        // CURRENT (documented) behaviour: handler observes the block's end-state definition `v0 = 2`.
        // The runtime-correct value on the exception path is `v0 = 1`; fixing that is the TODO.
        assertEquals(IrOpcode.CONST, def.opcode)
        assertEquals(2L, (def.getArg(0) as LiteralOperand).value)
    }
}
