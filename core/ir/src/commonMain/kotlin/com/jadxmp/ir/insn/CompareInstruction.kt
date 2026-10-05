package com.jadxmp.ir.insn

import com.jadxmp.ir.type.IrType

/** DEX cmp-long, cmpl-*, and cmpg-* have distinct unordered (NaN) results. */
enum class CompareKind(val operandType: IrType, val nanResult: Int?) {
    LONG(IrType.LONG, null),
    FLOAT_LESS(IrType.FLOAT, -1),
    FLOAT_GREATER(IrType.FLOAT, 1),
    DOUBLE_LESS(IrType.DOUBLE, -1),
    DOUBLE_GREATER(IrType.DOUBLE, 1),
}

/** Produces -1/0/1. Floating comparisons treat -0.0 and +0.0 as equal, unlike boxed compare. */
class CompareInstruction(
    val kind: CompareKind,
    result: RegisterOperand? = null,
    args: List<Operand> = emptyList(),
) : Instruction(IrOpcode.CMP, result, args)
