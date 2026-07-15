package com.jadxmp.codegen

/**
 * An immutable symbol → **user comment** override map, consumed at codegen to render a free-text note
 * immediately before a class/method/field definition. It is the exact structural sibling of [AliasMap]
 * (which overrides a symbol's *name*); this one overrides nothing about the symbol — it only injects
 * decoration — but it is carried and threaded identically so codegen stays a pure read of the model plus
 * these two side maps.
 *
 * ## What it is
 * A read-only lookup keyed by the SAME [CodeNodeRef] identities the backend records as definition
 * metadata ([ClassNodeRef] by binary full name, [FieldNodeRef]/[MethodNodeRef] by binary owner + name
 * (+ erased arg descriptors)) — the identity a navigation tree / find-usages / rename already speaks. So a
 * UI can comment the symbol under the cursor with the very ref it would rename, and the note lands at that
 * symbol's definition when the class is rendered. The value is the raw (already-trimmed) comment text,
 * possibly multi-line; codegen sanitizes it to `//` line(s) at emit time (see [emitLineComment]).
 *
 * ## Why immutable + built once
 * jadxmp codegen renders classes lazily and in parallel over distinct nodes; every naming/decoration input
 * must therefore be COMPUTED ONCE up front and only READ during rendering, never written cross-node, so the
 * sequential and parallel paths produce identical output. This type is that once-built, read-only carrier —
 * `core:api` rebuilds it on every comment edit and swaps the reference in between renders.
 *
 * ## The safety invariant (load-bearing)
 * [EMPTY] (the default everywhere) must make the comment seam emit NOTHING, so output with no user comments
 * is byte-for-byte identical to output built without this feature — which is what keeps the differential
 * oracle (which always runs with zero comments) completely unaffected. Callers guarantee this by
 * short-circuiting on [isEmpty] before doing any comment-specific work, and by [of] returning [EMPTY] by
 * identity for an empty input.
 */
class CommentMap private constructor(private val comments: Map<CodeNodeRef, String>) {

    /** True when no comment is present — the signal every seam uses to take its byte-identical fast path. */
    val isEmpty: Boolean get() = comments.isEmpty()

    /** The user comment text for [ref] (raw, possibly multi-line), or `null` to emit no decoration. */
    fun commentFor(ref: CodeNodeRef): String? = comments[ref]

    companion object {
        /** The no-comment map: every consumer takes its byte-identical (emit-nothing) fast path. */
        val EMPTY: CommentMap = CommentMap(emptyMap())

        /**
         * Build a map from [comments]. Returns [EMPTY] for an empty input so consumers hit the fast path
         * by identity. The map is copied defensively so a later mutation of the source cannot leak in.
         */
        fun of(comments: Map<CodeNodeRef, String>): CommentMap =
            if (comments.isEmpty()) EMPTY else CommentMap(comments.toMap())
    }
}

/**
 * Emit [text] as one or more `//` line comments at the writer's current indent — the user-comment render
 * used by the source backends (Java today; Kotlin is a documented follow-up, and `//` is valid in both).
 * Matches jadx's `CommentStyle.LINE` (`// ` per line). The emitted comment carries **no** annotation: it is
 * pure decoration, so it never becomes a definition/reference and cannot perturb `nodeAt`/find-usages.
 *
 * ## Sanitization — a user comment can never break the source (CLAUDE rule 4)
 * The text is free-form, so we defuse every way it could escape a `//` line comment and spill into code:
 *  - **Real line terminators** (LF, CRLF, lone CR) are split into separate `//` lines — a newline can no
 *    longer end the comment early because each physical line becomes its own comment.
 *  - **NUL** is stripped (some toolchains reject a NUL in source; keeps output NUL-clean).
 *  - **Every Unicode-escape marker** (a backslash directly followed by `u`) is neutralized — the subtle
 *    one, and it must be ALL markers, not just newline escapes. Java expands Unicode escapes in an early
 *    lexer phase BEFORE comments are recognized (JLS §3.3 runs before §3.7), so a backslash-u inside a `//`
 *    comment is still processed: one NOT followed by exactly four hex digits is an *illegal unicode escape*
 *    that fails the WHOLE file (everyday comment text — a `C:\users` path, `\unit`, a trailing `\u`, `\u00`),
 *    and a well-formed one silently rewrites the comment (backslash-u-000a even ends the line). We break them
 *    all by inserting a space after the backslash; over-breaking a valid escape is harmless — it renders as
 *    e.g. `\ u0041` in a decorative comment and compiles. See [defuseUnicodeEscapeMarkers].
 *
 * A stray block-comment terminator needs no handling — it is inert inside a `//` line comment (only a block
 * comment would care). Trailing whitespace per line is dropped so no comment introduces a trailing-space
 * diff. An empty line renders as a bare `//` (no trailing space), keeping the writer's "blank lines carry no
 * trailing whitespace" invariant.
 */
fun CodeWriter.emitLineComment(text: String) {
    for (rawLine in splitCommentLines(text)) {
        // strip NUL by code point so this source file carries no literal NUL of its own
        val line = defuseUnicodeEscapeMarkers(rawLine.filter { it.code != 0 }).trimEnd()
        if (line.isEmpty()) add("//") else add("// ").add(line)
        newLine()
    }
}

/** Split on every Java line terminator (CRLF, lone CR, LF), so each physical line becomes its own `//`. */
private fun splitCommentLines(text: String): List<String> =
    text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

/**
 * Break EVERY Unicode-escape marker (a backslash directly followed by `u`) by inserting a space after the
 * backslash, so Java's pre-lex escape expansion (JLS §3.3, which runs before comment recognition in §3.7)
 * can't act on it inside the comment. This must cover ALL markers, not just newline escapes: a backslash-u
 * NOT followed by exactly four hex digits is an *illegal unicode escape* that fails the WHOLE file — so
 * everyday comment text like `C:\users`, `\unit`, a trailing `\u`, or `\u00` would otherwise make the class
 * uncompilable — and a well-formed escape (even a non-newline one) would silently rewrite the comment.
 * Over-breaking a valid escape is harmless: it renders as e.g. `\ u0041` in a decorative comment and
 * compiles. Fast-pathed on the common no-backslash line.
 */
private fun defuseUnicodeEscapeMarkers(line: String): String {
    if ('\\' !in line) return line
    val sb = StringBuilder(line.length + 2)
    var i = 0
    while (i < line.length) {
        if (startsUnicodeEscapeMarker(line, i)) {
            sb.append("\\ ") // the backslash is no longer directly followed by 'u' → no escape can form
        } else {
            sb.append(line[i])
        }
        i++
    }
    return sb.toString()
}

/**
 * True when [s] at [at] is a backslash that begins a Unicode-escape marker — i.e. it is directly followed by
 * `u`. That single `\u` is the whole trigger: it starts every escape spelling (including the multi-`u`
 * `\uu000a` form), and breaking right after this backslash defuses the entire marker whatever follows it.
 */
private fun startsUnicodeEscapeMarker(s: String, at: Int): Boolean =
    s[at] == '\\' && at + 1 < s.length && s[at + 1] == 'u'
