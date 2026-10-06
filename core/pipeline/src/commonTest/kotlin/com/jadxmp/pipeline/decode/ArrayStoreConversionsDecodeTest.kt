package com.jadxmp.pipeline.decode

import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.insn.TypeInstruction
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.Insn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ArrayStoreConversionsDecodeTest {
    @Test fun integerToBooleanIsExplicitTypedCast() {
        val result = decode(Opcode.INT_TO_BOOLEAN)
        assertEquals(IrOpcode.CAST, result.opcode)
        assertEquals(IrType.BOOLEAN, result.result!!.type)
        assertEquals(IrType.INT, result.getArg(0).type)
    }

    @Test fun referenceArrayViewNeedsNoConstantPoolAndKeepsDistinctDefinition() {
        val result = decode(Opcode.REFERENCE_ARRAY_TO_OBJECT_ARRAY) as TypeInstruction
        assertEquals(IrOpcode.CHECK_CAST, result.opcode)
        assertEquals(IrType.array(IrType.OBJECT), result.result!!.type)
        assertEquals(IrType.array(IrType.UNKNOWN_OBJECT), result.getArg(0).type)
    }

    private fun decode(opcode: Opcode): com.jadxmp.ir.insn.Instruction {
        val decoded = MethodDecoder().decode(FakeCodeReader(1,
            listOf(Insn(opcode, offset = 7, registers = intArrayOf(0, 0)))))
        assertTrue(decoded.errors.isEmpty(), decoded.errors.toString())
        val result = decoded.instructions.single().insn
        assertEquals(0, result.result!!.regNum)
        assertEquals(0, (result.getArg(0) as RegisterOperand).regNum)
        assertNotSame(result.result!!, result.getArg(0) as RegisterOperand)
        return result
    }
}
