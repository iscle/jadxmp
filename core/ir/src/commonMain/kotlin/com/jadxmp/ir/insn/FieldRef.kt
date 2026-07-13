package com.jadxmp.ir.insn

import com.jadxmp.ir.type.IrType

/**
 * A symbolic reference to a field, in resolved [IrType] terms.  **jadx: FieldInfo**
 *
 * Part of THE canonical instruction-payload contract (see [FieldInstruction]); the IR owns the single
 * field-ref representation both pipeline and codegen use. Whether an access is static or a put is a
 * property of the *access* ([FieldInstruction.isStatic]/[FieldInstruction.isPut]), not of the field
 * reference, so it is not carried here. Value type (structural equality).
 */
data class FieldRef(
    val declaringType: IrType,
    val name: String,
    val type: IrType,
) {
    override fun toString(): String = "$declaringType.$name: $type"
}
