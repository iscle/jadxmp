package com.jadxmp.pipeline.cfg

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.pipeline.InstructionEffects
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.pass.CancellationCheck

/**
 * Make exceptional transfer explicit before dominance/liveness/SSA. Each protected throwing operation
 * ends its block. A throwing definition writes a scratch register; a MOVE on its normal continuation
 * commits the result to the original register. Thus the ordinary CFG correctly models that a failed
 * array access, cast, or call leaves the destination unchanged, with no exceptional SSA special cases.
 */
internal class ExceptionProgramPoints(
    private val method: IrMethod,
    private val cancellation: CancellationCheck = CancellationCheck.None,
) {
    fun run() {
        val original = method.blocks.toList()
        if (original.none { !it[PipelineAttrs.PROTECTING_HANDLERS].isNullOrEmpty() }) return
        val oldExceptional = method[PipelineAttrs.EXCEPTION_EDGES].orEmpty()
        val normal = original.associateWith { block -> block.successors.filter { edgeKey(block, it) !in oldExceptional } }
        val needsScratch = original.any { block ->
            !block[PipelineAttrs.PROTECTING_HANDLERS].isNullOrEmpty() &&
                block.instructions.any { it.result != null && InstructionEffects.mayThrow(it) }
        }
        if (needsScratch) {
            val frameSize = method[PipelineAttrs.REGISTER_COUNT] ?: return
            // Two slots handle wide results. Shift the original frame upward so incoming DEX arguments
            // still occupy its highest registers, as MethodParams requires.
            for (block in original) for (insn in block.instructions) shiftRegisters(insn)
            method[PipelineAttrs.REGISTER_COUNT] = frameSize + 2
        }
        var nextId = (original.maxOfOrNull { it.id } ?: -1) + 1
        val rebuilt = ArrayList<BasicBlock>()
        val exceptional = HashSet<Long>()
        for (block in original) {
            cancellation.ensureActive()
            val handlers = block[PipelineAttrs.PROTECTING_HANDLERS].orEmpty()
            if (handlers.isEmpty()) {
                rebuilt.add(block)
                continue
            }
            val instructions = block.instructions.toList()
            block.instructions.clear()
            block.successors.clear()
            rebuilt.add(block)
            var current = block
            var beginsContinuation = false
            for (insn in instructions) {
                if (beginsContinuation) {
                    val next = BasicBlock(nextId++)
                    next[PipelineAttrs.PROTECTING_HANDLERS] = handlers
                    current.successors.add(next)
                    rebuilt.add(next)
                    current = next
                    beginsContinuation = false
                }
                current.instructions.add(insn)
                if (!InstructionEffects.mayThrow(insn)) continue
                for (handler in handlers) {
                    if (handler !in current.successors) current.successors.add(handler)
                    exceptional.add(edgeKey(current, handler))
                }
                val destination = insn.result
                if (destination != null) {
                    insn.result = RegisterOperand(0, destination.type)
                    val next = BasicBlock(nextId++)
                    next[PipelineAttrs.PROTECTING_HANDLERS] = handlers
                    val commit = Instruction(IrOpcode.MOVE, destination, listOf(RegisterOperand(0, destination.type)))
                    commit.offset = -1
                    commit[PipelineAttrs.EXCEPTION_RESULT_COMMIT] = true
                    next.instructions.add(commit)
                    current.successors.add(next)
                    rebuilt.add(next)
                    current = next
                } else {
                    beginsContinuation = true
                }
            }
            for (successor in normal.getValue(block)) {
                if (successor !in current.successors) current.successors.add(successor)
                // An edge can have both interpretations; the normal path must remain visible.
                exceptional.remove(edgeKey(current, successor))
            }
        }
        // The coarse CFG may give a protected RETURN only handler successors and no exit edge.
        // Once impossible return exceptions are removed, restore its mandatory normal termination.
        // A protected THROW is different: it can legitimately have only exceptional successors.
        method.exitBlock?.let { exit ->
            for (block in rebuilt) {
                if (block.instructions.lastOrNull()?.opcode != IrOpcode.RETURN) continue
                if (exit !in block.successors) block.successors.add(exit)
                exceptional.remove(edgeKey(block, exit))
            }
        }
        method.blocks.clear()
        method.blocks.addAll(rebuilt)
        for (block in rebuilt) block.predecessors.clear()
        for (block in rebuilt) for (successor in block.successors) {
            if (block !in successor.predecessors) successor.predecessors.add(block)
        }
        method[PipelineAttrs.EXCEPTION_EDGES] = exceptional
    }

    private fun shiftRegisters(insn: Instruction) {
        insn.result?.let { insn.result = RegisterOperand(it.regNum + 2, it.type) }
        for (index in insn.args.indices) {
            when (val operand = insn.getArg(index)) {
                is RegisterOperand -> insn.setArg(index, RegisterOperand(operand.regNum + 2, operand.type))
                is InstructionOperand -> shiftRegisters(operand.instruction)
                else -> Unit
            }
        }
    }

    private fun edgeKey(from: BasicBlock, to: BasicBlock): Long =
        (from.id.toLong() shl 32) or (to.id.toLong() and 0xFFFFFFFFL)
}
