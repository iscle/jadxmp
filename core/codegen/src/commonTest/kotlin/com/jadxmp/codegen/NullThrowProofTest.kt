package com.jadxmp.codegen

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NullThrowProofTest {
    @Test fun followsOnlyTheExactSsaVersion() {
        fun version(number: Int, value: Long): RegisterOperand {
            val result = RegisterOperand(0, IrType.THROWABLE)
            Instruction(IrOpcode.CONST, result, listOf(LiteralOperand(value, IrType.THROWABLE)))
            val ssa = SsaValue(0, number, result)
            return RegisterOperand(0, IrType.THROWABLE).also { it.ssaValue = ssa }
        }
        val proof = NullThrowProof()
        assertTrue(proof.isNull(version(0, 0)))
        assertFalse(proof.isNull(version(1, 1)))
        assertFalse(proof.isNull(RegisterOperand(0, IrType.THROWABLE)))
    }

    @Test fun rejectsCallsPhiAndCastsEvenWithNullArguments() {
        val proof = NullThrowProof()
        for (opcode in listOf(IrOpcode.INVOKE, IrOpcode.PHI, IrOpcode.CHECK_CAST)) {
            assertFalse(proof.isNull(InstructionOperand(Instruction(opcode,
                args = listOf(LiteralOperand(0, IrType.THROWABLE))))))
        }
        assertFalse(proof.isNull(InstructionOperand(Instruction(IrOpcode.MOVE))))
    }

    @Test fun cyclesFailConservatively() {
        val instruction = Instruction(IrOpcode.MOVE)
        val operand = InstructionOperand(instruction)
        instruction.addArg(operand)
        val proof = NullThrowProof()
        assertFalse(proof.isNull(operand))
        assertFalse(proof.isNull(operand))
    }

    @Test fun longForwardingChainsAreIterativeAndMemoized() {
        val proof = NullThrowProof()
        val operands = mutableListOf<Operand>(LiteralOperand(0, IrType.THROWABLE))
        repeat(20_000) {
            operands += InstructionOperand(Instruction(IrOpcode.MOVE, args = listOf(operands.last())))
        }
        assertTrue(proof.isNull(operands.last()))
        for (operand in operands) assertTrue(proof.isNull(operand))
    }
}
