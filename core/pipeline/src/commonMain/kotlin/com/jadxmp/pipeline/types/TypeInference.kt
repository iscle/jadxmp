package com.jadxmp.pipeline.types

import com.jadxmp.ir.insn.ArithInstruction
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.ConditionOp
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.type.TypeKind
import com.jadxmp.pipeline.pass.CancellationCheck
import com.jadxmp.ir.insn.PhiInstruction

/**
 * Reconstructs Java types for every [SsaValue] over the `core:ir` type lattice.  **jadx: the
 * `typeinference` package (TypeInferenceVisitor / TypeUpdate / TypeSearch / FixTypesVisitor).**
 *
 * Three layers, applied in order (each a fallback for the previous):
 * 1. **Seed + propagate** — anchor values whose definition fixes a concrete type (const-string, new,
 *    check-cast, field/invoke result, cast, cmp, array-length, resolved arithmetic), then run a
 *    constraint fixpoint. Each value's type is derived from its definition and refined (narrowed) by
 *    the required type at each use; `move`/`φ` values flow from their sources (φ joins via the class
 *    hierarchy). Integral ambiguity rides on the lattice's `Unknown` sets and narrows by intersection.
 * 2. **Backtracking search** — for the (small) set of values left ambiguous by propagation, try
 *    concrete candidates from each value's possible set, keeping only assignments that stay consistent
 *    with every neighbour; backtrack on conflict.
 * 3. **Heuristic repair** — anything still ambiguous is forced to a deterministic default from its
 *    possible set (int-first for numerics, `java.lang.Object` for references).
 *
 * The reference cases the `core:ir` lattice cannot decide (two UNRELATED named classes) are resolved
 * with [ClassHierarchy] against the loaded [com.jadxmp.ir.node.IrRoot].
 *
 * Deferred (documented, not attempted here): overload-directed argument typing, generic-signature
 * reconstruction, and precise bidirectional reference bounds — these need signatures/overload
 * resolution beyond the erased descriptors this stage sees.
 */
class TypeInference(
    private val method: IrMethod,
    private val hierarchy: ClassHierarchy,
    private val cancellation: CancellationCheck = CancellationCheck.None,
) {
    private val values: List<SsaValue> get() = method.ssaValues

    fun run() {
        if (values.isEmpty()) return
        applyContextBounds()
        seedFixedTypes()
        propagate()
        refineBitwiseBooleans()
        backtrackAmbiguous()
        repairRemaining()
        writeBack()
        retypeConstantOperands()
    }

    /**
     * Resolve the DEX `int`/`boolean` ambiguity of a bitwise `and`/`or`/`xor` toward **boolean** when the
     * evidence is unambiguously boolean. DEX has no boolean opcodes: `!b` is emitted as `xor b, 1`, `b1 &&
     * b2`-style masking as `and`/`or`, and `false`/`true` as the integer `0`/`1`. Such a result decodes as
     * [IrType.INT_BOOLEAN] (int-or-boolean) and, absent other evidence, the deterministic default picks
     * `int` — printing `int i = z ^ (i2 != 0);` (**boolean cannot be converted to int**). jadx keeps it
     * boolean; so do we, but only under a hard rule-4 guard:
     *
     *  - **positive evidence** — the def is a bitwise `and`/`or`/`xor` with at least one genuinely boolean
     *    operand (e.g. a comparison / `equals` result), so the operation IS the boolean form; AND
     *  - **every use admits boolean** — see [useAdmitsBoolean]. A use is boolean-admitting ONLY when it is
     *    an equality-to-zero (`== 0`/`!= 0`), a two-register equality whose sibling is boolean-compatible,
     *    a boolean-typed context, or a φ/move that routes onward to such uses (with no int-forced sibling).
     *    An ordering compare (`< > <= >=`), an equality against a genuine int, arithmetic, an array index,
     *    a switch selector, an int `return`, or a φ with an int sibling all make the value genuinely
     *    `int` — leave it, since a non-flip is honest where a wrong narrowing (`false + 1`) is a miscompile.
     *
     * Narrowing is committed by [TypeCell.fix] so the follow-up [propagate] cascades the boolean through
     * the φ-merge (the merged loop-condition variable) and its `const 0`/`1` arms (→ `false`/`true`)
     * without re-widening. Runs only after the main fixpoint, so it never perturbs monotone convergence.
     */
    private fun refineBitwiseBooleans() {
        var narrowedAny = false
        for (v in values) {
            if (v.typeCell.immutable || v.type != IrType.INT_BOOLEAN) continue
            val def = v.assign.parent ?: continue
            if (def.opcode != IrOpcode.ARITH) continue
            val op = (def as? ArithInstruction)?.op
            if (op != ArithOp.AND && op != ArithOp.OR && op != ArithOp.XOR || def.argCount < 2) continue
            val hasBooleanOperand = (0 until 2).any { sourceType(def.getArg(it)) == IrType.BOOLEAN }
            if (!hasBooleanOperand) continue
            // Rule 4: only narrow when EVERY use genuinely admits boolean (not just the permissive bound).
            if (v.uses.any { !useAdmitsBoolean(it, hashSetOf(v)) }) continue
            v.typeCell.fix(IrType.BOOLEAN)
            narrowedAny = true
        }
        if (narrowedAny) propagate()
    }

    /**
     * Whether [use] genuinely permits its read value to be `boolean`. The lattice bound alone is too
     * permissive — comparison operands decode as `UNKNOWN`/`NARROW` and a `const` result as `NARROW`, all
     * of which "admit" boolean — so classify the real consumer:
     *  - a **φ** routes the value into a merged variable: boolean-OK only if every OTHER incoming operand
     *    is boolean-compatible (else a genuine-int sibling like `const 2` would be dragged to `true`) AND
     *    the merged result's own uses all admit boolean;
     *  - a **move** routes it to another variable — follow that variable's uses;
     *  - an **if**: equality-to-zero (`== 0`/`!= 0`) is boolean-OK; a two-register equality is boolean-OK
     *    only when the sibling operand is itself boolean-compatible; an ordering compare (`< > <= >=`) is
     *    int-requiring and blocks;
     *  - anything else (arithmetic, array store, switch selector, `return`, an invoke argument, …) admits
     *    boolean ONLY when its bound is provably boolean-only ([boundIsBooleanOnly]) — a generic `aput`
     *    value (`NARROW_NUMBERS`) or an invoke-custom arg (`UNKNOWN`) merely *contains* boolean in its
     *    permissive decode set and must block, else `!z` would be stored into an `int[]`.
     */
    private fun useAdmitsBoolean(use: RegisterOperand, visited: MutableSet<SsaValue>): Boolean {
        val parent = use.parent ?: return true
        return when {
            parent is PhiInstruction -> {
                for (i in 0 until parent.argCount) {
                    val arg = parent.getArg(i) as? RegisterOperand ?: continue
                    if (arg === use) continue
                    if (!isBooleanCompatibleSource(arg.ssaValue, visited)) return false
                }
                val res = parent.result?.ssaValue ?: return false
                if (!visited.add(res)) return true // already on the path — another consumer decides
                res.uses.all { useAdmitsBoolean(it, visited) }
            }
            parent.opcode == IrOpcode.MOVE && parent.argCount > 0 && parent.getArg(0) === use -> {
                val res = parent.result?.ssaValue ?: return true
                if (!visited.add(res)) return true
                res.uses.all { useAdmitsBoolean(it, visited) }
            }
            parent is IfInstruction -> {
                if (parent.condition != ConditionOp.EQ && parent.condition != ConditionOp.NE) return false
                val other = if (parent.argCount > 1 && parent.getArg(0) === use) parent.getArg(1) else parent.getArg(0)
                when (other) {
                    is LiteralOperand -> other.value == 0L || other.value == 1L
                    is RegisterOperand -> isBooleanCompatibleSource(other.ssaValue, visited)
                    else -> false
                }
            }
            else -> boundIsBooleanOnly(useBound(use))
        }
    }

    /**
     * Whether [bound] proves the value must be `boolean` — its kind-set is a SUBSET of `{boolean}`,
     * actively excluding `int` and every other kind. This is the safe test for the fallback consumer:
     * the decoder stamps PERMISSIVE operand bounds (a generic `aput` value is `NARROW_NUMBERS`, an
     * invoke-custom arg is `UNKNOWN`), and `isCompatible(boolean)` is true whenever the set merely
     * *contains* boolean — which would wrongly admit an int-requiring use. Requiring boolean-only flips the
     * fallback to "block unless proven boolean", so an unresolved-but-really-boolean use that decodes
     * permissively is at worst a missed flip (honest), never a miscompile.
     */
    private fun boundIsBooleanOnly(bound: IrType): Boolean = when (bound) {
        IrType.BOOLEAN -> true
        is IrType.Unknown -> bound.possible == setOf(TypeKind.BOOLEAN)
        else -> false
    }

    /**
     * Whether [value] is a producer whose result can legitimately be `boolean` — used to decide if a φ
     * sibling or an equality peer is safe to carry boolean if the bitwise value is narrowed. Two things
     * must hold:
     *  - **structural** — it is a resolved `boolean`, a `const 0`/`1` (the boolean literal encodings), a
     *    boolean-operand bitwise op, or a move/φ transitively built from those; AND
     *  - **no int-forcing use** — every one of ITS uses also admits boolean. This is what stops a shared
     *    polymorphic zero: a `const 0` that is also fed to `x + 0` is pinned to `int` by that arithmetic
     *    use, so it must not be dragged to boolean by the merge (that pin can surface only order-
     *    dependently in the fixpoint, so we check the use directly rather than trusting the snapshot type).
     *
     * A genuine int (an int-returning call, an arithmetic result, a `const` that is not `0`/`1`, or a
     * value already resolved to a concrete non-boolean primitive) is never compatible.
     */
    private fun isBooleanCompatibleSource(value: SsaValue?, visited: MutableSet<SsaValue>): Boolean {
        val v = value ?: return false
        if (v.type == IrType.BOOLEAN) return true
        // Already resolved to a concrete non-boolean primitive (int/long/…): genuinely that type.
        if (v.type.isTypeKnown && v.type.isPrimitive) return false
        if (!visited.add(v)) return true // cycle (loop φ): assume OK, another path decides
        val def = v.assign.parent ?: return false // a param that is not boolean-typed
        val structurallyBoolean = when {
            def.opcode == IrOpcode.CONST -> {
                val lit = def.args.firstOrNull() as? LiteralOperand
                lit != null && (lit.value == 0L || lit.value == 1L)
            }
            def.opcode == IrOpcode.MOVE && def.argCount > 0 ->
                isBooleanCompatibleSource((def.getArg(0) as? RegisterOperand)?.ssaValue, visited)
            def is PhiInstruction ->
                (0 until def.argCount).all {
                    isBooleanCompatibleSource((def.getArg(it) as? RegisterOperand)?.ssaValue, visited)
                }
            def is ArithInstruction && (def.op == ArithOp.AND || def.op == ArithOp.OR || def.op == ArithOp.XOR) ->
                (0 until def.argCount).any {
                    isBooleanCompatibleSource((def.getArg(it) as? RegisterOperand)?.ssaValue, visited)
                }
            else -> false
        }
        if (!structurallyBoolean) return false
        // No use may force this value to a strict int — else narrowing it to boolean would miscompile
        // that use (`false + 0`). Ties the sibling's fate to the same use-classification as the root.
        return v.uses.all { useAdmitsBoolean(it, visited) }
    }

    // ---- method-level context bounds ---------------------------------------

    /**
     * Apply the constraints decode could not see because they depend on the method signature: a
     * `return x` requires `x` to be (assignable to) the method's declared return type. Stamped as a
     * use bound on the operand so propagation refines the returned value.
     */
    private fun applyContextBounds() {
        val ret = method.returnType
        if (ret == IrType.VOID) return
        for (block in method.blocks) {
            for (insn in block.instructions) {
                if (insn.opcode == IrOpcode.RETURN && insn.argCount > 0) {
                    insn.getArg(0).type = ret
                }
            }
        }
    }

    // ---- layer 1: seed + propagate -----------------------------------------

    private fun seedFixedTypes() {
        for (v in values) {
            if (v.typeCell.immutable) continue
            val def = v.assign.parent ?: continue
            if (def is PhiInstruction || def.opcode == IrOpcode.MOVE) continue
            val produced = def.result?.type ?: continue
            // Only anchor genuinely resolved types; partial hints (const NARROW, aget-object) keep
            // flowing so uses can refine them.
            //
            // The **root** `java.lang.Object` (the erased result of `next()`/`get()`/… — a signature
            // the decoder can only see as `Object`) is deliberately NOT anchored: it is the top of the
            // reference lattice and carries no more information than "some reference", so locking it
            // would suppress the very use-driven narrowing that reconstructs the real type. DEX (unlike
            // the JVM) lets an `invoke-{interface,virtual}` dispatch on such an erased value with no
            // preceding `check-cast` (see types/TestGenerics2: `next()` → `Map$Entry.getKey()`), so the
            // receiver operand's declaring class is the only evidence of the value's true type. Left to
            // flow, [computeType] meets it with every use bound; because SSA guarantees all uses of a
            // value are mutually consistent, that meet is a sound narrowing every use accepts (a
            // receiver bound is bytecode-guaranteed by the verifier). A value with only `Object`-typed
            // uses simply stays `Object`.
            if (produced.isTypeKnown && produced != IrType.UNKNOWN_OBJECT && produced != IrType.OBJECT) {
                v.typeCell.fix(produced)
            }
        }
    }

    private fun propagate() {
        // Each value's type is recomputed from constant definition hints, its (monotonically narrowing)
        // sources, and its use bounds — so values only move downward in the lattice and the fixpoint is
        // reached in a bounded number of passes. The guard is a defensive safety net only: if it ever
        // trips (a latent non-monotone bug) we throw so PassRunner records a method error, rather than
        // silently continuing with half-inferred types.
        val limit = values.size.toLong() * 64 + 64
        var guard = 0L
        var changed = true
        while (changed) {
            cancellation.ensureActive()
            changed = false
            for (v in values) {
                if (v.typeCell.immutable) continue
                val newType = computeType(v)
                if (newType != v.type) {
                    v.typeCell.set(newType)
                    changed = true
                }
            }
            if (guard++ > limit) {
                error("type inference did not converge for $method (possible lattice non-monotonicity)")
            }
        }
    }

    private fun computeType(v: SsaValue): IrType {
        val def = v.assign.parent
        var t: IrType = when {
            def == null -> v.assign.type
            def is PhiInstruction -> phiJoin(def)
            def.opcode == IrOpcode.MOVE -> sourceType(def.getArg(0))
            def.opcode == IrOpcode.ARRAY_GET -> arrayGetType(def)
            else -> def.result?.type ?: v.assign.type
        }
        // Refine by the required type at each use (upper bounds).
        for (use in v.uses) {
            t = meet(t, useBound(use))
        }
        return t
    }

    /**
     * The type bound a use imposes on the value it reads. For a plain use that is the operand's own
     * required type; but a use that **feeds an assignment** — a φ argument or a `move` source — takes
     * the type of the *destination* value, because the read value must be assignable to what it is
     * copied into. This is what pulls a `const 0` used only through a φ/move into an object type (so it
     * renders as `null`, not `0`), without ever widening a primitive in the lattice.
     */
    private fun useBound(use: RegisterOperand): IrType {
        val parent = use.parent ?: return use.type
        if (parent is PhiInstruction) {
            return parent.result?.ssaValue?.type ?: use.type
        }
        if (parent.opcode == IrOpcode.MOVE && parent.argCount > 0 && parent.getArg(0) === use) {
            return parent.result?.ssaValue?.type ?: use.type
        }
        return use.type
    }

    /** Result type of an `aget`: the element type of the (resolved) array operand, when known. */
    private fun arrayGetType(def: com.jadxmp.ir.insn.Instruction): IrType {
        val arrayType = sourceType(def.getArg(0))
        val elem = arrayType.arrayElement
        return if (elem != null && elem.isTypeKnown) elem else def.result?.type ?: IrType.UNKNOWN
    }

    private fun phiJoin(phi: PhiInstruction): IrType {
        var acc: IrType? = null
        for (i in 0 until phi.argCount) {
            val argType = sourceType(phi.getArg(i))
            acc = if (acc == null) argType else join(acc, argType)
        }
        return acc ?: IrType.UNKNOWN
    }

    private fun sourceType(operand: com.jadxmp.ir.insn.Operand): IrType {
        val reg = operand as? RegisterOperand ?: return operand.type
        return reg.ssaValue?.type ?: reg.type
    }

    /** Meet (narrow) of a value's current type with a use-required bound, hierarchy-aware for refs. */
    private fun meet(current: IrType, bound: IrType): IrType {
        if (current == bound) return current
        val merged = current.merge(bound)
        if (merged != null) return merged
        // merge == null: UNRELATED refs (undecidable by lattice) or a hard conflict.
        if (current.isCompatible(bound)) {
            if (hierarchy.isSubtype(current, bound)) return current // current already satisfies bound
            if (hierarchy.isSubtype(bound, current)) return bound
        }
        return current // conflict / unresolved: keep best-effort, do not corrupt
    }

    /** Join (common supertype) for φ merges; primitives/partials fold with the lattice meet. */
    private fun join(a: IrType, b: IrType): IrType {
        if (a == b) return a
        val aRef = isReferenceType(a)
        val bRef = isReferenceType(b)
        if (aRef && bRef) return hierarchy.commonSuperType(a, b)
        return a.merge(b) ?: if (aRef || bRef) IrType.UNKNOWN_OBJECT else a
    }

    private fun isReferenceType(t: IrType): Boolean =
        t is IrType.Object || t is IrType.ArrayType || t is IrType.TypeVariable || t is IrType.Wildcard

    // ---- layer 2: backtracking search --------------------------------------

    private fun backtrackAmbiguous() {
        val unresolved = values.filter { !it.typeCell.immutable && it.type is IrType.Unknown }
        if (unresolved.isEmpty() || unresolved.size > SEARCH_LIMIT) return
        search(unresolved, 0)
    }

    private fun search(vars: List<SsaValue>, index: Int): Boolean {
        cancellation.ensureActive()
        if (index == vars.size) return true
        val v = vars[index]
        val original = v.type
        val possible = (original as? IrType.Unknown)?.possible ?: return search(vars, index + 1)
        for (candidate in candidatesFor(possible)) {
            v.typeCell.set(candidate)
            if (consistent(v) && search(vars, index + 1)) return true
        }
        // No candidate worked: restore the ambiguous type and let repair handle it.
        v.typeCell.set(original)
        return false
    }

    /** A value is consistent if it conflicts with none of its use bounds nor its definition. */
    private fun consistent(v: SsaValue): Boolean {
        val t = v.type
        for (use in v.uses) if (!t.isCompatible(use.type)) return false
        val def = v.assign.parent
        if (def != null && def !is PhiInstruction && def.opcode != IrOpcode.MOVE) {
            def.result?.type?.let { if (!t.isCompatible(it)) return false }
        }
        return true
    }

    // ---- layer 3: heuristic repair -----------------------------------------

    private fun repairRemaining() {
        for (v in values) {
            if (v.typeCell.immutable) continue
            val t = v.type
            if (t is IrType.Unknown) {
                v.typeCell.set(defaultFor(t.possible))
            }
        }
    }

    private fun candidatesFor(possible: Set<TypeKind>): List<IrType> =
        PREFERENCE.filter { it in possible }.map { concreteFor(it) }

    private fun defaultFor(possible: Set<TypeKind>): IrType {
        for (k in PREFERENCE) if (k in possible) return concreteFor(k)
        return IrType.OBJECT
    }

    private fun concreteFor(kind: TypeKind): IrType = when (kind) {
        TypeKind.OBJECT -> IrType.OBJECT
        TypeKind.ARRAY -> IrType.OBJECT_ARRAY
        else -> IrType.primitive(kind)
    }

    // ---- finalize -----------------------------------------------------------

    private fun writeBack() {
        for (v in values) {
            val t = v.type
            v.assign.type = t
            for (use in v.uses) use.type = t
        }
    }

    /**
     * A [LiteralOperand] carries no [SsaValue], so [writeBack] never reaches it — yet codegen renders a
     * literal purely from *its own* type (a zero renders as `null` only when its operand type is
     * reference-like, as `false` only when boolean, …). After the SSA-value types are final, push them
     * onto the two literal positions that would otherwise misrender:
     * - a `const`'s literal takes its (now inferred) result type, so `const 0` assigned into an object
     *   variable prints `null` and into a boolean prints `false`;
     * - a zero-compare `if`'s literal takes the compared register's type, so `x == 0` against an object
     *   prints `x == null` (and against a boolean, `x == false`) — compilable, not `incomparable types`.
     *
     * This only ever changes how the constant `0`/`1` renders; it never alters a value's inferred type,
     * so it cannot introduce an unsound cast.
     */
    private fun retypeConstantOperands() {
        for (block in method.blocks) {
            for (insn in block.instructions) {
                when (insn.opcode) {
                    IrOpcode.CONST -> {
                        val lit = insn.args.firstOrNull() as? LiteralOperand ?: continue
                        insn.result?.type?.let { lit.type = it }
                    }
                    IrOpcode.IF -> {
                        if (insn.argCount < 2) continue
                        val reg = insn.getArg(0) as? RegisterOperand ?: continue
                        val lit = insn.getArg(1) as? LiteralOperand ?: continue
                        lit.type = reg.type
                    }
                    else -> {}
                }
            }
        }
    }

    private companion object {
        const val SEARCH_LIMIT = 16
        // Deterministic candidate order: numerics int-first, then references.
        val PREFERENCE = listOf(
            TypeKind.INT, TypeKind.FLOAT, TypeKind.LONG, TypeKind.DOUBLE,
            TypeKind.BOOLEAN, TypeKind.CHAR, TypeKind.SHORT, TypeKind.BYTE,
            TypeKind.OBJECT, TypeKind.ARRAY,
        )
    }
}
