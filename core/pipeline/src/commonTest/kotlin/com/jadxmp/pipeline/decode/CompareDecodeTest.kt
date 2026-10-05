package com.jadxmp.pipeline.decode

import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.CompareInstruction
import com.jadxmp.ir.insn.CompareKind
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.Insn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CompareDecodeTest {
    @Test
    fun everyDexComparisonRetainsItsOperandKindAndNanBias() {
        val cases = listOf(
            Opcode.CMP_LONG to CompareKind.LONG,
            Opcode.CMPL_FLOAT to CompareKind.FLOAT_LESS,
            Opcode.CMPG_FLOAT to CompareKind.FLOAT_GREATER,
            Opcode.CMPL_DOUBLE to CompareKind.DOUBLE_LESS,
            Opcode.CMPG_DOUBLE to CompareKind.DOUBLE_GREATER,
        )
        for ((opcode, expectedKind) in cases) {
            val code = MethodDecoder().decode(FakeCodeReader(5, listOf(Insn(opcode, 7, intArrayOf(0, 1, 3)))))
            val compare = assertIs<CompareInstruction>(code.instructions.single().insn)
            assertEquals(expectedKind, compare.kind)
            assertEquals(IrType.INT, compare.result?.type)
            assertEquals(listOf(expectedKind.operandType, expectedKind.operandType), compare.args.map { it.type })
            assertEquals(7, compare.offset)
        }
    }
}
