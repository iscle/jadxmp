package com.jadxmp.pipeline.cfg

import com.jadxmp.ir.insn.ArithInstruction
import com.jadxmp.ir.insn.ConstStringInstruction
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.FillArrayInstruction
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeCustomInstruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.PhiInstruction
import com.jadxmp.ir.insn.PhiOperand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.insn.SwitchInstruction
import com.jadxmp.ir.insn.TypeInstruction
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.pass.CancellationCheck

/**
 * Make a **multi-entry (irreducible) loop reducible by bounded node-splitting**, so the reducibility
 * precondition of structuring ([com.jadxmp.pipeline.structure.RegionMaker.run] bails on an irreducible
 * CFG) is met and the loop then structures with the ordinary machinery. **jadx: FixMultiEntryLoops.**
 *
 * ## Why this is safe and where it runs
 * It runs **before dominators/SSA** (right after the CFG is built), on raw register instructions. It
 * duplicates a single **straight-line** block that a cycle is entered at from two paths, so that each
 * entry path has its own copy and one entry becomes an ordinary forward edge into a single header. This
 * is the textbook node-splitting transform and is *behavior-preserving*: the duplicated block runs on
 * exactly the paths it ran on before (its copies sit on mutually-exclusive predecessor edges), no
 * block/edge is dropped, and — because it runs pre-SSA — SSA reconstruction rebuilds correct φ/values
 * for the split and out-of-SSA coalesces them back to a single dominating-declared local. The
 * downstream honesty nets (φ-freedom, coalescing soundness, edge/block coverage) are the final backstop:
 * if a fixed CFG somehow does not structure faithfully, structuring still bails to unstructured — never
 * wrong code (rule 4). A residual irreducibility this transform cannot fix is likewise left to that bail.
 *
 * ## The supported shapes (mirroring jadx)
 * A DFS coloring classifies edges into **back edges** (to a still-open/GRAY block) and **cross edges**
 * (to a finished/BLACK block). A back edge whose header does NOT dominate its loop-end is a multi-entry
 * loop. For such a back edge (header `H`, loop-end `E`):
 *  - **header-successor entry**: `H`'s immediate dominator has a cross edge into `H`'s single successor
 *    `S`. `H` is duplicated onto the back edge (`E -> copy(H) -> S`), so the true entry stays `H` and the
 *    latch reaches `S` directly. Requires `H` straight-line (single successor `S`).
 *  - **end-block entry**: a cross edge `X -> E` enters the loop-end `E` from outside. `E` is duplicated
 *    onto that path (`X -> copy(E) -> H`) and the direct `X -> E` edge removed, so `E` is reached only
 *    from inside the loop. Requires `E` straight-line whose single successor is `H`, and — the guard jadx
 *    omits — `X` must NOT already branch straight to `H` (else rerouting would collapse a two-armed
 *    branch that feeds both `E` and `H`, silently changing control flow).
 *  - **header back-edge split (fallback)**: when neither above matches but the back-edge header `H` is
 *    itself straight-line, it is duplicated onto the back edge exactly like header-successor entry
 *    (`E -> copy(H) -> S`), without requiring the idom/cross precondition. This handles the mirror DFS
 *    orientation in which the loop's second entry is `H` reached via a tree edge. See
 *    [tryHeaderBackEdgeSplit] for the soundness argument (the split is behavior-preserving for any
 *    straight-line header; the dropped precondition was pattern-recognition, not a correctness gate).
 *
 * ## Bounds / termination (rule 4)
 * Only a straight-line, single-successor, non-self-looping, unprotected, monitor-free block with
 * [MAX_BLOCK_INSNS] clonable instructions is ever duplicated; anything else leaves the loop for the
 * honest bail. Each fix adds exactly one block; at most [MAX_SPLITS] fixes and [MAX_ITERATIONS]
 * detect/fix rounds are performed, so duplication is strictly bounded and cannot blow up or loop forever.
 */
internal class FixMultiEntryLoops(
    private val method: IrMethod,
    private val cancellation: CancellationCheck = CancellationCheck.None,
) {
    private val exceptionEdges: Set<Long> = method[PipelineAttrs.EXCEPTION_EDGES] ?: emptySet()
    private val hasExceptions: Boolean = exceptionEdges.isNotEmpty()
    private var nextId: Int = (method.blocks.maxOfOrNull { it.id } ?: -1) + 1
    private var splitsPerformed = 0

    private class Edge(val start: BasicBlock, val end: BasicBlock)
    private class Classified(val backEdges: List<Edge>, val crossEdges: List<Edge>)

    /** @return true if the CFG was modified (one or more loops split). */
    fun process(): Boolean {
        val entry = method.entryBlock ?: return false
        var changed = false
        var rounds = 0
        while (rounds++ < MAX_ITERATIONS && splitsPerformed < MAX_SPLITS) {
            cancellation.ensureActive()
            // Edge classification needs only a DFS — no dominators. A method with no back edge has no loop,
            // so we skip the (redundant with DominatorsPass) dominator computation entirely for it.
            val edges = classifyEdges(entry)
            if (edges.backEdges.isEmpty()) break
            // Fresh dominator/order data over the (possibly just-mutated) CFG to classify single- vs
            // multi-entry loops. The real DominatorsPass recomputes these again after this transform, so
            // mutating them here is inert.
            Dominators.compute(method, cancellation)
            val multiEntry = edges.backEdges.filter { !isSingleEntryLoop(it) }
            if (multiEntry.isEmpty()) break
            var fixedAny = false
            for (be in multiEntry) {
                if (splitsPerformed >= MAX_SPLITS) break
                if (fixLoop(be, edges.crossEdges)) {
                    changed = true
                    fixedAny = true
                    splitsPerformed++
                    break // recompute from scratch before touching the next loop
                }
            }
            if (!fixedAny) break // no remaining multi-entry loop matches a safe shape — leave the honest bail
        }
        return changed
    }

    // ---- edge classification (DFS coloring) ---------------------------------

    private fun cleanSucc(block: BasicBlock): List<BasicBlock> =
        if (!hasExceptions) block.successors else block.successors.filter { edgeKey(block, it) !in exceptionEdges }

    /**
     * Iterative DFS three-coloring (WHITE=absent, GRAY=on stack, BLACK=finished): an edge to a GRAY node
     * is a back edge, an edge to a BLACK node is a cross/forward edge. Matches jadx's `colorDFS`, made
     * non-recursive (wasm-safe for deep graphs).
     */
    private fun classifyEdges(entry: BasicBlock): Classified {
        val gray = HashSet<BasicBlock>()
        val black = HashSet<BasicBlock>()
        val back = ArrayList<Edge>()
        val cross = ArrayList<Edge>()
        val stack = ArrayDeque<BasicBlock>()
        val iterIndex = HashMap<BasicBlock, Int>()
        gray.add(entry)
        stack.addLast(entry)
        while (stack.isNotEmpty()) {
            cancellation.ensureActive()
            val node = stack.last()
            val succs = cleanSucc(node)
            val idx = iterIndex.getOrElse(node) { 0 }
            if (idx < succs.size) {
                iterIndex[node] = idx + 1
                val v = succs[idx]
                when {
                    v in gray -> back.add(Edge(node, v))
                    v in black -> cross.add(Edge(node, v))
                    else -> { gray.add(v); stack.addLast(v) }
                }
            } else {
                stack.removeLast()
                gray.remove(node)
                black.add(node)
            }
        }
        return Classified(back, cross)
    }

    /** A back edge is a normal (single-entry) loop when its header dominates its loop-end. */
    private fun isSingleEntryLoop(e: Edge): Boolean =
        e.end === e.start || e.end.id in e.start.dominators

    // ---- the two supported fix shapes ---------------------------------------

    private fun fixLoop(backEdge: Edge, crossEdges: List<Edge>): Boolean {
        if (tryHeaderSuccessorEntry(backEdge, crossEdges)) return true
        if (tryEndBlockEntry(backEdge, crossEdges)) return true
        if (tryHeaderBackEdgeSplit(backEdge)) return true
        return false
    }

    private fun tryHeaderSuccessorEntry(backEdge: Edge, crossEdges: List<Edge>): Boolean {
        val header = backEdge.end
        val headerIDom = header.immediateDominator ?: return false
        val subEntries = crossEdges.filter { it.start === headerIDom }
        if (subEntries.size != 1) return false
        val headerSuccs = cleanSucc(header)
        if (headerSuccs.size != 1 || headerSuccs[0] !== subEntries[0].end) return false
        // The header's alternative entry is the idom's cross edge into the header's single successor:
        // recognizing that pattern, duplicate the header onto the back edge.
        return splitHeaderOntoBackEdge(header, backEdge.start)
    }

    /**
     * **Shape 3 — fallback header split.** Tried only after shapes 1 & 2 decline, so it never changes a
     * loop those already handle. When a multi-entry back edge's header is itself straight-line, duplicate
     * it onto the back edge exactly as [tryHeaderSuccessorEntry] does — `loopEnd -> copy(header) -> S`,
     * leaving the header's entry-side predecessors reaching `S` unchanged. This covers the orientation in
     * which the loop's second entry is the back-edge **header** (reached via a DFS *tree* edge), rather
     * than the header-successor cross edge shape 1 keys on (jadx sees the mirror orientation because its
     * DFS discovers the dominating header first; the transform is the same node split).
     *
     * ## Why dropping shape 1's idom/cross precondition is sound
     * That precondition only *recognizes* a known-good pattern; it is not a correctness gate. The split is
     * behavior-preserving for ANY straight-line header: [canDuplicate] guarantees a single clean successor
     * `S`, so `copy(header)` reproduces exactly the `header -> S` run, the back-edge path `loopEnd -> copy
     * -> S` does precisely what `loopEnd -> header -> S` did, and `header` keeps every other predecessor
     * still flowing to `S`. No block/edge is dropped and the reroute is SLOT-PRESERVING ([replaceSuccessor])
     * so branch polarity of a conditional latch is unchanged. If this fails to reduce the loop, structuring
     * still bails to unstructured (rule 4) — never wrong code; and duplication stays bounded by [MAX_SPLITS].
     */
    private fun tryHeaderBackEdgeSplit(backEdge: Edge): Boolean =
        splitHeaderOntoBackEdge(backEdge.end, backEdge.start)

    /**
     * Duplicate the straight-line [header] onto the `loopEnd -> header` back edge:
     * `loopEnd -> copy(header) -> S` where `S` is the header's single successor, keeping the header's other
     * predecessors intact. The reroute mutates the back-edge slot IN PLACE ([replaceSuccessor]): a
     * conditional latch's back-edge arm may be its taken arm (`successors[0]`), and that index must stay
     * the arm that reaches the loop — else branch polarity inverts while the condition text is unchanged
     * (a rule-4 miscompile). Refuses (no change) any header that is not a bounded, clonable, single clean
     * successor block, or if any touched block is exception-protected.
     */
    private fun splitHeaderOntoBackEdge(header: BasicBlock, loopEnd: BasicBlock): Boolean {
        val successor = cleanSucc(header).singleOrNull() ?: return false
        if (!canDuplicate(header) || anyProtected(header, loopEnd, successor)) return false
        val copy = newBlock()
        copyBlockData(header, copy)
        replaceSuccessor(loopEnd, header, copy)
        connect(copy, successor)
        return true
    }

    private fun tryEndBlockEntry(backEdge: Edge, crossEdges: List<Edge>): Boolean {
        val header = backEdge.end
        val loopEnd = backEdge.start
        val subEntries = crossEdges.filter { it.end === loopEnd }
        if (subEntries.size != 1) return false
        val subEntry = subEntries[0]
        val start = subEntry.start
        // Duplicate the loop-end onto the entering path: `start -> copy(loopEnd) -> header`, dropping the
        // direct `start -> loopEnd` edge so `loopEnd` is reached only from inside the loop. Faithful only
        // when `loopEnd` is straight-line whose single successor IS the header (so `start -> loopEnd ->
        // header` is preserved by the copy) AND `start` does not already branch straight to `header` —
        // otherwise the reroute would collapse a two-way branch that feeds both `loopEnd` and `header`
        // (the hazard jadx's version does not guard). The reroute REPLACES `start`'s `loopEnd` arm with
        // the copy IN PLACE ([replaceSuccessor]): when `start` is a two-way branch whose taken arm
        // (`successors[0]`) is `loopEnd`, that index must stay the arm that reaches the copy/loop — else
        // taken/not-taken inverts while the condition is unchanged (a rule-4 miscompile).
        if (!canDuplicate(loopEnd)) return false
        if (cleanSucc(loopEnd).singleOrNull() !== header) return false
        if (header in cleanSucc(start)) return false
        if (anyProtected(header, loopEnd, start)) return false
        val copy = newBlock()
        copyBlockData(loopEnd, copy)
        replaceSuccessor(start, loopEnd, copy)
        connect(copy, header)
        return true
    }

    // ---- duplication safety -------------------------------------------------

    /**
     * Whether [block] is a bounded, provably-clonable straight-line block safe to duplicate: not the
     * entry/exit, a single clean successor that is not itself (no self-loop), no monitor op, not inside a
     * try, at most [MAX_BLOCK_INSNS] instructions, and every instruction faithfully clonable.
     */
    private fun canDuplicate(block: BasicBlock): Boolean {
        if (block === method.entryBlock || block === method.exitBlock) return false
        val succ = cleanSucc(block)
        if (succ.size != 1 || succ[0] === block) return false
        if (isProtected(block)) return false
        if (block.instructions.size > MAX_BLOCK_INSNS) return false
        for (insn in block.instructions) {
            if (insn.opcode == IrOpcode.MONITOR_ENTER || insn.opcode == IrOpcode.MONITOR_EXIT) return false
            if (cloneInsn(insn) == null) return false
        }
        return true
    }

    private fun isProtected(block: BasicBlock): Boolean =
        block[PipelineAttrs.PROTECTING_HANDLERS]?.isNotEmpty() == true

    private fun anyProtected(vararg blocks: BasicBlock): Boolean = blocks.any { isProtected(it) }

    // ---- CFG surgery (mirrors jadx BlockSplitter) ---------------------------

    private fun newBlock(): BasicBlock = BasicBlock(nextId++).also { method.blocks.add(it) }

    /** Add a fresh `from -> to` edge (used only to wire a freshly-created copy block's single successor). */
    private fun connect(from: BasicBlock, to: BasicBlock) {
        if (to !in from.successors) from.successors.add(to)
        if (from !in to.predecessors) to.predecessors.add(from)
    }

    /**
     * Reroute the single `from -> oldTo` edge to `from -> newTo`, **preserving `oldTo`'s slot index** in
     * [from]'s successor list. Branch-arm identity in this engine is POSITIONAL: [CfgBuilder] wires the
     * branch target first, so `successors[0]` is the condition-true/taken arm and `successors[1]` the
     * fall-through, and [com.jadxmp.pipeline.structure.RegionMaker] pairs `succ[0]`/`succ[1]` with the raw
     * condition. A remove-then-append would shift the surviving arm to index 0 and drop the new edge at
     * index 1, INVERTING taken/not-taken while the condition text is unchanged — a recompilable-but-wrong
     * miscompile (rule 4). Replacing in place keeps every other arm at its original index. Predecessor
     * lists are order-insensitive (φ operands carry their own source block and SSA is built after this
     * transform), so only the successor slot must be preserved.
     */
    private fun replaceSuccessor(from: BasicBlock, oldTo: BasicBlock, newTo: BasicBlock) {
        val idx = from.successors.indexOf(oldTo)
        if (idx >= 0) from.successors[idx] = newTo else from.successors.add(newTo)
        oldTo.predecessors.remove(from)
        if (from !in newTo.predecessors) newTo.predecessors.add(from)
    }

    private fun copyBlockData(from: BasicBlock, to: BasicBlock) {
        for (insn in from.instructions) to.instructions.add(cloneInsn(insn)!!)
    }

    // ---- instruction cloning (pre-SSA, no φ / wrapped operands expected) -----

    /**
     * Deep-clone [insn] with fresh operands, or null if it cannot be faithfully reproduced. Runs pre-SSA,
     * so operands are flat register/literal reads (no φ, no inlined [InstructionOperand] yet) — but the
     * recursion handles a wrapped operand defensively and refuses a φ. Every payload-carrying subclass is
     * reconstructed explicitly; a plain [Instruction] (move/const/array/arith-free op) is the `else` case.
     */
    private fun cloneInsn(insn: Instruction): Instruction? {
        val args = ArrayList<Operand>(insn.argCount)
        for (k in 0 until insn.argCount) args.add(cloneOperand(insn.getArg(k)) ?: return null)
        val res = insn.result?.let { RegisterOperand(it.regNum, it.type) }
        val copy: Instruction = when (insn) {
            is PhiInstruction -> return null
            is SwitchInstruction -> return null // never straight-line; refuse rather than guess
            is ArithInstruction -> ArithInstruction(insn.op, res, args)
            is IfInstruction -> IfInstruction(insn.condition, args)
            is InvokeCustomInstruction -> InvokeCustomInstruction(
                insn.bootstrapMethod, insn.bootstrapKind, insn.callSiteName,
                insn.protoReturnType, insn.protoParamTypes, insn.renderable, res, args,
            )
            is InvokeInstruction -> InvokeInstruction(insn.methodRef, insn.invokeKind, res, args, insn.opcode)
            is FieldInstruction -> FieldInstruction(insn.fieldRef, insn.isStatic, insn.isPut, res, args)
            is ConstStringInstruction -> ConstStringInstruction(insn.value, res)
            is TypeInstruction -> TypeInstruction(insn.opcode, insn.referencedType, res, args)
            is FillArrayInstruction -> FillArrayInstruction(insn.elementWidth, insn.elements.copyOf(), args[0])
            else -> Instruction(insn.opcode, res, args)
        }
        copy.offset = insn.offset
        return copy
    }

    private fun cloneOperand(op: Operand): Operand? = when (op) {
        is PhiOperand -> null
        is RegisterOperand -> RegisterOperand(op.regNum, op.type)
        is LiteralOperand -> LiteralOperand(op.value, op.type)
        is InstructionOperand -> cloneInsn(op.instruction)?.let { InstructionOperand(it) }
    }

    private fun edgeKey(from: BasicBlock, to: BasicBlock): Long =
        (from.id.toLong() shl 32) or (to.id.toLong() and 0xFFFFFFFFL)

    private companion object {
        const val MAX_SPLITS = 8
        const val MAX_ITERATIONS = 16
        const val MAX_BLOCK_INSNS = 16
    }
}
