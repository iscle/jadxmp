package com.jadxmp.pipeline.cfg

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.pass.CancellationCheck

/** Removes only handlers whose entire protected input cannot raise a matching exception. */
internal class ExceptionEdgePruner(
    private val method: IrMethod,
    private val cancellation: CancellationCheck = CancellationCheck.None,
) {
    fun run() {
        val edges = method[PipelineAttrs.EXCEPTION_EDGES]?.toMutableSet() ?: return
        for (handler in method.blocks) {
            cancellation.ensureActive()
            val caught = handler[PipelineAttrs.EXC_HANDLER] ?: continue
            val protected = method.blocks.filter { handler in it[PipelineAttrs.PROTECTING_HANDLERS].orEmpty() }
            if (protected.isEmpty()) continue
            if (protected.any { block -> block.instructions.any { canReach(it, caught) } }) continue
            for (block in protected) {
                block[PipelineAttrs.PROTECTING_HANDLERS] = block[PipelineAttrs.PROTECTING_HANDLERS].orEmpty().filter { it !== handler }
                // A coincident normal edge must survive even though its exceptional interpretation dies.
                if (edges.remove(edgeKey(block, handler))) {
                    block.successors.remove(handler)
                    handler.predecessors.remove(block)
                }
            }
            handler.remove(PipelineAttrs.EXC_HANDLER)
        }
        method[PipelineAttrs.EXCEPTION_EDGES] = edges
        // Dominators subsequently removes unreachable handler blocks; no reachable normal flow is erased.
    }

    private fun canReach(insn: Instruction, handler: ExceptionHandler): Boolean {
        if (insn.args.any { it is InstructionOperand && canReach(it.instruction, handler) }) return true
        return when (insn.opcode) {
            IrOpcode.CONST, IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.MOVE_EXCEPTION,
            IrOpcode.ONE_ARG, IrOpcode.NEG, IrOpcode.NOT, IrOpcode.CAST, IrOpcode.CMP,
            IrOpcode.GOTO, IrOpcode.NOP, IrOpcode.RETURN, IrOpcode.IF, IrOpcode.SWITCH -> false
            // Resolving a string may fail with an Error (e.g. allocation/linkage), so keep Error,
            // Throwable, catch-all and unknown handlers. Only proven Exception subtypes cannot match.
            IrOpcode.CONST_STRING -> handler.catchAll || handler.types.any { !isExceptionSubtype(it) }
            else -> true // calls, memory access, allocation, monitors, division and unknown opcodes
        }
    }

    private fun isExceptionSubtype(type: IrType): Boolean {
        var name = (type as? IrType.Object)?.className ?: return false
        val seen = HashSet<String>()
        while (seen.add(name)) {
            if (name in EXCEPTION_BASES) return true
            val cls = method.declaringClass.root.findClass(name) ?: return false
            name = (cls.superType as? IrType.Object)?.className ?: return false
        }
        return false
    }

    private fun edgeKey(from: BasicBlock, to: BasicBlock): Long =
        (from.id.toLong() shl 32) or (to.id.toLong() and 0xFFFFFFFFL)

    private companion object {
        // Known platform ancestry anchors; unresolved user/library classes are never guessed.
        val EXCEPTION_BASES = setOf("java.lang.Exception", "java.lang.RuntimeException", "java.io.IOException")
    }
}
