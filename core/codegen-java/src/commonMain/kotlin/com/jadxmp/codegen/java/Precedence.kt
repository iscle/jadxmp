package com.jadxmp.codegen.java

import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.ConditionOp

/**
 * Java operator precedence levels — higher binds tighter. **jadx: InsnGen precedence handling**
 *
 * The expression emitter passes each sub-expression the minimum precedence it may have without needing
 * parentheses; a sub-expression whose own level is lower wraps itself. For a left-associative binary
 * operator at level `P`, the left operand is emitted with minimum `P` and the right with `P + 1`, which
 * yields correct, minimal parenthesization (`a - b - c` but `a - (b - c)`).
 */
internal object Prec {
    const val LOWEST = 0
    const val ASSIGN = 1
    const val TERNARY = 2
    const val LOGIC_OR = 3
    const val LOGIC_AND = 4
    const val BIT_OR = 5
    const val BIT_XOR = 6
    const val BIT_AND = 7
    const val EQUALITY = 8
    const val RELATIONAL = 9
    const val SHIFT = 10
    const val ADD = 11
    const val MUL = 12
    const val UNARY = 14
    const val PRIMARY = 15
}

internal fun ArithOp.precedence(): Int = when (this) {
    ArithOp.MUL, ArithOp.DIV, ArithOp.REM -> Prec.MUL
    ArithOp.ADD, ArithOp.SUB -> Prec.ADD
    ArithOp.SHL, ArithOp.SHR, ArithOp.USHR -> Prec.SHIFT
    ArithOp.AND -> Prec.BIT_AND
    ArithOp.XOR -> Prec.BIT_XOR
    ArithOp.OR -> Prec.BIT_OR
    ArithOp.NEGATE -> Prec.UNARY
}

internal fun ConditionOp.precedence(): Int = when (this) {
    ConditionOp.EQ, ConditionOp.NE -> Prec.EQUALITY
    ConditionOp.LT, ConditionOp.GE, ConditionOp.GT, ConditionOp.LE -> Prec.RELATIONAL
}
