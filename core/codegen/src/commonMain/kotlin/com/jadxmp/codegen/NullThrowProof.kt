package com.jadxmp.codegen

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand

/**
 * Method-scoped proof for a throw operand only: zero at this reference use denotes null.
 * Keep one instance while emitting an unchanged method. Unknown and effectful definitions fail
 * conservatively; following exact SSA versions cannot confuse a later register reassignment.
 */
class NullThrowProof {
    private val results = mutableMapOf<Instruction, Boolean>()

    /** Follow only side-effect-free forwarding of one SSA value, never a call or a phi guess. */
    fun isNull(operand: Operand): Boolean {
        var current = operand
        val seen = mutableSetOf<Instruction>()
        fun finish(result: Boolean): Boolean {
            for (instruction in seen) results[instruction] = result
            return result
        }
        while (true) {
            if (current is LiteralOperand) return finish(current.value == 0L)
            val definition = when (current) {
                is RegisterOperand -> current.ssaValue?.assign?.parent
                is InstructionOperand -> current.instruction
                else -> null
            } ?: return finish(false)
            results[definition]?.let { return finish(it) }
            if (!seen.add(definition) || definition.argCount != 1) return finish(false)
            when (definition.opcode) {
                IrOpcode.CONST, IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG -> current = definition.getArg(0)
                else -> return finish(false)
            }
        }
    }
}
