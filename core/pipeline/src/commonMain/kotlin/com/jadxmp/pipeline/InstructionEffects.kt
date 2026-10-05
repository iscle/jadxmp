package com.jadxmp.pipeline

import com.jadxmp.ir.insn.ArithInstruction
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.IrOpcode

/** Shared conservative exception effects, including expression operands created by shaping. */
internal object InstructionEffects {
    fun mayThrow(insn: Instruction): Boolean {
        if (insn.args.any { it is InstructionOperand && mayThrow(it.instruction) }) return true
        return when (insn.opcode) {
            IrOpcode.CONST, IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.MOVE_EXCEPTION,
            IrOpcode.ONE_ARG, IrOpcode.NEG, IrOpcode.NOT, IrOpcode.CAST, IrOpcode.CMP,
            IrOpcode.GOTO, IrOpcode.NOP, IrOpcode.RETURN, IrOpcode.IF, IrOpcode.SWITCH -> false
            IrOpcode.ARITH -> when ((insn as? ArithInstruction)?.op) {
                null, ArithOp.DIV, ArithOp.REM -> true
                else -> false
            }
            // String/class resolution, memory access, reference casts, calls, allocation and monitors
            // can all raise exceptions. Unknown operations must retain their exceptional control flow.
            else -> true
        }
    }
}
