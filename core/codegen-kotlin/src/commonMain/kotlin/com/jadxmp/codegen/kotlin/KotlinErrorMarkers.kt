package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodeWriter
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.AttrKey
import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.attr.DecompileError
import com.jadxmp.ir.attr.IrAttrs

/**
 * The error-accounting honesty invariant for the Kotlin backend, in ONE place.
 *
 * Invariant: any output the backend renders that is broken / not faithfully reconstructed — and so
 * carries an honesty marker (`// JADXMP ERROR`, a pre-structuring `// block N` label, an
 * `/* unhandled … */` note, or an inline `/* OPCODE */` on an instruction the writer cannot lower) —
 * MUST also flag [AttrFlag.HAS_ERROR] on the node that `Decompiler.countErrors` sums over for that
 * output location (the owning [com.jadxmp.ir.node.IrClass] for a field/property, the
 * [com.jadxmp.ir.node.IrMethod] for a body statement).
 *
 * Why: `countErrors` / `isFullyStructured` / the Kotlin accuracy scoreboard (`reportedErrors`) read
 * HAS_ERROR, and `KotlinCodeGenerator.emitErrorComment` re-surfaces a flagged node's error at its top.
 * A marker emitted WITHOUT the flag makes every one of those undercount — the class reports zero
 * errors while its source is knowingly wrong (CLAUDE rule 4: no silent code loss / honest error
 * reporting). Routing every marker site through these helpers makes the marker↔flag coupling
 * structural, so the bug class cannot recur.
 *
 * Mirrors `RenderabilityGuard.flagIfUnrenderable` (core:api): set [IrAttrs.ERROR] once (never
 * clobbering a pre-existing, more-specific diagnostic) and add the flag.
 */

/**
 * Set on a node whose error is ALREADY shown inline in the source via [emitErrorMarker], so
 * `emitErrorComment` must NOT also emit its own `// JADXMP ERROR` line at the node's top — that would
 * duplicate the same marker (the generator renders each class twice: an import-collecting pass then the
 * real pass, and the flag set on the shared IR node during pass 1 is visible to pass 2's top-of-node
 * `emitErrorComment`). Only [emitErrorMarker] sets this; the `flagError`-only markers (`// block`,
 * `/* unhandled … */`, `/* OPCODE */`) deliberately leave it unset so their node still gets the honest
 * `// JADXMP ERROR` summary line at the top (a distinct string — no duplication).
 */
internal val ERROR_SURFACED_INLINE: AttrKey<Boolean> = AttrKey("kotlin.codegen.errorSurfacedInline")

/** Flag [owner] HAS_ERROR (with [reason] as its diagnostic), then emit `// JADXMP ERROR: <reason>`. */
internal fun CodeWriter.emitErrorMarker(owner: AttrNode, reason: String) {
    flagError(owner, reason)
    owner[ERROR_SURFACED_INLINE] = true
    add("// JADXMP ERROR: ").add(reason).newLine()
}

/**
 * Flag [owner] HAS_ERROR with [reason] for an honesty marker whose *comment text* is intentionally
 * not the `// JADXMP ERROR` form (a `// block N` label, `/* unhandled … */`, or `/* OPCODE */`). The
 * caller emits its own comment; this only couples the HAS_ERROR flag to it so error accounting stays
 * honest. Idempotent — safe to call for a node a prior pass already flagged (e.g. a branchy method
 * RenderabilityGuard flagged before codegen ran).
 */
internal fun flagError(owner: AttrNode, reason: String) {
    if (!owner.contains(IrAttrs.ERROR)) {
        owner[IrAttrs.ERROR] = DecompileError(reason)
    }
    owner.add(AttrFlag.HAS_ERROR)
}
