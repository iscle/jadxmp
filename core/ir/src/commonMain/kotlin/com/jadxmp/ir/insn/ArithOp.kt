package com.jadxmp.ir.insn

/**
 * Arithmetic / bitwise operators for [IrOpcode.ARITH] (and unary [IrOpcode.NEG]).
 * jadx: ArithOp
 */
enum class ArithOp(val symbol: String) {
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/"),
    REM("%"),
    AND("&"),
    OR("|"),
    XOR("^"),
    SHL("<<"),
    SHR(">>"),
    USHR(">>>"),
    NEGATE("-"), // unary minus
}
