package com.jadxmp.ir.region

import com.jadxmp.ir.insn.ConditionOp
import com.jadxmp.ir.insn.Operand

/**
 * A boolean condition attached to an [IfRegion] or [LoopRegion].  **jadx: IfCondition**
 *
 * Structuring builds these from the CFG's branch instructions, folding short-circuit `&&`/`||`
 * chains into [And]/[Or] trees and branch inversions into [Not], so codegen emits a single readable
 * expression instead of a tangle of gotos. Immutable.
 */
sealed class Condition {

    /** A relational comparison `left <op> right`. */
    data class Compare(val op: ConditionOp, val left: Operand, val right: Operand) : Condition()

    /** Truthiness of a single boolean operand. */
    data class BoolTest(val operand: Operand) : Condition()

    /** Logical negation. */
    data class Not(val negated: Condition) : Condition()

    /** Short-circuit conjunction of two-or-more terms. */
    data class And(val terms: List<Condition>) : Condition()

    /** Short-circuit disjunction of two-or-more terms. */
    data class Or(val terms: List<Condition>) : Condition()
}
