package com.jadxmp.codegen

import com.jadxmp.ir.annotation.AnnotationMetadata
import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod

/** One rendering pass's transactional coverage; semantic plans are shared, replay state is not. */
class AnnotationAttachmentTracker(private val plan: AnnotationEmissionPlan) {
    data class Problem(val node: AttrNode, val reason: String)
    private data class Slot(val node: AttrNode, val index: Int)
    private val covered = HashSet<Slot>()
    private val journal = ArrayList<Slot>()

    fun declaration(node: AttrNode) = mark(Slot(node, -1))
    fun default(method: IrMethod) = mark(Slot(method, -2))
    fun parameter(method: IrMethod, index: Int) { require(index >= 0); mark(Slot(method, index)) }
    fun checkpoint(): Int = journal.size
    fun restore(checkpoint: Int) {
        require(checkpoint in 0..journal.size)
        while (journal.size > checkpoint) covered.remove(journal.removeAt(journal.lastIndex))
    }

    private fun mark(slot: Slot) {
        require(journal.size < 20_000) { "annotation attachment coverage budget exhausted" }
        if (covered.add(slot)) journal.add(slot)
    }

    /** Audit all owned nodes, including ones hidden by enum/data/anonymous reconstruction. */
    fun unhandled(cls: IrClass): List<Problem> {
        val result = ArrayList<Problem>()
        val pending = ArrayDeque<IrClass>()
        val seen = HashSet<IrClass>()
        var remaining = 20_000
        pending.add(cls)
        fun check(node: AttrNode) {
            val source = plan.node(node)
            if ((source.annotations.isNotEmpty() || source.problems.isNotEmpty()) && Slot(node, -1) !in covered)
                result.add(Problem(node, "annotation declaration metadata was not emitted"))
            if ((source.default is AnnotationMetadata.Unavailable ||
                    (source.default as? AnnotationMetadata.Ready)?.value != null) && Slot(node, -2) !in covered)
                result.add(Problem(node, "annotation default was not emitted"))
            for ((index, annotations) in source.parameters.withIndex()) {
                if (annotations.isNotEmpty() && Slot(node, index) !in covered)
                    result.add(Problem(node, "annotation parameter $index metadata was not emitted"))
            }
        }
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (!seen.add(current)) continue
            val count = 1L + current.fields.size + current.methods.size + current.innerClasses.size
            if (count > remaining) {
                result.add(Problem(current, "annotation attachment audit budget exhausted"))
                break
            }
            remaining -= count.toInt()
            check(current)
            current.fields.forEach(::check)
            current.methods.forEach(::check)
            pending.addAll(current.innerClasses)
        }
        return result
    }
}
