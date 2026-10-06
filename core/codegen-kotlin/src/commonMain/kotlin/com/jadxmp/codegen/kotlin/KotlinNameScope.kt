package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.type.IrType

/**
 * An import alias can be captured by an inherited callable/property, including one absent from the
 * input. Only a complete loaded member hierarchy for each lexical owner proves that name allocation
 * can avoid those declarations. An ancestor's outer class is not the caller's lexical owner.
 *
 * Identity-keyed per-output proofs avoid repeated descriptor hashing. Both retained results and all
 * traversal/string lookup work are bounded; after exhaustion existing proofs remain usable, while
 * new queries return false without allocating cache entries. This is a proof, not classpath loading.
 */
internal class KotlinNameScope(
    private val workLimit: Int = 1_000_000,
    private val entryLimit: Int = 100_000,
) {
    private val proofs = mutableMapOf<IrClass, Boolean>()
    private var remaining = workLimit
    private var exhausted = false
    internal val retainedProofCount: Int get() = proofs.size
    private class Limit : RuntimeException()
    private class Frame(val cls: IrClass, var edge: Int = -1)

    fun hasCompleteNameScope(context: IrClass): Boolean {
        proofs[context]?.let { return it }
        if (exhausted) return false
        if (proofs.size >= entryLimit) {
            exhausted = true
            return false
        }
        // Reserve before traversal; this one result fits even if its computation exhausts work.
        val result = try {
            complete(context)
        } catch (_: Limit) {
            exhausted = true
            false
        }
        proofs[context] = result
        return result
    }

    private fun complete(context: IrClass): Boolean {
        val lexical = mutableSetOf<IrClass>()
        val completeMembers = mutableSetOf<IrClass>()
        var owner: IrClass? = context
        while (owner != null) {
            charge(1)
            if (!lexical.add(owner)) return false
            if (!members(owner, completeMembers)) return false
            owner = owner.outerClass
        }
        return true
    }

    private fun members(start: IrClass, complete: MutableSet<IrClass>): Boolean {
        val active = mutableSetOf<IrClass>()
        val frames = ArrayDeque<Frame>()
        charge(1)
        frames.addLast(Frame(start))
        active.add(start)
        while (frames.isNotEmpty()) {
            charge(1)
            val frame = frames.last()
            val cls = frame.cls
            // Visit each edge directly, without concatenating an unbounded interface collection.
            val edge = frame.edge++
            if (edge >= cls.interfaces.size) {
                active.remove(cls)
                complete.add(cls)
                frames.removeLast()
                continue
            }
            val type = if (edge < 0) cls.superType else cls.interfaces[edge]
            if (type == null) {
                charge(cls.fullName.length + 1)
                if (cls.fullName != OBJECT) return false
                continue
            }
            val name = (type as? IrType.Object)?.className ?: return false
            charge(name.length + 1)
            if (name == OBJECT) continue
            val parent = cls.root.findClass(name) ?: return false
            if (parent in active) return false
            if (parent in complete) continue
            charge(1)
            active.add(parent)
            frames.addLast(Frame(parent))
        }
        return true
    }

    private fun charge(amount: Int) {
        if (amount < 0 || amount > remaining) throw Limit()
        remaining -= amount
    }

    private companion object { const val OBJECT = "java.lang.Object" }
}
