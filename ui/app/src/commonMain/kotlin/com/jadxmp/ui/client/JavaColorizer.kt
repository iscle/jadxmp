package com.jadxmp.ui.client

import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.CodeMetadata
import com.jadxmp.codegen.CodeNodeRef
import com.jadxmp.codegen.DefinitionAnnotation
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.codegen.ReferenceAnnotation

/**
 * Turns decompiled Java text plus the engine's per-offset [CodeMetadata] into the viewer's line/token
 * model, combining the two coloring sources (see [JavaLexer] for the rationale):
 *  - **[JavaLexer]** classifies token *shapes* (keywords, strings, chars, numbers, comments,
 *    annotations, punctuation) — the syntactic scaffolding the backend does not annotate.
 *  - **[CodeMetadata]** then *overrides* identifier tokens sitting at an annotated offset with a
 *    precise [TokenKind] (class→TYPE, method→METHOD, field→FIELD) and a jump-to-definition target.
 *
 * Kept separate from [CoreApiDecompilerClient] (and free of any `core:api` `Decompiler`) so the whole
 * coloring pipeline is unit-testable with synthetic metadata. Pure Kotlin — wasm-safe.
 */
internal object JavaColorizer {

    /**
     * Colorize [source] into 1-based [CodeLine]s.
     *
     * @param resolveClass maps a class/owner fully-qualified name (as it appears in a [CodeNodeRef]) to
     *   a navigable [NodeId], or null when the target is not a loaded class. The caller normalizes
     *   nested-class separators, so a reference may arrive dotted (`Outer.Inner`) or `$`-qualified
     *   (`Outer$Inner`); the resolver reconciles both to the actual loaded name.
     */
    fun colorize(
        source: String,
        metadata: CodeMetadata?,
        resolveClass: (fullName: String) -> NodeId?,
    ): List<CodeLine> {
        val tokens = JavaLexer.lex(source)
        val finalTokens = ArrayList<CodeToken>(tokens.size)
        for ((index, tok) in tokens.withIndex()) {
            finalTokens += if (tok.isIdentifier) {
                classifyIdentifier(tok, metadata, nextSignificant(tokens, index), resolveClass)
            } else {
                CodeToken(tok.text, tok.kind)
            }
        }
        return groupIntoLines(finalTokens)
    }

    /** Engine metadata first (precise kind + jump target), else a syntactic name heuristic. */
    private fun classifyIdentifier(
        tok: JavaLexer.LexToken,
        metadata: CodeMetadata?,
        next: JavaLexer.LexToken?,
        resolveClass: (String) -> NodeId?,
    ): CodeToken {
        when (val annotation = metadata?.at(tok.offset)) {
            is DefinitionAnnotation -> return refine(tok.text, annotation.ref, resolveClass)
            is ReferenceAnnotation -> return refine(tok.text, annotation.ref, resolveClass)
            else -> Unit // variables and un-annotated tokens fall through to the heuristic
        }
        val kind = when {
            tok.text.first().isUpperCase() -> TokenKind.TYPE
            next != null && next.text.startsWith("(") -> TokenKind.METHOD
            else -> TokenKind.PLAIN
        }
        return CodeToken(tok.text, kind)
    }

    /** Map an engine node reference to a [TokenKind] and a navigable [NodeId] (via [resolveClass]). */
    private fun refine(text: String, ref: CodeNodeRef, resolveClass: (String) -> NodeId?): CodeToken =
        when (ref) {
            is ClassNodeRef -> CodeToken(text, TokenKind.TYPE, resolveClass(ref.fullName))
            is MethodNodeRef -> CodeToken(text, TokenKind.METHOD, resolveClass(ref.ownerClass))
            is FieldNodeRef -> CodeToken(text, TokenKind.FIELD, resolveClass(ref.ownerClass))
            else -> CodeToken(text, TokenKind.PLAIN)
        }

    /** The next non-blank token after [from], used by the "name before `(`" method heuristic. */
    private fun nextSignificant(tokens: List<JavaLexer.LexToken>, from: Int): JavaLexer.LexToken? {
        var i = from + 1
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.text.isNotBlank()) return t
            i++
        }
        return null
    }

    /**
     * Fold the flat token list into 1-based [CodeLine]s, splitting any token that spans newlines
     * (whitespace runs, multi-line block comments) at each `\n`. Identifier tokens never contain a
     * newline, so their metadata-derived kind/target survives intact. `internal` for direct testing.
     */
    internal fun groupIntoLines(tokens: List<CodeToken>): List<CodeLine> {
        val lines = ArrayList<CodeLine>()
        var current = ArrayList<CodeToken>()
        var lineNo = 1
        for (token in tokens) {
            if ('\n' !in token.text) {
                if (token.text.isNotEmpty()) current += token
                continue
            }
            val pieces = token.text.split('\n')
            for ((index, piece) in pieces.withIndex()) {
                if (index > 0) {
                    lines += CodeLine(lineNo, current)
                    lineNo++
                    current = ArrayList()
                }
                if (piece.isNotEmpty()) current += CodeToken(piece, token.kind, token.definition)
            }
        }
        lines += CodeLine(lineNo, current)
        return lines
    }
}
