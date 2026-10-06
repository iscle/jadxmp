package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReaderException

/** Per-method limits bound dense frame storage and repeated work on hostile control-flow graphs. */
internal data class JvmAnalysisLimits(
    val maxFrameCells: Long = 4_000_000,
    val maxWork: Long = 10_000_000,
)

/** Normal edges only. Ordered exception edges and reference/type hierarchy joins are later layers. */
internal class JvmPrimitiveFlow(private val instructions: List<JvmInstruction>) {
    private data class Block(val start: Int, val end: Int, val successors: IntArray)
    private val blocks: List<Block>

    init {
        val indexAt = instructions.withIndex().associate { it.value.offset to it.index }
        val leaders = mutableSetOf(0)
        for ((index, instruction) in instructions.withIndex()) {
            when (val operand = instruction.operand) {
                is JvmOperand.Branch -> leaders += indexAt.getValue(operand.target)
                is JvmOperand.Switch -> {
                    leaders += indexAt.getValue(operand.defaultTarget)
                    for (case in operand.cases) leaders += indexAt.getValue(case.target)
                }
                else -> Unit
            }
            if (endsBlock(instruction) && index + 1 < instructions.size) leaders += index + 1
        }
        val starts = leaders.sorted()
        val blockAt = IntArray(instructions.size)
        for (block in starts.indices) {
            for (index in starts[block] until starts.getOrElse(block + 1) { instructions.size }) {
                blockAt[index] = block
            }
        }
        blocks = starts.mapIndexed { block, start ->
            val end = starts.getOrElse(block + 1) { instructions.size }
            val last = instructions[end - 1]
            fun target(offset: Int) = blockAt[indexAt.getValue(offset)]
            fun following(): Int {
                if (block + 1 == starts.size) invalid("method falls through after bytecode ${last.offset}")
                return block + 1
            }
            val successors = when {
                last.opcode in 0xac..0xb1 || last.opcode == 0xbf -> intArrayOf()
                last.operand is JvmOperand.Switch -> {
                    val table = last.operand
                    (listOf(target(table.defaultTarget)) + table.cases.map { target(it.target) }).distinct().toIntArray()
                }
                last.operand is JvmOperand.Branch -> {
                    val destination = target(last.operand.target)
                    if (last.opcode == 0xa7 || last.opcode == 0xc8) intArrayOf(destination)
                    else intArrayOf(destination, following()).distinct().toIntArray()
                }
                else -> intArrayOf(following())
            }
            Block(start, end, successors)
        }
    }

    /** Transfer mutates one working frame; retained incoming states are never passed to it. */
    fun analyze(initial: JvmFrame, limits: JvmAnalysisLimits,
        transfer: (JvmFrame, JvmInstruction) -> Unit,
    ): List<JvmFrame> {
        val states = arrayOfNulls<JvmFrame>(blocks.size)
        val queued = BooleanArray(blocks.size)
        val pending = ArrayDeque<Int>()
        // The caller's initial frame stays live during analysis. Reserve it and capacity for the
        // one working frame, even before that frame's operand stack grows.
        var cells = initial.stateCells.toLong() + initial.maxLocals + initial.maxStack
        var work = 0L
        fun charge(amount: Int) {
            work += amount.toLong()
            if (work > limits.maxWork) invalid("analysis work limit exceeded")
        }
        fun retain(state: JvmFrame): JvmFrame {
            cells += state.stateCells
            if (cells > limits.maxFrameCells) invalid("analysis frame storage limit exceeded")
            charge(state.stateCells)
            return state.snapshot()
        }
        fun enqueue(block: Int) {
            if (!queued[block]) { queued[block] = true; pending.addLast(block) }
        }
        states[0] = retain(initial)
        enqueue(0)
        while (pending.isNotEmpty()) {
            val index = pending.removeFirst()
            queued[index] = false
            val state = checkNotNull(states[index])
            charge(state.stateCells)
            val working = state.snapshot()
            val block = blocks[index]
            for (instruction in block.start until block.end) {
                charge(1)
                transfer(working, instructions[instruction])
            }
            for (successor in block.successors) {
                val previous = states[successor]
                if (previous == null) {
                    states[successor] = retain(working)
                    enqueue(successor)
                } else {
                    charge(previous.stateCells)
                    if (previous.mergeFrom(working)) enqueue(successor)
                }
            }
        }
        return states.mapIndexed { index, frame ->
            frame ?: invalid("unreachable bytecode region at ${instructions[blocks[index].start].offset} is unsupported")
        }
    }

    fun emit(states: List<JvmFrame>, transfer: (JvmFrame, JvmInstruction) -> Unit) {
        for ((index, block) in blocks.withIndex()) {
            val working = states[index].snapshot()
            for (instruction in block.start until block.end) transfer(working, instructions[instruction])
        }
    }

    private fun endsBlock(instruction: JvmInstruction): Boolean =
        instruction.operand is JvmOperand.Branch || instruction.operand is JvmOperand.Switch ||
            instruction.opcode in 0xac..0xb1 || instruction.opcode == 0xbf

    private fun invalid(message: String): Nothing = throw ByteReaderException("JVM primitive control flow: $message")
}
