package com.jadxmp.ui.client

/**
 * A lightweight, pure-Kotlin lexer for the *syntactic* layer of Java source coloring.
 *
 * ## Why a lexer at all — the coloring split
 * The engine's [com.jadxmp.codegen.CodeMetadata] is authoritative for **semantics**: which
 * identifiers are real class / method / field references and where their definitions live
 * (jump-to-definition, find-usages). It does **not**, however, annotate every keyword, string,
 * number, comment or punctuation mark — those carry no navigational meaning, so the backend does not
 * emit an annotation for each. Rendering readable source therefore needs a purely *syntactic* pass to
 * color that scaffolding.
 *
 * So [CoreApiDecompilerClient] uses **two complementary sources**:
 *  - this lexer classifies the token *shapes* (keyword / type / string / char / number / comment /
 *    annotation / punctuation, and a heuristic guess for bare identifiers), and
 *  - the engine metadata then **overrides** identifier tokens that sit at an annotated offset, giving
 *    them a precise [TokenKind] and a jump target — semantics beat the heuristic every time.
 *
 * This is deliberately different from [StubHighlighter] (a naive per-line regex used only to fake the
 * stub). This lexer is a real character scanner: it handles multi-line block comments, string/char
 * escapes and numeric suffixes, and it reports each token's **absolute character offset** into the
 * source so metadata lookups line up exactly with the offsets the backend recorded.
 *
 * Pure Kotlin, no `java.*` — safe in `commonMain` on every target including wasmJs.
 */
internal object JavaLexer {

    /** One lexed token: its [text], absolute [offset] into the source, syntactic [kind], and whether
     * it is a bare identifier eligible for metadata-driven refinement. */
    data class LexToken(
        val text: String,
        val offset: Int,
        val kind: TokenKind,
        val isIdentifier: Boolean,
    )

    private val keywords: Set<String> = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null", "var", "record", "sealed", "permits", "yield",
    )

    fun lex(src: String): List<LexToken> {
        val out = ArrayList<LexToken>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            val start = i
            when {
                c == ' ' || c == '\t' || c == '\r' || c == '\n' -> {
                    i++
                    while (i < n && (src[i] == ' ' || src[i] == '\t' || src[i] == '\r' || src[i] == '\n')) i++
                    out += LexToken(src.substring(start, i), start, TokenKind.PLAIN, isIdentifier = false)
                }
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    i += 2
                    while (i < n && src[i] != '\n') i++
                    out += LexToken(src.substring(start, i), start, TokenKind.COMMENT, isIdentifier = false)
                }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                    i += 2
                    while (i < n && !(src[i] == '*' && i + 1 < n && src[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(n) // consume the closing */ (tolerate an unterminated comment)
                    out += LexToken(src.substring(start, i), start, TokenKind.COMMENT, isIdentifier = false)
                }
                c == '"' -> {
                    i = scanQuoted(src, i, '"')
                    out += LexToken(src.substring(start, i), start, TokenKind.STRING, isIdentifier = false)
                }
                c == '\'' -> {
                    // Char literals share the string color (there is no dedicated CHAR TokenKind).
                    i = scanQuoted(src, i, '\'')
                    out += LexToken(src.substring(start, i), start, TokenKind.STRING, isIdentifier = false)
                }
                c == '@' && i + 1 < n && isIdentStart(src[i + 1]) -> {
                    i++
                    while (i < n && isIdentPart(src[i])) i++
                    out += LexToken(src.substring(start, i), start, TokenKind.ANNOTATION, isIdentifier = false)
                }
                c.isDigit() || (c == '.' && i + 1 < n && src[i + 1].isDigit()) -> {
                    i++
                    // A permissive numeric run: digits, hex, underscores, dots, exponent signs, suffixes.
                    while (i < n && (isIdentPart(src[i]) || src[i] == '.' ||
                            ((src[i] == '+' || src[i] == '-') && (src[i - 1] == 'e' || src[i - 1] == 'E')))
                    ) {
                        i++
                    }
                    out += LexToken(src.substring(start, i), start, TokenKind.NUMBER, isIdentifier = false)
                }
                isIdentStart(c) -> {
                    i++
                    while (i < n && isIdentPart(src[i])) i++
                    val text = src.substring(start, i)
                    if (text in keywords) {
                        out += LexToken(text, start, TokenKind.KEYWORD, isIdentifier = false)
                    } else {
                        // Provisional PLAIN; the client refines it from metadata, then a name heuristic.
                        out += LexToken(text, start, TokenKind.PLAIN, isIdentifier = true)
                    }
                }
                else -> {
                    i++
                    out += LexToken(src.substring(start, i), start, TokenKind.PUNCTUATION, isIdentifier = false)
                }
            }
        }
        return out
    }

    /** Advance past a quoted literal starting at [open] (the opening quote), honoring `\` escapes. */
    private fun scanQuoted(src: String, open: Int, quote: Char): Int {
        var i = open + 1
        val n = src.length
        while (i < n) {
            val ch = src[i]
            when {
                ch == '\\' -> i += 2 // skip the escaped character
                ch == quote -> return i + 1
                ch == '\n' -> return i // unterminated on this line — bail so coloring stays sane
                else -> i++
            }
        }
        return n
    }

    private fun isIdentStart(c: Char): Boolean = c == '_' || c == '$' || c.isLetter()

    private fun isIdentPart(c: Char): Boolean = c == '_' || c == '$' || c.isLetterOrDigit()
}
