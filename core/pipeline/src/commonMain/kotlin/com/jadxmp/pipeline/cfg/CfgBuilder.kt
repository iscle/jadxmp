package com.jadxmp.pipeline.cfg

import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.decode.DecodedInstruction
import com.jadxmp.pipeline.decode.MethodCode
import com.jadxmp.pipeline.pass.CancellationCheck

/**
 * Splits a decoded method body into basic blocks and wires the control-flow graph.  **jadx: BlockSplitter.**
 *
 * Produces the invariants every downstream stage relies on:
 * - a single synthetic **entry** block (predecessor-free) and a single synthetic **exit** block that
 *   every return/throw/leaf block flows into (needed for post-dominators and structuring);
 * - normal successor/predecessor edges from resolved jump targets and fall-through;
 * - **exception edges**: every block inside a try's protected range gets an edge to each of that
 *   try's handler entry blocks, and each handler block is tagged with its [ExceptionHandler].
 *
 * Leaders (block-start offsets) are the classic set: the first instruction, every branch target,
 * every instruction after a block-terminating one, each handler entry, and each try boundary.
 */
class CfgBuilder(
    private val method: IrMethod,
    private val code: MethodCode,
    private val cancellation: CancellationCheck = CancellationCheck.None,
) {
    private var nextId = 0
    private fun newBlock(): BasicBlock = BasicBlock(nextId++).also { method.blocks.add(it) }

    fun build() {
        val insns = code.instructions
        val entry = newBlock()
        method.entryBlock = entry

        if (insns.isEmpty()) {
            val exit = newBlock()
            method.exitBlock = exit
            connect(entry, exit)
            return
        }

        val offsetToIndex = HashMap<Int, Int>(insns.size)
        for (i in insns.indices) offsetToIndex[insns[i].offset] = i

        val leaders = computeLeaders(insns, offsetToIndex)

        // Build blocks: contiguous runs starting at each leader. offsetToBlock maps every instruction
        // offset to the block that contains it (for edge wiring).
        val offsetToBlock = HashMap<Int, BasicBlock>(insns.size)
        val blockOfLeaderIndex = HashMap<Int, BasicBlock>()
        var current: BasicBlock? = null
        for (i in insns.indices) {
            cancellation.ensureActive()
            val di = insns[i]
            if (i == 0 || i in leaders) {
                current = newBlock()
                blockOfLeaderIndex[i] = current
            }
            val block = current!!
            block.instructions.add(di.insn)
            offsetToBlock[di.offset] = block
        }

        // Entry -> first real block.
        connect(entry, offsetToBlock[insns[0].offset]!!)

        wireNormalEdges(insns, offsetToIndex, offsetToBlock)
        // Snapshot the purely-normal edges before exception edges are added, so a handler block that is
        // ALSO a normal successor (shared merge / empty catch) is never mis-marked as exceptional.
        val normalEdges = HashSet<Long>()
        for (b in method.blocks) for (s in b.successors) normalEdges.add(edgeKey(b, s))
        wireExceptionEdges(offsetToBlock, normalEdges)

        // Single exit: every block without a successor (return/throw/last) flows to it.
        val exit = newBlock()
        method.exitBlock = exit
        for (block in method.blocks) {
            if (block !== exit && block.successors.isEmpty()) {
                connect(block, exit)
            }
        }
    }

    private fun computeLeaders(insns: List<DecodedInstruction>, offsetToIndex: Map<Int, Int>): Set<Int> {
        val leaders = HashSet<Int>()
        leaders.add(0)
        for (i in insns.indices) {
            val di = insns[i]
            if (di.terminatesBlock) {
                // instruction after a terminator starts a block
                if (i + 1 < insns.size) leaders.add(i + 1)
                for (t in di.targets) offsetToIndex[t]?.let { leaders.add(it) }
            }
        }
        // try boundaries + handler entries
        for (t in code.tries) {
            offsetToIndex[t.start]?.let { leaders.add(it) }
            // first instruction strictly after the protected range
            firstIndexAfter(insns, t.end)?.let { leaders.add(it) }
            for (h in t.handlers) offsetToIndex[h.handlerOffset]?.let { leaders.add(it) }
        }
        return leaders
    }

    private fun firstIndexAfter(insns: List<DecodedInstruction>, endOffset: Int): Int? {
        for (i in insns.indices) if (insns[i].offset > endOffset) return i
        return null
    }

    private fun wireNormalEdges(
        insns: List<DecodedInstruction>,
        offsetToIndex: Map<Int, Int>,
        offsetToBlock: Map<Int, BasicBlock>,
    ) {
        for (i in insns.indices) {
            val di = insns[i]
            // A block ends when the next instruction starts a new block (or this is the last insn).
            val isLastOfBlock = i + 1 >= insns.size || offsetToBlock[insns[i + 1].offset] !== offsetToBlock[di.offset]
            if (!isLastOfBlock) continue
            val block = offsetToBlock[di.offset]!!
            for (t in di.targets) {
                val target = offsetToBlock[t] ?: continue
                connect(block, target)
            }
            if (di.fallsThrough && i + 1 < insns.size) {
                connect(block, offsetToBlock[insns[i + 1].offset]!!)
            }
        }
    }

    private fun wireExceptionEdges(offsetToBlock: Map<Int, BasicBlock>, normalEdges: Set<Long>) {
        if (code.tries.isEmpty()) return
        val exceptionEdges = HashSet<Long>()
        // Per-block try membership (its protecting handler blocks, in try order, deduplicated).
        val protecting = HashMap<BasicBlock, MutableList<BasicBlock>>()
        // Aggregate handler types per handler offset (several catch types may share one handler).
        for (t in code.tries) {
            cancellation.ensureActive()
            val handlerBlocks = ArrayList<BasicBlock>(t.handlers.size)
            for (h in t.handlers) {
                val hb = offsetToBlock[h.handlerOffset] ?: continue
                markHandler(hb, h.type)
                handlerBlocks.add(hb)
            }
            // Connect every block whose first instruction lies in the protected range to each handler.
            for (block in method.blocks) {
                val first = block.instructions.firstOrNull() ?: continue
                val off = first.offset
                if (off in t.start..t.end) {
                    val membership = protecting.getOrPut(block) { ArrayList() }
                    for (hb in handlerBlocks) {
                        connect(block, hb)
                        if (hb !in membership) membership.add(hb)
                        // Record as exceptional only if it is not (also) a real normal-flow edge; a marked
                        // edge is later removable for the "clean" structural CFG without losing control flow.
                        val key = edgeKey(block, hb)
                        if (key !in normalEdges) exceptionEdges.add(key)
                    }
                }
            }
        }
        method[PipelineAttrs.EXCEPTION_EDGES] = exceptionEdges
        for ((block, handlers) in protecting) block[PipelineAttrs.PROTECTING_HANDLERS] = handlers
    }

    private fun edgeKey(from: BasicBlock, to: BasicBlock): Long =
        (from.id.toLong() shl 32) or (to.id.toLong() and 0xFFFFFFFFL)

    /** Tag [handlerBlock] with (or extend) its [ExceptionHandler], and fix the MOVE_EXCEPTION type. */
    private fun markHandler(handlerBlock: BasicBlock, type: IrType?) {
        val existing = handlerBlock[PipelineAttrs.EXC_HANDLER]
        val types = ArrayList<IrType>()
        var catchAll = false
        if (existing != null) {
            types.addAll(existing.types)
            catchAll = existing.catchAll
        }
        if (type == null) catchAll = true else if (type !in types) types.add(type)
        val handler = ExceptionHandler(handlerBlock, types, catchAll)
        handlerBlock[PipelineAttrs.EXC_HANDLER] = handler
        // Fix the caught-exception operand type on the leading move-exception, if present.
        val firstInsn = handlerBlock.instructions.firstOrNull()
        if (firstInsn != null && firstInsn.opcode == IrOpcode.MOVE_EXCEPTION) {
            firstInsn.result?.type = handler.caughtType
        }
    }

    private fun connect(from: BasicBlock, to: BasicBlock) {
        if (to !in from.successors) from.successors.add(to)
        if (from !in to.predecessors) to.predecessors.add(from)
    }
}
