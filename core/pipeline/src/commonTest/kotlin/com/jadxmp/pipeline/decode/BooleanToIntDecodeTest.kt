package com.jadxmp.pipeline.decode

import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.Insn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class BooleanToIntDecodeTest {
    @Test fun normalizedBooleanConversionPreservesTypedSourceAndNewIntegerDefinition() {
        for (destination in listOf(0, 1)) {
            val decoded = MethodDecoder().decode(FakeCodeReader(2, listOf(
                Insn(Opcode.BOOLEAN_TO_INT, offset = 7, registers = intArrayOf(destination, 0)),
            )))
            assertTrue(decoded.errors.isEmpty(), decoded.errors.toString())
            val instruction = decoded.instructions.single()
            assertEquals(7, instruction.offset)
            assertEquals(IrOpcode.CAST, instruction.insn.opcode)
            val result = instruction.insn.result!!
            val source = instruction.insn.getArg(0) as RegisterOperand
            assertEquals(IrType.INT, result.type)
            assertEquals(IrType.BOOLEAN, source.type)
            assertEquals(destination, result.regNum)
            assertEquals(0, source.regNum)
            assertNotSame(result, source)
        }
    }
}
