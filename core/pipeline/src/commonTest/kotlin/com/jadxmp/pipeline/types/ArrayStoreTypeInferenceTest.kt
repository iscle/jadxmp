package com.jadxmp.pipeline.types

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals

class ArrayStoreTypeInferenceTest {
    @Test fun arrayStoreReceivesTypedBitsThroughMoves() {
        for ((type, descriptor) in listOf(IrType.FLOAT to "[F", IrType.DOUBLE to "[D", IrType.INT to "[I", IrType.LONG to "[J")) {
            val wide = type == IrType.DOUBLE || type == IrType.LONG
            val bits = if (wide) (-20.0).toBits() else (-20.0f).toBits().toLong()
            val method = TestPipeline.buildMethod(FakeCodeReader(7, listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 1),
                Insn(Opcode.NEW_ARRAY, 1, intArrayOf(1, 0), indexType = IndexType.TYPE_REF, typeValue = descriptor),
                Insn(if (wide) Opcode.CONST_WIDE else Opcode.CONST, 2, intArrayOf(2), literal = bits),
                Insn(if (wide) Opcode.MOVE_WIDE else Opcode.MOVE, 3, intArrayOf(4, 2)),
                Insn(Opcode.CONST, 4, intArrayOf(6), literal = 0),
                Insn(if (wide) Opcode.APUT_WIDE else Opcode.APUT, 5, intArrayOf(4, 1, 6)),
                Insn(Opcode.RETURN_VOID, 6),
            )))
            TestPipeline.full(method)
            val store = method.blocks.flatMap { it.instructions }.single { it.opcode == IrOpcode.ARRAY_PUT }
            val literal = kotlin.test.assertIs<LiteralOperand>(store.getArg(0))
            assertEquals(type, literal.type, descriptor)
            assertEquals(bits, literal.value)

        }
    }
}
