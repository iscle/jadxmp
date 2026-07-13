package com.jadxmp.pipeline.constructor

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.insn.TypeInstruction
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.SsaValue
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
                if (receiver.ssaValue === method.thisArg) continue // super()/this() delegation
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

    /**
     * Give each `<init>` its own `new T(args)` in its own block, rewriting that arm's uses of the shared
     * reference to the fresh object. Sound ONLY when the receiver is the shared value directly (no move
     * alias) and every use of the shared reference is dominated by exactly ONE `<init>` (so it belongs to a
     * single arm) — otherwise the reference escapes the arms (used before construction, or live across a
     * merge that would need a φ) and we bail, leaving codegen's unfused-new-instance marker to fail honestly.
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

        // Partition every non-<init> use of the shared reference to the single arm (site) that dominates it.
        val initInsns = sites.map { it.invoke }.toHashSet()
        val useOwner = HashMap<RegisterOperand, InitSite>()
        for (use in newValue.uses) {
            if (use.parent in initInsns) continue // the <init> receiver itself is consumed by the fusion
            val owner = sites.singleOrNull { dominatesUse(it, use) } ?: return // escapes all arms ⇒ bail
            useOwner[use] = owner
        }

        for (site in sites) {
            val freshOperand = RegisterOperand(newValue.regNum, resultOperand.type)
            val freshValue = SsaValue(newValue.regNum, method.ssaValues.size, freshOperand)
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
            for ((use, owner) in useOwner) {
                if (owner === site) {
                    newValue.removeUse(use)
                    use.ssaValue = freshValue
                    freshValue.addUse(use)
                }
            }
            // Construct-and-discard: an arm whose fresh object is never read renders `new T(args);`.
            if (freshValue.useCount == 0) {
                ctor.result = null
                method.ssaValues.remove(freshValue)
            }
        }
        removeInstruction(newInsn)
        method.ssaValues.remove(newValue)
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
