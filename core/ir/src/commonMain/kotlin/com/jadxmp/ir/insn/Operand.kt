package com.jadxmp.ir.insn

import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType

/**
 * An operand of an [Instruction].  **jadx: InsnArg**
 *
 * The three shapes are the whole hierarchy:
 * - [RegisterOperand] — a virtual register / SSA value.
 * - [LiteralOperand] — a compile-time constant.
 * - [InstructionOperand] — a *nested* instruction, i.e. an inlined sub-expression. This is how flat
 *   register code becomes an expression tree: a single-use definition is folded into the operand
 *   position where it is used.
 *
 * [type] is mutable because type inference progressively refines it; [parent] is the back-link to
 * the owning instruction (null while detached or for a method-parameter register).
 */
sealed class Operand : AttrNode() {
    abstract var type: IrType

    /** The instruction this operand belongs to; null when detached. Maintained by [Instruction]. */
    var parent: Instruction? = null

    val isRegister: Boolean get() = this is RegisterOperand
    val isLiteral: Boolean get() = this is LiteralOperand
    val isNested: Boolean get() = this is InstructionOperand
}

/**
 * A virtual register operand, linked to its [SsaValue] once SSA is built.  **jadx: RegisterArg**
 *
 * Before SSA, [ssaValue] is null and [regNum] identifies the raw register. After SSA construction
 * every occurrence points at the single [SsaValue] it reads or defines.
 */
open class RegisterOperand(
    val regNum: Int,
    override var type: IrType,
) : Operand() {
    var ssaValue: SsaValue? = null

    override fun toString(): String = "r$regNum:$type"
}

/**
 * A constant operand.  **jadx: LiteralArg**
 *
 * The raw [value] is the bit pattern (booleans/chars/ints/longs are the integer bits; float/double
 * are the IEEE-754 bits). Its meaning is fixed by [type].
 */
class LiteralOperand(
    val value: Long,
    override var type: IrType,
) : Operand() {
    val isZero: Boolean get() = value == 0L

    override fun toString(): String = "$value:$type"
}

/**
 * An operand that is itself an instruction — an inlined sub-expression.  **jadx: InsnWrapArg**
 *
 * [type] mirrors the wrapped instruction's result type (as last known); it may be refined
 * independently as inference proceeds.
 */
class InstructionOperand(
    val instruction: Instruction,
) : Operand() {
    override var type: IrType = instruction.result?.type ?: IrType.UNKNOWN

    override fun toString(): String = "($instruction)"
}
