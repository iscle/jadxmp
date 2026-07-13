package com.jadxmp.pipeline.structure

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
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
 * ## Safety envelope (why this never changes semantics)
 * Only a definition that is **all** of the following is inlined:
 *  - **single-use** — its SSA value is read exactly once (so folding cannot duplicate a computation);
 *  - **not coalesced** — its value does not share a [com.jadxmp.ir.node.LocalVar] with another version
 *    (a merged variable is a real, multiply-assigned local, never an expression);
 *  - **pure** — a side-effect-free, memory-independent opcode (const/arith/compare/cast/…). Memory
 *    reads and calls are left as named statements: reordering them past other effects is not provably
 *    safe here, so per the cardinal rule we keep the uglier-but-correct form;
 *  - **used later in the same block** — the def and use live in one straight-line run, so no control
 *    flow sits between them.
 *
 * SSA guarantees the def's own operands are immutable values, so sinking a pure def down to its use
 * within a block cannot observe a different input. Anything outside this envelope is deliberately not
 * touched.
 */
internal class ExpressionShaping(
    private val method: IrMethod,
    private val cancellation: CancellationCheck = CancellationCheck.None,
) {
    fun run() {
        for (block in method.blocks) {
            cancellation.ensureActive()
            shapeBlock(block)
        }
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
        val pure = isPure(def)
        val effectful = isEffectSensitiveInlinable(def)
        if (!pure && !effectful) return false

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

        // Order-preservation (rule 4): a memory READ, a CALL, or a CHECK_CAST observes/mutates memory or
        // throws, so sinking it to its use may only cross INERT instructions — non-throwing, memory-
        // independent, side-effect-free register computations. Then the reorder cannot change what the def
        // reads, cannot reorder a side effect, and cannot reorder an exception. Bottom-up folding makes
        // most cross-sets empty (adjacent reads/calls collapse first). Pure defs keep their prior envelope.
        if (effectful && !crossSetIsInert(block, defIndex, useIndex)) return false

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

    /**
     * A pure opcode: side-effect-free and independent of mutable memory, so it may be freely sunk to
     * its use within a block. Explicitly excludes memory reads (field/array get), calls, monitors,
     * array/field writes, control flow, and `check-cast` (which can throw and so is order-sensitive).
     */
    private fun isPure(insn: Instruction): Boolean = when (insn.opcode) {
        IrOpcode.CONST, IrOpcode.CONST_STRING, IrOpcode.CONST_CLASS,
        IrOpcode.ARITH, IrOpcode.NEG, IrOpcode.NOT,
        IrOpcode.MOVE, IrOpcode.CAST, IrOpcode.INSTANCE_OF,
        IrOpcode.CMP, IrOpcode.ARRAY_LENGTH, IrOpcode.ONE_ARG,
        -> true
        else -> false
    }

    /**
     * Effect-sensitive shapes that jadx inlines into their single use so conditions/expressions read
     * `this.a == null`, `(T) x`, `a.equals(b)` instead of dangling `t = …` statements. Unlike [isPure]
     * these observe/mutate memory or throw, so they are inlined ONLY when [crossSetIsInert] proves the
     * sink is order-preserving. `CONSTRUCTOR` is deliberately excluded (object creation has its own
     * reconstruction and per-arm materialization).
     */
    private fun isEffectSensitiveInlinable(insn: Instruction): Boolean = when (insn.opcode) {
        IrOpcode.INSTANCE_GET, IrOpcode.STATIC_GET, IrOpcode.ARRAY_GET,
        IrOpcode.CHECK_CAST, IrOpcode.INVOKE,
        -> true
        else -> false
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
        val opcodeInert = when (insn.opcode) {
            IrOpcode.CONST, IrOpcode.CONST_STRING, IrOpcode.MOVE, IrOpcode.MOVE_RESULT,
            IrOpcode.ONE_ARG, IrOpcode.NEG, IrOpcode.NOT, IrOpcode.CMP, IrOpcode.CAST,
            -> true
            IrOpcode.ARITH -> {
                val op = (insn as? com.jadxmp.ir.insn.ArithInstruction)?.op
                op != com.jadxmp.ir.insn.ArithOp.DIV && op != com.jadxmp.ir.insn.ArithOp.REM
            }
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
