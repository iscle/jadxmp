package com.jadxmp.ir.node

import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.type.IrType

/**
 * A source-level local variable.  **jadx: CodeVar**
 *
 * Several [SsaValue]s (the SSA versions of one register, once they are known to represent the same
 * program variable) collapse into a single `LocalVar` — this is what gets one name and one
 * declaration in the generated source. [type] is the variable's final reconstructed type; it may be
 * null before inference for all but bytecode-fixed variables.
 */
class LocalVar : AttrNode() {
    var name: String? = null
    var type: IrType? = null

    private val mutableSsaValues: MutableList<SsaValue> = ArrayList(2)
    val ssaValues: List<SsaValue> get() = mutableSsaValues

    var isFinal: Boolean = false
    var isThis: Boolean = false

    /** Whether a declaration has already been emitted for this variable during codegen. */
    var isDeclared: Boolean = false

    fun addSsaValue(value: SsaValue) {
        value.localVar = this
        mutableSsaValues.add(value)
    }

    override fun toString(): String = name ?: "<var>"
}
