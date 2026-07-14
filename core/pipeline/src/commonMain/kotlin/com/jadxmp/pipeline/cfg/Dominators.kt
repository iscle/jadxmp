package com.jadxmp.pipeline.cfg

import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.pass.CancellationCheck

/**
 * Dominator analysis over a finished CFG.  **jadx: DominatorTree / PostDominatorTree.**
 *
 * Implements the Cooper–Harvey–Kennedy "A Simple, Fast Dominance Algorithm" (2001): iterate the
 * immediate-dominator relation to a fixpoint over blocks in reverse-postorder, using the RPO index as
 * the finger for the `intersect` walk. From the idom relation we derive, on each [BasicBlock]:
 * - [BasicBlock.immediateDominator], [BasicBlock.dominatedBlocks] (the dominator tree),
 * - [BasicBlock.dominators] (the full dominator set, by block id, including self),
 * - [BasicBlock.dominanceFrontier] (the φ-placement set).
 *
 * [computePostDominators] runs the identical algorithm on the reversed CFG rooted at the exit block,
 * recording each block's immediate post-dominator on [PipelineAttrs.IMMEDIATE_POST_DOMINATOR].
 *
 * Exactness is essential — the whole SSA/structuring stack assumes these are correct — so this is
 * heavily unit-tested against textbook graphs.
 */
object Dominators {

    fun compute(method: IrMethod, cancellation: CancellationCheck = CancellationCheck.None) {
        val entry = method.entryBlock ?: return
        val rpo = reversePostOrder(entry, cancellation) { it.successors }
        pruneUnreachable(method, rpo)
        assignOrder(rpo)
        // IrMethod contract: blocks are held in reverse-postorder once numbering has run.
        method.blocks.clear()
        method.blocks.addAll(rpo)

        val idom = buildIdom(rpo, cancellation) { it.predecessors }
        applyDominators(rpo, idom)
        computeDominanceFrontier(rpo, cancellation)
    }

    fun computePostDominators(method: IrMethod, cancellation: CancellationCheck = CancellationCheck.None) {
        val exit = method.exitBlock ?: return
        val rpo = reversePostOrder(exit, cancellation) { it.predecessors }
        // Blocks that cannot reach the exit (infinite loops) are absent from rpo: their post-dom stays
        // unset, matching jadx's tolerant behaviour rather than throwing.
        val order = HashMap<BasicBlock, Int>(rpo.size)
        for (i in rpo.indices) order[rpo[i]] = i
        val idom = buildIdomIndexed(rpo, order, cancellation) { it.successors }
        for (i in 1 until rpo.size) {
            val ip = idom[i]
            if (ip >= 0) rpo[i][PipelineAttrs.IMMEDIATE_POST_DOMINATOR] = rpo[ip]
        }
    }

    // ---- shared machinery ---------------------------------------------------

    private inline fun reversePostOrder(
        root: BasicBlock,
        cancellation: CancellationCheck,
        succ: (BasicBlock) -> List<BasicBlock>,
    ): List<BasicBlock> {
        val visited = HashSet<BasicBlock>()
        val postorder = ArrayList<BasicBlock>()
        // Iterative DFS postorder (explicit stack: safe for deep graphs on wasm).
        val stack = ArrayDeque<BasicBlock>()
        val iterIndex = HashMap<BasicBlock, Int>()
        stack.addLast(root)
        visited.add(root)
        while (stack.isNotEmpty()) {
            cancellation.ensureActive()
            val node = stack.last()
            val succs = succ(node)
            val idx = iterIndex.getOrElse(node) { 0 }
            if (idx < succs.size) {
                iterIndex[node] = idx + 1
                val next = succs[idx]
                if (visited.add(next)) stack.addLast(next)
            } else {
                stack.removeLast()
                postorder.add(node)
            }
        }
        postorder.reverse()
        return postorder
    }

    private fun pruneUnreachable(method: IrMethod, reachable: List<BasicBlock>) {
        if (reachable.size == method.blocks.size) return
        val keep = reachable.toHashSet()
        for (block in method.blocks) {
            if (block !in keep) {
                for (p in block.predecessors) p.successors.remove(block)
                for (s in block.successors) s.predecessors.remove(block)
                block.predecessors.clear()
                block.successors.clear()
            }
        }
        method.blocks.retainAll(keep)
        // A pure-infinite-loop method has no path to the exit, so the synthetic exit is unreachable and
        // was just pruned; clear the dangling reference rather than leave exitBlock pointing at a
        // detached block. (Post-dominators then simply have no root, which computePostDominators tolerates.)
        val exit = method.exitBlock
        if (exit != null && exit !in keep) method.exitBlock = null
    }

    private fun assignOrder(rpo: List<BasicBlock>) {
        for (i in rpo.indices) rpo[i].order = i
    }

    /** Build idom (by RPO index) using [BasicBlock.order] as the finger; root is index 0. */
    private inline fun buildIdom(
        rpo: List<BasicBlock>,
        cancellation: CancellationCheck,
        preds: (BasicBlock) -> List<BasicBlock>,
    ): IntArray {
        val n = rpo.size
        val idom = IntArray(n) { -1 }
        if (n == 0) return idom
        idom[0] = 0
        var changed = true
        while (changed) {
            cancellation.ensureActive()
            changed = false
            for (i in 1 until n) {
                val b = rpo[i]
                var newIdom = -1
                for (p in preds(b)) {
                    val pi = p.order
                    if (pi < 0 || pi >= n || rpo[pi] !== p) continue // pred not in this RPO
                    if (idom[pi] == -1) continue
                    newIdom = if (newIdom == -1) pi else intersect(idom, pi, newIdom)
                }
                if (newIdom != -1 && idom[i] != newIdom) {
                    idom[i] = newIdom
                    changed = true
                }
            }
        }
        return idom
    }

    /**
     * Variant used for post-dominators where block [BasicBlock.order] is the *forward* RPO index and
     * cannot be reused: the reverse-graph position comes from an explicit [order] map instead.
     */
    private inline fun buildIdomIndexed(
        rpo: List<BasicBlock>,
        order: Map<BasicBlock, Int>,
        cancellation: CancellationCheck,
        preds: (BasicBlock) -> List<BasicBlock>,
    ): IntArray {
        val n = rpo.size
        val idom = IntArray(n) { -1 }
        if (n == 0) return idom
        idom[0] = 0
        var changed = true
        while (changed) {
            cancellation.ensureActive()
            changed = false
            for (i in 1 until n) {
                val b = rpo[i]
                var newIdom = -1
                for (p in preds(b)) {
                    val pi = order[p] ?: continue
                    if (idom[pi] == -1) continue
                    newIdom = if (newIdom == -1) pi else intersect(idom, pi, newIdom)
                }
                if (newIdom != -1 && idom[i] != newIdom) {
                    idom[i] = newIdom
                    changed = true
                }
            }
        }
        return idom
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

    private fun applyDominators(rpo: List<BasicBlock>, idom: IntArray) {
        // Clear the dominator-tree child lists on EVERY block first: [compute] may be re-run on a method
        // (e.g. after a CFG transform such as FixMultiEntryLoops recomputes and then DominatorsPass runs
        // again), and dominatedBlocks is otherwise only appended to — a second run would accumulate stale
        // duplicate children. The dominators sets are cleared per block below.
        for (block in rpo) block.dominatedBlocks.clear()
        val entry = rpo[0]
        entry.immediateDominator = null
        entry.dominators.clear()
        entry.dominators.add(entry.id)
        for (i in 1 until rpo.size) {
            val block = rpo[i]
            val idomBlock = rpo[idom[i]]
            block.immediateDominator = idomBlock
            idomBlock.dominatedBlocks.add(block)
            block.dominators.clear()
        }
        // Dominator sets: walk the idom chain (parents already have consistent chains).
        for (i in rpo.indices) {
            val block = rpo[i]
            block.dominators.add(block.id)
            var d = block.immediateDominator
            while (d != null) {
                block.dominators.add(d.id)
                d = d.immediateDominator
            }
        }
    }

    private fun computeDominanceFrontier(rpo: List<BasicBlock>, cancellation: CancellationCheck) {
        for (block in rpo) block.dominanceFrontier.clear()
        for (block in rpo) {
            cancellation.ensureActive()
            val preds = block.predecessors
            if (preds.size < 2) continue
            val idom = block.immediateDominator
            for (pred in preds) {
                var runner: BasicBlock? = pred
                while (runner != null && runner !== idom) {
                    runner.dominanceFrontier.add(block)
                    runner = runner.immediateDominator
                }
            }
        }
    }
}
