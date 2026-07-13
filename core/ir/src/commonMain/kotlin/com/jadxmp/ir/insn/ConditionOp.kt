package com.jadxmp.ir.insn

/**
 * Comparison operator of an [IrOpcode.IF] branch (and reused by if-region conditions).
 * jadx: IfOp
 */
enum class ConditionOp(val symbol: String) {
    EQ("=="),
    NE("!="),
    LT("<"),
    GE(">="),
    GT(">"),
    LE("<="),
    ;

    /** The operator that negates this one (`==`⇄`!=`, `<`⇄`>=`, `>`⇄`<=`). */
    fun negate(): ConditionOp = when (this) {
        EQ -> NE
        NE -> EQ
        LT -> GE
        GE -> LT
        GT -> LE
        LE -> GT
    }
}
