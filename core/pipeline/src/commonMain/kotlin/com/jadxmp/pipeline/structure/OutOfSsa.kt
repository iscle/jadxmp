package com.jadxmp.pipeline.structure

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.PhiInstruction
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.LocalVar
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.pass.CancellationCheck

/**
 * SSA destruction: removes every [PhiInstruction] so the method has ordinary def/use variables that
 * codegen can render.  **jadx: the SSA→CodeVar merge in `SSATransform`/`CodeVar` + φ handling.**
 *
 * ## Why
 * The analysis pipeline hands off *pruned SSA* — a φ at every control-flow merge. Codegen renders
 * source-level variables ([LocalVar]s), not φ. This pass bridges the two: after it runs no φ remains
 * and every SSA value that participates in a merge shares one [LocalVar] with the others it merges
 * with, so all versions of one source variable print under one name.
 *
 * ## How (standard SSA destruction with copy insertion, coalescing the trivial case)
 * For each φ-connected component (the φ result unioned with all its operands, transitively via
 * φ-of-φ), we decide between two provably-correct modes:
 *
 *  1. **Coalesce** (the common case, and every case that arises from ordinary Java control flow): if
 *     no two members of the component are *simultaneously live* (interference-free), they can share a
 *     single variable with **no copies at all** — the register-versioned SSA collapses straight back to
 *     the original source variable. This yields the clean `x = …; … x …` form and never a redundant
 *     `x = x`.
 *
 *  2. **Copy insertion** (fallback for a component whose members interfere — e.g. a value still live
 *     across the merge it feeds, or a parallel swap): give each φ result its own variable and, on every
 *     predecessor edge, move the incoming value into that variable, then drop the φ. This is always
 *     semantics-preserving **provided** the edge is not critical and the per-edge copy set has no
 *     write-before-read conflict; when either does not hold we **bail** (leave φ removed but the method
 *     unstructured) rather than risk wrong output — the cardinal non-lossy rule.
 *
 * Interference is decided with per-SSA-value liveness (single-variable backward flow from uses to the
 * unique def, φ operands counted as a use at the end of their predecessor) and half-open live segments
 * so a value that dies *into* the instruction that redefines it (`i = i + 1`) does not spuriously
 * interfere with its successor version.
 */
internal class OutOfSsa(
    private val method: IrMethod,
    private val cancellation: CancellationCheck = CancellationCheck.None,
    private val hierarchy: com.jadxmp.pipeline.types.ClassHierarchy? = null,
) {
    /** Thrown internally when SSA destruction cannot be done safely; the pass turns it into "no region". */
    class UnsupportedShapeException(message: String) : RuntimeException(message)

    private val blocks: List<BasicBlock> get() = method.blocks

    /** Block that defines each SSA value (its unique def). Params/synthetic defs map to the entry block. */
    private val defBlock = HashMap<SsaValue, BasicBlock>()

    /** LocalVars this pass creates that merge >1 SSA value; may need a hoisted declaration point. */
    private val mergedLocals = ArrayList<LocalVar>()

    fun run() {
        val phis = collectPhis()
        // Fast path: nothing merges, no try/catch, AND no loop — every value has a single normal def/use
        // whose declaration codegen can place on first assignment. A loop breaks that: a value defined in
        // a loop body but read after the loop needs its declaration hoisted OUT of the loop scope
        // ([hoistLoopEscapingDeclarations]), so a looping method must take the full path even with no φ.
        if (phis.isEmpty() && blocks.none { isProtected(it) } && !hasLoop()) return
        indexDefs()
        materializeSharedZeroConstMoves()

        if (phis.isNotEmpty()) {
            val groups = buildComponents(phis)
            for (group in groups) {
                cancellation.ensureActive()
                if (group.members.size <= 1 || !interferes(group)) {
                    val splitParam = conflictingParam(group)
                    if (splitParam != null) coalesceSplittingParam(group, splitParam) else coalesce(group)
                } else {
                    insertCopies(group)
                }
            }
            removePhis(phis)
        }
        // A variable that several SSA versions collapse into must be declared where the declaration is
        // visible to every use. When one def already dominates all occurrences (loop counters, params)
        // codegen's declare-on-first-assign is enough; otherwise (both arms of a diamond assign it) we
        // add a dominating default-init so the source declares it in an enclosing scope.
        ensureDominatingDeclarations()
        // A value DEFINED inside a `try {}` whose uses escape it (a `catch`, or after the try) must have
        // its declaration hoisted OUT of the try scope — source scope is narrower than dominance, so the
        // in-try declaration would be invisible outside. Without this the structuring stage bails.
        hoistTryEscapingDeclarations()
        // Same narrowing for a loop: a value DEFINED inside a loop body but read AFTER the loop is
        // dominated by its in-loop def, yet source-level loop scope hides it after the loop. Hoist its
        // declaration to a default-init BEFORE the loop so the post-loop read is in scope.
        hoistLoopEscapingDeclarations()
    }

    // ---- polymorphic-zero re-materialization -------------------------------

    /**
     * Re-materialize a **shared compile-time zero** at each move that copies it.
     *
     * A DEX `const 0` is a *polymorphic zero*: the same bit pattern is a null reference, `false`, `0`,
     * or `0L` depending on the consuming type. When one such zero is copied by a `move` into a value
     * that ends up in a **different** source variable (e.g. `const v2, 0; move v0, v2; move v1, v0` where
     * `v0` becomes a `List` local and `v1` a `String` local), coalescing forces the two variables to
     * share the zero's holder, and codegen prints the cross-type read `str = list` — which does not
     * compile. jadx instead gives each consumer its own zero, typed to that consumer (`list = null;
     * str = null;`). We do the same: rewrite `dst = move <zero-holder>` into `dst = const 0` typed to
     * `dst`, detaching the shared read so the two variables no longer alias.
     *
     * Safety (rule 4): only a **proven** compile-time zero — a `const 0`/`0L` reached transitively
     * through pure `move`s — is duplicated; a computed or loaded value is never re-materialized (that
     * would clone a side effect). We act only when the source is genuinely **shared** (has another use),
     * since an unshared move is a plain rename that this rewrite would not meaningfully change. The
     * rewritten instruction keeps the destination's own SSA value and def site, so [coalescingIsSound]'s
     * def-dominates-use invariant is preserved (the const sits exactly where the move did).
     *
     * Iteration-order note: when ONE zero directly feeds 2+ moves, rewriting the first drops the source's
     * use count to 1, so the next move sees an unshared source and is left as a `move`. That is harmless
     * and **self-healing**: the un-rewritten move now reads a single-use value, so [ExpressionShaping]
     * inlines the zero const into it and codegen renders the correctly-typed zero at the consumer anyway.
     * We only need to break the *aliasing* case (a zero shared with a still-live consumer), which is
     * exactly what the `useCount > 1` guard catches on the move that would otherwise print the cross-type
     * read.
     */
    private fun materializeSharedZeroConstMoves() {
        for (block in blocks) {
            val insns = block.instructions
            for (idx in insns.indices) {
                val insn = insns[idx]
                if (insn.opcode != IrOpcode.MOVE || insn.argCount != 1) continue
                val result = insn.result ?: continue
                val srcOp = insn.getArg(0) as? RegisterOperand ?: continue
                val src = srcOp.ssaValue ?: continue
                if (src.useCount <= 1) continue // not shared — a plain rename, nothing to break apart
                if (!isProvenZero(src)) continue
                // Type the fresh zero to the destination so a reference consumer prints `null` and a
                // primitive consumer prints `0`/`0L`/`false` — exactly the polymorphic-zero re-typing.
                val zeroType = result.ssaValue?.type ?: result.type
                srcOp.ssaValue?.removeUse(srcOp)
                val const = Instruction(
                    IrOpcode.CONST,
                    result, // reuse the move's result operand: same SSA value, same def site
                    listOf(com.jadxmp.ir.insn.LiteralOperand(0L, zeroType)),
                )
                const.offset = insn.offset
                insns[idx] = const
            }
        }
    }

    /** Whether [value] is provably a compile-time zero: a `const 0`/`0L`, transitively through pure moves. */
    private fun isProvenZero(value: SsaValue): Boolean {
        var v: SsaValue? = value
        var guard = 0
        while (v != null && guard++ < 64) {
            val def = v.assign.parent ?: return false
            when (def.opcode) {
                IrOpcode.CONST -> {
                    val lit = def.args.firstOrNull() as? com.jadxmp.ir.insn.LiteralOperand ?: return false
                    return lit.value == 0L
                }
                IrOpcode.MOVE -> {
                    if (def.argCount != 1) return false
                    v = (def.getArg(0) as? RegisterOperand)?.ssaValue ?: return false
                }
                else -> return false
            }
        }
        return false
    }

    // ---- φ collection & components -----------------------------------------

    private fun collectPhis(): List<PhiInstruction> {
        val out = ArrayList<PhiInstruction>()
        for (block in blocks) {
            val list = block[PipelineAttrs.PHI_LIST] ?: continue
            for (insn in list) if (insn is PhiInstruction) out.add(insn)
        }
        return out
    }

    private fun indexDefs() {
        val entry = method.entryBlock
        for (value in method.ssaValues) {
            val parent = value.assign.parent
            val block = if (parent != null) blockOf(parent) else entry
            if (block != null) defBlock[value] = block
        }
    }

    private fun blockOf(insn: Instruction): BasicBlock? {
        for (block in blocks) if (insn in block.instructions) return block
        return null
    }

    /** A φ-connected component: the SSA values merged together and the φ that merge them. */
    private class Component {
        val members = LinkedHashSet<SsaValue>()
        val phis = ArrayList<PhiInstruction>()
    }

    private fun buildComponents(phis: List<PhiInstruction>): List<Component> {
        val uf = UnionFind<SsaValue>()
        for (phi in phis) {
            val res = phi.result?.ssaValue ?: continue
            uf.add(res)
            for (i in 0 until phi.argCount) {
                val v = (phi.getArg(i) as RegisterOperand).ssaValue ?: continue
                uf.add(v)
                uf.union(res, v)
            }
        }
        val byRoot = LinkedHashMap<SsaValue, Component>()
        for (phi in phis) {
            val res = phi.result?.ssaValue ?: continue
            val comp = byRoot.getOrPut(uf.find(res)) { Component() }
            comp.phis.add(phi)
            comp.members.add(res)
            for (i in 0 until phi.argCount) {
                (phi.getArg(i) as RegisterOperand).ssaValue?.let { comp.members.add(it) }
            }
        }
        return byRoot.values.toList()
    }

    // ---- coalescing ---------------------------------------------------------

    private fun coalesce(group: Component) {
        val local = LocalVar()
        var type: IrType? = null
        for (member in group.members) {
            local.addSsaValue(member) // sets member.localVar = local
            if (type == null && member.type.isTypeKnown) type = member.type
        }
        local.type = type ?: group.members.firstOrNull()?.type
        if (local.ssaValues.size > 1) mergedLocals.add(local)
    }

    /** SSA values that are method parameters (defined at entry, fixed signature type). */
    private val paramValues: Set<SsaValue> = method[PipelineAttrs.PARAMETERS]?.toHashSet() ?: emptySet()

    /**
     * A parameter member of an interference-free (coalescing) [group] whose fixed signature type CANNOT
     * hold every other member's value — a type conflict that would miscompile if coalesced.
     *
     * A merged local containing a parameter is rendered by codegen under the parameter's **fixed** name and
     * signature type (it cannot be widened). So if another member's value is not assignable to that type —
     * e.g. register `p1` is the `String` parameter yet is later reassigned `Integer.valueOf(…)` /
     * `Boolean.valueOf(…)` whose results merge back into it — coalescing emits an uncompilable cross-type
     * store (`String str = Integer.valueOf(…)`). Such a parameter must be split out ([coalesceSplittingParam]).
     * Returns null (⇒ ordinary coalesce, unchanged behavior) unless a class hierarchy is available and there
     * is exactly one parameter member with a genuine, **provable** conflict.
     *
     * Crucially, the conflict must be *proven*, not merely *unprovable*: [ClassHierarchy] is conservative, so
     * for two UNLOADED library types a non-subtype answer only means "cannot prove a subtype" — the two may
     * still be genuinely compatible (e.g. an unloaded `rx.c.c` that really IS an `rx.i`). Splitting on that
     * would needlessly discard the real receiver type (widening the local to `Object`) and diverge from the
     * oracle. So we split ONLY on [ClassHierarchy.provablyNotSubtype] (a final param type, or two loaded
     * types) and keep the status-quo coalesce whenever the relation is unknown.
     */
    private fun conflictingParam(group: Component): SsaValue? {
        val h = hierarchy ?: return null // no hierarchy ⇒ cannot prove a conflict; keep status-quo coalesce
        if (group.members.size <= 1) return null
        val params = group.members.filter { it in paramValues }
        val p = params.singleOrNull() ?: return null // 0 params, or ≥2 (ambiguous) ⇒ leave as-is
        val pType = p.type
        if (!pType.isTypeKnown || pType !is IrType.Object) return null
        val conflict = group.members.any { m ->
            m !== p && m.type.isTypeKnown && h.provablyNotSubtype(m.type, pType)
        }
        return if (conflict) p else null
    }

    /**
     * Coalesce [group] **excluding** the conflicting [param], which keeps its own parameter local. The
     * remaining members merge into one local typed to their join (a common supertype of all of them). The
     * parameter's φ-edge contribution is reproduced by a **dominating pre-assignment** `merged = param` at a
     * point dominating every occurrence: every OTHER φ operand is itself in `merged` and assigns it on its
     * own incoming path, so `merged` still holds `param` on exactly the edges the φ named it — identical
     * observable behavior, with no critical-edge copy needed. `param` (defined at method entry) dominates
     * that point, so the pre-assignment is always in scope.
     */
    private fun coalesceSplittingParam(group: Component, param: SsaValue) {
        val h = hierarchy!!
        val local = LocalVar()
        var joined: IrType? = null
        for (member in group.members) {
            if (member === param) continue
            local.addSsaValue(member) // param is intentionally left out ⇒ CodegenBridge gives it its own local
            if (member.type.isTypeKnown) {
                joined = if (joined == null) member.type else h.commonSuperType(joined, member.type)
            }
        }
        local.type = joined ?: param.type
        materializeParamPreAssign(local, param)
    }

    /**
     * Insert `local = param` at a point that dominates every occurrence of [local] and lies **outside every
     * try and every loop** that carries the accumulator — reproducing the parameter's φ edge exactly once.
     *
     * The point must be:
     *  - a strict dominating block that itself neither defines nor uses [local] ([at] `∉` occurrences), so
     *    the end-of-block insertion can never sit after an in-block use (uninitialized read) or clobber an
     *    in-block def;
     *  - outside every enclosing try (a def inside `try {}` is invisibly scoped); and
     *  - outside every loop that contains one of the merged members' DEFS — otherwise the pre-assign would
     *    re-execute each iteration and RESET the accumulator (a silent miscompile).
     *
     * Any of these unmet ⇒ [fallbackCoalesceWithParam] (the honest status-quo coalesce), never an unsound
     * placement (rule 4).
     */
    private fun materializeParamPreAssign(local: LocalVar, param: SsaValue) {
        val byId = HashMap<Int, BasicBlock>(blocks.size)
        for (b in blocks) byId[b.id] = b
        val defBlocksOfMembers = local.ssaValues.mapNotNull { defBlock[it] }.toHashSet()
        val occ = HashSet<BasicBlock>()
        for (m in local.ssaValues) {
            defBlock[m]?.let { occ.add(it) }
            for (u in m.uses) u.parent?.let { p -> blockOfCached(p)?.let { occ.add(it) } }
        }
        if (occ.isEmpty()) return
        val common = deepestCommonDominator(occ, byId) ?: run { fallbackCoalesceWithParam(local, param); return }
        // Walk the point out of every enclosing try AND out of every loop the occurrences straddle, so it is
        // a genuine pre-header of any accumulator loop rather than a per-iteration reset.
        val loops = computeLoops()
        val hoistedLoop = hoistOutOfEscapedLoops(common, occ, loops) ?: run {
            fallbackCoalesceWithParam(local, param); return
        }
        val at = hoistOutOfProtected(hoistedLoop)
        val safe = occ.all { at.id in it.dominators } && // dominates every occurrence
            at !in occ && // holds no def/use of the merged local ⇒ no in-block ordering hazard
            loops.none { (_, body) -> at in body && defBlocksOfMembers.any { it in body } } // not a resetting loop
        if (!safe) {
            fallbackCoalesceWithParam(local, param)
            return
        }
        val type = local.type ?: param.type
        val dstReg = RegisterOperand(param.regNum, type)
        val dstValue = SsaValue(param.regNum, method.ssaValues.size, dstReg)
        local.addSsaValue(dstValue)
        method.ssaValues.add(dstValue)
        // Register the pre-assign as this value's def site so declaration-scoping sees a dominating def and
        // does NOT add a redundant `merged = default` init after it (which would clobber the param value).
        defBlock[dstValue] = at
        val srcReg = RegisterOperand(param.regNum, param.type)
        srcReg.ssaValue = param
        param.addUse(srcReg)
        val move = Instruction(IrOpcode.MOVE, dstReg, listOf(srcReg))
        val insns = at.instructions
        val insertAt = if (insns.isNotEmpty() && isTerminator(insns.last())) insns.size - 1 else insns.size
        insns.add(insertAt, move)
        if (local.ssaValues.size > 1) mergedLocals.add(local)
    }

    /** Give up on the split and fold [param] back into [local] (the ordinary coalesced form). */
    private fun fallbackCoalesceWithParam(local: LocalVar, param: SsaValue) {
        local.addSsaValue(param)
        if (local.type == null && param.type.isTypeKnown) local.type = param.type
        if (local.ssaValues.size > 1) mergedLocals.add(local)
    }

    // ---- copy insertion (interfering component) ----------------------------

    private fun insertCopies(group: Component) {
        // Give each φ result its own variable; move incoming values into it along every edge.
        // Collect the copies grouped by the predecessor block they must be inserted at the end of.
        class Copy(val destLocal: LocalVar, val destType: IrType, val source: SsaValue)

        val perPred = LinkedHashMap<BasicBlock, MutableList<Copy>>()
        for (phi in group.phis) {
            val res = phi.result?.ssaValue ?: continue
            val destLocal = res.localVar ?: LocalVar().also { it.addSsaValue(res); mergedLocals.add(it) }
            destLocal.type = destLocal.type ?: res.type
            val succ = defBlock[res] ?: throw UnsupportedShapeException("φ result has no block")
            for (i in 0 until phi.argCount) {
                val op = phi.getArg(i) as com.jadxmp.ir.insn.PhiOperand
                val pred = op.from
                // A critical edge (pred forks AND merge joins) cannot host the copy safely.
                if (pred.successors.size > 1 && succ.predecessors.size > 1) {
                    throw UnsupportedShapeException("critical edge on interfering φ; would need edge split")
                }
                val source = op.ssaValue ?: throw UnsupportedShapeException("φ operand without ssa value")
                perPred.getOrPut(pred) { ArrayList() }.add(Copy(destLocal, res.type, source))
            }
        }
        for ((pred, copies) in perPred) {
            // Reject a write-before-read conflict inside one edge's parallel copy set (e.g. a swap):
            // if a destination variable is also read as a source, sequential emission could clobber it.
            val destLocals = copies.map { it.destLocal }.toHashSet()
            val sourceLocals = copies.mapNotNull { it.source.localVar }.toHashSet()
            if (destLocals.any { it in sourceLocals }) {
                throw UnsupportedShapeException("parallel-copy conflict on edge into merge")
            }
            insertAtEnd(pred, copies.map { copyMove(it.destLocal, it.destType, it.source) })
        }
    }

    private fun copyMove(destLocal: LocalVar, destType: IrType, source: SsaValue): Instruction {
        val dstReg = RegisterOperand(source.regNum, destType)
        val dstValue = SsaValue(source.regNum, method.ssaValues.size, dstReg)
        destLocal.addSsaValue(dstValue)
        method.ssaValues.add(dstValue)
        val srcReg = RegisterOperand(source.regNum, source.type)
        srcReg.ssaValue = source
        source.addUse(srcReg)
        return Instruction(IrOpcode.MOVE, dstReg, listOf(srcReg))
    }

    /** Insert [copies] at the end of [block], before its terminating branch/return/throw. */
    private fun insertAtEnd(block: BasicBlock, copies: List<Instruction>) {
        val insns = block.instructions
        val insertAt = if (insns.isNotEmpty() && isTerminator(insns.last())) insns.size - 1 else insns.size
        insns.addAll(insertAt, copies)
    }

    private fun isTerminator(insn: Instruction): Boolean = when (insn.opcode) {
        IrOpcode.GOTO, IrOpcode.IF, IrOpcode.SWITCH, IrOpcode.RETURN, IrOpcode.THROW -> true
        else -> false
    }

    // ---- φ removal ----------------------------------------------------------

    private fun removePhis(phis: List<PhiInstruction>) {
        for (phi in phis) {
            // Detach the φ's own operand uses from the values they read, so use-lists stay accurate.
            for (i in 0 until phi.argCount) {
                val op = phi.getArg(i) as RegisterOperand
                op.ssaValue?.removeUse(op)
            }
        }
        for (block in blocks) {
            val list = block[PipelineAttrs.PHI_LIST] ?: continue
            block.instructions.removeAll { it is PhiInstruction }
            block.remove(PipelineAttrs.PHI_LIST)
            list.clear()
        }
    }

    // ---- declaration scoping ------------------------------------------------

    private fun ensureDominatingDeclarations() {
        if (mergedLocals.isEmpty()) return
        val byId = HashMap<Int, BasicBlock>(blocks.size)
        for (b in blocks) byId[b.id] = b
        for (local in mergedLocals) {
            cancellation.ensureActive()
            val members = local.ssaValues
            if (members.size <= 1) continue
            // A parameter/`this`/synthetic def is defined at method entry, which dominates everything and
            // is already declared (in the signature) — no init needed.
            if (members.any { it.assign.parent == null }) continue

            val occ = HashSet<BasicBlock>()
            val defBlocks = ArrayList<BasicBlock>()
            for (m in members) {
                defBlock[m]?.let { occ.add(it); defBlocks.add(it) }
                for (u in m.uses) u.parent?.let { p -> blockOfCached(p)?.let { occ.add(it) } }
            }
            if (occ.isEmpty()) continue
            // A def that dominates every occurrence gives a natural declaration — UNLESS that def sits
            // inside a try-protected block while an occurrence lies outside it. A try scope is narrower
            // than dominance (a def inside `try {}` is invisible to the `catch`/after even though it
            // dominates them via the exception edge), so such an in-try declaration would not compile;
            // fall through to insert a hoisted init before the try.
            if (defBlocks.any { d -> !isProtected(d) && occ.all { d.id in it.dominators } }) continue

            val common = deepestCommonDominator(occ, byId) ?: continue
            insertDeclInit(hoistOutOfProtected(common), local)
        }
    }

    /** A block is protected (inside a `try`) if it has an exception edge to a handler entry. */
    private fun isProtected(block: BasicBlock): Boolean =
        block.successors.any { it.contains(PipelineAttrs.EXC_HANDLER) }

    /** The handler blocks a block is protected by (its try membership), for scope comparison. */
    private fun protectingHandlers(block: BasicBlock): List<BasicBlock> =
        block[PipelineAttrs.PROTECTING_HANDLERS] ?: emptyList()

    private val exceptionEdges: Set<Long> = method[PipelineAttrs.EXCEPTION_EDGES] ?: emptySet()

    /** Clean (exception-free) successors — the same normal-flow view the structuring stage walks. */
    private fun cleanSucc(block: BasicBlock): List<BasicBlock> {
        if (exceptionEdges.isEmpty()) return block.successors
        return block.successors.filter { (block.id.toLong() shl 32 or (it.id.toLong() and 0xFFFFFFFFL)) !in exceptionEdges }
    }

    /**
     * The blocks reachable from [from] over clean (normal) flow while staying in the *same try region* —
     * blocks carrying exactly the same protecting [handlers]. This is the def's own try body: a use NOT in
     * this set escapes it (it is in a `catch`, after the try, or in a *different* try region that happens
     * to share the same handler — the cross-region case). Mirrors the structuring stage's protected-region
     * flood, so the hoist criterion matches exactly what would otherwise bail.
     */
    private fun cleanReachableSameTry(from: BasicBlock, handlers: List<BasicBlock>): Set<BasicBlock> {
        val set = HashSet<BasicBlock>()
        val stack = ArrayDeque<BasicBlock>()
        stack.addLast(from)
        while (stack.isNotEmpty()) {
            val b = stack.removeLast()
            if (b in set) continue
            if (protectingHandlers(b) != handlers) continue // left this try region
            set.add(b)
            for (s in cleanSucc(b)) stack.addLast(s)
        }
        return set
    }

    /**
     * Hoist the declaration of every **single-def** value that is defined inside a `try {}` and read
     * outside that try's body (in a `catch`, or after) to a point dominating all its occurrences and
     * outside every enclosing try. Merged locals are already handled by [ensureDominatingDeclarations];
     * this covers the single-def case the structuring stage would otherwise bail on. The value becomes a
     * two-member local — a hoisted default init plus its in-try assignment — so codegen declares it in the
     * enclosing scope and the in-try store is a re-assignment, exactly like jadx's `T v = null; try { v =
     * … } …`.
     */
    private fun hoistTryEscapingDeclarations() {
        val byId = HashMap<Int, BasicBlock>(blocks.size)
        for (b in blocks) byId[b.id] = b
        for (value in method.ssaValues.toList()) {
            cancellation.ensureActive()
            val local = value.localVar
            if (local != null && local.ssaValues.size > 1) continue // already merged/hoisted
            val defParent = value.assign.parent ?: continue // params are declared in the signature
            if (defParent.opcode == IrOpcode.MOVE_EXCEPTION) continue // the catch param, bound by `catch (T e)`
            val defBlock = blockOfCached(defParent) ?: continue
            if (!isProtected(defBlock)) continue // not defined inside a try BODY
            if (defBlock.contains(PipelineAttrs.EXC_HANDLER)) continue // a handler entry, not a try body
            val defHandlers = protectingHandlers(defBlock)
            val sameTry = cleanReachableSameTry(defBlock, defHandlers)
            // Occurrences: the def block plus every use's block. A use escapes the def's try region when it
            // is NOT in that region — after the try, in a `catch`, or in a DIFFERENT try region sharing the
            // same handler (the cross-region case) — all of which need the declaration hoisted before the try.
            val occ = HashSet<BasicBlock>()
            occ.add(defBlock)
            var escapes = false
            for (use in value.uses) {
                val ub = use.parent?.let { blockOfCached(it) } ?: continue
                occ.add(ub)
                if (ub !in sameTry) escapes = true
            }
            if (!escapes) continue

            val hoisted = local ?: LocalVar().also { it.addSsaValue(value) }
            if (hoisted.type == null) hoisted.type = value.type
            val common = deepestCommonDominator(occ, byId) ?: continue
            insertDeclInit(hoistOutOfProtected(common), hoisted)
        }
    }

    /**
     * Hoist the declaration of every **single-def** value that is defined inside a loop body and read
     * AFTER the loop (outside its body) to a default-init BEFORE the loop, so the post-loop read is in
     * scope — jadx's `T v = default; while (…) { v = …; } … v`. The in-loop def dominates the post-loop
     * read (SSA guarantees it for a single-def value), so this is purely a source-scope fix, mirroring
     * [hoistTryEscapingDeclarations].
     *
     * Rule 4: the value becomes a two-member local (the hoisted default + its in-loop assignment); the
     * hoist point is walked OUT of every loop the read escapes and must dominate every occurrence (checked
     * explicitly). A value already merged/hoisted (e.g. by the try-escape pass, whose hoist-out-of-try
     * already lands before an enclosing loop) is left untouched. If no dominating out-of-loop point exists,
     * we skip it — [RegionMaker.coalescingIsSound] is the backstop that catches any non-dominating result.
     */
    private fun hoistLoopEscapingDeclarations() {
        val loops = computeLoops()
        if (loops.isEmpty()) return
        val byId = HashMap<Int, BasicBlock>(blocks.size)
        for (b in blocks) byId[b.id] = b
        for (value in method.ssaValues.toList()) {
            cancellation.ensureActive()
            val local = value.localVar
            if (local != null && local.ssaValues.size > 1) continue // already merged/hoisted
            val defParent = value.assign.parent ?: continue // params are declared in the signature
            if (defParent.opcode == IrOpcode.MOVE_EXCEPTION) continue // the catch param
            val defBlock = blockOfCached(defParent) ?: continue

            val occ = HashSet<BasicBlock>()
            occ.add(defBlock)
            for (use in value.uses) use.parent?.let { blockOfCached(it) }?.let { occ.add(it) }

            // The def must sit inside a loop that at least one occurrence escapes (a read after the loop).
            val escapesSomeLoop = loops.any { (_, body) -> defBlock in body && occ.any { it !in body } }
            if (!escapesSomeLoop) continue

            val common = deepestCommonDominator(occ, byId) ?: continue
            val hoisted = hoistOutOfEscapedLoops(common, occ, loops) ?: continue
            // The hoist point must dominate every occurrence or the declaration would not be in scope.
            if (occ.any { hoisted.id !in it.dominators }) continue

            val target = local ?: LocalVar().also { it.addSsaValue(value) }
            if (target.type == null) target.type = value.type
            insertDeclInit(hoisted, target)
        }
    }

    /** Whether the method contains a loop — a back-edge `tail → header` whose target dominates its source. */
    private fun hasLoop(): Boolean =
        blocks.any { tail -> tail.successors.any { header -> header.id in tail.dominators } }

    /**
     * Natural loops keyed by header: for every back-edge `tail → header` (a clean edge whose target
     * dominates its source) the loop body is the header plus every block that reaches `tail` over clean
     * (normal-flow) predecessors without passing through the header.
     */
    private fun computeLoops(): Map<BasicBlock, Set<BasicBlock>> {
        val loops = HashMap<BasicBlock, MutableSet<BasicBlock>>()
        for (tail in blocks) {
            for (header in cleanSucc(tail)) {
                if (header.id !in tail.dominators) continue // header does not dominate tail ⇒ not a back-edge
                val body = loops.getOrPut(header) { hashSetOf(header) }
                val stack = ArrayDeque<BasicBlock>()
                if (tail !== header && body.add(tail)) stack.addLast(tail)
                while (stack.isNotEmpty()) {
                    val x = stack.removeLast()
                    for (p in cleanPreds(x)) if (p !== header && body.add(p)) stack.addLast(p)
                }
            }
        }
        return loops
    }

    /** Walk up the dominator tree past the header of every loop the occurrences straddle, so the point is outside them. */
    private fun hoistOutOfEscapedLoops(
        block: BasicBlock,
        occ: Set<BasicBlock>,
        loops: Map<BasicBlock, Set<BasicBlock>>,
    ): BasicBlock? {
        var b: BasicBlock? = block
        var guard = 0
        while (b != null && guard++ <= blocks.size) {
            val current = b
            val escaped = loops.entries.firstOrNull { (_, body) -> current in body && occ.any { it !in body } }
                ?: return current
            b = escaped.key.immediateDominator // move above the loop header, out of the loop
        }
        return b
    }

    /** Clean (exception-free) predecessors — the normal-flow inverse of [cleanSucc]. */
    private fun cleanPreds(block: BasicBlock): List<BasicBlock> {
        if (exceptionEdges.isEmpty()) return block.predecessors
        return block.predecessors.filter {
            (it.id.toLong() shl 32 or (block.id.toLong() and 0xFFFFFFFFL)) !in exceptionEdges
        }
    }

    /** Walk up the dominator tree until leaving every enclosing try, so the declaration is in scope. */
    private fun hoistOutOfProtected(block: BasicBlock): BasicBlock {
        var b: BasicBlock? = block
        while (b != null && isProtected(b)) b = b.immediateDominator
        return b ?: block
    }

    private fun deepestCommonDominator(occ: Set<BasicBlock>, byId: Map<Int, BasicBlock>): BasicBlock? {
        val iter = occ.iterator()
        val common = HashSet(iter.next().dominators)
        while (iter.hasNext()) common.retainAll(iter.next().dominators)
        if (common.isEmpty()) return null
        return common.mapNotNull { byId[it] }.maxByOrNull { it.order }
    }

    /** Insert `var = <default>` at the end (before any terminator) of [block] so codegen declares [local] there. */
    private fun insertDeclInit(block: BasicBlock, local: LocalVar) {
        val type = local.type ?: IrType.INT
        val regNum = local.ssaValues.firstOrNull()?.regNum ?: 0
        val dst = RegisterOperand(regNum, type)
        val value = SsaValue(regNum, method.ssaValues.size, dst)
        local.addSsaValue(value)
        method.ssaValues.add(value)
        val init = Instruction(
            IrOpcode.CONST,
            dst,
            listOf(com.jadxmp.ir.insn.LiteralOperand(0L, type)),
        )
        val insns = block.instructions
        val at = if (insns.isNotEmpty() && isTerminator(insns.last())) insns.size - 1 else insns.size
        insns.add(at, init)
    }

    // ---- interference -------------------------------------------------------

    /** Half-open live segment of a value inside one block, in program-point units (`before insn i`). */
    private class Segment(val start: Int, val end: Int)

    private fun interferes(group: Component): Boolean {
        val members = group.members.toList()
        val segments = HashMap<SsaValue, Map<BasicBlock, Segment>>(members.size)
        for (m in members) segments[m] = liveSegments(m)
        for (i in members.indices) {
            for (j in i + 1 until members.size) {
                if (overlaps(segments.getValue(members[i]), segments.getValue(members[j]))) return true
            }
        }
        return false
    }

    private fun overlaps(a: Map<BasicBlock, Segment>, b: Map<BasicBlock, Segment>): Boolean {
        for ((block, sa) in a) {
            val sb = b[block] ?: continue
            if (maxOf(sa.start, sb.start) <= minOf(sa.end, sb.end)) return true
        }
        return false
    }

    /**
     * Per-block live segments of a single SSA value. A value becomes live one point *after* its def
     * (so it does not clash with the operands read by that same instruction), is live at a use's point,
     * and extends to the block end (`n`) when it is live-out.
     */
    private fun liveSegments(value: SsaValue): Map<BasicBlock, Segment> {
        val dv = defBlock[value]
        val defIndex = defIndexIn(value, dv)
        val defAtEntry = defIndex < 0 // param/synthetic/φ result: live from block entry

        val (liveIn, liveOut) = singleVarLiveness(value, dv)

        val result = HashMap<BasicBlock, Segment>()
        // Candidate blocks: def block, any live-in/out block, any block with a use.
        val candidates = HashSet<BasicBlock>()
        dv?.let { candidates.add(it) }
        candidates.addAll(liveIn)
        candidates.addAll(liveOut)
        for (use in value.uses) use.parent?.let { p -> blockOfCached(p)?.let { candidates.add(it) } }

        for (block in candidates) {
            val n = block.instructions.size
            val start = when {
                block === dv && !defAtEntry -> defIndex + 1
                block === dv && defAtEntry -> 0
                block in liveIn -> 0
                else -> {
                    // Used in this block though not live-in and not the def block: a local use only.
                    val first = firstUseIndex(value, block)
                    if (first < 0) continue else first
                }
            }
            val end = if (block in liveOut) {
                n
            } else {
                val last = lastUseIndex(value, block)
                when {
                    last >= 0 -> last
                    block === dv && !defAtEntry -> defIndex + 1 // dead def: minimal segment
                    else -> continue
                }
            }
            if (start <= end) result[block] = Segment(start, end)
        }
        return result
    }

    /** Single-variable liveness: the blocks where [value] is live-in / live-out. φ operands count as a use at the end of their predecessor. */
    private fun singleVarLiveness(value: SsaValue, dv: BasicBlock?): Pair<Set<BasicBlock>, Set<BasicBlock>> {
        val liveIn = HashSet<BasicBlock>()
        val liveOut = HashSet<BasicBlock>()
        val stack = ArrayDeque<BasicBlock>()

        fun seedLiveIn(block: BasicBlock) {
            if (block === dv) return
            if (liveIn.add(block)) stack.addLast(block)
        }

        // Non-φ uses seed live-in of their block.
        for (use in value.uses) {
            val insn = use.parent ?: continue
            if (insn is PhiInstruction) continue
            val block = blockOfCached(insn) ?: continue
            seedLiveIn(block)
        }
        // φ-operand uses: value is live-out of the predecessor the operand came from.
        for (phi in phisReading(value)) {
            for (i in 0 until phi.argCount) {
                val op = phi.getArg(i) as com.jadxmp.ir.insn.PhiOperand
                if (op.ssaValue === value) {
                    val pred = op.from
                    liveOut.add(pred)
                    seedLiveIn(pred)
                }
            }
        }
        // Propagate live-in up predecessor edges to their live-out, stopping at the def block.
        while (stack.isNotEmpty()) {
            val block = stack.removeLast()
            for (pred in block.predecessors) {
                liveOut.add(pred)
                seedLiveIn(pred)
            }
        }
        return liveIn to liveOut
    }

    // ---- small caches / lookups --------------------------------------------

    private val insnBlockCache = HashMap<Instruction, BasicBlock>()

    private fun blockOfCached(insn: Instruction): BasicBlock? {
        insnBlockCache[insn]?.let { return it }
        val b = blockOf(insn) ?: return null
        insnBlockCache[insn] = b
        return b
    }

    private var phiIndex: Map<SsaValue, List<PhiInstruction>>? = null

    private fun phisReading(value: SsaValue): List<PhiInstruction> {
        val index = phiIndex ?: buildPhiIndex().also { phiIndex = it }
        return index[value].orEmpty()
    }

    private fun buildPhiIndex(): Map<SsaValue, List<PhiInstruction>> {
        val map = HashMap<SsaValue, MutableList<PhiInstruction>>()
        for (block in blocks) {
            val list = block[PipelineAttrs.PHI_LIST] ?: continue
            for (insn in list) {
                if (insn !is PhiInstruction) continue
                for (i in 0 until insn.argCount) {
                    (insn.getArg(i) as RegisterOperand).ssaValue?.let {
                        map.getOrPut(it) { ArrayList() }.add(insn)
                    }
                }
            }
        }
        return map
    }

    private fun defIndexIn(value: SsaValue, block: BasicBlock?): Int {
        val insn = value.assign.parent ?: return -1
        // A φ result is defined atomically at block entry (all φ execute before the first real insn),
        // so its live range must start at point 0 — returning -1 marks it as entry-defined. Treating it
        // as index+1 would under-approximate the range and could miss a real interference (unsound).
        if (insn is PhiInstruction) return -1
        if (block == null) return -1
        return block.instructions.indexOf(insn)
    }

    private fun firstUseIndex(value: SsaValue, block: BasicBlock): Int {
        var best = -1
        for (i in block.instructions.indices) {
            val insn = block.instructions[i]
            if (insn is PhiInstruction) continue
            if (readsValue(insn, value)) {
                best = i
                break
            }
        }
        return best
    }

    private fun lastUseIndex(value: SsaValue, block: BasicBlock): Int {
        for (i in block.instructions.indices.reversed()) {
            val insn = block.instructions[i]
            if (insn is PhiInstruction) continue
            if (readsValue(insn, value)) return i
        }
        return -1
    }

    private fun readsValue(insn: Instruction, value: SsaValue): Boolean {
        for (i in 0 until insn.argCount) {
            val a = insn.getArg(i)
            if (a is RegisterOperand && a.ssaValue === value) return true
        }
        return false
    }

    /** Minimal disjoint-set structure over identity-compared SSA values. */
    private class UnionFind<T> {
        private val parent = HashMap<T, T>()

        fun add(x: T) {
            parent.getOrPut(x) { x }
        }

        fun find(x: T): T {
            var root = x
            while (parent[root] != root) root = parent.getValue(root)
            var cur = x
            while (parent[cur] != root) {
                val next = parent.getValue(cur)
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: T, b: T) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }
    }
}
