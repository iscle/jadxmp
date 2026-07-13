package com.jadxmp.ir.node

import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.type.IrType

/**
 * A field declaration.  **jadx: FieldNode**
 *
 * [accessFlags] is the raw JVM/DEX access bitmask, kept as an [Int] so the IR does not depend on the
 * input model's flag types.
 */
class IrField(
    val declaringClass: IrClass,
    val name: String,
    val type: IrType,
    val accessFlags: Int,
) : AttrNode() {
    /**
     * The compile-time constant value of a `static final` field, or null.  **jadx: FieldNode.constValue**
     *
     * This is ONLY for values that belong at the field declaration itself — the constant-pool
     * `ConstantValue` a `static final` primitive/`String` carries, which the backend renders as
     * `= <literal>`. Fields assigned non-constant values in `<clinit>` leave this null; that
     * initialization stays as instructions in the static initializer.
     *
     * IR-native on purpose: `core:ir` does not depend on the input model's `EncodedValue`; the
     * pipeline maps `FieldData.constValue` onto an [IrFieldConst].
     */
    var constValue: IrFieldConst? = null

    override fun toString(): String = "${declaringClass.fullName}.$name"
}

/**
 * A field's compile-time constant initializer.  **jadx: EncodedValue (constant subset)**
 *
 * Covers the corpus cases for `static final` declaration-site initializers. TYPE/ENUM/array constants
 * are omitted (rare); the pipeline leaves [IrField.constValue] null for those.
 */
sealed class IrFieldConst {
    /**
     * A primitive/boolean/char constant, stored the same way as [com.jadxmp.ir.insn.LiteralOperand]:
     * [bits] is the raw bit pattern and [type] fixes its meaning. Handles INT/LONG/SHORT/BYTE/CHAR/
     * BOOLEAN (integer bits) and FLOAT/DOUBLE (raw IEEE-754 bits — same convention as `LiteralOperand`),
     * so codegen can render it via `JavaLiterals.format(LiteralOperand(bits, type))`.
     */
    data class Primitive(val bits: Long, val type: IrType) : IrFieldConst()

    /** A `String` constant. */
    data class Str(val value: String) : IrFieldConst()
}
