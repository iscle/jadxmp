package com.jadxmp.ir.attr

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.type.IrType

/**
 * Source-level analysis metadata shared by the pipeline and source backends.
 *
 * The IR owns these identity-based keys so analysis never depends on an emitter. Keep the stable
 * diagnostic names for compatibility; consumers must share these instances, not recreate them.
 * Instruction payload belongs on the typed instruction subclasses rather than in these attributes.
 */
object SourceAttributes {
    /** On an `IrMethod`: parameter source names in argument order, excluding the receiver. */
    val PARAM_NAMES: AttrKey<List<String>> = AttrKey("codegen.paramNames")

    /** On an `IrMethod`: declared checked exception types, for the Java `throws` clause. */
    val THROWS: AttrKey<List<IrType>> = AttrKey("codegen.throws")

    /** On a `FOR` loop region: the inline initializer of its three-part header. */
    val LOOP_INIT: AttrKey<Instruction> = AttrKey("codegen.loopInit")

    /** On a `FOR` loop region: the inline update of its three-part header. */
    val LOOP_UPDATE: AttrKey<Instruction> = AttrKey("codegen.loopUpdate")
}
