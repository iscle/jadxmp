package com.jadxmp.api

import com.jadxmp.codegen.CodeNodeRef

/**
 * The outcome of a user-driven [Decompiler.rename]. A rename either takes effect ([Applied]) or is
 * **rejected without changing anything** — jadxmp never silently clobbers a name, so a bad request is
 * reported back verbatim rather than being quietly mangled by codegen's fallback disambiguation.
 *
 * Every case carries the [target] it concerns and, for the rejections, a short human-readable [reason]/
 * conflict a UI can surface directly (a later phase's rename dialog). The rejections are exhaustive:
 *  - [InvalidName] — the requested spelling is not a legal, non-reserved Java identifier, so emitting it
 *    verbatim (codegen copies a user override through *unsanitized*) would produce uncompilable source.
 *  - [Collision] — the name is legal but already taken by a sibling in the same scope; applying it would
 *    force codegen's within-scope uniqueness pass to suffix one of them, defeating the user's intent.
 *  - [UnrenamableTarget] — the symbol cannot be renamed in this version (not in the loaded model, a
 *    library symbol, a nested/inner class, a constructor/static-initializer, or a member of an
 *    enum/annotation whose members are reshaped by dedicated reconstructors).
 *
 * jadx: the GUI's rename validation (`RenameDialog` / `UserRenames`), redesigned here as a pure result.
 */
public sealed interface RenameResult {

    /** The symbol the rename concerned — the exact [CodeNodeRef] passed to [Decompiler.rename]. */
    public val target: CodeNodeRef

    /** The rename took effect; [name] is the identifier now emitted at the definition and every use. */
    public data class Applied(override val target: CodeNodeRef, val name: String) : RenameResult

    /** Rejected: [requestedName] is not a legal, non-reserved Java identifier ([reason] says why). */
    public data class InvalidName(
        override val target: CodeNodeRef,
        val requestedName: String,
        val reason: String,
    ) : RenameResult

    /** Rejected: [requestedName] collides with another symbol in the same scope ([conflict] describes it). */
    public data class Collision(
        override val target: CodeNodeRef,
        val requestedName: String,
        val conflict: String,
    ) : RenameResult

    /** Rejected: [target] is not a renamable symbol in this version ([reason] says why). */
    public data class UnrenamableTarget(override val target: CodeNodeRef, val reason: String) : RenameResult
}
