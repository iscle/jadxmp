package com.jadxmp.pipeline.structure

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.DecompileError
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.PhiInstruction
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.insn.SwitchInstruction
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.LocalVar
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.region.Condition
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopKind
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.region.CatchClause
import com.jadxmp.ir.region.SwitchCase
import com.jadxmp.ir.region.SwitchRegion
import com.jadxmp.ir.region.SyncRegion
import com.jadxmp.ir.region.TryCatchRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.pass.CancellationCheck

/**
 * Reconstructs the nested [Region] tree from the block CFG.  **jadx: RegionMaker (redesigned around a
 * dominance-driven recursive descent rather than the heuristic maze).**
 *
 * ## Algorithm
 * A recursive descent over the CFG that leans on the already-computed dominator / immediate
 * post-dominator data. Every chain is built relative to a *chain-follow* (the block it falls through to
 * when done) and an optional enclosing *loop context* (its continue-target and break-target):
 *  - a straight-line block is emitted as a leaf and we advance to its single successor;
 *  - a **two-way branch** opens an [IfRegion]; the arms are built up to the branch's immediate
 *    post-dominator (the merge), which becomes the follow. Consecutive condition blocks that share an
 *    arm fold into short-circuit `&&`/`||` [Condition]s;
 *  - a **loop header** opens a [LoopRegion] — `while` (test at header), `do`/`while` (test at the
 *    single latch), or `while (true)`. Inside the body, an edge to the loop **follow** becomes a
 *    `break` and an edge to its **continue-target** (that is not the structural fall-through) becomes a
 *    `continue`. Multi-level break/continue is not modelled and bails.
 *
 * ## Correctness contracts (non-negotiable)
 *  - **φ-freedom.** A region is produced only when the body is fully de-SSA'd (no [PhiInstruction]).
 *    `region != null` implies renderable, and the method gets [PipelineAttrs.FULLY_STRUCTURED].
 *  - **Total EDGE coverage.** Every CFG edge between real blocks must be represented in the tree — as
 *    structural nesting/fall-through, a loop test/latch, an explicit `break`/`continue`, or a
 *    return/throw to the exit. If a single edge is unrepresented (e.g. a break/continue we didn't
 *    model), the **whole tree is discarded** (`region` stays null, method flagged unstructured). This
 *    edge-level net — not mere block coverage — is what makes structuring provably non-lossy: an edge
 *    that silently vanished would otherwise become clean-but-wrong output (a dropped break diverges).
 *  - **Never throw, never emit partial/incorrect trees.** Irreducible graphs and unsupported shapes
 *    (synchronized, `finally`, multi-level break, and the try/catch shapes [makeTry] can't prove) bail to
 *    the null-region fallback.
 *
 * ## try/catch over the clean CFG
 * Exception edges are excluded from the structural walk (the "clean" CFG — see [cleanSucc] /
 * [computeCleanPostDominators]) so an ordinary or internally-branchy protected body structures with the
 * same if/loop/switch machinery as normal code; [makeTry] alone consumes the exception edges. Try
 * *membership* (which handler(s) protect a block, [PipelineAttrs.PROTECTING_HANDLERS]) is taken from the
 * try ranges, so a `catch` whose entry is also a normal merge/follow (the common empty catch that
 * continues) is still reconstructed rather than dropped. `finally`/synchronized cleanup, escaping
 * try-scoped values, and ambiguous multi-exit shapes bail honestly.
 */
internal class RegionMaker(
    private val method: IrMethod,
    private val cancellation: CancellationCheck = CancellationCheck.None,
) {
    /** Internal signal that the graph cannot be structured; caught inside [run], never escapes. */
    private class Bail(message: String) : RuntimeException(message)

    /**
     * Enclosing loop while building its body: where `break` and (single-level) `continue` go, plus the
     * loop's node set. [continueTarget] is null when `continue` is not modelled (do-while, whose latch
     * holds update code a `continue` must not skip) — an edge that would need it then fails edge
     * coverage and the method bails.
     */
    private class LoopCtx(
        val continueTarget: BasicBlock?,
        val follow: BasicBlock?,
        val bodyNodes: Set<BasicBlock>,
    )

    private val entry = method.entryBlock
    private val exit = method.exitBlock

    /**
     * Marked **exception edges** (`u.id -> handler.id`, packed) — see [PipelineAttrs.EXCEPTION_EDGES].
     * The structural walk operates on the *clean* CFG (normal successors only); exception edges are
     * consumed exclusively by [makeTry]. Empty for a method with no try/catch, in which case every
     * clean-CFG helper below is byte-for-byte identical to reading the raw CFG — so exception handling
     * never perturbs the structuring of ordinary methods.
     */
    private val exceptionEdges: Set<Long> = method[PipelineAttrs.EXCEPTION_EDGES] ?: emptySet()
    private val hasExceptions: Boolean = exceptionEdges.isNotEmpty()

    /** Clean (exception-free) immediate post-dominators; populated in [run] only when [hasExceptions]. */
    private val cleanPostDom = HashMap<BasicBlock, BasicBlock>()

    /** Blocks already placed in the tree; a second placement attempt means an unstructured shape. */
    private val placed = HashSet<BasicBlock>()

    /**
     * The **active-exit stack** — the follows of all currently-open forward regions (an `if` merge, a
     * try/synchronized follow). Mirrors jadx's `RegionStack` exits (`addExit`/`containsExit`). Structuring a
     * chain ([makeRegion]) stops not only at its own immediate [chainFollow] but at ANY active exit: an arm
     * that reaches an *enclosing* region's follow belongs to that enclosing region (which places it), so it
     * stops there instead of re-placing the block (the sibling double-placement / revisit that the single
     * `chainFollow` could not see). Pushed/popped strictly LIFO around each forward region's sub-region
     * construction via [withActiveExit], so at any depth it holds exactly the enclosing forward follows.
     *
     * NB (Phase 1, behavior-preserving): in a correctly-nested method an arm always reaches its OWN merge
     * (the immediate post-dominator) before any enclosing follow, so the extra membership test is inert and
     * the board is byte-identical; it only fires earlier on the shapes that otherwise revisit-bail. Loop and
     * switch follows are deliberately NOT here — they are break/continue targets handled by [LoopCtx] with a
     * check that must keep its current precedence over the plain stop; putting them here would drop a break.
     */
    private val exitStack = ArrayList<BasicBlock>()

    /** Run [body] with [exit] pushed as an active exit (no-op when null); pops it LIFO afterwards. */
    private inline fun <T> withActiveExit(exit: BasicBlock?, body: () -> T): T {
        if (exit == null) return body()
        exitStack.add(exit)
        try {
            return body()
        } finally {
            exitStack.removeAt(exitStack.size - 1)
        }
    }

    /** CFG edges (`u.id -> v.id`, packed) represented in the region tree. See the edge-coverage net. */
    private val representedEdges = HashSet<Long>()

    private val loopHeaders = HashSet<BasicBlock>()
    private val loopNodes = HashMap<BasicBlock, Set<BasicBlock>>()
    private val activeLoopHeaders = HashSet<BasicBlock>()

    /**
     * Protected blocks of every currently-open try (see [makeTry]). A protected block reached while a
     * try enclosing it is already open must be structured as *ordinary* flow (its exception edges are
     * already represented by the enclosing try/catch) — not re-opened as a fresh try.
     */
    private val openTryBlocks = HashSet<BasicBlock>()

    /**
     * The `monitor-enter` instructions consumed into a [SyncRegion] by [makeSync]. Every monitor-enter
     * MUST be consumed (or the whole method bails) — a monitor-enter left un-consumed would render as an
     * absent `synchronized`, silently dropping the lock (a clean-but-wrong miscompile, rule 4).
     */
    private val consumedMonitorEnters = HashSet<Instruction>()

    fun run() {
        val start = entry ?: return
        if (hasResidualPhi()) return
        // Renderability precondition: out-of-SSA coalescing must place every variable's definition so it
        // dominates all its uses (otherwise codegen's declare-on-first-assign emits a forward reference or
        // an out-of-scope use — a silent miscompile that recompiles nowhere). If it doesn't, FLAG the
        // method (so even the branch-free linear codegen path is honestly marked, not read as clean) and
        // bail — never emit clean-but-wrong source (rule 4).
        if (!coalescingIsSound()) {
            flagUnrenderable("unresolved variable coalescing (definition does not dominate use)")
            return
        }
        if (hasExceptions) computeCleanPostDominators()
        if (!isReducible()) return

        identifyLoops()
        markBlockLocalTemps()

        val tree = try {
            val seq = makeRegion(start, chainFollow = null, loopCtx = null, bodyRootHeader = null)
            verifyAllMonitorsConsumed()
            verifyBlockCoverage()
            verifyEdgeCoverage()
            verifyNoLeakedBranches()
            seq
        } catch (b: Bail) {
            return // discard: leave region null, method flagged unstructured
        }
        method.region = tree
        method[PipelineAttrs.FULLY_STRUCTURED] = true
    }

    /**
     * The **no-leaked-branch** honesty net: every placed block that ends in a two-way `if` or a `switch`
     * MUST have had that branch *consumed* by [makeIf]/[makeSwitch] (marked `DONT_GENERATE`, its arms
     * emitted into the region tree). An un-consumed branch means the block was structured as a straight-
     * line leaf — which happens when one of its arms vanished (e.g. an arm entering a never-exiting inner
     * loop whose blocks were pruned, leaving a single successor). Codegen would then emit the condition as
     * a bare non-statement (`x == y;`) and the arm's code is silently gone. That is exactly the rule-4
     * failure the edge-level net cannot see (a condition's out-edge is recorded before its arm subtree is
     * placed), so this closes the hole universally: `region != null ⇒ no dropped branch / leaked
     * comparison`. Bail honestly instead.
     */
    private fun verifyNoLeakedBranches() {
        for (block in method.blocks) {
            if (block !in placed) continue // unplaced blocks are already caught by verifyBlockCoverage
            val last = block.instructions.lastOrNull() ?: continue
            if ((last is IfInstruction || last is SwitchInstruction) && !last.contains(AttrFlag.DONT_GENERATE)) {
                throw Bail("un-consumed branch would leak as a bare statement in B${block.id}")
            }
        }
    }

    /** Every `monitor-enter` must have been reconstructed into a [SyncRegion]; otherwise bail (rule 4). */
    private fun verifyAllMonitorsConsumed() {
        for (block in method.blocks) {
            for (insn in block.instructions) {
                if (insn.opcode == IrOpcode.MONITOR_ENTER && insn !in consumedMonitorEnters) {
                    throw Bail("monitor-enter not reconstructed as a synchronized block")
                }
            }
        }
    }

    // ---- pre-checks ---------------------------------------------------------

    private fun hasResidualPhi(): Boolean =
        method.blocks.any { block -> block.instructions.any { it is PhiInstruction } }

    /**
     * Whether the out-of-SSA coalescing is renderable: every use of every source variable ([LocalVar]) is
     * reached by a definition that dominates it (a proper-dominator block, an earlier def in the same
     * block, or a method-entry/parameter def). If some use has no dominating def, codegen would emit it
     * before any in-scope declaration — a forward reference or out-of-scope variable that never compiles.
     * We treat that as unstructurable and bail, converting a silent miscompile into an honest error.
     */
    private fun coalescingIsSound(): Boolean {
        val blockOf = HashMap<Instruction, BasicBlock>()
        for (b in method.blocks) for (insn in b.instructions) blockOf[insn] = b

        // A source "variable" is a LocalVar's coalesced members, or (when out-of-SSA did no coalescing —
        // no φ) a single SSA value. SSA guarantees a value's def dominates its uses; a later pass that
        // violates that (e.g. a `new-instance` def relocated to a fused constructor while earlier `move`s
        // still read it) produces a forward reference that must not be emitted.
        val groups = ArrayList<List<SsaValue>>()
        val seen = HashSet<SsaValue>()
        for (v in method.ssaValues) {
            if (!seen.add(v)) continue
            val lv = v.localVar
            if (lv != null) {
                for (m in lv.ssaValues) seen.add(m)
                groups.add(lv.ssaValues)
            } else {
                groups.add(listOf(v))
            }
        }

        for (group in groups) {
            // A member defined at method entry (parameter / `this` / synthetic) dominates everything.
            if (group.any { it.assign.parent == null }) continue
            // Only a *declaring* definition binds the variable in source: an emittable assignment, or a
            // move-exception (which becomes the `catch (T e)` parameter). A def consumed by an earlier pass
            // (DONT_GENERATE) does not declare it.
            val declaringDefs = group.mapNotNull { m -> m.assign.parent?.takeIf { declaresVar(it) } }
            val defBlocks = HashSet<BasicBlock>()
            for (d in declaringDefs) blockOf[d]?.let { defBlocks.add(it) }
            for (m in group) {
                for (use in m.uses) {
                    val useInsn = use.parent ?: continue
                    if (!isEmittable(useInsn)) continue // a hidden use needs no in-scope declaration
                    val useBlock = blockOf[useInsn] ?: continue
                    if (!defReachesUse(useBlock, useInsn, defBlocks, declaringDefs, blockOf)) return false
                }
            }
        }
        return true
    }

    /** An instruction that binds/declares its result variable in source (emittable, or a catch param). */
    private fun declaresVar(insn: Instruction): Boolean =
        isEmittable(insn) || insn.opcode == IrOpcode.MOVE_EXCEPTION

    /**
     * Mark the method as unrenderable so the honesty accounting fires (`no-error` fails) and codegen emits
     * its `// JADXMP ERROR` marker — even on the branch-free linear path, which [RenderabilityGuard]
     * otherwise trusts. Mirrors that guard's flagging; never clobbers a pre-existing diagnostic.
     */
    private fun flagUnrenderable(reason: String) {
        if (!method.contains(IrAttrs.ERROR)) {
            method[IrAttrs.ERROR] = DecompileError(reason)
        }
        method.add(AttrFlag.HAS_ERROR)
    }

    /** A definition dominates [useInsn] in [useBlock]: a proper-dominator def block, or an earlier same-block def. */
    private fun defReachesUse(
        useBlock: BasicBlock,
        useInsn: Instruction,
        defBlocks: Set<BasicBlock>,
        declaringDefs: List<Instruction>,
        blockOf: Map<Instruction, BasicBlock>,
    ): Boolean {
        for (bd in defBlocks) {
            if (bd !== useBlock && bd.id in useBlock.dominators) return true
        }
        // Same-block definition strictly before the use.
        val useIdx = useBlock.instructions.indexOf(useInsn)
        for (defInsn in declaringDefs) {
            if (blockOf[defInsn] !== useBlock) continue
            val defIdx = useBlock.instructions.indexOf(defInsn)
            if (defIdx in 0 until useIdx) return true
        }
        return false
    }

    private fun isReducible(): Boolean {
        for (block in method.blocks) {
            for (succ in cleanSucc(block)) {
                if (succ.order != -1 && block.order != -1 && succ.order <= block.order) {
                    if (succ.id !in block.dominators) return false // retreating non-back edge
                }
            }
        }
        return true
    }

    // ---- clean (exception-free) CFG view ------------------------------------

    /**
     * The block's **clean successors**: its normal-flow successors, with marked exception edges removed.
     * Everything structural (branch detection, if-merges, loops, switch) walks this view so that the
     * exception edges — which would otherwise make an ordinary try body look like a fan-out to its
     * handler — do not distort the region tree. Identity to [BasicBlock.successors] when the method has
     * no try/catch.
     */
    private fun cleanSucc(block: BasicBlock): List<BasicBlock> {
        val base = if (!hasExceptions) {
            block.successors
        } else {
            block.successors.filter { edgeKey(block, it) !in exceptionEdges }
        }
        // A block ending in RETURN always transfers to the method exit. Inside a try the CFG builder
        // leaves that exit edge off (the block's successor slot is taken by its exception edge to the
        // handler), so for the *clean* structural view we restore it — otherwise a branchy try/sync body
        // whose branches merge at a returning block has no post-dominator and can't be structured.
        val e = exit
        if (e != null && block.instructions.lastOrNull()?.opcode == IrOpcode.RETURN && e !in base) {
            return base + e
        }
        return base
    }

    /**
     * The handler entry blocks protecting this block — its try membership (see
     * [PipelineAttrs.PROTECTING_HANDLERS]). Derived from try ranges, so it is correct even when the
     * exception edge to a handler coincides with a normal edge (and could not be marked in [exceptionEdges]).
     */
    private fun protectingHandlers(block: BasicBlock): List<BasicBlock> =
        block[PipelineAttrs.PROTECTING_HANDLERS] ?: emptyList()

    /** Clean immediate post-dominator (over the exception-free CFG), falling back to the raw attribute. */
    private fun ipostdom(block: BasicBlock): BasicBlock? =
        if (hasExceptions) cleanPostDom[block] else block[PipelineAttrs.IMMEDIATE_POST_DOMINATOR]

    /**
     * Cooper–Harvey–Kennedy post-dominators over the **clean** CFG (marked exception edges removed).
     * Handlers still reach the method exit through their own normal flow, so they remain rooted; the try
     * body's post-dominators no longer get pulled toward the handler. Mirrors [Dominators.computePostDominators].
     */
    private fun computeCleanPostDominators() {
        val exitBlock = exit ?: return
        // Reverse-clean-CFG reverse-postorder rooted at exit; step via clean predecessors.
        val cleanPreds = HashMap<BasicBlock, MutableList<BasicBlock>>()
        for (b in method.blocks) for (s in cleanSucc(b)) cleanPreds.getOrPut(s) { ArrayList() }.add(b)
        val preds: (BasicBlock) -> List<BasicBlock> = { cleanPreds[it].orEmpty() }
        val succsInReverse: (BasicBlock) -> List<BasicBlock> = { cleanSucc(it) }

        val rpo = reversePostOrderClean(exitBlock, preds)
        val index = HashMap<BasicBlock, Int>(rpo.size)
        for (i in rpo.indices) index[rpo[i]] = i
        val n = rpo.size
        val idom = IntArray(n) { -1 }
        if (n == 0) return
        idom[0] = 0
        var changed = true
        while (changed) {
            cancellation.ensureActive()
            changed = false
            for (i in 1 until n) {
                var newIdom = -1
                for (p in succsInReverse(rpo[i])) {
                    val pi = index[p] ?: continue
                    if (idom[pi] == -1) continue
                    newIdom = if (newIdom == -1) pi else intersect(idom, pi, newIdom)
                }
                if (newIdom != -1 && idom[i] != newIdom) {
                    idom[i] = newIdom
                    changed = true
                }
            }
        }
        for (i in 1 until n) {
            val ip = idom[i]
            if (ip >= 0 && ip != i) cleanPostDom[rpo[i]] = rpo[ip]
        }
    }

    private fun reversePostOrderClean(root: BasicBlock, next: (BasicBlock) -> List<BasicBlock>): List<BasicBlock> {
        val visited = HashSet<BasicBlock>()
        val postorder = ArrayList<BasicBlock>()
        val stack = ArrayDeque<BasicBlock>()
        val iterIndex = HashMap<BasicBlock, Int>()
        stack.addLast(root)
        visited.add(root)
        while (stack.isNotEmpty()) {
            cancellation.ensureActive()
            val node = stack.last()
            val succs = next(node)
            val idx = iterIndex.getOrElse(node) { 0 }
            if (idx < succs.size) {
                iterIndex[node] = idx + 1
                val nxt = succs[idx]
                if (visited.add(nxt)) stack.addLast(nxt)
            } else {
                stack.removeLast()
                postorder.add(node)
            }
        }
        postorder.reverse()
        return postorder
    }

    private fun intersect(idom: IntArray, a: Int, b: Int): Int {
        var f1 = a
        var f2 = b
        while (f1 != f2) {
            while (f1 > f2) f1 = idom[f1]
            while (f2 > f1) f2 = idom[f2]
        }
        return f1
    }

    // ---- loop discovery -----------------------------------------------------

    private fun identifyLoops() {
        for (block in method.blocks) {
            for (succ in cleanSucc(block)) {
                if (succ.id in block.dominators) loopHeaders.add(succ) // back edge
            }
        }
        for (header in loopHeaders) loopNodes[header] = loopBody(header)
    }

    /** Clean predecessors: blocks whose *normal* flow reaches [block] (exception edges excluded). */
    private fun cleanPreds(block: BasicBlock): List<BasicBlock> {
        if (!hasExceptions) return block.predecessors
        return block.predecessors.filter { edgeKey(it, block) !in exceptionEdges }
    }

    private fun naturalLoop(header: BasicBlock): Set<BasicBlock> {
        val nodes = HashSet<BasicBlock>()
        nodes.add(header)
        val stack = ArrayDeque<BasicBlock>()
        for (pred in cleanPreds(header)) if (header.id in pred.dominators) stack.addLast(pred)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (!nodes.add(n)) continue
            for (p in cleanPreds(n)) stack.addLast(p)
        }
        return nodes
    }

    /**
     * The natural loop extended with **break-tail** blocks: header-dominated blocks whose only way out
     * is the loop follow (e.g. the `work` in `if (c) { work; break; }`). Without this they fall outside
     * the natural loop and the containment check would bail; including them lets the common break
     * pattern structure. The follow is the header's immediate post-dominator (the loop's true merge),
     * which is robust when the break-tail itself would otherwise look like a second exit. A block is
     * added only if the header dominates it, the follow does NOT dominate it (post-loop code excluded),
     * and every successor stays inside the loop / follow / exit.
     */
    private fun loopBody(header: BasicBlock): Set<BasicBlock> {
        val base = naturalLoop(header)
        val postdom = ipostdom(header)
        // Ordinary case (unchanged): a clean single post-dominator follow — a block outside the loop that
        // every loop exit reaches. Break-tail blocks reaching only it are pulled into the body.
        if (postdom != null && postdom !== exit && postdom !in base) {
            return includeTails(header, base, postdom)
        }
        // No clean post-dominator follow: the loop has MULTIPLE exit targets — a normal follow plus one or
        // more terminal `throw`/`return` side-exits, which pull the post-dominator down to the method exit.
        // Attempt a multi-exit collapse. For a pure-test header the loop's normal follow is simply the
        // header's own exit edge; the terminal side-exits and any break-tails are then pulled into the body
        // (they reach only that follow or the method exit), leaving a single follow so the loop renders with
        // in-place `throw`/`return` and `break`. Anything ambiguous keeps the natural loop (which then bails
        // honestly at [loopFollow]) — never a mis-structured second exit.
        val follow = multiExitFollow(header, base) ?: return base
        val body = includeTails(header, base, follow)
        val exits = body.flatMap { cleanSucc(it) }.filter { it !in body && it !== exit }.toSet()
        return if (exits.size == 1 && exits.first() === follow) body else base
    }

    /**
     * The block a pure-test [header] branches to when it LEAVES the loop — its single successor outside the
     * natural-loop [base]. Null unless the header is a two-way pure test with exactly one in-loop and one
     * out-of-loop successor (any other shape is not a plain multi-exit `while` and bails). Never the method
     * exit (a header that branches straight to the exit is a `return`/`throw`, not a loop follow).
     */
    private fun multiExitFollow(header: BasicBlock, base: Set<BasicBlock>): BasicBlock? {
        if (branchKind(header) != BranchKind.TWO_WAY || !headerIsPureTest(header)) return null
        val succ = cleanSucc(header)
        if (succ.size != 2) return null
        val external = succ.filter { it !in base }
        if (external.size != 1) return null // exactly one edge leaves the loop
        val follow = external.first()
        return if (follow === exit) null else follow
    }

    /**
     * The [base] natural loop extended with the blocks that reach only the loop [follow] or the method exit:
     * break-tails (`if (c) { work; break; }`) and terminal `throw`/`return` side-exits. A block joins only
     * if the header dominates it, the follow does NOT (post-loop code excluded), and every one of its clean
     * successors stays inside the loop / follow / exit.
     */
    private fun includeTails(header: BasicBlock, base: Set<BasicBlock>, follow: BasicBlock): Set<BasicBlock> {
        if (follow === exit || follow in base) return base // no clean external follow to break to
        val body = HashSet(base)
        var changed = true
        while (changed) {
            changed = false
            for (b in method.blocks) {
                if (b in body || b === follow || b === exit) continue
                if (header.id !in b.dominators) continue // must live under the header
                if (follow.id in b.dominators) continue // exclude blocks after the loop
                if (cleanSucc(b).all { it in body || it === follow || it === exit }) {
                    body.add(b)
                    changed = true
                }
            }
        }
        return body
    }

    // ---- recursive descent --------------------------------------------------

    /**
     * Build the region for the chain beginning at [start]. The chain ends (without emitting it) at
     * [chainFollow] (its fall-through merge). Inside a loop ([loopCtx]) an edge to the loop follow emits
     * a `break` and an edge to the continue-target emits a `continue`. [bodyRootHeader] is the one block
     * (an infinite-loop header) whose stop-check is skipped on the very first visit so its own body can
     * start there.
     */
    private fun makeRegion(
        start: BasicBlock,
        chainFollow: BasicBlock?,
        loopCtx: LoopCtx?,
        bodyRootHeader: BasicBlock?,
    ): SequenceRegion {
        val seq = SequenceRegion()
        var cur: BasicBlock? = start
        var first = true
        while (cur != null) {
            cancellation.ensureActive()
            val block = cur
            val atStart = first && block === bodyRootHeader
            first = false
            if (!atStart) {
                if (block === exit) break
                if (block === chainFollow) break
                if (loopCtx != null && block === loopCtx.follow) {
                    seq.add(breakLeaf()); break
                }
                if (loopCtx?.continueTarget != null && block === loopCtx.continueTarget) {
                    seq.add(continueLeaf()); break
                }
                // An ENCLOSING forward region's follow (an active exit pushed by an outer if/try/sync).
                // Checked AFTER the loop/switch break and continue-target checks: a block that is BOTH an
                // inner loop/switch break-target AND an enclosing follow must emit its break/continue leaf
                // FIRST — stopping here plain would drop that break (switch cases fall through; a
                // `while (true)` break-to-follow that coincides with an enclosing follow becomes an infinite
                // loop). By this point the block is neither the immediate follow nor a loop/switch jump
                // target, so it genuinely belongs to an enclosing region (which places it) — stop here and
                // let that region continue from it, instead of re-placing/revisiting it.
                if (block in exitStack) break
                // A block outside the enclosing loop that is not its follow is a non-local exit
                // (multi-level break/continue) we do not model — bail rather than mis-structure it.
                if (loopCtx != null && block !== exit && block !in loopCtx.bodyNodes) {
                    throw Bail("non-local exit from loop at B${block.id}")
                }
                // A duplicable straight-line block reached from several arms is DUPLICATED into each
                // (jadx's return / tail-block duplication) rather than treated as an illegal shared merge.
                // Any other revisit is a real unstructured shape and bails.
                if (block in placed && !isDuplicable(block)) {
                    throw Bail("unexpected revisit of placed B${block.id}")
                }
            }

            if (startsSynchronized(block)) {
                val built = makeSync(block, loopCtx)
                seq.add(block) // the monitor-enter block's prefix (lock setup; the monitor-enter is hidden)
                seq.add(built.region)
                cur = built.follow
                continue
            }
            if (isProtected(block) && block !in openTryBlocks) {
                val built = makeTry(block, loopCtx)
                seq.add(built.region)
                cur = built.follow
                continue
            }
            if (block in loopHeaders && block !in activeLoopHeaders) {
                val loop = makeLoop(block)
                seq.add(loop.region)
                cur = loop.follow
                continue
            }
            when (branchKind(block)) {
                BranchKind.TWO_WAY -> {
                    val built = makeIf(block, loopCtx, chainFollow)
                    seq.add(block) // the condition block's straight-line prefix (its `if` is DONT_GENERATE)
                    seq.add(built.region)
                    cur = built.follow
                }
                BranchKind.SWITCH -> {
                    val built = makeSwitch(block, loopCtx)
                    seq.add(block) // the selector block's prefix (the `switch` insn is DONT_GENERATE)
                    seq.add(built.region)
                    cur = built.follow
                }
                BranchKind.NONE -> {
                    // A block ending in a two-way `if` whose BOTH arms are the same target collapses to a
                    // single successor at the CFG level (raw successors == 1) — a DEGENERATE branch whose
                    // condition is dead. Consume the `if` (mark DONT_GENERATE) so it does not leak as a bare
                    // comparison, provided the condition has no side effect (`!mayThrow`: a register/const
                    // comparison — the value it tests is computed in a predecessor). A throwing/effectful
                    // condition (an inlined call/field-read) keeps the honest leaked-branch bail — dropping
                    // it would lose the effect. Such a block bails today either way, so this is flip-only.
                    val last = block.instructions.lastOrNull()
                    if (last is IfInstruction && block.successors.size == 1) {
                        if (mayThrow(last)) throw Bail("degenerate if with a side-effecting condition in B${block.id}")
                        last.add(AttrFlag.DONT_GENERATE)
                    }
                    placeLeaf(block)
                    seq.add(block)
                    val s = cleanSucc(block).firstOrNull()
                    if (s != null) recordEdge(block, s)
                    cur = s
                }
                BranchKind.UNSUPPORTED -> throw Bail("unsupported branch at B${block.id}")
            }
        }
        return seq
    }

    /**
     * Whether [insn] can raise an exception at runtime. Only provably-safe operations return false: register
     * moves/copies, constant loads (except `const-class`, which can raise a linkage error), non-throwing
     * arithmetic (all but integer div/rem), comparisons/casts that never throw, and control transfer. Anything
     * that touches memory, calls a method, allocates, casts a reference (`check-cast`), or divides may throw.
     */
    private fun mayThrow(insn: Instruction): Boolean {
        // The instruction's OWN opcode may throw, OR any wrapped sub-expression does. ExpressionShaping runs
        // immediately before structuring and inlines "pure" (= reorderable, NOT non-throwing) defs into a
        // single later use in the same block — so a whitelisted top-level opcode can WRAP a throwing op:
        // `return (a / b)` (RETURN wrapping DIV), `x = arr.length + 5` (ARITH wrapping ARRAY_LENGTH),
        // `z = x instanceof T` folded into a use. Inspecting only `insn.opcode` would miss those (same
        // wrapped-operand blindness fixed in readsSsaValue/tryDefsEscape). Recurse through the operand tree.
        val opcodeThrows = when (insn.opcode) {
            IrOpcode.CONST, IrOpcode.CONST_STRING,
            IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG,
            IrOpcode.NEG, IrOpcode.NOT, IrOpcode.CAST, IrOpcode.CMP,
            IrOpcode.GOTO, IrOpcode.NOP, IrOpcode.RETURN, IrOpcode.IF, IrOpcode.SWITCH,
            -> false
            // Integer div/rem throw on a zero divisor.
            IrOpcode.ARITH -> {
                val op = (insn as? com.jadxmp.ir.insn.ArithInstruction)?.op
                op == com.jadxmp.ir.insn.ArithOp.DIV || op == com.jadxmp.ir.insn.ArithOp.REM
            }
            // INSTANCE_OF (like CONST_CLASS, check-cast, new-*) resolves a class reference → a linkage error
            // (NoClassDefFoundError / IncompatibleClassChangeError, JVM §5.4.3.1) — throwing.
            else -> true // invoke, field/array access, check-cast, const-class, instance-of, new-*, monitor, throw…
        }
        if (opcodeThrows) return true
        for (k in 0 until insn.argCount) {
            val arg = insn.getArg(k)
            if (arg is InstructionOperand && mayThrow(arg.instruction)) return true
        }
        return false
    }

    private enum class BranchKind { NONE, TWO_WAY, SWITCH, UNSUPPORTED }

    private fun branchKind(block: BasicBlock): BranchKind {
        val succ = cleanSucc(block)
        return when {
            block.instructions.lastOrNull() is SwitchInstruction -> BranchKind.SWITCH
            succ.size <= 1 -> BranchKind.NONE
            succ.size == 2 && block.instructions.lastOrNull() is IfInstruction &&
                succ[0] !== succ[1] -> BranchKind.TWO_WAY
            else -> BranchKind.UNSUPPORTED
        }
    }

    private fun placeLeaf(block: BasicBlock) {
        // A duplicable block may legitimately appear in more than one arm (jadx: return / tail-block
        // duplication). Any other second placement is a real unstructured shape.
        if (!placed.add(block) && !isDuplicable(block)) throw Bail("block B${block.id} placed twice")
    }

    /**
     * Whether a block reached from several arms may be **duplicated** (emitted as a copy in each) rather
     * than requiring a single shared placement. This is jadx's return / tail-block duplication and is how
     * a shared `return z` or a shared `z = …; goto merge` reached from nested `if` arms structures instead
     * of bailing. Safe only for a **straight-line** block (one clean successor — never a branch, whose
     * whole subtree would be copied) that declares no *intra-block* temporary (a variable defined and read
     * within itself, whose declaration would be missing in the second copy). Cross-block declarations are
     * already guaranteed to dominate their uses by [coalescingIsSound], which ran before structuring — so
     * re-emitting an assignment to such a (hoisted / dominating-declared) variable stays renderable.
     *
     * Duplication only ever fires when a block is reached twice — a shape that otherwise *bails* today —
     * so it cannot change a method that already structures cleanly.
     */
    private fun isDuplicable(block: BasicBlock): Boolean {
        if (block in loopHeaders || startsSynchronized(block)) return false
        // A protected block may be duplicated ONLY if it cannot throw: then its exception edge to the
        // handler is vacuous, so a copy needs no protection and is safe wherever it lands. A protected block
        // holding any potentially-throwing instruction must NOT duplicate — a copy's exception could escape
        // the try's protection (rule 4). A pure block (moves/consts/non-div arithmetic) that merely sits in
        // the try range is such a vacuously-protected shared tail.
        if (isProtected(block) && block.instructions.any { mayThrow(it) }) return false
        if (cleanSucc(block).size != 1) return false // straight-line / forwarding / single-exit only
        // An intra-block temp (a value defined then read again inside this block) used to forbid
        // duplication: codegen declares a variable once, so the second copy's use would be out of scope.
        // That is now safe **provided every such temp is a block-local temp** (marked BLOCK_LOCAL_TEMP —
        // defined AND used only within this block): codegen re-declares those per copy. A temp that also
        // ESCAPES the block is NOT block-local; we can't prove per-copy re-declaration is enough, so we
        // conservatively still refuse (keeping the old behavior / bailing). See [markBlockLocalTemps].
        if (!everyIntraBlockTempIsBlockLocal(block)) return false
        // A GENUINELY-DEAD result def (result read nowhere, and NOT a coalesced variable) would render
        // `T u = expr;` in one copy and a bare, out-of-scope `u = expr;` in the sibling copy — a silent
        // miscompile. Pure dead defs are already removed by SsaBuilder's DCE; only a potentially-throwing
        // dead read (array-length/-get, field-get, div/rem…) can survive, and it is never block-local
        // (zero readers). Refuse to duplicate such a block. NB: a *coalesced* variable's per-branch
        // assignment legitimately has `useCount == 0` (its only use — the φ operand — was detached when
        // out-of-SSA removed the φ) yet is LIVE via the shared local, so it must NOT count as dead.
        if (block.instructions.any { insn -> isGenuinelyDeadResult(insn) }) return false
        return true
    }

    /** A result whose value has no uses AND is not a coalesced (multi-version) local — i.e. truly dead. */
    private fun isGenuinelyDeadResult(insn: Instruction): Boolean {
        val v = insn.result?.ssaValue ?: return false
        if (v.useCount != 0) return false
        val lv = v.localVar
        return lv == null || lv.ssaValues.size <= 1
    }

    /**
     * True when every value defined-and-reread inside [block] (an intra-block temp) is a [block-local
     * temp][AttrFlag.BLOCK_LOCAL_TEMP] — safe for codegen to re-declare in each duplicated copy. A block
     * with no intra-block temp trivially qualifies (the old rule refused any intra-block temp outright).
     */
    private fun everyIntraBlockTempIsBlockLocal(block: BasicBlock): Boolean {
        val insns = block.instructions
        for (i in insns.indices) {
            val defInsn = insns[i]
            val value = defInsn.result?.ssaValue ?: continue
            var readLater = false
            for (j in i + 1 until insns.size) {
                if (readsSsaValue(insns[j], value)) {
                    readLater = true
                    break
                }
            }
            if (!readLater) continue // not an intra-block temp — no barrier
            if (!defInsn.contains(AttrFlag.BLOCK_LOCAL_TEMP)) return false // escapes ⇒ can't duplicate safely
        }
        return true
    }

    /**
     * Mark every **block-local temporary** with [AttrFlag.BLOCK_LOCAL_TEMP]: a value defined in a block
     * whose EVERY read (recursively through inlined/wrapped operands) is within that same block — it never
     * escapes to a `catch`, a later region, or another arm. Such a temp is safe for codegen to re-declare
     * in each copy of a DUPLICATED block; a cross-block or coalesced (multiply-assigned) value is never
     * marked (it keeps its single dominating declaration — [coalescingIsSound] guarantees that). Runs once
     * before structuring so [isDuplicable] can consult the marks.
     */
    private fun markBlockLocalTemps() {
        // value -> the set of blocks whose (top-level) instructions read it, following wrapped operands.
        val readerBlocks = HashMap<SsaValue, MutableSet<BasicBlock>>()
        for (b in method.blocks) {
            for (insn in b.instructions) collectReadValues(insn, b, readerBlocks)
        }
        for (b in method.blocks) {
            for (insn in b.instructions) {
                val value = insn.result?.ssaValue ?: continue
                // A coalesced (multiply-assigned) variable is a real cross-block local, never a temp.
                val lv = value.localVar
                if (lv != null && lv.ssaValues.size > 1) continue
                val readers = readerBlocks[value] ?: continue // dead def: nothing to declare
                if (readers.all { it === b }) insn.add(AttrFlag.BLOCK_LOCAL_TEMP)
            }
        }
    }

    /** Record, for every SSA value [insn] reads (recursively through wrapped operands), that [block] reads it. */
    private fun collectReadValues(
        insn: Instruction,
        block: BasicBlock,
        into: HashMap<SsaValue, MutableSet<BasicBlock>>,
    ) {
        for (k in 0 until insn.argCount) {
            when (val arg = insn.getArg(k)) {
                is RegisterOperand -> arg.ssaValue?.let { into.getOrPut(it) { HashSet() }.add(block) }
                is InstructionOperand -> collectReadValues(arg.instruction, block, into)
                else -> {}
            }
        }
    }

    /**
     * Whether [insn] reads [value] — **recursively through inlined (wrapped) sub-expressions**. Expression
     * shaping runs before structuring and folds a single-use def into its use, so an in-block temporary's
     * only read may sit nested inside an [InstructionOperand] rather than as a top-level argument. Missing
     * that nested read would let [isDuplicable] copy a block whose temp is declared in one copy and left a
     * bare undeclared use in the other (a silent miscompile) — so this must match [ExpressionShaping]'s
     * own recursion over wrapped operands.
     */
    private fun readsSsaValue(insn: Instruction, value: SsaValue): Boolean {
        for (k in 0 until insn.argCount) {
            when (val arg = insn.getArg(k)) {
                is RegisterOperand -> if (arg.ssaValue === value) return true
                is InstructionOperand -> if (readsSsaValue(arg.instruction, value)) return true
                else -> {}
            }
        }
        return false
    }

    // ---- if / short-circuit -------------------------------------------------

    private class BuiltIf(val region: Region, val follow: BasicBlock?)

    private enum class ArmKind { NORMAL, BREAK, CONTINUE }

    private fun makeIf(condBlock: BasicBlock, loopCtx: LoopCtx?, chainFollow: BasicBlock?): BuiltIf {
        placeLeaf(condBlock)
        val folded = foldCondition(condBlock)
        val thenTarget = folded.trueTarget
        val elseTarget = folded.falseTarget
        val thenK = armKind(thenTarget, loopCtx)
        val elseK = armKind(elseTarget, loopCtx)

        // If a direct arm target is a break/continue (loop exit / re-test), it must be emitted as that
        // jump — NOT confused with a fall-through to the if's post-dominator, which can be the SAME block
        // (e.g. `if (i==1) break;` where the merge IS the loop follow). The non-jump arm becomes the
        // continuation (the enclosing chain resumes there); two jumps end the chain.
        if (thenK != ArmKind.NORMAL || elseK != ArmKind.NORMAL) {
            return when {
                thenK != ArmKind.NORMAL && elseK == ArmKind.NORMAL ->
                    BuiltIf(IfRegion(folded.condition, jumpRegion(thenK)), elseTarget)
                elseK != ArmKind.NORMAL && thenK == ArmKind.NORMAL ->
                    BuiltIf(IfRegion(negate(folded.condition), jumpRegion(elseK)), thenTarget)
                else ->
                    BuiltIf(IfRegion(folded.condition, jumpRegion(thenK), jumpRegion(elseK)), null)
            }
        }

        // Both arms are ordinary: reconverge at the immediate post-dominator (the merge). When that
        // post-dominator is a real block, it IS the merge (the clean diamond). When it collapses to the
        // method exit — a sub-path returns/throws early, so post-dominance can't see the reconvergence — the
        // true merge is the DOMINANCE-FRONTIER INTERSECTION of the arms ([findOutBlock]): a block that
        // returns early still carries the real merge in its dominance frontier (jadx's IfRegionMaker.
        // findOutBlock). Use it ONLY when provably a genuine single merge ([isGenuineMerge]) — the honesty
        // nets don't check placement position, so that dominance proof is the rule-4 correctness guarantee.
        // Otherwise fall back to the iter-34 in-body-return clamp: an in-body `return` inside a
        // `synchronized`/single-non-terminal-exit body whose follow is the enclosing [chainFollow]. Phase 1's
        // active-exit stack (below) is what lets the resulting merge partition a chain+siblings of diamonds.
        val ipd = ipostdom(folded.lastBlock)
        val merge = when {
            ipd != null && ipd !== exit -> ipd
            else -> findOutBlock(condBlock, thenTarget, elseTarget)
                ?: if (chainFollow != null && chainFollow !== exit) chainFollow else ipd
        }
        val thenIsMerge = thenTarget === merge
        val elseIsMerge = elseTarget === merge
        // The merge is this if's follow — an active exit while its arms are built, so an arm that reaches it
        // (or an enclosing follow) stops there and the merge is placed once by the enclosing chain.
        val region: Region = withActiveExit(merge) {
            when {
                thenIsMerge && !elseIsMerge -> {
                    // Empty then-arm ⇒ invert so the sole arm is the then-branch.
                    IfRegion(negate(folded.condition), makeArm(elseTarget, merge, loopCtx))
                }
                elseIsMerge && !thenIsMerge -> {
                    IfRegion(folded.condition, makeArm(thenTarget, merge, loopCtx))
                }
                else -> {
                    IfRegion(folded.condition, makeArm(thenTarget, merge, loopCtx), makeArm(elseTarget, merge, loopCtx))
                }
            }
        }
        return BuiltIf(region, merge)
    }

    /**
     * jadx's `IfRegionMaker.findOutBlock` (dominance-frontier intersection). The arms reconverge somewhere
     * in `(DF(then) ∪ {then}) ∩ (DF(else) ∪ {else})` minus the method exit. Unlike the post-dominator this
     * survives an early `return`/`throw` on a sub-path (the returning block still carries the true merge in
     * its dominance frontier), which is exactly why it flips the diamond/short-circuit cluster whose
     * post-dominator collapses to the exit.
     *
     * The intersection may hold MORE than one block — a diamond whose arms can both `return false` OR both
     * continue yields two: the shared `return` tail (the false-paths merge) AND the real continue merge.
     * We disambiguate by the correctness proof itself: keep only candidates that are a PROVABLY genuine
     * single merge ([isGenuineMerge] — which rejects a duplicable shared `return` tail, leaving it to the
     * duplication path) and require EXACTLY ONE to survive. Zero (both arms terminate) or two-or-more
     * genuine (truly ambiguous) ⇒ null, and the caller keeps the honest clamp/bail rather than guess (the DF
     * fallbacks jadx uses — getPathCross / union candidates — can pick a wrong out-block, rule 4).
     */
    private fun findOutBlock(condBlock: BasicBlock, thenBlock: BasicBlock, elseBlock: BasicBlock): BasicBlock? {
        val elseSet = HashSet<BasicBlock>(elseBlock.dominanceFrontier).apply { add(elseBlock) }
        val e = exit
        val nonDup = ArrayList<BasicBlock>()
        val dup = ArrayList<BasicBlock>()
        for (b in HashSet<BasicBlock>(thenBlock.dominanceFrontier).apply { add(thenBlock) }) {
            if (b === e || b !in elseSet) continue
            if (!isGenuineMerge(condBlock, b)) continue
            (if (isDuplicable(b)) dup else nonDup).add(b)
        }
        // A diamond whose arms can both `return false` OR both continue yields TWO genuine candidates: the
        // shared `return` tail (duplicable) and the real continuation block (a branch, not duplicable).
        // Prefer the unique NON-duplicable merge (the continuation). A duplicable shared tail is the follow
        // only when it is the SOLE genuine reconvergence (the last diamond in a chain, whose merge IS the
        // shared `return` — jadx dedups these via `isEqualReturnBlocks`), placed once instead of duplicated.
        // Ambiguity within the chosen tier (>1) ⇒ null (bail) — never guess between two real merges.
        return nonDup.singleOrNull() ?: dup.singleOrNull()?.takeIf { nonDup.isEmpty() }
    }

    /**
     * Whether [outBlock] (a [findOutBlock] candidate) is a PROVABLY genuine single merge of the branch at
     * [condBlock] — the rule-4 proof that placing it ONCE as the follow drops no code (the honesty nets
     * only check a block is placed *somewhere*, not that post-branch code is on every path):
     *  1. [condBlock] dominates [outBlock] — it sits under this branch.
     *  2. every clean predecessor of [outBlock] is dominated by [condBlock] — NO path enters it from
     *     outside the branch's region (an external predecessor would mean placing it as the follow drops
     *     that path's structure).
     *  3. every non-terminating path out of [condBlock] reaches [outBlock] ([provenPostDominatesModuloTerminals]):
     *     terminal sub-paths `return`/`throw` inside their branch; no path escapes to a second merge.
     * Any failure ⇒ not a follow (the caller keeps the honest bail).
     */
    private fun isGenuineMerge(condBlock: BasicBlock, outBlock: BasicBlock): Boolean {
        if (outBlock === exit || outBlock in loopHeaders) return false
        // A DUPLICABLE shared tail (a straight-line `return z` / forwarding block) reached from several arms
        // is left to jadx-style duplication at the revisit guard — never placed once as a follow here. This
        // also disambiguates the common two-candidate diamond (a shared `return` tail + the real branch
        // merge): only the non-duplicable branch survives, so [findOutBlock] resolves to it.
        if (isDuplicable(outBlock)) return false
        if (condBlock.id !in outBlock.dominators) return false
        val preds = cleanPreds(outBlock)
        if (preds.size < 2) return false // a genuine merge has ≥2 incoming clean paths
        if (preds.any { condBlock.id !in it.dominators }) return false // an external predecessor
        return provenPostDominatesModuloTerminals(condBlock, outBlock)
    }

    /** A block that transfers straight to the method exit: its code cannot fall past the branch. */
    private fun isTerminal(block: BasicBlock): Boolean {
        val last = block.instructions.lastOrNull()?.opcode
        return last == IrOpcode.RETURN || last == IrOpcode.THROW
    }

    /**
     * Clause 3 of [isGenuineMerge]: every block strictly between [condBlock] and [merge] (dominated by
     * [condBlock] but not by [merge]) that is not a `return`/`throw` sends ALL its clean successors into
     * that between-region or straight to [merge] — so every non-terminating path out of [condBlock] reaches
     * [merge] and none escapes to a different target (which would be a second, dropped exit). A non-terminal
     * successor to the raw exit, a back edge to [condBlock], or any successor outside the region falsifies it.
     */
    private fun provenPostDominatesModuloTerminals(condBlock: BasicBlock, merge: BasicBlock): Boolean {
        val e = exit
        val between = method.blocks.filter {
            it !== condBlock && it !== merge && it !== e &&
                condBlock.id in it.dominators && merge.id !in it.dominators
        }
        for (x in listOf(condBlock) + between) {
            if (x !== condBlock && isTerminal(x)) continue // a terminal sub-path returns inside its branch
            for (s in cleanSucc(x)) {
                if (s === merge) continue
                if (s in between) continue
                return false // escapes to the exit, back to the branch, or to a second merge ⇒ not provable
            }
        }
        return true
    }

    private fun armKind(target: BasicBlock, loopCtx: LoopCtx?): ArmKind = when {
        loopCtx == null -> ArmKind.NORMAL
        target === loopCtx.follow -> ArmKind.BREAK
        loopCtx.continueTarget != null && target === loopCtx.continueTarget -> ArmKind.CONTINUE
        else -> ArmKind.NORMAL
    }

    private fun jumpRegion(kind: ArmKind): Region = SequenceRegion().apply {
        add(if (kind == ArmKind.BREAK) breakLeaf() else continueLeaf())
    }

    /** Build one arm of an if, up to the [merge]; a bare edge to a break/continue target becomes that. */
    private fun makeArm(target: BasicBlock, merge: BasicBlock?, loopCtx: LoopCtx?): Region =
        makeRegion(target, chainFollow = merge, loopCtx = loopCtx, bodyRootHeader = null)

    private class Folded(
        val condition: Condition,
        val trueTarget: BasicBlock,
        val falseTarget: BasicBlock,
        val lastBlock: BasicBlock,
    )

    /**
     * Fold a short-circuit chain of condition blocks starting at [condBlock] into one [Condition].
     * AND: the branch-taken side is another condition sharing our false target (`a && b`). OR: the
     * fall-through side is another condition sharing our true target (`a || b`). Each consumed
     * condition block's two CFG out-edges are recorded (represented by the fold / the arms).
     */
    private fun foldCondition(condBlock: BasicBlock): Folded {
        markConditionConsumed(condBlock)
        val succ = cleanSucc(condBlock)
        recordEdge(condBlock, succ[0])
        recordEdge(condBlock, succ[1])
        var condition = branchCondition(condBlock)
        var trueTarget = succ[0]
        var falseTarget = succ[1]
        var last = condBlock
        // A chained condition may only fold into this one if it lives under the SAME try (same protecting
        // handlers) — folding `a && b` keeps both operands guarded by the same catch, whereas folding
        // across a try boundary would move an operand's evaluation out of (or into) a protected region.
        val headHandlers = protectingHandlers(condBlock).toHashSet()

        while (true) {
            cancellation.ensureActive()
            val andCont = trueTarget.takeIf { isChainableCondition(it, headHandlers) && cleanSucc(it)[1] === falseTarget }
            if (andCont != null) {
                consumeContinuation(andCont)
                condition = Condition.And(flatten(condition, branchCondition(andCont), and = true))
                trueTarget = cleanSucc(andCont)[0]
                last = andCont
                continue
            }
            val orCont = falseTarget.takeIf { isChainableCondition(it, headHandlers) && cleanSucc(it)[0] === trueTarget }
            if (orCont != null) {
                consumeContinuation(orCont)
                condition = Condition.Or(flatten(condition, branchCondition(orCont), and = false))
                falseTarget = cleanSucc(orCont)[1]
                last = orCont
                continue
            }
            break
        }
        return Folded(condition, trueTarget, falseTarget, last)
    }

    private fun consumeContinuation(cont: BasicBlock) {
        markConditionConsumed(cont)
        placeLeaf(cont)
        val succ = cleanSucc(cont)
        recordEdge(cont, succ[0])
        recordEdge(cont, succ[1])
    }

    private fun flatten(existing: Condition, next: Condition, and: Boolean): List<Condition> {
        val terms = ArrayList<Condition>()
        when {
            and && existing is Condition.And -> terms.addAll(existing.terms)
            !and && existing is Condition.Or -> terms.addAll(existing.terms)
            else -> terms.add(existing)
        }
        terms.add(next)
        return terms
    }

    private fun isChainableCondition(block: BasicBlock, headHandlers: Set<BasicBlock>): Boolean {
        val succ = cleanSucc(block)
        return block !== exit &&
            block !in placed &&
            block !in loopHeaders &&
            succ.size == 2 &&
            succ[0] !== succ[1] &&
            block.instructions.lastOrNull() is IfInstruction &&
            cleanPreds(block).size == 1 &&
            // Fold only within one try: the chained condition must share the head's protecting handlers,
            // so the fold never moves an operand's evaluation across a try boundary.
            protectingHandlers(block).toHashSet() == headHandlers &&
            // A real statement before the `if` would be skipped by folding, so refuse to fold it.
            block.instructions.dropLast(1).none { isEmittable(it) }
    }

    private fun branchCondition(block: BasicBlock): Condition {
        val ifInsn = block.instructions.last() as IfInstruction
        return Condition.Compare(ifInsn.condition, ifInsn.getArg(0), ifInsn.getArg(1))
    }

    private fun markConditionConsumed(block: BasicBlock) {
        (block.instructions.lastOrNull() as? IfInstruction)?.add(AttrFlag.DONT_GENERATE)
    }

    private fun negate(cond: Condition): Condition = when (cond) {
        is Condition.Compare -> Condition.Compare(cond.op.negate(), cond.left, cond.right)
        is Condition.Not -> cond.negated
        is Condition.BoolTest -> Condition.Not(cond)
        is Condition.And -> Condition.Or(cond.terms.map { negate(it) })
        is Condition.Or -> Condition.And(cond.terms.map { negate(it) })
    }

    // ---- switch -------------------------------------------------------------

    private class BuiltSwitch(val region: Region, val follow: BasicBlock?)

    /**
     * Build a [SwitchRegion] from a dense/sparse int switch. Each `case`/`default` body is built up to
     * the switch merge (its immediate post-dominator); an edge to that merge becomes a `break` (reusing
     * the loop break machinery). Case **fall-through** (one case flowing into another) is not modelled
     * yet: it surfaces as a double-placement and bails. `continue` still targets an enclosing loop.
     */
    private fun makeSwitch(switchBlock: BasicBlock, loopCtx: LoopCtx?): BuiltSwitch {
        placeLeaf(switchBlock)
        val sw = switchBlock.instructions.last() as SwitchInstruction
        sw.add(AttrFlag.DONT_GENERATE)
        val switchSucc = cleanSucc(switchBlock)
        for (s in switchSucc) recordEdge(switchBlock, s)

        val follow = ipostdom(switchBlock) ?: throw Bail("switch without a merge")

        // Resolve case/default target offsets to blocks via the successor edges (robust to instruction
        // rewrites): CfgBuilder wired successors in first-appearance order of (caseTargets…, default).
        val orderedTargets = LinkedHashSet<Int>().apply {
            for (t in sw.caseTargets) add(t)
            add(sw.defaultTarget)
        }.toList()
        if (orderedTargets.size != switchSucc.size) throw Bail("switch edge/target mismatch")
        val offsetToBlock = HashMap<Int, BasicBlock>(orderedTargets.size)
        for (i in orderedTargets.indices) offsetToBlock[orderedTargets[i]] = switchSucc[i]

        val bodyNodes = HashSet<BasicBlock>()
        for (b in method.blocks) {
            if (b === follow || b === exit) continue
            if (switchBlock.id in b.dominators && follow.id !in b.dominators) bodyNodes.add(b)
        }
        val ctx = LoopCtx(continueTarget = loopCtx?.continueTarget, follow = follow, bodyNodes = bodyNodes)

        // Group keys sharing one target into a single case, preserving key order.
        val byTarget = LinkedHashMap<BasicBlock, MutableList<Long>>()
        for (i in sw.keys.indices) {
            val tb = offsetToBlock[sw.caseTargets[i]] ?: throw Bail("unresolved switch case target")
            byTarget.getOrPut(tb) { ArrayList() }.add(sw.keys[i].toLong())
        }
        val cases = byTarget.map { (target, keys) -> SwitchCase(keys, makeSwitchCase(target, ctx)) }

        val defaultBlock = offsetToBlock[sw.defaultTarget]
        val defaultCase = if (defaultBlock != null && defaultBlock !== follow) makeSwitchCase(defaultBlock, ctx) else null

        return BuiltSwitch(SwitchRegion(sw.selector, cases, defaultCase), follow)
    }

    /** One case/default arm, up to the switch merge; a bare edge to the merge becomes a `break`. */
    private fun makeSwitchCase(target: BasicBlock, ctx: LoopCtx): Region =
        makeRegion(target, chainFollow = null, loopCtx = ctx, bodyRootHeader = null)

    // ---- try / catch --------------------------------------------------------

    /** A block is protected if it lies inside a try (has [PipelineAttrs.PROTECTING_HANDLERS] membership). */
    private fun isProtected(block: BasicBlock): Boolean = protectingHandlers(block).isNotEmpty()

    private class BuiltTry(val region: Region, val follow: BasicBlock?)

    /**
     * Build a [TryCatchRegion] for a protected body — **straight-line OR internally branchy** — with one
     * or more `catch` clauses.
     *
     * The protected region is the maximal set of blocks that share this exact handler set, gathered over
     * *clean* (exception-free) flow. Its body is then structured by the ordinary [makeRegion] recursion
     * (which now walks clean successors and uses clean post-dominators), so internal `if`/`switch`/loop
     * shapes are handled for free; the exception edges are represented by the try/catch itself and never
     * traversed structurally. Bails honestly (leaving the method unstructured, never wrong) on: a
     * finally/synchronized cleanup handler (catch-all that re-throws), a nested try in a handler, multiple
     * or absent normal exits, or a try-scoped value that escapes and would need declaration hoisting.
     */
    private fun makeTry(head: BasicBlock, loopCtx: LoopCtx?): BuiltTry {
        val handlerSet = protectingHandlers(head).toHashSet()
        if (handlerSet.isEmpty()) throw Bail("protected head without a handler")

        val protectedBlocks = collectProtectedRegion(head, handlerSet)

        // Clean exits of the protected region: where normal flow leaves the try. A handler that appears
        // here is a *coincident* handler — its exception edge equals a real normal edge (the common empty
        // `catch` that just continues to the follow), so it stays in the clean CFG. Such a handler MUST be
        // the single follow; a coincident handler that is not the follow is a shape we don't model.
        //
        val exits = LinkedHashSet<BasicBlock>()
        for (b in protectedBlocks) for (s in cleanSucc(b)) if (s !in protectedBlocks && s !== exit) exits.add(s)
        val follow: BasicBlock? = when (exits.size) {
            0 -> null // the try body always returns/throws — nothing runs after the try/catch
            1 -> exits.first()
            else -> throw Bail("try body with multiple normal exits not supported yet")
        }
        for (h in handlerSet) {
            if (h in exits && h !== follow) throw Bail("coincident handler is not the try follow")
        }

        // A single-def value produced inside the try and read outside it (in a catch or after) would be
        // declared inside the `try {}` scope and not compile. Merged locals are hoisted by out-of-SSA;
        // anything else here we cannot place safely, so bail (honest, not wrong).
        if (tryDefsEscape(protectedBlocks)) throw Bail("try-scoped value escapes; declaration hoisting needed")

        // Structure the (possibly branchy) body over clean flow, up to the follow. While the region is
        // open its blocks must not re-trigger a nested try for the SAME handler set.
        openTryBlocks.addAll(protectedBlocks)
        val tryRegion: Region = try {
            // The try's normal follow is an active exit while the body is structured (a branchy body's arm
            // reaching the follow stops there; the follow is placed once after the try by the enclosing chain).
            withActiveExit(follow) {
                makeRegion(head, chainFollow = follow, loopCtx = loopCtx, bodyRootHeader = null)
            }
        } finally {
            openTryBlocks.removeAll(protectedBlocks)
        }
        // Every exception edge of the protected body is represented by the try/catch (coverage net).
        for (b in protectedBlocks) for (h in handlerSet) recordEdge(b, h)

        // A synthetic `catch(Throwable){ cleanup; throw }` cleanup handler is a `finally`. Try to hoist its
        // cleanup into a `finally {}` for the narrow, provably-identical shape; otherwise finishTry bails.
        reconstructFinally(protectedBlocks, handlerSet, follow, tryRegion)?.let { return it }

        return finishTry(tryRegion, handlerSet, follow, loopCtx)
    }

    /**
     * Reconstruct `try { body } finally { cleanup }` from javac's inlined-cleanup shape, for the **narrow,
     * provably-correct** case only:
     *  - a SINGLE handler that is a catch-all whose one block is exactly `[move-exception; cleanup…; throw]`
     *    (the synthetic finally cleanup that re-throws);
     *  - a SINGLE normal exit ([follow]) reached ONLY from the try body, whose LEADING instructions are an
     *    **instruction-identical copy** of that cleanup (the compiler's duplicate on the fall-through path);
     *  - no explicit catches.
     *
     * The cleanup is then emitted ONCE in the `finally` (the handler block, with its move-exception and
     * re-throw hidden); both duplicated copies are marked `DONT_GENERATE`. The follow's post-cleanup tail
     * (e.g. a `return`) stays and is emitted after the try/finally by the enclosing chain — so the cleanup
     * runs on EVERY exit (finally semantics) and no copy is dropped. ANY deviation returns null → the caller
     * bails honestly (rule 4): a cleanup that isn't provably present-and-identical on the normal path is
     * never hoisted, and a real `catch(Throwable)` that does more than cleanup+rethrow is never collapsed.
     */
    private fun reconstructFinally(
        protectedBlocks: Set<BasicBlock>,
        handlerSet: Set<BasicBlock>,
        follow: BasicBlock?,
        tryRegion: Region,
    ): BuiltTry? {
        if (follow == null) return null
        val h = handlerSet.singleOrNull() ?: return null // exactly one handler (no explicit catches)
        if (h === follow) return null // a coincident empty catch is a different (already-handled) shape
        val excHandler = h[PipelineAttrs.EXC_HANDLER] ?: return null
        if (!excHandler.catchAll) return null
        if (handlerRegionBlocks(h).size != 1) return null // single-block cleanup only
        val hInsns = h.instructions
        if (hInsns.size < 3) return null
        if (hInsns.first().opcode != IrOpcode.MOVE_EXCEPTION) return null
        if (hInsns.last().opcode != IrOpcode.THROW) return null
        val handlerCleanup = hInsns.subList(1, hInsns.size - 1) // between move-exception and re-throw
        if (handlerCleanup.isEmpty() || handlerCleanup.any { it.opcode == IrOpcode.THROW }) return null

        // The follow must be the try body's SOLE non-exceptional exit. javac inlines the cleanup on EVERY
        // exit, so an in-body `return`/throw (a protected block that transfers straight to the method exit)
        // carries its OWN inlined cleanup copy that this narrow reconstruction would leave LIVE — the
        // finally would then run the cleanup a SECOND time on that path (a silent double-execution no
        // coverage net catches). That is Tier-3 (cleanup-before-transfer); bail honestly here.
        val e = exit
        if (e != null && protectedBlocks.any { e in cleanSucc(it) }) return null

        // The follow must be reached ONLY from inside the try, so its leading instructions are the compiler's
        // duplicate of the cleanup — not some unrelated block.
        if (cleanPreds(follow).any { it !in protectedBlocks }) return null
        val followInsns = follow.instructions
        // The follow must be EXACTLY [cleanup…; one transfer terminator]: same-length cleanup as the handler
        // (a longer normal-path cleanup would leave an un-hoisted fragment that never runs on the exceptional
        // path), followed by a single return/goto/throw that stays as the post-finally transfer.
        if (followInsns.size != handlerCleanup.size + 1) return null
        val tail = followInsns.last()
        if (tail.opcode != IrOpcode.RETURN && tail.opcode != IrOpcode.GOTO && tail.opcode != IrOpcode.THROW) return null
        val followCleanup = followInsns.subList(0, handlerCleanup.size)
        if (!cleanupsIdentical(followCleanup, handlerCleanup)) return null

        // Hiding the follow's cleanup must not strand a value: if a hidden cleanup insn's result is read by
        // anything OUTSIDE that hidden cleanup (the surviving tail, or elsewhere), that use would become
        // undeclared (coalescingIsSound already ran and won't re-catch it). Bail rather than risk it.
        val hidden = followCleanup.toHashSet()
        for (insn in followCleanup) {
            val v = insn.result?.ssaValue ?: continue
            if (v.uses.any { it.parent != null && it.parent !in hidden }) return null
        }

        // Reconstruct: hide the handler's move-exception + re-throw and the follow's duplicate cleanup;
        // emit the cleanup ONCE in the finally (the handler block). Place the handler for coverage and
        // represent its throw→exit edge (now carried by the finally).
        hInsns.first().add(AttrFlag.DONT_GENERATE)
        hInsns.last().add(AttrFlag.DONT_GENERATE)
        for (i in handlerCleanup.indices) followInsns[i].add(AttrFlag.DONT_GENERATE)
        placed.add(h)
        for (s in h.successors) recordEdge(h, s)
        val finallyRegion = SequenceRegion().apply { add(h) }
        return BuiltTry(TryCatchRegion(tryRegion, catches = emptyList(), finallyRegion = finallyRegion), follow)
    }

    /** Instruction-by-instruction identity of two duplicated cleanup copies (same ops, refs, and value operands). */
    private fun cleanupsIdentical(a: List<Instruction>, b: List<Instruction>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (!sameCleanupInsn(a[i], b[i])) return false
        return true
    }

    private fun sameCleanupInsn(x: Instruction, y: Instruction): Boolean {
        if (x.opcode != y.opcode || x.argCount != y.argCount) return false
        if (x is InvokeInstruction && y is InvokeInstruction && x.methodRef != y.methodRef) return false
        if (x is FieldInstruction && y is FieldInstruction && x.fieldRef != y.fieldRef) return false
        for (i in 0 until x.argCount) if (!sameCleanupOperand(x.getArg(i), y.getArg(i))) return false
        return true
    }

    private fun sameCleanupOperand(p: Operand, q: Operand): Boolean = when {
        p is RegisterOperand && q is RegisterOperand -> p.ssaValue != null && p.ssaValue === q.ssaValue
        p is LiteralOperand && q is LiteralOperand -> p.value == q.value && p.type == q.type
        p is InstructionOperand && q is InstructionOperand -> sameCleanupInsn(p.instruction, q.instruction)
        else -> false
    }

    /**
     * The maximal set of blocks that lie in the try with this [handlerSet]: reachable from [head] over
     * clean flow while every block carries exactly the same handler set. A block with a different (or no)
     * handler set is outside the try — a follow, a handler, or an inner/outer try's block.
     */
    private fun collectProtectedRegion(head: BasicBlock, handlerSet: Set<BasicBlock>): Set<BasicBlock> {
        val region = HashSet<BasicBlock>()
        val stack = ArrayDeque<BasicBlock>()
        stack.addLast(head)
        while (stack.isNotEmpty()) {
            cancellation.ensureActive()
            val b = stack.removeLast()
            if (b in region) continue
            if (protectingHandlers(b).toHashSet() != handlerSet) continue // outside this try
            region.add(b)
            for (s in cleanSucc(b)) stack.addLast(s)
        }
        return region
    }

    /**
     * Whether a single-def (non-hoisted) value produced in the protected body is read OUTSIDE it. The read
     * test is over the block's *top-level* instructions and recurses through inlined (wrapped) operands —
     * an escaping value's only read may sit nested inside a top-level instruction (which still lives in its
     * block), so counting `use.parent` against a flat instruction set would falsely flag a value used
     * inside the try via an inlined expression (mirrors the wrapped-read fix in [readsSsaValue]).
     */
    private fun tryDefsEscape(protectedSeen: Set<BasicBlock>): Boolean {
        for (b in protectedSeen) {
            for (insn in b.instructions) {
                val v = insn.result?.ssaValue ?: continue
                val lv = v.localVar
                if (lv != null && lv.ssaValues.size > 1) continue // merged ⇒ hoisted by out-of-SSA
                if (readOutsideTryBody(v, protectedSeen)) return true
            }
        }
        return false
    }

    /** Whether some top-level instruction in a block NOT in [protectedSeen] reads [value] (recursively). */
    private fun readOutsideTryBody(value: SsaValue, protectedSeen: Set<BasicBlock>): Boolean {
        for (b in method.blocks) {
            if (b in protectedSeen) continue
            for (insn in b.instructions) {
                if (readsSsaValue(insn, value)) return true
            }
        }
        return false
    }

    private fun finishTry(
        tryRegion: Region,
        handlerSet: Set<BasicBlock>,
        follow: BasicBlock?,
        loopCtx: LoopCtx?,
    ): BuiltTry {
        if (follow === exit) throw Bail("try body with no normal follow not supported yet")
        // Order catches by handler position (approximates source/try-table order).
        val handlers = handlerSet.sortedBy { it.order }
        val catches = ArrayList<CatchClause>(handlers.size)
        for (h in handlers) {
            val excHandler = h[PipelineAttrs.EXC_HANDLER] ?: throw Bail("handler without EXC_HANDLER")
            // A rethrowing catch-all is a compiler `finally`'s cleanup. [reconstructFinally] already had
            // first crack at factoring it into a `finally {}` (the pretty form); reaching here means it
            // could NOT (a non-Tier-1 shape). Rather than bail, render it FAITHFULLY as an explicit
            // `catch (Throwable e) { <cleanup>; throw e; }` — a 1:1 transcription of the bytecode handler
            // (jadx does the same when it cannot extract the finally). This never factors/moves/dedups
            // cleanup, so it cannot drop it on a path or double it; the in-body returns keep their own
            // inlined cleanup copies (correct, mutually exclusive). Nested-try handlers and sync cleanup
            // are still caught below (isProtected / verifyAllMonitorsConsumed). Falls through to the
            // ordinary distinct-handler path.
            if (h === follow) {
                // Coincident empty catch: the handler entry IS the try follow — control merges there on
                // BOTH the exceptional and normal exits (the swallowing `catch (E e) {}` that continues).
                // The catch body is empty; the follow block is emitted after the try by the enclosing
                // chain. It must not bind an exception (a move-exception there would have no place to go).
                // The follow being itself protected (the NEXT try's body — a chain of `try{…}catch(E){}`)
                // is fine: the enclosing chain re-enters it as an ordinary block and opens that next try.
                if (h.instructions.firstOrNull()?.opcode == IrOpcode.MOVE_EXCEPTION) {
                    throw Bail("handler-as-follow binds an exception")
                }
                catches.add(CatchClause(catchTypesFor(excHandler), null, SequenceRegion()))
                continue
            }
            // A distinct handler (reached only exceptionally). A handler that is itself inside another try
            // (a nested try in the catch body) is not modelled yet — bail honestly.
            if (isProtected(h)) throw Bail("nested try in handler not supported yet")
            // The leading move-exception binds the caught
            // value; hide it (it becomes the catch param). makeRegion emits the catch body up to the shared
            // follow, recording/placing those blocks itself.
            val moveExc = h.instructions.firstOrNull()?.takeIf { it.opcode == IrOpcode.MOVE_EXCEPTION }
            moveExc?.add(AttrFlag.DONT_GENERATE)
            val exVar = moveExc?.result
            val body = makeRegion(h, chainFollow = follow, loopCtx = loopCtx, bodyRootHeader = null)
            catches.add(CatchClause(catchTypesFor(excHandler), exVar, body))
        }
        return BuiltTry(TryCatchRegion(tryRegion, catches), follow)
    }

    /**
     * The catch types to render for [h]. When the handler is a **catch-all** (its DEX entry includes a
     * `.catchall`, so it catches every throwable) any named alternatives it also lists are redundant —
     * `java.lang.Throwable` subsumes them all. Emitting the raw multi-catch `A | B | Throwable` would be
     * rejected by javac ("alternatives cannot be related by subclassing"), so we collapse to `Throwable`
     * alone, matching jadx. This is sound with NO class hierarchy: catch-all is a universal supertype, so
     * dropping the named alternatives never changes which exceptions the handler catches. A handler with
     * no catch-all keeps its listed types unchanged (a genuine `A | B` multi-catch of unrelated types).
     */
    private fun catchTypesFor(h: com.jadxmp.pipeline.cfg.ExceptionHandler): List<IrType> =
        if (h.catchAll) listOf(IrType.THROWABLE) else h.types

    /**
     * Whether a catch-all [handler] re-throws — i.e. is a `finally` / synchronized cleanup rather than a
     * real `catch (Throwable)`. Broadened from an entry-block-only check to the handler's whole region
     * (the blocks it dominates), so a **multi-block** cleanup — whose entry runs the cleanup and a *later*
     * block re-throws — is still recognized. Conservative by design: any `throw` anywhere in the handler
     * region counts as a re-throw, so an ambiguous cleanup is treated as one (structuring bails) rather
     * than risk emitting a cleanup handler as a real catch or wrongly collapsing a real catch into a
     * `finally`. Bounded by dominance, so it never wanders into the shared follow.
     */
    private fun handlerReThrows(handler: BasicBlock): Boolean {
        val visited = HashSet<BasicBlock>()
        val stack = ArrayDeque<BasicBlock>()
        stack.addLast(handler)
        while (stack.isNotEmpty()) {
            cancellation.ensureActive()
            val b = stack.removeLast()
            if (!visited.add(b)) continue
            if (b.instructions.lastOrNull()?.opcode == IrOpcode.THROW) return true
            for (s in cleanSucc(b)) {
                if (s !== exit && handler.id in s.dominators) stack.addLast(s)
            }
        }
        return false
    }

    // ---- synchronized -------------------------------------------------------

    private class BuiltSync(val region: Region, val follow: BasicBlock?)

    /** A block starts a synchronized region if it holds a `monitor-enter`. */
    private fun startsSynchronized(block: BasicBlock): Boolean =
        block.instructions.any { it.opcode == IrOpcode.MONITOR_ENTER }

    /**
     * Reconstruct a [SyncRegion] from a `monitor-enter` and the compiler's synthetic catch-all that
     * releases the lock and re-throws on the exceptional exit. **jadx: SynchronizedRegion.**
     *
     * The lock is released on EVERY exit (normal and exceptional) by the `synchronized {}` construct
     * itself, so the body's per-exit `monitor-exit`s are simply hidden (codegen never emits monitor ops)
     * and the catch-all cleanup handler is *consumed* — placed for coverage, never emitted. Bails honestly
     * (rule 4) on anything it can't prove a clean monitor construct: a mismatched lock, a cleanup handler
     * that does real work (which would be dropped), a multi-exit body, an overlapping handler, an escaping
     * value, or a monitor-enter that isn't the block's final action.
     */
    private fun makeSync(enterBlock: BasicBlock, loopCtx: LoopCtx?): BuiltSync {
        if (isProtected(enterBlock)) throw Bail("monitor-enter inside a try not supported yet")
        val monitorEnter = enterBlock.instructions.singleOrNull { it.opcode == IrOpcode.MONITOR_ENTER }
            ?: throw Bail("unsupported multi monitor-enter block")
        // The enter block renders as a prefix OUTSIDE the SyncRegion, so anything after the monitor-enter
        // renders before the lock. A side-effect-free out-of-SSA artifact — a hoisted declaration init
        // (`CONST`) or a copy (`MOVE`) the pipeline may place here (monitor-enter is not a terminator) — is
        // safe there: it has no observable effect, so its position relative to the lock is immaterial, and a
        // hoisted declaration MUST sit outside the sync anyway (its variable is read AFTER the synchronized
        // block, so an in-lock declaration would be out of scope — `synchronized(a){ Object o = null; … } … o`).
        // A GENUINELY effectful instruction there (a call, a field/array read or write, a monitor) would be
        // reordered out of the critical section, so bail on anything but those pure artifacts.
        val enterIdx = enterBlock.instructions.indexOf(monitorEnter)
        val postEnterEffectful = enterBlock.instructions.drop(enterIdx + 1).any { insn ->
            isEmittable(insn) && insn.opcode != IrOpcode.CONST && insn.opcode != IrOpcode.MOVE
        }
        if (postEnterEffectful) {
            throw Bail("effectful instruction after monitor-enter would reorder out of the lock")
        }
        // The monitor-enter's single clean successor is the synchronized body head.
        val lock = monitorEnter.getArg(0)
        val head = cleanSucc(enterBlock).singleOrNull() ?: throw Bail("monitor-enter without a single body head")

        val handlerSet = protectingHandlers(head).toHashSet()
        val handler = handlerSet.singleOrNull() ?: throw Bail("synchronized body without a single cleanup handler")
        val excHandler = handler[PipelineAttrs.EXC_HANDLER] ?: throw Bail("cleanup handler without EXC_HANDLER")
        if (!excHandler.catchAll) throw Bail("synchronized cleanup is not catch-all")
        val handlerRegion = handlerRegionBlocks(handler)
        if (!isMonitorRelease(handlerRegion, lock)) throw Bail("cleanup handler is not a pure monitor release")

        val protectedBlocks = collectProtectedRegion(head, handlerSet)
        if (protectedBlocks.any { it in handlerRegion }) throw Bail("cleanup handler overlaps the body")

        val exits = LinkedHashSet<BasicBlock>()
        for (b in protectedBlocks) {
            for (s in cleanSucc(b)) {
                if (s !in protectedBlocks && s !== exit && s !in handlerRegion) exits.add(s)
            }
        }
        val follow: BasicBlock? = when (exits.size) {
            0 -> null // the whole body returns/throws — nothing runs after the synchronized
            1 -> exits.first()
            else -> throw Bail("synchronized body with multiple exits not supported yet")
        }
        if (follow != null && tryDefsEscape(protectedBlocks)) throw Bail("sync-scoped value escapes")

        placeLeaf(enterBlock)
        recordEdge(enterBlock, head)

        openTryBlocks.addAll(protectedBlocks)
        val body: Region = try {
            // The synchronized follow is an active exit while the (possibly branchy) body is structured.
            withActiveExit(follow) {
                makeRegion(head, chainFollow = follow, loopCtx = loopCtx, bodyRootHeader = null)
            }
        } finally {
            openTryBlocks.removeAll(protectedBlocks)
        }
        for (b in protectedBlocks) for (h in handlerSet) recordEdge(b, h)
        consumeCleanupHandler(handlerRegion)

        consumedMonitorEnters.add(monitorEnter)
        return BuiltSync(SyncRegion(lock, body), follow)
    }

    /** A handler's own region: blocks reachable from its entry over clean flow and dominated by it. */
    private fun handlerRegionBlocks(handler: BasicBlock): Set<BasicBlock> {
        val region = HashSet<BasicBlock>()
        val stack = ArrayDeque<BasicBlock>()
        stack.addLast(handler)
        while (stack.isNotEmpty()) {
            val b = stack.removeLast()
            if (!region.add(b)) continue
            for (s in cleanSucc(b)) if (s !== exit && handler.id in s.dominators) stack.addLast(s)
        }
        return region
    }

    /**
     * True when the handler [region] does nothing but release [lock] and re-throw the caught exception —
     * the only shape whose instructions we may safely hide. Any other (real) instruction means work that
     * would be dropped, so the caller bails instead.
     */
    private fun isMonitorRelease(region: Set<BasicBlock>, lock: Operand): Boolean {
        var releasedLock = false
        var reThrows = false
        for (b in region) {
            for (insn in b.instructions) {
                when (insn.opcode) {
                    IrOpcode.MOVE_EXCEPTION, IrOpcode.GOTO, IrOpcode.NOP -> {}
                    IrOpcode.MONITOR_EXIT ->
                        if (sameLock(insn.getArg(0), lock)) releasedLock = true else return false
                    IrOpcode.THROW -> reThrows = true
                    else -> return false // any real instruction ⇒ not a pure release
                }
            }
        }
        return releasedLock && reThrows
    }

    /**
     * Whether two operands provably denote the same lock variable — the same source local or the same SSA
     * value. A bare register-number match is NOT accepted: without SSA/local identity we cannot prove it is
     * the same lock (the register may have been reused), so we bail rather than assume.
     */
    private fun sameLock(a: Operand, b: Operand): Boolean {
        if (a !is RegisterOperand || b !is RegisterOperand) return false
        val la = a.ssaValue?.localVar
        val lb = b.ssaValue?.localVar
        if (la != null && lb != null) return la === lb
        return a.ssaValue != null && a.ssaValue === b.ssaValue
    }

    /** Place the cleanup handler's blocks and record their edges for the coverage nets — never emit them. */
    private fun consumeCleanupHandler(region: Set<BasicBlock>) {
        for (b in region) {
            placeLeaf(b)
            for (s in b.successors) recordEdge(b, s)
        }
    }

    // ---- loops --------------------------------------------------------------

    private class BuiltLoop(val region: Region, val follow: BasicBlock?)

    private fun makeLoop(header: BasicBlock): BuiltLoop {
        activeLoopHeaders.add(header)
        try {
            return buildLoop(header)
        } finally {
            activeLoopHeaders.remove(header)
        }
    }

    private fun buildLoop(header: BasicBlock): BuiltLoop {
        val body = loopNodes[header] ?: throw Bail("loop header without body")
        val follow = loopFollow(body) // throws on multi-exit

        // WHILE: the exit test is the header's own two-way branch.
        if (branchKind(header) == BranchKind.TWO_WAY && headerIsPureTest(header)) {
            val succ0 = cleanSucc(header)[0]
            val succ1 = cleanSucc(header)[1]
            val cond = branchCondition(header)
            val bodyStart: BasicBlock
            val loopCond: Condition
            when {
                succ0 in body && succ1 === follow -> { bodyStart = succ0; loopCond = cond }
                succ1 in body && succ0 === follow -> { bodyStart = succ1; loopCond = negate(cond) }
                else -> return buildInfiniteLoop(header, body, follow)
            }
            placeLeaf(header)
            markConditionConsumed(header)
            recordEdge(header, succ0)
            recordEdge(header, succ1)
            val ctx = LoopCtx(continueTarget = header, follow = follow, bodyNodes = body)
            val bodyRegion = makeRegion(bodyStart, chainFollow = header, loopCtx = ctx, bodyRootHeader = null)
            return BuiltLoop(LoopRegion(LoopKind.WHILE, loopCond, bodyRegion), follow)
        }

        tryDoWhile(header, body, follow)?.let { return it }
        return buildInfiniteLoop(header, body, follow)
    }

    private fun tryDoWhile(header: BasicBlock, body: Set<BasicBlock>, follow: BasicBlock?): BuiltLoop? {
        val latch = cleanPreds(header).filter { header.id in it.dominators }.singleOrNull() ?: return null
        if (branchKind(latch) != BranchKind.TWO_WAY) return null
        val succ0 = cleanSucc(latch)[0]
        val succ1 = cleanSucc(latch)[1]
        val cond = branchCondition(latch)
        val loopCond: Condition = when {
            succ0 === header && (follow == null || succ1 === follow) -> cond
            succ1 === header && (follow == null || succ0 === follow) -> negate(cond)
            else -> return null
        }
        // `continue` is intentionally not modelled for do-while (the latch carries update code); an edge
        // that would need it fails edge coverage and the method bails. The latch is the fall-through.
        val ctx = LoopCtx(continueTarget = null, follow = follow, bodyNodes = body)
        // Body from the header up to (not including) the latch; single-block loop ⇒ empty body.
        val bodyRegion = if (header === latch) {
            SequenceRegion()
        } else {
            makeRegion(header, chainFollow = latch, loopCtx = ctx, bodyRootHeader = null)
        }
        if (latch in placed) return null // latch already consumed elsewhere ⇒ not this shape
        placeLeaf(latch)
        markConditionConsumed(latch)
        recordEdge(latch, succ0)
        recordEdge(latch, succ1)
        bodyRegion.add(latch)
        return BuiltLoop(LoopRegion(LoopKind.DO_WHILE, loopCond, bodyRegion), follow)
    }

    /** `while (true) { … }`: valid only when the loop has no conditional exit (follow == null). */
    /**
     * `while (true) { … }` — the general loop form for a header that is not a clean pre-test (`while`) or
     * post-test (`do/while`) loop: a header carrying real statements before its branch (a loop whose
     * condition depends on per-iteration work), or a `while(true)` with no conditional exit. A single
     * conditional exit to [follow] is emitted as a `break` (via the loop context); the body loops back to
     * the header. If the body has a shape the recursion can't cover, it still bails inside [makeRegion].
     */
    private fun buildInfiniteLoop(header: BasicBlock, body: Set<BasicBlock>, follow: BasicBlock?): BuiltLoop {
        val ctx = LoopCtx(continueTarget = header, follow = follow, bodyNodes = body)
        val bodyRegion = makeRegion(header, chainFollow = header, loopCtx = ctx, bodyRootHeader = header)
        return BuiltLoop(LoopRegion(LoopKind.INFINITE, null, bodyRegion), follow)
    }

    private fun loopFollow(body: Set<BasicBlock>): BasicBlock? {
        val targets = LinkedHashSet<BasicBlock>()
        for (b in body) for (s in cleanSucc(b)) if (s !in body && s !== exit) targets.add(s)
        return when (targets.size) {
            0 -> null
            1 -> targets.first()
            else -> throw Bail("multi-exit loop")
        }
    }

    private fun headerIsPureTest(header: BasicBlock): Boolean =
        header.instructions.dropLast(1).none { isEmittable(it) }

    // ---- break / continue leaves -------------------------------------------

    /** A synthetic, off-CFG leaf block carrying a single `break;`. Not part of [method] blocks. */
    private fun breakLeaf(): BasicBlock =
        BasicBlock(SYNTHETIC_ID).also { it.instructions.add(Instruction(IrOpcode.BREAK)) }

    private fun continueLeaf(): BasicBlock =
        BasicBlock(SYNTHETIC_ID).also { it.instructions.add(Instruction(IrOpcode.CONTINUE)) }

    // ---- coverage nets ------------------------------------------------------

    private fun recordEdge(from: BasicBlock, to: BasicBlock) {
        representedEdges.add(edgeKey(from, to))
    }

    private fun edgeKey(from: BasicBlock, to: BasicBlock): Long =
        (from.id.toLong() shl 32) or (to.id.toLong() and 0xFFFFFFFFL)

    private fun verifyBlockCoverage() {
        for (block in method.blocks) {
            if (block === entry || block === exit) continue
            if (block !in placed) throw Bail("block B${block.id} was never placed")
        }
    }

    /**
     * The structural non-lossy guarantee: every CFG edge between real blocks must be represented in the
     * tree (fall-through, loop test/latch, break/continue, or return/throw to exit). A single missing
     * edge means control flow silently vanished — discard the whole tree.
     */
    private fun verifyEdgeCoverage() {
        for (u in method.blocks) {
            for (v in u.successors) {
                if (edgeKey(u, v) !in representedEdges) {
                    throw Bail("unrepresented edge B${u.id} -> B${v.id}")
                }
            }
        }
    }

    private fun isEmittable(insn: Instruction): Boolean {
        if (insn.contains(AttrFlag.DONT_GENERATE)) return false
        return when (insn.opcode) {
            IrOpcode.NOP, IrOpcode.PHI, IrOpcode.GOTO,
            IrOpcode.MONITOR_ENTER, IrOpcode.MONITOR_EXIT, IrOpcode.MOVE_EXCEPTION,
            -> false
            else -> true
        }
    }

    private companion object {
        /** Id for synthetic break/continue leaves; identity (not id) distinguishes them. */
        const val SYNTHETIC_ID = -1
    }
}
