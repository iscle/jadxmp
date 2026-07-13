package com.jadxmp.ir.type

/**
 * Result of comparing two [IrType]s from the point of view of the **first** operand.
 *
 * The lattice is ordered by "narrowness": a narrower type is more specific (lower in the lattice,
 * closer to a concrete type / the bottom conflict), a wider type is more general (higher, closer to
 * the fully-unknown top). [IrType.compareTo] answers "where does `this` sit relative to `other`":
 *
 * - [EQUAL]    — the two types are the same point in the lattice.
 * - [NARROWER] — `this` is strictly more specific than `other` (e.g. `String` vs `Object`).
 * - [WIDER]    — `this` is strictly more general than `other` (e.g. `Object` vs `String`).
 * - [CONFLICT] — no common lower bound exists; the two can never be the same value (e.g. `int` vs an object).
 * - [UNRELATED] — comparable in principle but undecidable **without the class hierarchy**, which the
 *                 IR module deliberately does not hold (two unrelated named classes). The pipeline
 *                 resolves these against the loaded class graph.
 *
 * The `*_BY_GENERIC` variants carry the same narrow/wide/conflict meaning but where the *only*
 * difference is generic parameterization (`List` vs `List<String>`), so codegen can treat the raw
 * relation as compatible while still knowing a generic refinement happened.
 *
 * jadx: TypeCompareEnum
 */
enum class TypeRelation {
    EQUAL,
    NARROWER,
    NARROWER_BY_GENERIC,
    WIDER,
    WIDER_BY_GENERIC,
    CONFLICT,
    CONFLICT_BY_GENERIC,
    UNRELATED,
    ;

    /** Swap the point of view to the second operand (narrower⇄wider). Reflexive/conflict/unrelated are unchanged. */
    fun invert(): TypeRelation = when (this) {
        NARROWER -> WIDER
        NARROWER_BY_GENERIC -> WIDER_BY_GENERIC
        WIDER -> NARROWER
        WIDER_BY_GENERIC -> NARROWER_BY_GENERIC
        EQUAL, CONFLICT, CONFLICT_BY_GENERIC, UNRELATED -> this
    }

    val isEqual: Boolean get() = this == EQUAL
    val isNarrower: Boolean get() = this == NARROWER || this == NARROWER_BY_GENERIC
    val isWider: Boolean get() = this == WIDER || this == WIDER_BY_GENERIC
    val isConflict: Boolean get() = this == CONFLICT || this == CONFLICT_BY_GENERIC
    val isNarrowerOrEqual: Boolean get() = isEqual || isNarrower
    val isWiderOrEqual: Boolean get() = isEqual || isWider
}
