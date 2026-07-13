package com.jadxmp.pipeline.cfg

import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.type.IrType

/**
 * A realised exception handler: the [entryBlock] a protected region jumps to when it throws, and the
 * exception [types] it catches (a catch-all / `finally` is modelled by [catchAll] = true, in which
 * case [types] is empty). Attached to the handler entry block via [com.jadxmp.pipeline.PipelineAttrs.EXC_HANDLER].
 *
 * jadx: ExceptionHandler.
 */
class ExceptionHandler(
    val entryBlock: BasicBlock,
    val types: List<IrType>,
    val catchAll: Boolean,
) {
    /**
     * The static type of the caught exception operand: the single caught type, or `java.lang.Throwable`
     * when the handler catches several types or is a catch-all (the join of unrelated throwables that
     * this stage cannot name without the class graph).
     */
    val caughtType: IrType
        get() = if (!catchAll && types.size == 1) types[0] else IrType.THROWABLE
}
