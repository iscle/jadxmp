package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.ConditionOp

/**
 * Kotlin operator precedence levels — higher binds tighter. **jadx: InsnGen precedence handling**
 *
 * Kotlin's precedence table is materially different from Java's, so this is not a copy of the Java
 * `Prec`. The key differences the emitter depends on:
 *  - the bitwise/shift operators are **named infix functions** (`and`, `or`, `xor`, `shl`, `shr`,
 *    `ushr`) that all share ONE precedence level ([INFIX]) — unlike Java's five distinct levels — and
 *    sit *below* additive but *above* the comparison operators;
 *  - there is no ternary operator; `a ? b : c` becomes an `if` expression, emitted at [LOWEST] so any
 *    surrounding operator parenthesises it;
 *  - the type-cast `as`/`as?` binds tighter than the arithmetic operators ([AS]).
 *
 * The emitter passes each sub-expression the minimum precedence it may have without parentheses; a
 * sub-expression whose own level is lower wraps itself. For a left-associative binary operator at
 * level `P`, the left operand is emitted with minimum `P` and the right with `P + 1`.
 */
internal object KotlinPrec {
    const val LOWEST = 0
    const val DISJUNCTION = 1 // ||
    const val CONJUNCTION = 2 // &&
    const val EQUALITY = 3 // == !=
    const val COMPARISON = 4 // < > <= >=
    const val NAMED_CHECK = 5 // is / !is / in / !in
    const val ELVIS = 6 // ?:
    const val INFIX = 7 // named infix functions: and or xor shl shr ushr
    const val RANGE = 8 // ..
    const val ADD = 9 // + -
    const val MUL = 10 // * / %
    const val AS = 11 // as / as?
    const val PREFIX = 12 // unary - + !
    const val POSTFIX = 13 // calls, member access, .inv()
    const val PRIMARY = 14
}

/** The Kotlin precedence of an arithmetic/bitwise operator. */
internal fun ArithOp.kotlinPrecedence(): Int = when (this) {
    ArithOp.MUL, ArithOp.DIV, ArithOp.REM -> KotlinPrec.MUL
    ArithOp.ADD, ArithOp.SUB -> KotlinPrec.ADD
    // Bitwise & shift are all named infix functions at the single shared infix level.
    ArithOp.AND, ArithOp.OR, ArithOp.XOR, ArithOp.SHL, ArithOp.SHR, ArithOp.USHR -> KotlinPrec.INFIX
    ArithOp.NEGATE -> KotlinPrec.PREFIX
}

/** The Kotlin infix spelling of an arithmetic/bitwise operator (`&`→`and`, `>>>`→`ushr`, …). */
internal fun ArithOp.kotlinSymbol(): String = when (this) {
    ArithOp.ADD -> "+"
    ArithOp.SUB -> "-"
    ArithOp.MUL -> "*"
    ArithOp.DIV -> "/"
    ArithOp.REM -> "%"
    ArithOp.AND -> "and"
    ArithOp.OR -> "or"
    ArithOp.XOR -> "xor"
    ArithOp.SHL -> "shl"
    ArithOp.SHR -> "shr"
    ArithOp.USHR -> "ushr"
    ArithOp.NEGATE -> "-"
}

internal fun ConditionOp.kotlinPrecedence(): Int = when (this) {
    ConditionOp.EQ, ConditionOp.NE -> KotlinPrec.EQUALITY
    ConditionOp.LT, ConditionOp.GE, ConditionOp.GT, ConditionOp.LE -> KotlinPrec.COMPARISON
}
