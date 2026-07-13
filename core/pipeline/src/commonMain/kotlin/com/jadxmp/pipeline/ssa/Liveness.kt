package com.jadxmp.pipeline.ssa

import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.pipeline.pass.CancellationCheck

/**
 * Backward live-variable analysis over registers, used to **prune** φ placement (a φ is only useful
 * where its register is live at block entry).  **jadx: LiveVarAnalysis.**
 *
 * For each block it computes `gen` (registers used before any local definition) and `kill` (registers
 * defined), then iterates `liveIn[b] = gen[b] ∪ (⋃ liveIn[successors] − kill[b])` to a fixpoint. The
 * method-entry block is treated as defining the parameter registers so parameters are handled
 * uniformly with ordinary definitions.
 */
class Liveness(
    private val method: IrMethod,
    private val registerCount: Int,
    private val entryRegisters: Set<Int>,
) {
    private val gen = HashMap<Int, BooleanArray>()
    private val kill = HashMap<Int, BooleanArray>()
    private val liveIn = HashMap<Int, BooleanArray>()
    private val defBlocksByReg = Array(registerCount) { HashSet<Int>() }

    fun run(cancellation: CancellationCheck) {
        for (block in method.blocks) {
            gen[block.id] = BooleanArray(registerCount)
            kill[block.id] = BooleanArray(registerCount)
            liveIn[block.id] = BooleanArray(registerCount)
        }
        fillGenKill()
        iterate(cancellation)
    }

    private fun fillGenKill() {
        // Entry block "defines" the parameters.
        method.entryBlock?.let { entry ->
            val k = kill[entry.id]!!
            for (reg in entryRegisters) {
                k[reg] = true
                defBlocksByReg[reg].add(entry.id)
            }
        }
        for (block in method.blocks) {
            val g = gen[block.id]!!
            val k = kill[block.id]!!
            for (insn in block.instructions) {
                for (i in 0 until insn.argCount) {
                    val arg = insn.getArg(i)
                    if (arg is RegisterOperand && !k[arg.regNum]) g[arg.regNum] = true
                }
                val result = insn.result
                if (result != null) {
                    k[result.regNum] = true
                    defBlocksByReg[result.regNum].add(block.id)
                }
            }
        }
    }

    private fun iterate(cancellation: CancellationCheck) {
        // Backward live-variable analysis over a finite boolean lattice: each iteration that changes
        // anything sets at least one more bit and bits are never cleared, so the fixpoint is reached in
        // a bounded number of passes. No iteration cap — a cap here could exit with an under-approximated
        // live-in set, pruning a needed φ and silently miscompiling (cancellation still bounds runtime).
        var changed = true
        while (changed) {
            cancellation.ensureActive()
            changed = false
            for (block in method.blocks.asReversed()) {
                val g = gen[block.id]!!
                val k = kill[block.id]!!
                val newIn = BooleanArray(registerCount)
                for (succ in block.successors) {
                    val sIn = liveIn[succ.id] ?: continue
                    for (r in 0 until registerCount) if (sIn[r]) newIn[r] = true
                }
                for (r in 0 until registerCount) {
                    newIn[r] = g[r] || (newIn[r] && !k[r])
                }
                val prev = liveIn[block.id]!!
                if (!prev.contentEquals(newIn)) {
                    liveIn[block.id] = newIn
                    changed = true
                }
            }
        }
    }

    fun isLiveIn(blockId: Int, reg: Int): Boolean = liveIn[blockId]?.get(reg) ?: false

    fun defBlocks(reg: Int): Set<Int> = defBlocksByReg[reg]
}
