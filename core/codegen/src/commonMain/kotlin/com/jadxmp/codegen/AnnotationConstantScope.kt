package com.jadxmp.codegen

import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.type.IrType

/**
 * A bounded proof for generated annotation constant owners, not general classpath resolution.
 * Object, Enum and Annotation have fixed platform members: none can capture java/Float/Double or
 * the Kotlin Jvm-prefixed import aliases. Loaded declarations still take precedence over this
 * terminal knowledge. A caller proving arbitrary Java type names must also inspect [visitTerminal]:
 * `java.lang.Enum` exposes the public nested type `EnumDesc` in the JDK source API, even though
 * its members cannot capture the narrower constant-owner names above. This callback runs before
 * a successful proof is published; partial callback state must never justify a failed proof.
 * Unknown ancestors and malformed cycles cannot justify a source spelling.
 * The caller's output budget pays for every lookup and visited declaration; failure is terminal.
 */
class AnnotationConstantScope(
    private val owner: IrClass,
    private val budget: AnnotationRenderBudget,
    private val visitTerminal: (String) -> Unit = {},
    private val visit: (IrClass) -> Unit = {},
) {
    private var attempted = false
    private var complete = false
    private class Frame(val cls: IrClass, var edge: Int = -1)

    fun isComplete(): Boolean {
        if (attempted) return complete
        attempted = true
        val lexical = mutableSetOf<IrClass>()
        val done = mutableSetOf<IrClass>()
        var current: IrClass? = owner
        while (current != null) {
            budget.text(1)
            if (!lexical.add(current) || !members(current, done)) return false
            current = current.outerClass
        }
        complete = true
        return true
    }

    private fun members(start: IrClass, done: MutableSet<IrClass>): Boolean {
        if (start in done) return true
        val active = mutableSetOf<IrClass>()
        val pending = ArrayDeque<Frame>()
        fun push(cls: IrClass) {
            budget.text(1)
            active.add(cls)
            visit(cls)
            pending.addLast(Frame(cls))
        }
        push(start)
        while (pending.isNotEmpty()) {
            budget.text(1)
            val frame = pending.last()
            val cls = frame.cls
            val edge = frame.edge++
            if (edge >= cls.interfaces.size) {
                done.add(cls)
                active.remove(cls)
                pending.removeLast()
                continue
            }
            val type = if (edge < 0) cls.superType else cls.interfaces[edge]
            if (type == null) {
                budget.text(cls.fullName.length)
                if (cls.fullName != "java.lang.Object") return false
                continue
            }
            val name = (type as? IrType.Object)?.className ?: return false
            budget.text(name.length)
            val parent = owner.root.findClass(name)
            if (parent == null) {
                if (name !in TERMINALS) return false
                visitTerminal(name)
            } else {
                if (parent in active) return false
                if (parent !in done) push(parent)
            }
        }
        return true
    }

    private companion object {
        val TERMINALS = setOf("java.lang.Object", "java.lang.Enum", "java.lang.annotation.Annotation")
    }
}
