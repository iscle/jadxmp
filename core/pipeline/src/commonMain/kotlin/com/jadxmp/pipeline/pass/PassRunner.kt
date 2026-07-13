package com.jadxmp.pipeline.pass

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.attr.DecompileError
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot

/**
 * Orders passes by their [Pass.runAfter]/[Pass.runBefore] hints and runs them with **fault isolation**:
 * a pass that throws on one node attaches an [IrAttrs.ERROR] (and [AttrFlag.HAS_ERROR]) to that node
 * and processing continues, so one bad method never crashes the class and one bad class never crashes
 * the run (ARCHITECTURE §3, CONVENTIONS "Errors").
 *
 * jadx: the pass loop in `ProcessClass` / `DepthTraversal`, plus its per-method try/catch that turns a
 * failure into a `JadxError` attribute.
 */
class PassRunner(
    rootPasses: List<RootPass> = emptyList(),
    classPasses: List<ClassPass> = emptyList(),
    methodPasses: List<MethodPass> = emptyList(),
) {
    private val rootPasses = topoSort(rootPasses)
    private val classPasses = topoSort(classPasses)
    private val methodPasses = topoSort(methodPasses)

    /** Run every stage over the whole model: root passes, then each class (and its methods). */
    fun run(root: IrRoot, context: PassContext = PassContext(root)) {
        for (pass in rootPasses) {
            context.cancellation.ensureActive()
            guarded(root) { pass.run(root, context) }
        }
        for (cls in root.classes) {
            context.cancellation.ensureActive()
            runClass(cls, context)
        }
    }

    /** Run the per-class and per-method passes for a single class (used for lazy, on-demand decompile). */
    fun runClass(cls: IrClass, context: PassContext) {
        for (pass in classPasses) {
            context.cancellation.ensureActive()
            guarded(cls) { pass.run(cls, context) }
        }
        for (method in cls.methods) {
            context.cancellation.ensureActive()
            runMethod(method, context)
        }
    }

    /** Run all method passes for one method, isolating a failure to that method. */
    fun runMethod(method: IrMethod, context: PassContext) {
        for (pass in methodPasses) {
            context.cancellation.ensureActive()
            guarded(method) { pass.run(method, context) }
        }
    }

    private inline fun guarded(node: AttrNode, body: () -> Unit) {
        try {
            body()
        } catch (c: CancellationSignal) {
            throw c // never swallow cancellation
        } catch (t: Throwable) {
            recordError(node, t)
        }
    }

    private fun recordError(node: AttrNode, t: Throwable) {
        // Preserve the first error but record the flag on every failure.
        if (!node.contains(IrAttrs.ERROR)) {
            node[IrAttrs.ERROR] = DecompileError(t.message ?: t.toString(), t)
        }
        node.add(AttrFlag.HAS_ERROR)
    }

    companion object {
        /**
         * Deterministic topological sort honouring [Pass.runAfter]/[Pass.runBefore]. Ties break by the
         * original list order so the sequence is stable across runs. Throws on an ordering cycle.
         */
        fun <T : Pass> topoSort(passes: List<T>): List<T> {
            if (passes.size <= 1) return passes.toList()
            val byName = passes.associateBy { it.name }
            val indexOf = passes.withIndex().associate { (i, p) -> p.name to i }
            // Build edges: a -> b means a runs before b.
            val successors = HashMap<String, MutableSet<String>>()
            val indegree = HashMap<String, Int>()
            for (p in passes) {
                successors.getOrPut(p.name) { LinkedHashSet() }
                indegree.getOrPut(p.name) { 0 }
            }
            fun addEdge(before: String, after: String) {
                if (before !in byName || after !in byName) return
                if (successors.getValue(before).add(after)) {
                    indegree[after] = indegree.getValue(after) + 1
                }
            }
            for (p in passes) {
                for (a in p.runAfter) addEdge(a, p.name)
                for (b in p.runBefore) addEdge(p.name, b)
            }
            // Kahn's algorithm with a stable tie-break (lowest original index first).
            val ready = passes.filter { indegree.getValue(it.name) == 0 }
                .sortedBy { indexOf.getValue(it.name) }
                .map { it.name }
                .toMutableList()
            val out = ArrayList<T>(passes.size)
            val remaining = indegree.toMutableMap()
            while (ready.isNotEmpty()) {
                val nextName = ready.removeAt(0)
                out.add(byName.getValue(nextName))
                val newlyReady = ArrayList<String>()
                for (s in successors.getValue(nextName)) {
                    remaining[s] = remaining.getValue(s) - 1
                    if (remaining.getValue(s) == 0) newlyReady.add(s)
                }
                newlyReady.sortBy { indexOf.getValue(it) }
                // Merge preserving stable order.
                for (n in newlyReady) ready.add(n)
                ready.sortBy { indexOf.getValue(it) }
            }
            if (out.size != passes.size) {
                error("Pass ordering cycle among: ${passes.map { it.name } - out.map { it.name }.toSet()}")
            }
            return out
        }
    }
}

/**
 * Marker a [CancellationCheck] may throw to signal cancellation; [PassRunner] never converts it into a
 * node error. A cancellation implementation that throws a different type should subclass this (or the
 * orchestrator can map its own cancellation exception onto it).
 */
open class CancellationSignal(message: String = "cancelled") : RuntimeException(message)
