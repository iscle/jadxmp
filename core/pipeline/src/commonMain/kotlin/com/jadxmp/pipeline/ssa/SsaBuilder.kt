package com.jadxmp.pipeline.ssa

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.PhiInstruction
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.pass.CancellationCheck

/**
 * Builds pruned SSA form over a finished, dominator-analysed CFG.  **jadx: SSATransform.**
 *
 * Three classic phases (Cytron et al., pruned by liveness so no dead φ appear):
 * 1. **Liveness** — live-in per block per register, plus the set of blocks that define each register
 *    (including the entry block, which "defines" the parameters).
 * 2. **φ placement** — for each register, walk the iterated dominance frontier of its defining blocks
 *    and insert a φ wherever the register is live-in.
 * 3. **Renaming** — a dominator-tree walk with per-register version state, giving every definition a
 *    fresh [SsaValue] and pointing every use at the value reaching it; φ arguments are bound from the
 *    predecessor's end-of-block state (tracked with the source edge on [PhiInstruction.incoming]).
 *
 * A final cleanup removes useless φ (single distinct argument / self-referential / unused) and unused
 * invoke results, so the SSA shape is minimal and deterministic — type inference and variable merging
 * depend on that exact shape, so it is locked with tests.
 */
class SsaBuilder(
    private val method: IrMethod,
    private val registerCount: Int,
    private val cancellation: CancellationCheck = CancellationCheck.None,
) {
    private val blocks: List<BasicBlock> get() = method.blocks
    private val versionCounters = IntArray(registerCount)

    // Parameter/`this` initial SSA values, keyed by register.
    private val entryDefs = arrayOfNulls<SsaValue>(registerCount)

    fun build() {
        if (registerCount == 0) return
        val params = MethodParams.of(method, registerCount)
        seedParameters(params)

        val liveness = Liveness(method, registerCount, entryRegisters())
        liveness.run(cancellation)

        placePhis(liveness)
        rename()
        cleanupUselessPhis()
        removeUnusedResults()
    }

    // ---- parameters ---------------------------------------------------------

    private fun seedParameters(params: MethodParams) {
        val paramValues = ArrayList<SsaValue>(params.paramRegs.size)
        if (params.thisReg >= 0) {
            val thisType = params.thisType(method)
            val v = defineParam(params.thisReg, thisType)
            v.typeCell.fix(thisType)
            method.thisArg = v
        }
        for (i in params.paramRegs.indices) {
            val reg = params.paramRegs[i]
            val t = method.argTypes.getOrElse(i) { IrType.UNKNOWN }
            val v = defineParam(reg, t)
            if (t.isTypeKnown) v.typeCell.fix(t)
            paramValues.add(v)
        }
        method[PipelineAttrs.PARAMETERS] = paramValues
    }

    private fun defineParam(reg: Int, type: IrType): SsaValue {
        // The parameter's defining occurrence is a detached operand (no owning instruction).
        val assign = RegisterOperand(reg, type)
        val v = SsaValue(reg, versionCounters[reg]++, assign)
        entryDefs[reg] = v
        method.ssaValues.add(v)
        return v
    }

    private fun entryRegisters(): Set<Int> {
        val s = HashSet<Int>()
        for (r in entryDefs.indices) if (entryDefs[r] != null) s.add(r)
        return s
    }

    // ---- φ placement --------------------------------------------------------

    private fun placePhis(liveness: Liveness) {
        val blocksById = blocks.associateBy { it.id }
        for (reg in 0 until registerCount) {
            cancellation.ensureActive()
            val defBlocks = liveness.defBlocks(reg)
            if (defBlocks.isEmpty()) continue
            val hasPhi = HashSet<Int>()
            val processed = HashSet<Int>()
            val worklist = ArrayDeque<BasicBlock>()
            for (id in defBlocks) {
                blocksById[id]?.let {
                    processed.add(id)
                    worklist.addLast(it)
                }
            }
            while (worklist.isNotEmpty()) {
                val block = worklist.removeLast()
                for (df in block.dominanceFrontier) {
                    if (df.id in hasPhi) continue
                    if (!liveness.isLiveIn(df.id, reg)) continue
                    addPhi(df, reg)
                    hasPhi.add(df.id)
                    if (processed.add(df.id)) worklist.addLast(df)
                }
            }
        }
    }

    private fun addPhi(block: BasicBlock, reg: Int) {
        val phi = PhiInstruction(RegisterOperand(reg, IrType.UNKNOWN))
        phi.offset = block.instructions.firstOrNull()?.offset ?: -1
        block.instructions.add(0, phi)
        phiList(block).add(phi)
    }

    private fun phiList(block: BasicBlock): MutableList<Instruction> {
        block[PipelineAttrs.PHI_LIST]?.let { return it }
        val list = ArrayList<Instruction>(2)
        block[PipelineAttrs.PHI_LIST] = list
        return list
    }

    // ---- renaming -----------------------------------------------------------

    private fun rename() {
        val entry = method.entryBlock ?: return
        val stack = ArrayDeque<Pair<BasicBlock, Array<SsaValue?>>>()
        stack.addLast(entry to entryDefs.copyOf())
        while (stack.isNotEmpty()) {
            cancellation.ensureActive()
            val (block, incoming) = stack.removeLast()
            val cur = incoming.copyOf()
            renameBlock(block, cur)
            for (child in block.dominatedBlocks) {
                stack.addLast(child to cur.copyOf())
            }
        }
    }

    private fun renameBlock(block: BasicBlock, cur: Array<SsaValue?>) {
        for (insn in block.instructions) {
            if (insn is PhiInstruction) {
                defineResult(insn.result!!, cur)
                continue
            }
            for (i in 0 until insn.argCount) {
                val arg = insn.getArg(i)
                if (arg is RegisterOperand) useRegister(arg, cur)
            }
            insn.result?.let { defineResult(it, cur) }
        }
        // Bind this block's contribution to each successor φ.
        //
        // TODO(before inlining): try-catch φ imprecision. When the predecessor is a protected block that
        // reassigns `reg` and can throw *between* the two assignments, `cur[reg]` here is the later
        // version, but at runtime a handler entered mid-block sees the earlier one — so the handler φ can
        // bind the wrong SSA version. This is harmless in Phase 2 because every version of a register
        // still collapses to one source CodeVar (so `catch { use(reg) }` reads a correct value), but it
        // becomes wrong once a pass trusts SSA def-identity (inlining / type-splitting). The real fix is
        // jadx's approach: drop the try-leaving last-assign from handler φ args, or split each protected
        // block at every definition so exception edges leave from the exact program point. See
        // SsaTryCatchTest.handlerPhiBindsLastAssignWithinProtectedBlock for the documented current behaviour.
        for (succ in block.successors) {
            val phis = succ[PipelineAttrs.PHI_LIST] ?: continue
            for (phi in phis) {
                phi as PhiInstruction
                val reg = phi.result!!.regNum
                val reaching = cur[reg] ?: syntheticUndef(reg)
                // A φ operand is a PhiOperand carrying this predecessor block (its source edge travels
                // with the value, so value/block cannot desync under later operand rewrites).
                val use = phi.addIncoming(reg, IrType.UNKNOWN, block)
                reaching.addUse(use)
            }
        }
    }

    private fun useRegister(operand: RegisterOperand, cur: Array<SsaValue?>) {
        val reg = operand.regNum
        val v = cur[reg] ?: syntheticUndef(reg).also { cur[reg] = it }
        v.addUse(operand)
    }

    private fun defineResult(operand: RegisterOperand, cur: Array<SsaValue?>) {
        val reg = operand.regNum
        val v = SsaValue(reg, versionCounters[reg]++, operand)
        method.ssaValues.add(v)
        cur[reg] = v
    }

    /** A value for a read of a register with no reaching definition (malformed/dead code). */
    private fun syntheticUndef(reg: Int): SsaValue {
        val v = SsaValue(reg, versionCounters[reg]++, RegisterOperand(reg, IrType.UNKNOWN))
        method.ssaValues.add(v)
        return v
    }

    // ---- cleanup ------------------------------------------------------------

    private fun cleanupUselessPhis() {
        // Each pass that changes anything removes at least one φ, and φ are never added here, so this
        // terminates in at most (φ count) passes. No iteration cap — a cap could leave a useless φ in
        // place, distorting the SSA shape that type inference and variable merging depend on.
        var changed = true
        while (changed) {
            cancellation.ensureActive()
            changed = false
            for (block in blocks) {
                val phis = block[PipelineAttrs.PHI_LIST] ?: continue
                val it = phis.iterator()
                while (it.hasNext()) {
                    val phi = it.next() as PhiInstruction
                    val resVar = phi.result!!.ssaValue!!
                    val distinct = distinctArgVars(phi, resVar)
                    if (distinct.size <= 1) {
                        val replacement = distinct.firstOrNull()
                        replacePhi(phi, resVar, replacement)
                        it.remove()
                        block.instructions.remove(phi)
                        changed = true
                    }
                }
                if (phis.isEmpty()) block.remove(PipelineAttrs.PHI_LIST)
            }
        }
    }

    private fun distinctArgVars(phi: PhiInstruction, resVar: SsaValue): List<SsaValue> {
        val seen = LinkedHashSet<SsaValue>()
        for (i in 0 until phi.argCount) {
            val v = (phi.getArg(i) as RegisterOperand).ssaValue ?: continue
            if (v === resVar) continue // ignore self-reference (loop φ)
            seen.add(v)
        }
        return seen.toList()
    }

    /** Redirect all uses of the φ result to [replacement] (or drop them if the φ was fully dead). */
    private fun replacePhi(phi: PhiInstruction, resVar: SsaValue, replacement: SsaValue?) {
        // Detach the φ's own argument uses from their defining values.
        for (i in 0 until phi.argCount) {
            (phi.getArg(i) as RegisterOperand).ssaValue?.removeUse(phi.getArg(i) as RegisterOperand)
        }
        // Repoint every use of the φ result to the replacement value.
        val uses = ArrayList(resVar.uses)
        for (use in uses) {
            resVar.removeUse(use)
            if (replacement != null) {
                use.ssaValue = replacement
                replacement.addUse(use)
            }
        }
        method.ssaValues.remove(resVar)
    }

    /**
     * Strip results (and, for pure defs, whole instructions) that nothing reads.
     *
     *  - A dead **INVOKE** keeps its side effect: its result is nulled so it still renders as a bare
     *    call statement (existing behavior).
     *  - A dead **pure, non-throwing** def (`u = a + b`, a `cmp`, a cast, …) is genuinely dead code:
     *    the whole instruction is removed (standard DCE). This cleans up output AND closes a duplication
     *    scope hole — a dead def left with a result would emit `int u = a + b;` in one copy of a
     *    duplicated block and a bare, out-of-scope `u = a + b;` in the sibling copy (a silent miscompile,
     *    since codegen keys declaration off `result != null`, not liveness).
     *
     * Only provably side-effect-free, non-throwing opcodes are removed — never an invoke/field-or-array
     * write/monitor/allocation, nor a potentially-throwing op: an array-length/-get or check-cast (NPE /
     * ClassCastException), a **const-class or instance-of** (a linkage error — NoClassDefFoundError /
     * IllegalAccessError — on an unresolvable or inaccessible type), or a div/rem (ArithmeticException on
     * a zero divisor). Removal detaches the def's own operand uses, so a value that becomes dead as a
     * result is swept on the next iteration (a fixpoint).
     */
    private fun removeUnusedResults() {
        var changed = true
        while (changed) {
            changed = false
            val it = method.ssaValues.iterator()
            while (it.hasNext()) {
                val v = it.next()
                if (v.useCount != 0) continue
                val def = v.assign.parent ?: continue
                when {
                    def.opcode == IrOpcode.INVOKE -> {
                        def.result = null
                        it.remove()
                    }
                    isRemovableDeadDef(def) -> {
                        detachOperandUses(def)
                        removeFromBlock(def)
                        it.remove()
                        changed = true
                    }
                }
            }
        }
    }

    /** A provably side-effect-free, non-throwing def whose dead result makes the whole instruction dead. */
    private fun isRemovableDeadDef(def: Instruction): Boolean = when (def.opcode) {
        // NB: CONST_CLASS and INSTANCE_OF are deliberately EXCLUDED — both can raise a linkage error on an
        // unresolvable/inaccessible type, so they are kept (falling through to `else`).
        IrOpcode.CONST, IrOpcode.CONST_STRING,
        IrOpcode.MOVE, IrOpcode.NEG, IrOpcode.NOT,
        IrOpcode.CAST, IrOpcode.CMP,
        -> true
        // Arithmetic is pure EXCEPT integer div/rem, which throw ArithmeticException on a zero divisor —
        // removing those would drop an observable exception, so they are kept.
        IrOpcode.ARITH -> {
            val op = (def as? com.jadxmp.ir.insn.ArithInstruction)?.op
            op != null && op != com.jadxmp.ir.insn.ArithOp.DIV && op != com.jadxmp.ir.insn.ArithOp.REM
        }
        else -> false
    }

    /** Detach [insn]'s register operand uses from the values they read (keeps use-counts accurate). */
    private fun detachOperandUses(insn: Instruction) {
        for (i in 0 until insn.argCount) {
            val arg = insn.getArg(i)
            if (arg is RegisterOperand) arg.ssaValue?.removeUse(arg)
        }
    }

    private fun removeFromBlock(insn: Instruction) {
        for (block in blocks) {
            if (block.instructions.remove(insn)) return
        }
    }
}
