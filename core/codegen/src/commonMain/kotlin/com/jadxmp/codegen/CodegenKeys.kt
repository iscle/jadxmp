package com.jadxmp.codegen

import com.jadxmp.ir.attr.AttrKey
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.type.IrType

/**
 * Codegen-facing attribute keys for **method- and region-level metadata** — data that is genuinely a
 * property of the method or region, not of an individual instruction.
 *
 * Instruction *payload* (the called method, accessed field, string/type literal, switch table, φ
 * edges) is NOT here: it rides on the canonical `core:ir` instruction subclasses
 * ([com.jadxmp.ir.insn.InvokeInstruction], [com.jadxmp.ir.insn.FieldInstruction],
 * [com.jadxmp.ir.insn.ConstStringInstruction], [com.jadxmp.ir.insn.TypeInstruction], …), which
 * producer (pipeline) and consumers (both codegen backends) share directly. Only the keys below,
 * which describe an `IrMethod` or a `LoopRegion` rather than an instruction, remain as attributes.
 */
object CodegenKeys {
    /**
     * On an `IrMethod`: the source names of its parameters, in order (size == `argTypes.size`). When
     * absent the backend generates stable type-based names. The pipeline's debug-info / naming pass
     * populates this.
     */
    val PARAM_NAMES: AttrKey<List<String>> = AttrKey("codegen.paramNames")

    /** On an `IrMethod`: the declared checked exception types, rendered in a `throws` clause. */
    val THROWS: AttrKey<List<IrType>> = AttrKey("codegen.throws")

    /**
     * On a `LoopRegion` of kind `FOR`: the init and update clauses of the classic three-part `for`
     * header (the condition is the region's own). Each is rendered as an inline statement. When absent
     * the loop degrades to a `while`-style header. Structuring populates these when it recognises a
     * counting loop.
     */
    val LOOP_INIT: AttrKey<Instruction> = AttrKey("codegen.loopInit")
    val LOOP_UPDATE: AttrKey<Instruction> = AttrKey("codegen.loopUpdate")
}
