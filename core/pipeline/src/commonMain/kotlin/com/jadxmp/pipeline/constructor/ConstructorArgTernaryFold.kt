package com.jadxmp.pipeline.constructor

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.ConstStringInstruction
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.region.Condition
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.pipeline.pass.CancellationCheck

/**
 * Folds the *default-arguments* synthetic-constructor shape into a ternary-argument delegation.  **jadx:
 * TernaryMod + CodeShrink over a `this()`/`super()` delegation.**
 *
 * A Kotlin/Java compiler lowers a constructor with default parameter values into a synthetic
 * `<init>(…, int mask, …)` that mutates each defaulted argument with a guard *before* delegating:
 *
 * ```
 * if ((mask & 2) != 0) str2 = "";
 * if ((mask & 4) != 0) str3 = "";
 * if ((mask & 8) != 0) z = false;
 * this(str, str2, str3, z);
 * ```
 *
 * That is **illegal Java**: no statement may precede a `this()`/`super()` call in a constructor body.
 * jadx folds each one-branch conditional assignment into the delegation argument as a ternary:
 *
 * ```
 * this(str, (mask & 2) != 0 ? "" : str2, (mask & 4) != 0 ? "" : str3, (mask & 8) != 0 ? false : z);
 * ```
 *
 * This pass performs exactly that fold, and **only** when it is provably faithful:
 *  - the delegation is the first statement of its block and its receiver is `this`;
 *  - every statement that would precede it is a one-branch `if (cond) arg = <constant>` whose target is
 *    a delegation argument, with a **pure** condition (no reordered side effect);
 *  - the shared constants those guards assign are duplicated into the ternaries and their now-dead
 *    definitions removed, so nothing is left before the delegation.
 *
 * A ternary `cond ? v : arg` reproduces the guard exactly: `cond` is evaluated once (as the `if` did),
 * yields the assigned constant `v` when true and the argument's original value when false (the argument
 * is not otherwise reassigned between the guard and the delegation). Argument evaluation stays
 * left-to-right and every folded sub-expression is pure, so no side effect is added, dropped, or
 * reordered. Anything outside this envelope leaves the method **untouched** (no partial transform) —
 * codegen then renders the pre-existing, honestly-flagged shape rather than wrong code (rule 4).
 */
class ConstructorArgTernaryFold(
    private val method: IrMethod,
    private val cancellation: CancellationCheck = CancellationCheck.None,
) {
    /** A validated fold of one guard into one delegation argument. */
    private class Fold(
        val argIndex: Int,
        val condition: Operand,
        val trueValue: Operand,
        val originalArg: RegisterOperand,
        val ifRegion: IfRegion,
        /** The `MOVE` whose source constant is duplicated (so its dead source def can be dropped), or null. */
        val moveConstSource: RegisterOperand?,
    )

    fun run() {
        if (method.name != CONSTRUCTOR_NAME) return
        val region = method.region as? SequenceRegion ?: return
        cancellation.ensureActive()

        val children = region.children
        val delegIndex = children.indexOfFirst { it is BasicBlock && delegationInvoke(it) != null }
        if (delegIndex < 0) return
        val delegBlock = children[delegIndex] as BasicBlock
        val deleg = delegationInvoke(delegBlock) ?: return

        // The delegation must be the first emitting statement of its block: anything emitting before it
        // would also sit before `this()` and is not covered by this fold.
        if (firstEmitting(delegBlock) !== deleg) return

        // Validate & plan a fold for every guard that precedes the delegation. Bail (untouched) on the
        // first thing that is not a foldable guard, so the transform is all-or-nothing. Pre-delegation
        // blocks may hold only consumed branch `if`s and `const`/`const-string` defs (candidates for
        // removal, checked below); any other statement is unfoldable.
        val folds = ArrayList<Fold>()
        val foldedMoves = HashSet<Instruction>()
        for (i in 0 until delegIndex) {
            when (val child = children[i]) {
                is BasicBlock -> if (child.instructions.any { isEmitting(it) && !isConstDef(it) }) return
                is IfRegion -> {
                    val fold = planFold(child, deleg) ?: return
                    folds.add(fold)
                    fold.moveConstSource?.parent?.let { foldedMoves.add(it) }
                }
                else -> return
            }
        }
        if (folds.isEmpty()) return
        // Two guards mutating the SAME delegation argument would each rewrite one arg slot, dropping the
        // earlier fold — never fold that ambiguous shape.
        if (folds.map { it.argIndex }.toHashSet().size != folds.size) return

        // Cross-guard reordering hazard: each fold REMOVES its guard's assignment, so a later ternary that
        // reads a register another guard assigns would observe the stale pre-guard value (e.g.
        // `if (p!=0) r=0; if (r!=0) s="";` — the second condition must read the UPDATED r). If any fold's
        // condition or assigned value reads a register that any folded guard targets, bail. (The real
        // default-args shape reads only the synthetic mask register, never a delegation-arg target.)
        val targets = folds.mapTo(HashSet()) { it.originalArg.regNum }
        for (fold in folds) {
            val reads = HashSet<Int>()
            collectRegisterReads(fold.condition, reads)
            collectRegisterReads(fold.trueValue, reads)
            if (reads.any { it in targets }) return
        }

        // A shared constant fed into a guarded `move` may only be dropped if *every* read of it is one of
        // the folded moves; otherwise removing its def would strand a live read. If it can't be dropped it
        // would remain as a statement before `this()`, so bail.
        val constDefsToRemove = HashSet<Instruction>()
        for (fold in folds) {
            val src = fold.moveConstSource ?: continue
            val srcValue = src.ssaValue ?: return
            if (srcValue.uses.any { it.parent !in foldedMoves }) return
            val def = srcValue.assign.parent ?: return
            if (!isConstDef(def)) return
            // The removal sweep below only visits direct child blocks; a const defined elsewhere would not
            // be dropped (leaving a statement before `this()`), so require it to be a direct child.
            if (children.none { it is BasicBlock && def in it.instructions }) return
            constDefsToRemove.add(def)
        }

        // Final gate before mutating: every emitting statement left in a pre-delegation block must be a
        // consumed branch `if` or a const we are about to drop. A live const that survives would sit
        // before `this()` (illegal), so bail rather than emit it.
        for (i in 0 until delegIndex) {
            val block = children[i] as? BasicBlock ?: continue
            for (insn in block.instructions) {
                if (!isEmitting(insn)) continue
                if (insn is IfInstruction) continue
                if (insn !in constDefsToRemove) return
            }
        }

        // ---- commit ----
        for (fold in folds) {
            val ternary = Instruction(
                IrOpcode.TERNARY,
                result = null,
                args = listOf(fold.condition, fold.trueValue, fold.originalArg),
            )
            deleg.setArg(fold.argIndex, InstructionOperand(ternary))
            region.children.remove(fold.ifRegion)
        }
        // Drop the consumed guard branches and the now-dead shared constants from the surviving blocks.
        for (i in 0 until region.children.size) {
            val block = region.children.getOrNull(i) as? BasicBlock ?: continue
            block.instructions.removeAll { insn ->
                insn.contains(AttrFlag.DONT_GENERATE) && insn is IfInstruction || insn in constDefsToRemove
            }
        }
    }

    /** The `this()`/`super()` delegation invoke in [block], or null. */
    private fun delegationInvoke(block: BasicBlock): InvokeInstruction? {
        for (insn in block.instructions) {
            val invoke = insn as? InvokeInstruction ?: continue
            if (invoke.opcode != IrOpcode.INVOKE) continue
            if (!invoke.methodRef.isConstructor) continue
            val receiver = invoke.instanceArg as? RegisterOperand ?: continue
            if (isThis(receiver)) return invoke
        }
        return null
    }

    private fun isThis(reg: RegisterOperand): Boolean {
        val sv = reg.ssaValue ?: return false
        return sv === method.thisArg || sv.localVar?.isThis == true
    }

    /** Validate that [ifRegion] is a one-branch guard assigning a constant to a delegation argument. */
    private fun planFold(ifRegion: IfRegion, deleg: InvokeInstruction): Fold? {
        if (ifRegion.elseRegion != null) return null
        val assign = singleEmitting(ifRegion.thenRegion) ?: return null
        val target = assign.result ?: return null

        // The guarded register must be a delegation argument (never the receiver). If it feeds MORE than
        // one argument slot (`this(x, x)`), folding only the first slot would leave the second reading the
        // now-unguarded original value — a silent miscompile — so bail on that shape.
        if (deleg.args.count { it is RegisterOperand && it.regNum == target.regNum } != 1) return null
        val argIndex = deleg.args.indexOfFirst { it is RegisterOperand && it.regNum == target.regNum }
        if (argIndex <= 0) return null
        val originalArg = deleg.getArg(argIndex) as? RegisterOperand ?: return null

        val condition = conditionOperand(ifRegion.condition) ?: return null
        if (!isPure(condition)) return null

        // The assigned value must be a duplicable constant so folding it into the ternary (and, for a
        // shared const, dropping its def) cannot change or reorder any effect.
        return when (assign.opcode) {
            IrOpcode.CONST, IrOpcode.CONST_STRING -> {
                val value = duplicateConst(assign) ?: return null
                Fold(argIndex, condition, value, freshLike(originalArg), ifRegion, moveConstSource = null)
            }
            IrOpcode.MOVE -> {
                val src = assign.getArg(0) as? RegisterOperand ?: return null
                val srcDef = src.ssaValue?.assign?.parent ?: return null
                val value = duplicateConst(srcDef) ?: return null
                Fold(argIndex, condition, value, freshLike(originalArg), ifRegion, moveConstSource = src)
            }
            else -> null
        }
    }

    /** A detached duplicate of a `const`/`const-string` def, usable as a ternary operand. */
    private fun duplicateConst(def: Instruction): Operand? = when (def.opcode) {
        IrOpcode.CONST_STRING ->
            (def as? ConstStringInstruction)?.let { InstructionOperand(ConstStringInstruction(it.value)) }
        IrOpcode.CONST -> (def.args.getOrNull(0) as? LiteralOperand)?.let { LiteralOperand(it.value, it.type) }
        else -> null
    }

    /** Convert an if-region condition into a pure boolean operand codegen can render inline. */
    private fun conditionOperand(cond: Condition): Operand? = when (cond) {
        is Condition.Compare -> InstructionOperand(IfInstruction(cond.op, listOf(cond.left, cond.right)))
        is Condition.Not -> {
            val inner = cond.negated
            if (inner is Condition.Compare) {
                InstructionOperand(IfInstruction(inner.op.negate(), listOf(inner.left, inner.right)))
            } else {
                null
            }
        }
        is Condition.BoolTest -> cond.operand
        // And/Or short-circuit trees are not folded here: keeping the guard is safer than risking a
        // mis-rendered or effect-reordered compound condition.
        is Condition.And, is Condition.Or -> null
    }

    /** Side-effect-free: only value-producing opcodes, transitively. Bars memory reads and calls. */
    private fun isPure(op: Operand): Boolean = when (op) {
        is RegisterOperand, is LiteralOperand -> true
        is InstructionOperand -> {
            val insn = op.instruction
            insn.opcode in PURE_OPCODES && (0 until insn.argCount).all { isPure(insn.getArg(it)) }
        }
    }

    /** Register numbers READ (not defined) anywhere in [op]'s operand tree. */
    private fun collectRegisterReads(op: Operand, out: MutableSet<Int>) {
        when (op) {
            is RegisterOperand -> out.add(op.regNum)
            is LiteralOperand -> Unit
            is InstructionOperand -> for (i in 0 until op.instruction.argCount) {
                collectRegisterReads(op.instruction.getArg(i), out)
            }
        }
    }

    /** A fresh register operand mirroring [reg] (same value), so the original arg operand is not aliased. */
    private fun freshLike(reg: RegisterOperand): RegisterOperand =
        RegisterOperand(reg.regNum, reg.type).also { it.ssaValue = reg.ssaValue }

    /** The single emitting instruction of a (possibly nested) region, or null if not exactly one. */
    private fun singleEmitting(region: Region): Instruction? {
        val found = ArrayList<Instruction>(1)
        collectEmitting(region, found)
        return found.singleOrNull()
    }

    private fun collectEmitting(region: Region, out: MutableList<Instruction>) {
        when (region) {
            is SequenceRegion -> for (c in region.children) {
                when (c) {
                    is BasicBlock -> for (insn in c.instructions) if (isEmitting(insn)) out.add(insn)
                    is Region -> collectEmitting(c, out)
                    else -> out.add(Instruction(IrOpcode.NOP)) // unknown container ⇒ force "not single"
                }
            }
            is IfRegion -> out.add(Instruction(IrOpcode.NOP)) // nested control flow ⇒ not a simple guard
            else -> out.add(Instruction(IrOpcode.NOP))
        }
    }

    private fun firstEmitting(block: BasicBlock): Instruction? = block.instructions.firstOrNull { isEmitting(it) }

    private fun isConstDef(insn: Instruction): Boolean =
        insn.opcode == IrOpcode.CONST || insn.opcode == IrOpcode.CONST_STRING

    /** Whether [insn] renders as a source statement (mirrors codegen's statement suppression). */
    private fun isEmitting(insn: Instruction): Boolean {
        if (insn.contains(AttrFlag.DONT_GENERATE)) return false
        return insn.opcode !in NON_EMITTING_OPCODES
    }

    private companion object {
        const val CONSTRUCTOR_NAME = "<init>"

        val NON_EMITTING_OPCODES = setOf(
            IrOpcode.GOTO, IrOpcode.NOP, IrOpcode.PHI, IrOpcode.MOVE_EXCEPTION,
            IrOpcode.MONITOR_ENTER, IrOpcode.MONITOR_EXIT,
        )

        val PURE_OPCODES = setOf(
            IrOpcode.ARITH, IrOpcode.NEG, IrOpcode.NOT, IrOpcode.CMP, IrOpcode.CAST,
            IrOpcode.CONST, IrOpcode.CONST_STRING, IrOpcode.CONST_CLASS, IrOpcode.IF,
            IrOpcode.INSTANCE_OF, IrOpcode.TERNARY, IrOpcode.MOVE,
        )
    }
}
