package com.jadxmp.codegen.java

import com.jadxmp.codegen.CodeWriter
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.attr.DecompileError
import com.jadxmp.ir.attr.IrAttrs

/**
 * The error-accounting honesty invariant for the Java backend, in ONE place — reachable from both
 * [JavaCodeGenerator] and [MethodBodyWriter]. Mirrors `core:codegen-kotlin`'s `KotlinErrorMarkers`.
 *
 * Invariant: any output the backend renders that is broken / not faithfully reconstructed — and so
 * carries an honesty marker (`// JADXMP ERROR`, a pre-structuring `blockN:` label, an `/* unhandled … */`
 * note, an inline `/* OPCODE */` on an instruction the writer cannot lower, or an `/* empty */`
 * placeholder) — MUST also flag [AttrFlag.HAS_ERROR] on the node that `Decompiler.countErrors` sums over
 * for that output location (the owning [com.jadxmp.ir.node.IrClass] for a field/enum constant, the
 * [com.jadxmp.ir.node.IrMethod] for a body statement).
 *
 * Why: `countErrors` / `isFullyStructured` / the accuracy scoreboard (`reportedErrors`) read HAS_ERROR,
 * and `JavaCodeGenerator.emitErrorComment` re-surfaces a flagged node's error at its top. A marker
 * emitted WITHOUT the flag makes every one of those undercount — the class reports zero errors while its
 * source is knowingly wrong (CLAUDE rule 4: no silent code loss / honest error reporting). Routing every
 * marker site through these helpers makes the marker↔flag coupling structural, so the bug class cannot
 * recur.
 *
 * Mirrors `RenderabilityGuard.flagIfUnrenderable` (core:api): set [IrAttrs.ERROR] once (never clobbering
 * a pre-existing, more-specific diagnostic) and add the flag.
 */

/** Flag [owner] HAS_ERROR (with [reason] as its diagnostic), then emit `// JADXMP ERROR: <reason>`. */
internal fun CodeWriter.emitErrorMarker(owner: AttrNode, reason: String) {
    flagError(owner, reason)
    // The error is now shown inline at this exact site, so the two-pass generator's top-of-node
    // `emitErrorComment` must not ALSO emit its own identical `// JADXMP ERROR` line (that would
    // duplicate the same string — the flag set in pass 1 is visible to pass 2's top comment).
    owner[ERROR_SURFACED_INLINE] = true
    add("// JADXMP ERROR: ").add(reason).newLine()
}

/**
 * Flag [owner] HAS_ERROR with [reason] for an honesty marker whose *comment text* is intentionally not
 * the `// JADXMP ERROR` form (a `blockN:` label, `/* unhandled … */`, `/* OPCODE */`, or `/* empty */`).
 * The caller emits its own comment; this only couples the HAS_ERROR flag to it so error accounting stays
 * honest. Idempotent — safe to call for a node a prior pass already flagged (e.g. a branchy method
 * RenderabilityGuard flagged before codegen ran). Deliberately leaves [ERROR_SURFACED_INLINE] unset so
 * the node still gets the honest `// JADXMP ERROR` summary line at its top — a distinct string, so no
 * duplication with the inline non-`// JADXMP ERROR` marker.
 */
internal fun flagError(owner: AttrNode, reason: String) {
    if (!owner.contains(IrAttrs.ERROR)) {
        owner[IrAttrs.ERROR] = DecompileError(reason)
    }
    owner.add(AttrFlag.HAS_ERROR)
}
