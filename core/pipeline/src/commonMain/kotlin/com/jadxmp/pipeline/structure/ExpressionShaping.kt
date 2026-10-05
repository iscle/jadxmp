package com.jadxmp.pipeline.structure

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.pipeline.InstructionEffects
import com.jadxmp.pipeline.pass.CancellationCheck

/**
 * Minimal, **provably non-lossy** expression shaping: folds a single-use value's definition into the
 * one place it is read, turning flat register code into small expression trees.  **jadx: CodeShrink /
 * the InsnWrapArg inlining (a conservative subset).**
 *
 * The point is readability *and* correctness of conditions: a compare that feeds an `if` should become
 * the condition expression, not a bare `x = a < b;` statement left dangling. This runs *before*
 * structuring so the branch instruction already carries the inlined expression when [RegionMaker]
 * reads its operands into a `Condition`.
 *
 * Only single-use, uncoalesced values used later in the same block can be folded. Inert
 * computations may move freely; memory reads, calls and potentially throwing computations may
 * cross only inert instructions and inert earlier operands of the consuming instruction. The
 * latter matters after other definitions have already been folded into its argument list.
 * Coalesced source locals remain materialized so folding cannot reread a reassigned variable.
 */
internal class ExpressionShaping(
    private val method: IrMethod,
    private val cancellation: CancellationCheck = CancellationCheck.None,
) {
    fun run() {
        propagateCatchAliases()
        for (block in method.blocks) {
            cancellation.ensureActive()
            shapeBlock(block)
        }
    }

    /**
     * Keep an immutable caught exception as the source of its plain copies, including cross-block
     * rethrows. Java's precise-rethrow rule applies to the catch binding, but not to a fresh Throwable
     * local initialized from it. Coalesced locals are excluded: they can be reassigned after a copy,
     * in which case replacing that snapshot with a later read would change the thrown object.
     */
    private fun propagateCatchAliases() {
        var changed: Boolean
        do {
            changed = false
            for (block in method.blocks) {
                cancellation.ensureActive()
                val instructions = block.instructions.iterator()
                while (instructions.hasNext()) {
                    val move = instructions.next()
                    if (move.opcode != IrOpcode.MOVE || move.argCount != 1) continue
                    val sourceArg = move.getArg(0) as? RegisterOperand ?: continue
                    val source = sourceArg.ssaValue ?: continue
                    val value = move.result?.ssaValue ?: continue
                    if (source.assign.parent?.opcode != IrOpcode.MOVE_EXCEPTION) continue
                    if (source.localVar?.ssaValues?.size?.let { it > 1 } == true) continue
                    if (value.localVar?.ssaValues?.size?.let { it > 1 } == true) continue
                    if (source.type != value.type) continue
                    val uses = value.uses.toList()
                    if (uses.any { use -> use.parent?.args?.none { it === use } != false }) continue
                    for (use in uses) {
                        val replacement = RegisterOperand(source.regNum, use.type)
                        use.parent!!.replaceArg(use, replacement) // the preflight above proves ownership
                        value.removeUse(use)
                        source.addUse(replacement)
                    }
                    source.removeUse(sourceArg)
                    method.ssaValues.remove(value)
                    instructions.remove()
                    changed = true
                }
            }
        } while (changed)
    }

    private fun shapeBlock(block: BasicBlock) {
        // Repeat to a fixpoint so chains collapse (a def inlined into another inlinable def, e.g. a
        // compare feeding an if whose operand is itself a pure sub-expression).
        var changed = true
        while (changed) {
            changed = false
            val insns = block.instructions
            var i = insns.size - 1
            while (i >= 0) {
                val def = insns[i]
                if (tryInline(block, def, i)) {
                    changed = true
                }
                i--
            }
        }
    }

    /** Try to fold [def] (at index [defIndex] in [block]) into its single use. Returns true if folded. */
    private fun tryInline(block: BasicBlock, def: Instruction, defIndex: Int): Boolean {
        val result = def.result ?: return false
        val value = result.ssaValue ?: return false
        if (value.useCount != 1) return false
        // A coalesced (merged) variable is a real local, not an inlinable temporary.
        val local = value.localVar
        if (local != null && local.ssaValues.size > 1) return false
        if (!isInlinable(def)) return false
        val effectful = !isInert(def)

        // Coalesced-variable read hazard: a def that reads a multiply-assigned variable cannot be safely
        // sunk to a later point (the variable may be reassigned in between). Since out-of-SSA ran, such
        // variables have `localVar.ssaValues.size > 1`.
        if (readsCoalescedVar(def)) return false

        val use = value.uses.single()
        val useInsn = use.parent ?: return false
        // The use must be a later instruction in the SAME block (single straight-line run).
        val useIndex = block.instructions.indexOf(useInsn)
        if (useIndex <= defIndex) return false
        // Do not inline into a φ (should already be gone) or into another already-wrapped position.
        if (useInsn.opcode == IrOpcode.PHI) return false
        // Floating CMP expands to ordered tests that read each operand more than once. Keep its
        // inputs materialized so calls, field reads and throwing expressions execute exactly once.
        if (useInsn.opcode == IrOpcode.CMP) return false

        // Earlier folded operands execute before this operand in source. Even an empty cross-set
        // can reorder effects: first(); second(); consume(second, first) must not become
        // consume(second(), first()). Check both the remaining statements and the use's prefix.
        if (effectful && (!crossSetIsInert(block, defIndex, useIndex) ||
                !earlierOperandsAreInert(useInsn, use))) return false

        // Fold: replace the reading operand with the def's instruction as a nested expression, and drop
        // the now-inlined statement. The wrapped instruction keeps its result (codegen renders the
        // expression and ignores the result slot for a wrapped operand).
        val wrapped = InstructionOperand(def)
        if (!useInsn.replaceArg(use, wrapped)) return false
        value.removeUse(use)
        block.instructions.removeAt(defIndex)
        return true
    }

    /** Whether [insn] reads a coalesced (multiply-assigned) variable, recursively through sub-expressions. */
    private fun readsCoalescedVar(insn: Instruction): Boolean {
        for (k in 0 until insn.argCount) {
            when (val arg = insn.getArg(k)) {
                is RegisterOperand ->
                    if (arg.ssaValue?.localVar?.let { it.ssaValues.size > 1 } == true) return true
                is InstructionOperand ->
                    if (readsCoalescedVar(arg.instruction)) return true
                else -> {}
            }
        }
        return false
    }

    /** Shapes with expression syntax; allocation remains owned by constructor reconstruction. */
    private fun isInlinable(insn: Instruction): Boolean = when (insn.opcode) {
        IrOpcode.CONST, IrOpcode.CONST_STRING, IrOpcode.CONST_CLASS,
        IrOpcode.ARITH, IrOpcode.NEG, IrOpcode.NOT, IrOpcode.MOVE, IrOpcode.CAST,
        IrOpcode.INSTANCE_OF, IrOpcode.CMP, IrOpcode.ARRAY_LENGTH, IrOpcode.ONE_ARG,
        IrOpcode.INSTANCE_GET, IrOpcode.STATIC_GET, IrOpcode.ARRAY_GET,
        IrOpcode.CHECK_CAST, IrOpcode.INVOKE -> true
        else -> false
    }

    private fun earlierOperandsAreInert(use: Instruction, operand: RegisterOperand): Boolean {
        // ARRAY_PUT stores [value, array, index], but both emitters evaluate array[index] = value.
        val order = if (use.opcode == IrOpcode.ARRAY_PUT) listOf(1, 2, 0) else use.args.indices.toList()
        for (index in order) {
            val arg = use.args.getOrNull(index) ?: return false
            if (arg === operand) return true
            if (arg is InstructionOperand && !isInert(arg.instruction)) return false
        }
        return false // A detached use is not proof of safe evaluation order.
    }

    /** Whether every instruction strictly between [defIndex] and [useIndex] in [block] is [isInert]. */
    private fun crossSetIsInert(block: BasicBlock, defIndex: Int, useIndex: Int): Boolean {
        val insns = block.instructions
        for (i in defIndex + 1 until useIndex) {
            if (!isInert(insns[i])) return false
        }
        return true
    }

    /**
     * An **inert** instruction: a non-throwing, memory-independent, side-effect-free register computation
     * (const, move, non-div arithmetic, comparison, primitive cast, …). Crossing only inert instructions
     * cannot change what a sunk def reads, cannot reorder a side effect, and cannot reorder an exception.
     * Recurses through wrapped sub-expressions — an ARITH that WRAPS a field-read/call is NOT inert.
     * Deliberately excludes memory reads (field/array get), calls, `check-cast`/`instance-of`/`const-class`
     * (throwing), `array-length` (NPE), div/rem (arithmetic exception), writes, monitors, and control flow.
     */
    private fun isInert(insn: Instruction): Boolean {
        if (InstructionEffects.mayThrow(insn)) return false
        val opcodeInert = when (insn.opcode) {
            IrOpcode.CONST, IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG,
            IrOpcode.NEG, IrOpcode.NOT, IrOpcode.CMP, IrOpcode.CAST, IrOpcode.ARITH -> true
            else -> false
        }
        if (!opcodeInert) return false
        for (k in 0 until insn.argCount) {
            val arg = insn.getArg(k)
            if (arg is InstructionOperand && !isInert(arg.instruction)) return false
        }
        return true
    }
}
