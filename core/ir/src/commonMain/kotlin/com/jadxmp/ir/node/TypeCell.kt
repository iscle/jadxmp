package com.jadxmp.ir.node

import com.jadxmp.ir.type.IrType

/**
 * The mutable type-inference cell carried by an [SsaValue].  **jadx: TypeInfo**
 *
 * Inference starts every value at [IrType.UNKNOWN] and progressively **narrows** [type] as
 * constraints arrive, via [narrow] (the lattice meet). Once [immutable] is set — because the type is
 * fixed by the bytecode (a field access, a checked cast, a method signature) — narrowing is locked.
 *
 * This is a single mutable slot on purpose: it is the one place a value's reconstructed type is
 * recorded, so passes read/refine a value's type without walking every occurrence.
 */
class TypeCell(
    initial: IrType = IrType.UNKNOWN,
) {
    var type: IrType = initial
        private set

    /** When true, [type] is authoritative and [narrow]/[set] must not change it. */
    var immutable: Boolean = false
        private set

    /** Force the type and lock it (used for bytecode-guaranteed types). */
    fun fix(fixedType: IrType) {
        type = fixedType
        immutable = true
    }

    /** Directly set the current best guess (no locking). Ignored once [immutable]. */
    fun set(newType: IrType) {
        if (!immutable) type = newType
    }

    /**
     * Meet the current type with [bound], returning a distinct outcome for each case so a caller can
     * never silently drop a conflict:
     * - [NarrowResult.NARROWED] — [type] moved to a strictly narrower value.
     * - [NarrowResult.UNCHANGED] — [bound] was already implied; nothing changed.
     * - [NarrowResult.CONFLICT] — [bound] is incompatible with [type]; [type] is left untouched and
     *   inference must treat this as an error signal, not a no-op.
     * - [NarrowResult.LOCKED] — the cell is [immutable]; the incoming bound is ignored.
     *
     * Note: an [IrType.merge] of `null` from an UNRELATED pair (two named classes)
     * also reports [NarrowResult.CONFLICT] here, because the meet cannot be computed without the
     * class graph; a caller with the hierarchy resolves it and calls [set]/[fix] explicitly.
     */
    fun narrow(bound: IrType): NarrowResult {
        if (immutable) return NarrowResult.LOCKED
        val merged = type.merge(bound) ?: return NarrowResult.CONFLICT
        if (merged == type) return NarrowResult.UNCHANGED
        type = merged
        return NarrowResult.NARROWED
    }
}

/** Outcome of [TypeCell.narrow]. */
enum class NarrowResult { NARROWED, UNCHANGED, CONFLICT, LOCKED }
