package com.jadxmp.ir.node

import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.type.IrType

/**
 * A single-assignment value produced by SSA construction.  **jadx: SSAVar**
 *
 * SSA's defining invariant: **exactly one definition** ([assign]) and any number of [uses]. Each
 * SSA value pins a `(regNum, version)` pair — one raw register splits into as many SSA values as it
 * has definitions. A [TypeCell] holds the value's inferred type; [localVar] links the value to the
 * source-level variable that several SSA versions ultimately collapse into.
 */
class SsaValue(
    val regNum: Int,
    val version: Int,
    assign: RegisterOperand,
) : AttrNode() {

    /** The one occurrence that defines this value. */
    var assign: RegisterOperand = assign
        set(value) {
            field = value
            value.ssaValue = this
        }

    private val mutableUses: MutableList<RegisterOperand> = ArrayList(2)

    /** All occurrences that read this value. */
    val uses: List<RegisterOperand> get() = mutableUses

    /** Type-inference cell (see [TypeCell]). */
    val typeCell: TypeCell = TypeCell()

    /** The source-level variable this value belongs to; set during variable collapsing. */
    var localVar: LocalVar? = null

    init {
        assign.ssaValue = this
    }

    val useCount: Int get() = mutableUses.size

    fun addUse(use: RegisterOperand) {
        use.ssaValue = this
        mutableUses.add(use)
    }

    fun removeUse(use: RegisterOperand) {
        mutableUses.remove(use)
    }

    /** Current inferred type (shorthand for `typeCell.type`). */
    val type: IrType get() = typeCell.type

    override fun toString(): String = "r${regNum}v$version"
}
