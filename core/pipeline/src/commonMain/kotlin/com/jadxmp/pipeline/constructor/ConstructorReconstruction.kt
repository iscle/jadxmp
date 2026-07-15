package com.jadxmp.pipeline.constructor

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.PhiInstruction
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.insn.TypeInstruction
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.pass.CancellationCheck

/**
 * Fuses a `new-instance vX, T` (which produces an *uninitialized* reference) with its subsequent
 * `invoke-direct {vX, args}, T.<init>` into a single normalized constructor instruction.  **jadx:
 * ConstructorVisitor.**
 *
 * DEX splits object creation into two steps; codegen renders them as an orphan `new T()` plus a
 * separate `new T(args)` statement, which is wrong (the initialized object is discarded). This
 * rewrites the pair into one [InvokeInstruction] with [IrOpcode.CONSTRUCTOR] whose result is `vX` and
 * whose args are the actual constructor arguments (the receiver dropped) — codegen then emits
 * `vX = new T(args)` (result used) or `new T(args);` (result discarded).
 *
 * Runs on SSA form (after type inference, before out-of-SSA) so the uninitialized reference can be
 * traced from the `<init>` receiver back to its `new-instance` — including through intervening
 * `move`s. It is **non-lossy**: every fused pair removes exactly the redundant `new-instance` and
 * folds the `<init>` call; no instruction's semantics are dropped.
 *
 * A `this()`/`super()` delegation (`invoke-direct {this,...}, <init>`) has no `new-instance` behind
 * its receiver, so it is left untouched for codegen to render as `this(...)`/`super(...)`.
 */
class ConstructorReconstruction(
    private val method: IrMethod,
    private val cancellation: CancellationCheck = CancellationCheck.None,
) {
    private class InitSite(val block: BasicBlock, val invoke: InvokeInstruction, val receiver: RegisterOperand)

    fun run() {
        // Collect every `<init>` and the `new-instance` it constructs BEFORE mutating: fusion rewrites
        // def-use, so a single `new-instance` shared by SEVERAL `<init>` calls (the hoisted-allocation /
        // branched-constructor shape) must be seen as a group up front — otherwise the first fusion
        // consumes the `new-instance` and the rest can no longer resolve it.
        val consumers = LinkedHashMap<TypeInstruction, MutableList<InitSite>>()
        for (block in method.blocks) {
            cancellation.ensureActive()
            for (insn in block.instructions.toList()) {
                val invoke = insn as? InvokeInstruction ?: continue
                if (invoke.opcode != IrOpcode.INVOKE) continue // already a CONSTRUCTOR
                if (invoke.invokeKind != InvokeKind.DIRECT) continue
                if (!invoke.methodRef.isConstructor) continue
                val receiver = invoke.instanceArg as? RegisterOperand ?: continue
                if (redirectThisDelegation(invoke, receiver)) continue // this()/super() delegation
                val newInsn = resolveNewInstance(receiver) ?: continue
                consumers.getOrPut(newInsn) { ArrayList() }.add(InitSite(block, invoke, receiver))
            }
        }
        for ((newInsn, sites) in consumers) {
            cancellation.ensureActive()
            if (sites.size == 1) {
                fuse(sites[0].block, sites[0].invoke, sites[0].receiver, newInsn)
            } else {
                fuseSharedNewInstance(newInsn, sites)
            }
        }
    }

    /**
     * A single `new-instance` whose uninitialized reference is constructed by SEVERAL `<init>` calls — a
     * compiler hoists the ALLOCATION above a branch and constructs it on each arm
     * (`T v = new T(); if (c) v.<init>(a) else v.<init>(b)`). Fusing just the first `<init>` (the
     * single-consumer path) would strand the others as bare `new T()` reading an out-of-scope value.
     *
     * We NEVER hoist a single constructor to the (dominating) allocation site: that would run the
     * constructor — which may throw / have side effects — on paths that reached NO `<init>` (e.g. an outer
     * guard skips both arms), a silent miscompile (rule 4). Instead each arm gets its OWN `new T(argsₖ)`,
     * materialized only where an `<init>` actually ran, matching jadx's branched-constructor output. If the
     * shape can't be split per-arm ([materializePerArm]'s guard fails), we leave it untouched and codegen's
     * unfused-new-instance marker fails honestly.
     */
    private fun fuseSharedNewInstance(newInsn: TypeInstruction, sites: List<InitSite>) {
        val resultOperand = newInsn.result ?: return
        materializePerArm(newInsn, resultOperand, sites)
    }

    /** A validated plan to merge the per-arm fresh constructor results at a join with a fresh φ. */
    private class PhiJoinPlan(
        /** The merge block that receives the φ (dominates every post-merge use). */
        val join: BasicBlock,
        /** One entry per predecessor edge of [join]: the arm whose construction reaches along that edge. */
        val operands: List<Pair<BasicBlock, InitSite>>,
        /** The post-merge uses to redirect onto the φ result. */
        val uses: List<RegisterOperand>,
    )

    /**
     * Give each `<init>` its own `new T(args)` in its own block, rewriting that arm's uses of the shared
     * reference to the fresh object. An in-arm use (dominated by exactly ONE `<init>`) is repointed to that
     * arm's fresh object directly. A **post-merge** use — reached after the arms rejoin, dominated by no
     * single arm — is served by a fresh φ inserted at the join that merges the per-arm fresh results
     * ([resolvePhiJoin] validates a clean diamond first; a non-diamond bails).
     *
     * Sound ONLY when the receiver is the shared value directly (no move alias). If a post-merge use exists
     * but the merge is not a clean diamond (some predecessor of the join reached no `<init>`, or two arms
     * both dominate one edge), we bail, leaving codegen's unfused-new-instance marker to fail honestly —
     * never a φ that reads an undefined value on some edge (rule 4).
     *
     * A construction whose result is never read renders as a bare `new T(args);` statement (mirrors the
     * single-`<init>` construct-and-discard). The construction runs ONLY on the arms that had an `<init>`:
     * a path skipping every arm (an outer guard) constructs nothing, exactly as the original bytecode did.
     */
    private fun materializePerArm(
        newInsn: TypeInstruction,
        resultOperand: RegisterOperand,
        sites: List<InitSite>,
    ) {
        val newValue = resultOperand.ssaValue ?: return
        if (sites.any { it.receiver.ssaValue !== newValue }) return // a move alias — not handled here

        // Partition every non-<init> use of the shared reference into in-arm uses (dominated by exactly one
        // arm) and post-merge uses (dominated by none). A use read by a pre-existing φ is out of scope for
        // this dominance reasoning (a φ operand is live-out of its predecessor, not of the φ's block) — bail.
        val initInsns = sites.map { it.invoke }.toHashSet()
        val useOwner = HashMap<RegisterOperand, InitSite>()
        val postMergeUses = ArrayList<RegisterOperand>()
        for (use in newValue.uses) {
            if (use.parent in initInsns) continue // the <init> receiver itself is consumed by the fusion
            if (use.parent is PhiInstruction) return // φ-operand read: dominance below would be unsound
            val owner = sites.singleOrNull { dominatesUse(it, use) }
            if (owner != null) useOwner[use] = owner else postMergeUses.add(use)
        }

        // Post-merge uses need a φ merging the per-arm fresh results. Validate a clean diamond BEFORE any
        // mutation so a non-diamond bails with the method untouched (no half-transform, rule 4).
        val plan = if (postMergeUses.isEmpty()) null else resolvePhiJoin(sites, postMergeUses) ?: return

        val freshBySite = HashMap<InitSite, SsaValue>()
        val ctorBySite = HashMap<InitSite, InvokeInstruction>()
        for (site in sites) {
            val freshOperand = RegisterOperand(newValue.regNum, resultOperand.type)
            val freshValue = SsaValue(newValue.regNum, method.ssaValues.size, freshOperand)
            freshValue.typeCell.set(objectType(newValue, resultOperand))
            method.ssaValues.add(freshValue)
            val ctor = InvokeInstruction(
                methodRef = site.invoke.methodRef,
                invokeKind = InvokeKind.DIRECT,
                result = freshOperand,
                args = ArrayList(site.invoke.args.drop(1)),
                opcode = IrOpcode.CONSTRUCTOR,
            )
            ctor.offset = site.invoke.offset
            val idx = site.block.instructions.indexOf(site.invoke)
            if (idx < 0) return
            site.block.instructions[idx] = ctor
            site.receiver.ssaValue?.removeUse(site.receiver) // drop the <init>'s read of the shared ref
            freshBySite[site] = freshValue
            ctorBySite[site] = ctor
            for ((use, owner) in useOwner) {
                if (owner === site) {
                    newValue.removeUse(use)
                    use.ssaValue = freshValue
                    freshValue.addUse(use)
                }
            }
        }

        // Insert the φ and redirect post-merge uses onto it BEFORE the construct-and-discard sweep, so an
        // arm whose only reader is the φ keeps its result (a discarded ctor would strand the φ operand).
        if (plan != null) insertPhiMerge(plan, newValue, resultOperand, freshBySite)

        // Construct-and-discard: an arm whose fresh object is now read by nobody renders `new T(args);`.
        for (site in sites) {
            val freshValue = freshBySite[site] ?: continue
            if (freshValue.useCount == 0) {
                ctorBySite[site]?.result = null
                method.ssaValues.remove(freshValue)
            }
        }
        removeInstruction(newInsn)
        method.ssaValues.remove(newValue)
    }

    /**
     * Validate that the [postMergeUses] are served by a single clean-diamond join and, if so, describe the
     * φ to place there. Returns null (⇒ caller bails honestly) when the shape is NOT a clean diamond:
     *  - the deepest block dominating every post-merge use can't be found, or
     *  - some predecessor edge of that join is dominated by **≠ 1** arm's construction — i.e. a path reaches
     *    the join having constructed nothing (undefined on that edge) or through two nested constructions
     *    (ambiguous). Either would make a φ read an undefined/ambiguous value, a silent miscompile.
     */
    private fun resolvePhiJoin(sites: List<InitSite>, postMergeUses: List<RegisterOperand>): PhiJoinPlan? {
        val useBlocks = HashSet<BasicBlock>()
        for (use in postMergeUses) useBlocks.add(blockOf(use.parent ?: return null) ?: return null)
        val join = deepestCommonDominator(useBlocks) ?: return null
        if (join.predecessors.isEmpty()) return null

        val operands = ArrayList<Pair<BasicBlock, InitSite>>(join.predecessors.size)
        for (pred in join.predecessors) {
            // Exactly one arm's construction must dominate this incoming edge; that arm's fresh result is
            // the value flowing in. (A block dominates itself, so an arm whose <init> sits in `pred` counts.)
            val arm = sites.singleOrNull { it.block.id in pred.dominators } ?: return null
            operands.add(pred to arm)
        }
        return PhiJoinPlan(join, operands, postMergeUses)
    }

    /**
     * Build the φ at [PhiJoinPlan.join] merging each predecessor edge's per-arm fresh result, then repoint
     * the post-merge uses onto the φ result. The φ is registered in the block's [PipelineAttrs.PHI_LIST] so
     * out-of-SSA processes (coalesces / removes) it exactly like a placement-pass φ; its result carries the
     * object's inferred type (type inference has already run and won't revisit it).
     */
    private fun insertPhiMerge(
        plan: PhiJoinPlan,
        newValue: SsaValue,
        resultOperand: RegisterOperand,
        freshBySite: Map<InitSite, SsaValue>,
    ) {
        val objType = objectType(newValue, resultOperand)
        val phiResult = RegisterOperand(newValue.regNum, objType)
        val phi = PhiInstruction(phiResult)
        phi.offset = plan.join.instructions.firstOrNull()?.offset ?: -1
        val phiValue = SsaValue(newValue.regNum, method.ssaValues.size, phiResult)
        phiValue.typeCell.set(objType)
        method.ssaValues.add(phiValue)

        for ((pred, arm) in plan.operands) {
            val fresh = freshBySite[arm] ?: return // arm produced no fresh value — abort insertion defensively
            val op = phi.addIncoming(newValue.regNum, objType, pred)
            fresh.addUse(op)
        }
        plan.join.instructions.add(0, phi)
        phiListOf(plan.join).add(phi)

        for (use in plan.uses) {
            newValue.removeUse(use)
            use.ssaValue = phiValue
            phiValue.addUse(use)
        }
    }

    /** The φ list for [block], created (and attached) on first use — mirrors [SsaBuilder]'s placement. */
    private fun phiListOf(block: BasicBlock): MutableList<Instruction> {
        block[PipelineAttrs.PHI_LIST]?.let { return it }
        val list = ArrayList<Instruction>(2)
        block[PipelineAttrs.PHI_LIST] = list
        return list
    }

    /** The object's inferred type, falling back to the new-instance operand's declared type. */
    private fun objectType(newValue: SsaValue, resultOperand: RegisterOperand) =
        if (newValue.type.isTypeKnown) newValue.type else resultOperand.type

    /** The deepest block (by reverse-postorder position) that dominates every block in [blocks]. */
    private fun deepestCommonDominator(blocks: Set<BasicBlock>): BasicBlock? {
        val iter = blocks.iterator()
        if (!iter.hasNext()) return null
        val common = HashSet(iter.next().dominators)
        while (iter.hasNext()) common.retainAll(iter.next().dominators)
        if (common.isEmpty()) return null
        val byId = HashMap<Int, BasicBlock>(method.blocks.size)
        for (b in method.blocks) byId[b.id] = b
        return common.mapNotNull { byId[it] }.maxByOrNull { it.order }
    }

    /** Whether the `<init>` at [site] dominates the instruction reading [use] (so [use] is in that arm). */
    private fun dominatesUse(site: InitSite, use: RegisterOperand): Boolean {
        val useInsn = use.parent ?: return false
        val useBlock = blockOf(useInsn) ?: return false
        if (useBlock === site.block) {
            return useBlock.instructions.indexOf(useInsn) > useBlock.instructions.indexOf(site.invoke)
        }
        return site.block.id in useBlock.dominators
    }

    /** The block that contains [insn], or null if it is not in any block. */
    private fun blockOf(insn: Instruction): BasicBlock? {
        for (block in method.blocks) if (insn in block.instructions) return block
        return null
    }

    /**
     * A `this(...)` constructor *delegation* invokes another `<init>` of the SAME class on the object
     * under construction — `this`. DEX routinely routes `this` onto the invoke's receiver register through
     * `move-object` copies (and, when the delegation follows a branch — e.g. a Kotlin default-args
     * synthetic ctor — a φ that merges `this` from every arm), so the receiver's SSA value is not
     * literally [IrMethod.thisArg]. When the receiver PROVABLY resolves to `this`, repoint it directly
     * onto `thisArg` (collapsing the alias) and return true. Downstream — the ternary-arg fold, out-of-SSA
     * and codegen — then all recognize the plain `this` receiver and render `this(...)`, never a spurious
     * `new T(...)` that would construct and DISCARD a distinct object (a rule-4 silent miscompile).
     * Returning true also skips new-instance fusion: a delegation has no `new-instance` behind its receiver.
     *
     * Scope: only SAME-CLASS delegations (`this(...)`) are collapsed. A `super(...)` whose receiver is a
     * moved `this` is left untouched here — recognizing it would emit `super(...)` after the register
     * shuffle's residual assignments, which is illegal Java (no statement may precede `super()`) until the
     * separate instructions-before-super reordering exists; collapsing it would trade one honest state for
     * another without a net correctness gain, so it is out of scope.
     *
     * The gate is *proof*, not heuristic: a genuine `new T(...)` whose receiver is a fresh `new-instance`
     * (or any value other than `this`) does not resolve to `this`, so it is never mis-detected here.
     */
    private fun redirectThisDelegation(invoke: InvokeInstruction, receiver: RegisterOperand): Boolean {
        if (!isSameClassConstructor(invoke)) return false
        val thisArg = method.thisArg ?: return false
        if (!resolvesToThis(receiver.ssaValue, thisArg, HashSet())) return false
        if (receiver.ssaValue !== thisArg) {
            receiver.ssaValue?.removeUse(receiver)
            receiver.ssaValue = thisArg
            thisArg.addUse(receiver)
        }
        return true
    }

    /** Whether [invoke]'s `<init>` target is declared by the enclosing class itself (a `this(...)` target). */
    private fun isSameClassConstructor(invoke: InvokeInstruction): Boolean =
        (invoke.methodRef.declaringType as? IrType.Object)?.className == method.declaringClass.fullName

    /**
     * Whether [value] provably denotes `this`: it IS [thisArg], or it is defined by a single-source `move`
     * copy / a φ whose *every* operand also denotes `this`. A φ operand that re-reads a value already on
     * the walk stack (a loop back-edge) is treated as satisfied — its contribution is `this` exactly when
     * the φ's other operands are, which the surrounding `all { }` still enforces. Anything else (a
     * `new-instance`, a field/array read, an invoke result, a parameter other than `this`, …) is NOT
     * `this`, so the walk is sound in both directions.
     */
    private fun resolvesToThis(value: SsaValue?, thisArg: SsaValue, seen: MutableSet<SsaValue>): Boolean {
        if (value == null) return false
        if (value === thisArg) return true
        if (!seen.add(value)) return true // φ back-edge: corroborated by the φ's other operands
        val def = value.assign.parent ?: return false
        return when {
            def.opcode == IrOpcode.MOVE && def.argCount == 1 ->
                resolvesToThis((def.getArg(0) as? RegisterOperand)?.ssaValue, thisArg, seen)
            def is PhiInstruction && def.argCount > 0 ->
                (0 until def.argCount).all {
                    resolvesToThis((def.getArg(it) as? RegisterOperand)?.ssaValue, thisArg, seen)
                }
            else -> false
        }
    }

    /** Follow the receiver's definition back to a `new-instance`, chasing `move` copies. */
    private fun resolveNewInstance(receiver: RegisterOperand): TypeInstruction? {
        var value = receiver.ssaValue
        val seen = HashSet<Any>()
        while (value != null && seen.add(value)) {
            val def = value.assign.parent ?: return null
            if (def is TypeInstruction && def.opcode == IrOpcode.NEW_INSTANCE) return def
            if (def.opcode == IrOpcode.MOVE && def.argCount > 0) {
                value = (def.getArg(0) as? RegisterOperand)?.ssaValue ?: return null
            } else {
                return null
            }
        }
        return null
    }

    private fun fuse(
        invokeBlock: BasicBlock,
        invoke: InvokeInstruction,
        receiver: RegisterOperand,
        newInsn: TypeInstruction,
    ) {
        val resultOperand = newInsn.result ?: return
        val objValue = resultOperand.ssaValue ?: return

        // The uninitialized reference and every `move` copy of it denote the SAME object; DEX may shuffle
        // it across registers (both onto the `<init>` receiver AND onto the register a later use reads)
        // before the constructor runs. Collect the whole copy cluster so fusion collapses it into one
        // variable defined by the constructor — otherwise a pre-`<init>` copy of the new-instance result
        // would read the object before its relocated definition (a forward reference).
        val cluster = collectMoveCluster(objValue)
        val clusterMoves = HashSet<Instruction>()
        for (v in cluster) {
            if (v === objValue) continue
            v.assign.parent?.let { clusterMoves.add(it) }
        }

        // External reads of the object: a use of any cluster value that is neither internal plumbing (a
        // cluster `move`) nor this `<init>`'s receiver (which the fusion consumes).
        val externalUses = ArrayList<RegisterOperand>()
        for (v in cluster) {
            for (use in v.uses) {
                val p = use.parent
                if (p === invoke) continue
                if (p != null && p in clusterMoves) continue
                externalUses.add(use)
            }
        }

        val ctorArgs: List<Operand> = ArrayList(invoke.args.drop(1))
        newInsn.result = null
        val ctor = InvokeInstruction(
            methodRef = invoke.methodRef,
            invokeKind = InvokeKind.DIRECT,
            result = resultOperand,
            args = ctorArgs,
            opcode = IrOpcode.CONSTRUCTOR,
        )
        ctor.offset = invoke.offset
        val idx = invokeBlock.instructions.indexOf(invoke)
        if (idx >= 0) invokeBlock.instructions[idx] = ctor else invokeBlock.instructions.add(ctor)
        removeInstruction(newInsn)

        // Every external read now refers to the single object value (defined by the constructor).
        for (use in externalUses) {
            if (use.ssaValue === objValue) continue
            use.ssaValue?.removeUse(use)
            use.ssaValue = objValue
            objValue.addUse(use)
        }
        // Drop the `<init>` receiver's read and delete the redundant copy moves (with their reads).
        receiver.ssaValue?.removeUse(receiver)
        for (v in cluster) {
            if (v === objValue) continue
            val def = v.assign.parent
            if (def != null) {
                for (a in def.args) (a as? RegisterOperand)?.ssaValue?.removeUse(a)
                removeInstruction(def)
            }
            method.ssaValues.remove(v)
        }

        // Construct-and-discard: if the constructed value is never read, drop the result so codegen emits
        // a bare `new T(args);` statement instead of an unused assignment.
        if (objValue.useCount == 0) {
            ctor.result = null
            method.ssaValues.remove(objValue)
        }
    }

    /**
     * The object's copy cluster: the [root] new-instance value plus every value reachable from it through
     * forward `move` copies (`move vDst, vSrc` where `vSrc` is already in the cluster). These are all the
     * SAME uninitialized object; fusion collapses them into one constructor-defined variable.
     */
    private fun collectMoveCluster(root: SsaValue): Set<SsaValue> {
        val cluster = LinkedHashSet<SsaValue>()
        val stack = ArrayDeque<SsaValue>()
        cluster.add(root)
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            for (use in v.uses) {
                val def = use.parent ?: continue
                if (def.opcode == IrOpcode.MOVE && def.argCount == 1 && def.getArg(0) === use) {
                    val copy = def.result?.ssaValue ?: continue
                    if (cluster.add(copy)) stack.addLast(copy)
                }
            }
        }
        return cluster
    }

    private fun removeInstruction(insn: Instruction) {
        for (block in method.blocks) {
            if (block.instructions.remove(insn)) return
        }
    }
}
