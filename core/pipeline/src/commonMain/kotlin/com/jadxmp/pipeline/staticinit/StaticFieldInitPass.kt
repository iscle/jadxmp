package com.jadxmp.pipeline.staticinit

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.ConstStringInstruction
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrFieldConst
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PassNames
import com.jadxmp.pipeline.pass.MethodPass
import com.jadxmp.pipeline.pass.PassContext

/**
 * Absorbs a class's `<clinit>` static-field initialization into the field model, so a
 * `static final` field is rendered at its declaration (`static final T N = <lit>;`) or as a proper
 * `static { … }` block — never as a double assignment to a `final` and never with the `<clinit>` body
 * leaking to class level. **jadx: ExtractFieldInit.moveStaticFieldsInit**
 *
 * DEX gives a `static final` field BOTH an encoded `ConstantValue` (its slot in the class's
 * `static_values`, which [com.jadxmp.pipeline.model.ModelBuilder] maps onto [IrField.constValue]) AND
 * a write in `<clinit>` whenever the initializer is not a javac compile-time constant — the canonical
 * case being an AGP `BuildConfig`:
 * ```
 * public static final boolean DEBUG;            // static_values slot: false
 * static { DEBUG = Boolean.parseBoolean("true"); }   // <clinit>
 * ```
 * Emitting the encoded `= false` at the declaration AND the `<clinit>` assignment is a double
 * assignment to a `final` ("cannot assign a value to static final variable DEBUG").
 *
 * The absorption, mirroring jadx:
 * 1. **Clear the encoded constant of every `final` field the `<clinit>` writes.** The `<clinit>`
 *    assignment is authoritative; the `static_values` slot is only the pre-`<clinit>` default. Without
 *    this the field would carry a declaration initializer AND be reassigned — the double-final-assign.
 * 2. **Fold a compile-time-constant assignment back onto the field and drop it from `<clinit>`.** When
 *    the value is a `const`/`const-string` (or an already-inlined literal), the field renders
 *    `static final T N = <lit>;` and the assignment is marked [AttrFlag.DONT_GENERATE]. Folded ONLY
 *    when the field is assigned exactly once, unconditionally (its block is on the single path from
 *    entry), AND no side-effecting instruction precedes the store on that path — hoisting the store to
 *    the declaration site moves it *before* everything that preceded it, so a preceding call / field
 *    access that could observe the write must block the fold (jadx: `canMove = canReorder && singlePath`).
 * 3. **Keep a non-constant initializer as a real `static { … }` block over a blank `final`.** A value
 *    like `Boolean.parseBoolean("true")` cannot be modelled by [IrFieldConst] (only primitive/String
 *    literals are), so — with its encoded constant cleared in step 1 — the field becomes a blank
 *    `final` and the write stays in `<clinit>`. A blank `static final` assigned exactly once in a
 *    static initializer is legal Java, so this recompiles (uglier than jadx's declaration-site
 *    expression, but correct — the no-silent-code-loss / bail-to-correct rule).
 * 4. **Drop a `<clinit>` that has nothing left to emit.** After folding, a *branch-free* `<clinit>`
 *    whose only remaining instructions are removed assignments, pure control ops, and its terminal
 *    `return-void` is [AttrFlag.DONT_GENERATE]'d so no empty `static { }` is emitted. A branchy
 *    `<clinit>` is never dropped (a branch could carry a conditional early return whose semantics must
 *    not be silently discarded); codegen suppresses only its terminal `return-void`.
 *
 * Runs per method (only acts on `<clinit>`) after structuring, reading the same block/instruction
 * stream codegen renders; `commonMain`, no reflection/threads. It mutates only fields of the
 * `<clinit>`'s own class (a `<clinit>` writes only its own class's statics), so it is safe under the
 * per-class parallel decompile.
 */
class StaticFieldInitPass : MethodPass {
    override val name: String get() = PASS_NAME
    override val runAfter: List<String> get() = listOf(PassNames.REGION_MAKER)

    override fun run(method: IrMethod, context: PassContext) {
        if (method.name != MethodRef.STATIC_INIT_NAME) return
        if (method.blocks.isEmpty()) return
        val cls = method.declaringClass

        val writes = collectOwnStaticPuts(cls, method)
        if (writes.isNotEmpty()) {
            val writeCount = HashMap<String, Int>()
            for (w in writes) writeCount[w.field.name] = (writeCount[w.field.name] ?: 0) + 1
            // jadx parity: a final field written here must not also keep its encoded ConstantValue.
            for (w in writes) if (isFinal(w.field)) w.field.constValue = null

            val foldable = foldableConstPuts(method, writes)
            for (w in writes) {
                if (!isFinal(w.field)) continue
                if (writeCount[w.field.name] != 1) continue
                if (w.insn !in foldable) continue
                val folded = constFromPut(w.insn, w.field.type) ?: continue
                w.field.constValue = folded
                w.insn.add(AttrFlag.DONT_GENERATE)
            }
        }

        dropIfNothingToEmit(method)
    }

    private class StaticPut(val block: BasicBlock, val insn: FieldInstruction, val field: IrField)

    private fun collectOwnStaticPuts(cls: IrClass, method: IrMethod): List<StaticPut> {
        val out = ArrayList<StaticPut>()
        for (block in method.blocks) {
            for (insn in block.instructions) {
                if (insn !is FieldInstruction || insn.opcode != IrOpcode.STATIC_PUT) continue
                val field = resolveOwnField(cls, insn.fieldRef) ?: continue
                out.add(StaticPut(block, insn, field))
            }
        }
        return out
    }

    /**
     * The subset of [writes] whose const value may be hoisted to the field declaration: the store's
     * block is on the unconditional single path AND no side-effecting (non-reorderable) instruction
     * precedes it on that path. Walks the single-path prefix in execution order tracking a `canReorder`
     * flag that flips off at the first non-reorderable instruction — a const store past that point (or
     * off the single path) is not foldable. **jadx: ExtractFieldInit.collectFieldsInit `canReorder`.**
     */
    private fun foldableConstPuts(method: IrMethod, writes: List<StaticPut>): Set<FieldInstruction> {
        val ownPuts = writes.map { it.insn }.toHashSet()
        val singlePath = singlePathBlocks(method)
        val foldable = HashSet<FieldInstruction>()
        var canReorder = true
        for (block in singlePath) {
            for (insn in block.instructions) {
                if (insn is FieldInstruction && insn in ownPuts) {
                    if (constFromPut(insn, insn.fieldRef.type) != null) {
                        // A pure const store is itself reorderable (does not flip canReorder); it is
                        // foldable only if nothing non-reorderable has preceded it.
                        if (canReorder) foldable.add(insn)
                    } else {
                        // A runtime-valued store is a side effect later const stores can't be hoisted past.
                        canReorder = false
                    }
                    continue
                }
                if (!isReorderable(insn)) canReorder = false
            }
        }
        return foldable
    }

    /**
     * True if [insn] has no side effect and observes no mutable state, so a const store may be moved
     * across it. Conservative allow-list (default: not reorderable) — anything that calls, reads/writes
     * a field or array, allocates, synchronizes, throws, or branches is a barrier. Nested sub-expression
     * operands are checked too, so e.g. `x = f()` (an arith wrapping an invoke) is a barrier.
     */
    private fun isReorderable(insn: Instruction): Boolean {
        if (insn.opcode !in REORDERABLE_OPCODES) return false
        return insn.args.all { operandReorderable(it) }
    }

    private fun operandReorderable(op: Operand): Boolean =
        op !is InstructionOperand || isReorderable(op.instruction)

    /** The [IrField] of [cls] named by [ref], or null if [ref] targets another class or is unknown. */
    private fun resolveOwnField(cls: IrClass, ref: FieldRef): IrField? {
        val owner = (ref.declaringType as? IrType.Object)?.className ?: return null
        if (owner != cls.fullName) return null
        return cls.fields.firstOrNull { it.name == ref.name }
    }

    /**
     * Blocks reachable from the entry along the *unconditional* single successor chain, i.e. blocks that
     * always execute exactly once before any branch, in execution order. An assignment outside this set
     * is conditional (or in a loop) and must not be hoisted. **jadx: BlockUtils.visitSinglePath**
     */
    private fun singlePathBlocks(method: IrMethod): Set<BasicBlock> {
        val result = LinkedHashSet<BasicBlock>()
        var block: BasicBlock? = method.entryBlock
        while (block != null && result.add(block)) {
            block = if (block.successors.size == 1) block.successors[0] else null
        }
        return result
    }

    /** The compile-time constant a static-put assigns, expressed for [fieldType], or null if not constant. */
    private fun constFromPut(insn: FieldInstruction, fieldType: IrType): IrFieldConst? {
        if (insn.argCount == 0) return null
        return constFromOperand(insn.getArg(insn.argCount - 1), fieldType)
    }

    private fun constFromOperand(op: Operand, fieldType: IrType): IrFieldConst? = when (op) {
        // A primitive/boolean/char literal; typed by the declared field so codegen renders `false`/'x'/…
        // A literal to a reference field is a `null` constant, which IrFieldConst cannot model — leave it.
        is LiteralOperand -> if (fieldType is IrType.Primitive) IrFieldConst.Primitive(op.value, fieldType) else null
        is InstructionOperand -> constFromInstruction(op.instruction, fieldType)
        else -> null
    }

    private fun constFromInstruction(insn: Instruction, fieldType: IrType): IrFieldConst? = when (insn.opcode) {
        // A `const` wraps its literal as arg 0 (matches codegen's CONST handling).
        IrOpcode.CONST -> if (insn.argCount > 0) constFromOperand(insn.getArg(0), fieldType) else null
        IrOpcode.CONST_STRING -> (insn as? ConstStringInstruction)?.let { IrFieldConst.Str(it.value) }
        else -> null
    }

    /**
     * Mark the whole `<clinit>` [AttrFlag.DONT_GENERATE] when nothing renderable remains — every
     * instruction is a folded (removed) assignment, a pure control op, or the terminal `return-void`.
     * Only a **branch-free** `<clinit>` is eligible: a branch may carry a conditional early return whose
     * semantics dropping would silently discard, so a branchy `<clinit>` is always kept.
     */
    private fun dropIfNothingToEmit(method: IrMethod) {
        if (method.blocks.any { it.successors.size > 1 }) return
        val hasRenderable = method.blocks.any { block -> block.instructions.any { isRenderable(it) } }
        if (!hasRenderable) method.add(AttrFlag.DONT_GENERATE)
    }

    private fun isRenderable(insn: Instruction): Boolean {
        if (insn.contains(AttrFlag.DONT_GENERATE)) return false
        return when (insn.opcode) {
            // Pure control / SSA bookkeeping ops never become a source statement (mirrors codegen's skip
            // set); a bare `return-void` is the terminal fall-off (codegen suppresses it in a static block).
            IrOpcode.NOP, IrOpcode.PHI, IrOpcode.MOVE_EXCEPTION,
            IrOpcode.MONITOR_ENTER, IrOpcode.MONITOR_EXIT, IrOpcode.GOTO,
            -> false
            IrOpcode.RETURN -> insn.argCount > 0
            else -> true
        }
    }

    private fun isFinal(field: IrField): Boolean = field.accessFlags and ACC_FINAL != 0

    companion object {
        /** Pass name; kept local (not in [PassNames]) so this stays a single new file. */
        const val PASS_NAME: String = "StaticFieldInit"

        /** JVM/DEX `ACC_FINAL`. */
        private const val ACC_FINAL = 0x0010

        /**
         * Opcodes with no side effect and no read of mutable state — safe to reorder a const store
         * across. Deliberately excludes every call, field/array access, allocation, monitor, throw,
         * switch, and branch (default-deny; see [isReorderable]).
         */
        private val REORDERABLE_OPCODES = setOf(
            IrOpcode.CONST, IrOpcode.CONST_STRING, IrOpcode.CONST_CLASS,
            IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG,
            IrOpcode.ARITH, IrOpcode.NEG, IrOpcode.NOT, IrOpcode.CAST,
            IrOpcode.CMP, IrOpcode.INSTANCE_OF, IrOpcode.TERNARY, IrOpcode.STRING_CONCAT,
            IrOpcode.NOP, IrOpcode.PHI, IrOpcode.GOTO, IrOpcode.RETURN,
        )
    }
}
