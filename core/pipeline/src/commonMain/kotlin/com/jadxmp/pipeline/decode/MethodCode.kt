package com.jadxmp.pipeline.decode

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.type.IrType

/**
 * The flat, decoded body of one method: normalized [DecodedInstruction]s in program order plus the
 * resolved exception table. This is the hand-off from IR-decode to CFG building — jump targets are
 * already resolved to absolute code-unit offsets and move-results are already folded into their
 * producing instruction.
 */
class MethodCode(
    val instructions: List<DecodedInstruction>,
    val tries: List<DecodedTry>,
    val registerCount: Int,
    /**
     * Diagnostics for opcodes that could not be decoded faithfully (e.g. `const-method-handle`). Each
     * such opcode still emits a register-preserving placeholder instruction (no silent code loss); the
     * CFG pass turns a non-empty list into a method-level [com.jadxmp.ir.attr.IrAttrs.ERROR] so the
     * no-error accuracy signal correctly fails instead of masking garbage output.
     */
    val errors: List<String> = emptyList(),
)

/**
 * One decoded IR instruction with the control-flow facts CFG building needs: whether control falls
 * through to the next instruction and the explicit branch [targets] (absolute code-unit offsets).
 */
class DecodedInstruction(
    insn: Instruction,
    val offset: Int,
) {
    /** The decoded IR instruction. Mutable only during decode's second (payload-resolve) pass. */
    var insn: Instruction = insn
        internal set

    /** Does control continue to the textually-next instruction? False for goto/return/throw. */
    var fallsThrough: Boolean = true

    /** Explicit branch targets (absolute code-unit offsets): the `if`/`goto`/`switch` destinations. */
    var targets: IntArray = EMPTY

    /** True when this instruction must end its basic block (it branches or cannot fall through). */
    val terminatesBlock: Boolean get() = !fallsThrough || targets.isNotEmpty()

    private companion object {
        val EMPTY = IntArray(0)
    }
}

/** A resolved protected range and its handlers. Offsets are absolute code-unit offsets. */
class DecodedTry(
    val start: Int,
    val end: Int,
    val handlers: List<DecodedHandler>,
)

/** One handler of a [DecodedTry]. [type] is null for a catch-all (`finally` / bare catch-all). */
class DecodedHandler(
    val type: IrType?,
    val handlerOffset: Int,
)
