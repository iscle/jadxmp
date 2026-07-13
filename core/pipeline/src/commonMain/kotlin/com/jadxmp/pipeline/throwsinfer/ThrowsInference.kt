package com.jadxmp.pipeline.throwsinfer

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.insn.TypeInstruction
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.decode.MethodDecoder
import com.jadxmp.pipeline.types.ClassHierarchy

/**
 * Infers the `throws` clause of a method.  **jadx: MethodThrowsVisitor.**
 *
 * A Java method that can propagate a **checked** exception must declare it. This computes, for one
 * method, the set of checked exception types it can throw — from its own uncaught `throw`s and,
 * transitively, from the methods it calls — and stores it on [CodegenKeys.THROWS] for codegen's
 * `throws` clause.
 *
 * ## Soundness
 * - **Checked only.** Unchecked exceptions (`RuntimeException`/`Error` and their subtypes) are never
 *   declared. Subtyping is resolved against the loaded [ClassHierarchy] plus a fixed set of well-known
 *   unchecked library classes; an unresolvable type defaults to *checked* (matching `extends Exception`).
 * - **Caught exceptions removed.** A type caught by an enclosing typed `catch` in the method (or by a
 *   `catch (Exception|Throwable)`) is not propagated. A catch-all (`finally`) does not swallow.
 * - **Under- over over-declaring.** When a callee cannot be resolved (a library method) or a thrown
 *   type is unknown, it is simply omitted — a missed `throws` fails recompilation honestly, whereas a
 *   *wrong* `throws` on an `@Override` could miscompile. We never over-declare beyond types actually
 *   thrown/propagated.
 *
 * The analysis works off a lightweight re-decode of each method body (so it needs no CFG/SSA and is
 * independent of pass ordering); the call graph is resolved against [IrRoot]. Cross-method propagation
 * uses a path-based recursion with a cycle guard and a bounded call budget.
 */
class ThrowsInference(
    private val root: IrRoot,
    private val hierarchy: ClassHierarchy,
) {
    private val infoCache = HashMap<IrMethod, MethodExceptions>()
    private var budget = 0

    /** Compute and attach the throws clause for [method]. */
    fun apply(method: IrMethod) {
        val throwsSet = throwsOf(method, HashSet())
        if (throwsSet.isNotEmpty()) {
            method[CodegenKeys.THROWS] = throwsSet.sortedBy { it.toString() }
        }
    }

    private fun throwsOf(method: IrMethod, path: MutableSet<IrMethod>): Set<IrType> {
        if (method in path) return emptySet() // cycle: its own throws counted at first entry
        if (budget++ > CALL_BUDGET) return emptySet()
        val info = infoOf(method)
        val result = LinkedHashSet<IrType>()
        for (t in info.directThrown) if (isChecked(t)) result.add(t)
        if (info.callees.isNotEmpty()) {
            path.add(method)
            for (ref in info.callees) {
                val callee = resolve(ref) ?: continue
                result.addAll(throwsOf(callee, path))
            }
            path.remove(method)
        }
        return result.filterNotTo(LinkedHashSet()) { isCaught(it, info) }
    }

    // ---- per-method extraction (lightweight decode) -------------------------

    private fun infoOf(method: IrMethod): MethodExceptions {
        infoCache[method]?.let { return it }
        val reader = method[PipelineAttrs.CODE_READER]
        val info = if (reader == null) MethodExceptions.EMPTY else analyze(reader)
        infoCache[method] = info
        return info
    }

    private fun analyze(reader: com.jadxmp.input.CodeReader): MethodExceptions {
        val code = MethodDecoder().decode(reader)
        val regType = HashMap<Int, IrType>() // linear last-known object type per register
        val thrown = LinkedHashSet<IrType>()
        val callees = ArrayList<MethodRef>()

        for (di in code.instructions) {
            val insn = di.insn
            if (insn is InvokeInstruction) callees.add(insn.methodRef)
            if (insn.opcode == IrOpcode.THROW && insn.argCount > 0) {
                val reg = (insn.getArg(0) as? RegisterOperand)?.regNum
                val t = reg?.let { regType[it] }
                if (t is IrType.Object) thrown.add(t)
            }
            // Track the object type a result register holds (for `throw vX`).
            val result = insn.result
            if (result != null) {
                val produced = producedObjectType(insn, regType)
                if (produced != null) regType[result.regNum] = produced else regType.remove(result.regNum)
            }
        }

        val caught = ArrayList<IrType>()
        var hasCatchAll = false
        for (t in code.tries) for (h in t.handlers) {
            if (h.type == null) hasCatchAll = true else caught.add(h.type)
        }
        return MethodExceptions(thrown, callees, caught, hasCatchAll)
    }

    /** The object type a defining instruction puts into its result register, or null (clears it). */
    private fun producedObjectType(insn: Instruction, regType: Map<Int, IrType>): IrType? = when {
        insn is TypeInstruction && (insn.opcode == IrOpcode.NEW_INSTANCE || insn.opcode == IrOpcode.CHECK_CAST) ->
            insn.referencedType
        insn is InvokeInstruction -> insn.methodRef.returnType as? IrType.Object
        insn is FieldInstruction -> insn.fieldRef.type as? IrType.Object
        insn.opcode == IrOpcode.MOVE && insn.argCount > 0 ->
            (insn.getArg(0) as? RegisterOperand)?.regNum?.let { regType[it] }
        insn.opcode == IrOpcode.MOVE_EXCEPTION -> insn.result?.type as? IrType.Object
        else -> null
    }

    // ---- classification & resolution ---------------------------------------

    private fun isChecked(type: IrType): Boolean {
        val obj = type as? IrType.Object ?: return false
        if (obj.className in UNCHECKED_NAMES) return false
        if (hierarchy.isSubtype(obj, RUNTIME_EXCEPTION) || hierarchy.isSubtype(obj, ERROR)) return false
        return true
    }

    private fun isCaught(type: IrType, info: MethodExceptions): Boolean {
        val name = (type as? IrType.Object)?.className ?: return false
        for (caught in info.caughtTypes) {
            val cn = (caught as? IrType.Object)?.className ?: continue
            if (cn == THROWABLE || cn == EXCEPTION) return true // catches all checked
            if (cn == name) return true
            if (hierarchy.isSubtype(type, caught)) return true
        }
        return false
    }

    private fun resolve(ref: MethodRef): IrMethod? {
        val className = (ref.declaringType as? IrType.Object)?.className ?: return null
        val cls = root.findClass(className) ?: return null
        return cls.methods.firstOrNull {
            it.name == ref.name && it.argTypes == ref.paramTypes
        }
    }

    private class MethodExceptions(
        val directThrown: Set<IrType>,
        val callees: List<MethodRef>,
        val caughtTypes: List<IrType>,
        val hasCatchAll: Boolean,
    ) {
        companion object {
            val EMPTY = MethodExceptions(emptySet(), emptyList(), emptyList(), false)
        }
    }

    private companion object {
        const val CALL_BUDGET = 4000
        const val THROWABLE = "java.lang.Throwable"
        const val EXCEPTION = "java.lang.Exception"
        val RUNTIME_EXCEPTION = IrType.objectType("java.lang.RuntimeException")
        val ERROR = IrType.objectType("java.lang.Error")

        /** Well-known unchecked library exceptions (RuntimeException/Error families) — never declared. */
        val UNCHECKED_NAMES = setOf(
            "java.lang.RuntimeException", "java.lang.Error",
            "java.lang.NullPointerException", "java.lang.IllegalArgumentException",
            "java.lang.IllegalStateException", "java.lang.IndexOutOfBoundsException",
            "java.lang.ArrayIndexOutOfBoundsException", "java.lang.StringIndexOutOfBoundsException",
            "java.lang.ClassCastException", "java.lang.ArithmeticException",
            "java.lang.NumberFormatException", "java.lang.UnsupportedOperationException",
            "java.lang.NegativeArraySizeException", "java.lang.ArrayStoreException",
            "java.lang.SecurityException", "java.lang.IllegalMonitorStateException",
            "java.util.ConcurrentModificationException", "java.util.NoSuchElementException",
            "java.lang.AssertionError", "java.lang.OutOfMemoryError", "java.lang.StackOverflowError",
            "java.lang.ExceptionInInitializerError", "java.lang.NoClassDefFoundError",
        )
    }
}
