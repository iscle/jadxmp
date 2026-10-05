package com.jadxmp.pipeline.cfg

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.pass.CancellationCheck

/**
 * A monitor-enter on a proven null register throws before acquiring a lock. Lower that operation to
 * `throw null` before SSA so unreachable normal successors cannot contribute values or source code.
 * This deliberately handles only local constant proofs outside protected ranges; unknown locks and
 * bare monitor sequences retain their original semantics and the structurer's safety checks.
 */
internal class NullMonitorLowering(
    private val method: IrMethod,
    private val cancellation: CancellationCheck = CancellationCheck.None,
) {
    fun run() {
        val exit = method.exitBlock ?: return
        for (block in method.blocks) {
            cancellation.ensureActive()
            if (!block[PipelineAttrs.PROTECTING_HANDLERS].isNullOrEmpty()) continue
            val zeroRegisters = HashSet<Int>()
            for (index in block.instructions.indices) {
                val insn = block.instructions[index]
                val lock = insn.args.singleOrNull() as? RegisterOperand
                if (insn.opcode == IrOpcode.MONITOR_ENTER && lock?.regNum in zeroRegisters) {
                    val throwing = Instruction(IrOpcode.THROW, args = listOf(LiteralOperand(0, IrType.THROWABLE)))
                    throwing.offset = insn.offset
                    block.instructions.subList(index, block.instructions.size).clear()
                    block.instructions.add(throwing)
                    for (successor in block.successors.toList()) successor.predecessors.remove(block)
                    block.successors.clear()
                    block.successors.add(exit)
                    if (block !in exit.predecessors) exit.predecessors.add(block)
                    break
                }
                insn.result?.let { result ->
                    zeroRegisters.remove(result.regNum)
                    // Invalidate the overlapping high word too, without depending on inferred types.
                    zeroRegisters.remove(result.regNum + 1)
                    if (insn.opcode == IrOpcode.CONST && (insn.args.singleOrNull() as? LiteralOperand)?.isZero == true) {
                        zeroRegisters.add(result.regNum)
                    }
                }
            }
        }
    }
}
