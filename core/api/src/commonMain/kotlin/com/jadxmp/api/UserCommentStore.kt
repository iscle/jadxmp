package com.jadxmp.api

import com.jadxmp.codegen.CodeNodeRef

/**
 * The session-local store of **user-authored comments** for one loaded [Decompiler] — the comment analogue
 * of [UserRenameStore]. Where the rename store feeds the codegen [com.jadxmp.codegen.AliasMap] (a symbol →
 * *name* override), this one feeds the codegen [com.jadxmp.codegen.CommentMap] (a symbol → *note* to render
 * immediately before its definition). Both are keyed by the identical [CodeNodeRef] identity the backend
 * records as definition metadata and find-usages/rename already speak, so a UI can comment the symbol under
 * the cursor with the very ref it would rename.
 *
 * ## Why it is so much simpler than the rename store
 * A rename must be *validated and collision-checked against the loaded model* before it is allowed, because
 * codegen copies a user name into the source verbatim and a bad one produces uncompilable Java. A comment
 * has no such constraint: it is free text that codegen renders as `//` line(s), and codegen sanitizes it so
 * it can never break the source (multi-line becomes multiple `//` lines; anything that could escape a line
 * comment is defused — see [com.jadxmp.codegen.emitLineComment]). So this store does **no validation beyond
 * trimming**: it never rejects, never inspects the model, and never fails. It only records or removes.
 *
 * ## Set / remove semantics
 * [set] trims the text (whitespace, including surrounding blank lines, is normalized away); **blank text
 * removes** the comment, matching the rename store's "empty clears" feel and giving a UI one call for both
 * edit and delete. Interior newlines are preserved (a multi-line comment stays multi-line). Both [set] and
 * [remove] report whether the store actually changed, so the [Decompiler] rebuilds the effective map and
 * invalidates render caches only when something moved (no needless cache churn — mirrors `clearRenames`).
 *
 * Not thread-safe: it is mutated only on the [Decompiler]'s single-threaded cached path, alongside the
 * class cache it shares invalidation with.
 */
internal class UserCommentStore {

    // Insertion-ordered so [snapshot]/[comments] enumerate deterministically (a UI list, a future .jadx
    // persistence). Keyed by the SAME CodeNodeRef identity codegen looks up and find-usages inverts.
    private val comments = LinkedHashMap<CodeNodeRef, String>()

    /** True when the user has authored no comments — the [Decompiler] uses this to keep the map [empty]. */
    val isEmpty: Boolean get() = comments.isEmpty()

    /**
     * The authored comments, in application order. Returned as a read-only view for building the effective
     * [com.jadxmp.codegen.CommentMap]; `CommentMap.of` copies defensively, so the live map never leaks.
     */
    fun comments(): Map<CodeNodeRef, String> = comments

    /** An immutable snapshot for enumeration (UI list / future persistence), independent of later edits. */
    fun snapshot(): Map<CodeNodeRef, String> = LinkedHashMap(comments)

    /** Drop every user comment (the [Decompiler] then rebuilds the map back to empty). */
    fun clear() {
        comments.clear()
    }

    /**
     * Record [text] as the comment on [target], or — when [text] is blank after trimming — remove any
     * existing comment (so a UI's "clear the field" deletes). Returns `true` iff the store changed (a new
     * comment, a different comment, or a removal), which is the [Decompiler]'s signal to rebuild + invalidate.
     * Never rejects and never throws: a comment is free text (see the class doc).
     */
    fun set(target: CodeNodeRef, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return comments.remove(target) != null
        val previous = comments.put(target, trimmed)
        return previous != trimmed
    }

    /** Remove any comment on [target]. Returns `true` iff one existed (the [Decompiler]'s rebuild signal). */
    fun remove(target: CodeNodeRef): Boolean = comments.remove(target) != null
}
