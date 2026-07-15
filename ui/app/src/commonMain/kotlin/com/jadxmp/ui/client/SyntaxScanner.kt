package com.jadxmp.ui.client

/**
 * A small, streaming, fault-isolated tokenizer for the "C-family" resource languages (JSON, SQL, JS)
 * whose lexical grammar shares the same primitives: line comments, one cross-line block comment,
 * quoted strings, numbers, keywords and punctuation. It mirrors [XmlColorizer]'s design — a per-line
 * state machine that carries only the "inside a block comment" flag across lines — so multi-line block
 * comments colorize correctly while everything else is line-local.
 *
 * Each language supplies a [Lexicon]; [scan] returns the viewer's 1-based [CodeLine] model with the
 * same [TokenKind] palette the other colorizers use (keywords → KEYWORD, strings → STRING, numbers →
 * NUMBER, comments → COMMENT, punctuation → PUNCTUATION, other words → PLAIN or [Lexicon.upperWordKind]).
 *
 * Robustness (rule 4, fault isolation): malformed input never throws. An unterminated string colors to
 * end of line; an unterminated block comment continues on the next line; an unrecognized character
 * degrades to a plain token. Pure Kotlin — wasm/js/jvm-safe.
 */
internal object SyntaxScanner {

    fun scan(text: String, lex: Lexicon): List<CodeLine> {
        val result = ArrayList<CodeLine>()
        var inBlock = false
        for ((idx, raw) in text.split('\n').withIndex()) {
            val line = raw.trimEnd('\r')
            val (tokens, next) = tokenizeLine(line, lex, inBlock)
            result += CodeLine(idx + 1, tokens)
            inBlock = next
        }
        return result
    }

    /** Tokenize one [line]; returns its tokens and whether a block comment is still open at line end. */
    private fun tokenizeLine(line: String, lex: Lexicon, incomingBlock: Boolean): Pair<List<CodeToken>, Boolean> {
        val tokens = ArrayList<CodeToken>()
        val pending = StringBuilder()
        fun flushPlain() {
            if (pending.isNotEmpty()) {
                tokens += CodeToken(pending.toString(), TokenKind.PLAIN)
                pending.clear()
            }
        }

        // Captured as locals so the null-checks below give clean, unambiguous smart-casts.
        val blockOpen = lex.blockOpen
        val blockClose = lex.blockClose

        var i = 0
        // Finish an already-open block comment before anything else on the line.
        if (incomingBlock && blockOpen != null && blockClose != null) {
            val end = line.indexOf(blockClose)
            if (end < 0) {
                if (line.isNotEmpty()) tokens += CodeToken(line, TokenKind.COMMENT)
                return tokens to true
            }
            tokens += CodeToken(line.substring(0, end + blockClose.length), TokenKind.COMMENT)
            i = end + blockClose.length
        }

        while (i < line.length) {
            val c = line[i]
            when {
                // Block comment — may run to the end of this line and continue on the next.
                blockOpen != null && blockClose != null && line.startsWith(blockOpen, i) -> {
                    flushPlain()
                    val end = line.indexOf(blockClose, i + blockOpen.length)
                    if (end < 0) {
                        tokens += CodeToken(line.substring(i), TokenKind.COMMENT)
                        return tokens to true
                    }
                    tokens += CodeToken(line.substring(i, end + blockClose.length), TokenKind.COMMENT)
                    i = end + blockClose.length
                }
                // Line comment — runs to end of line.
                lex.lineComments.any { line.startsWith(it, i) } -> {
                    flushPlain()
                    tokens += CodeToken(line.substring(i), TokenKind.COMMENT)
                    i = line.length
                }
                // String literal (quote-aware; respects backslash escaping; unterminated → to EOL).
                c in lex.stringQuotes -> {
                    flushPlain()
                    val end = stringEnd(line, i, c)
                    tokens += CodeToken(line.substring(i, end), TokenKind.STRING)
                    i = end
                }
                // Number literal. Identifiers never start with a digit (they take the word branch on
                // their first letter), so a digit here is always a genuine number start.
                c.isDigit() || (c == '.' && i + 1 < line.length && line[i + 1].isDigit()) -> {
                    flushPlain()
                    val end = numberEnd(line, i)
                    tokens += CodeToken(line.substring(i, end), TokenKind.NUMBER)
                    i = end
                }
                // Word: keyword, upper-cased "type", or a plain identifier.
                isWordStart(c) -> {
                    flushPlain()
                    val end = wordEnd(line, i)
                    val word = line.substring(i, end)
                    tokens += CodeToken(word, keywordKind(word, lex))
                    i = end
                }
                isPunct(c) -> {
                    flushPlain()
                    tokens += CodeToken(c.toString(), TokenKind.PUNCTUATION)
                    i++
                }
                else -> {
                    pending.append(c); i++ // whitespace and anything unrecognized
                }
            }
        }
        flushPlain()
        return tokens to false
    }

    private fun keywordKind(word: String, lex: Lexicon): TokenKind {
        val isKeyword = if (lex.ignoreKeywordCase) word.lowercase() in lex.keywords else word in lex.keywords
        return when {
            isKeyword -> TokenKind.KEYWORD
            lex.upperWordKind != null && word.first().isUpperCase() -> lex.upperWordKind
            else -> TokenKind.PLAIN
        }
    }
}

/**
 * The per-language lexical configuration for [SyntaxScanner]. Keep [keywords] **lower-cased** when
 * [ignoreKeywordCase] is true (the scanner lower-cases the candidate word before the set lookup).
 */
internal class Lexicon(
    val lineComments: List<String> = emptyList(),
    val blockOpen: String? = null,
    val blockClose: String? = null,
    val stringQuotes: String = "\"'",
    val keywords: Set<String> = emptySet(),
    val ignoreKeywordCase: Boolean = false,
    /** Kind for a non-keyword word starting with an upper-case letter (e.g. JS `Math`); null → PLAIN. */
    val upperWordKind: TokenKind? = null,
)

// ── shared lexical primitives (reused by the dedicated CSS/YAML/properties colorizers) ──────────────

/** A word starts with a letter, `_` or `$`. */
internal fun isWordStart(c: Char): Boolean = c == '_' || c == '$' || c.isLetter()

/** A word continues with letters, digits, `_` or `$`. */
internal fun isWordChar(c: Char): Boolean = c == '_' || c == '$' || c.isLetterOrDigit()

/** End (exclusive) of the word starting at [start]. */
internal fun wordEnd(line: String, start: Int): Int {
    var i = start
    while (i < line.length && isWordChar(line[i])) i++
    return i
}

/**
 * End (exclusive, past the closing quote) of a `[quote]…[quote]` string starting at [start]. A `\`
 * escapes the next character so an escaped quote does not end the string; an unterminated string
 * returns the line length (colors to EOL) rather than throwing.
 */
internal fun stringEnd(line: String, start: Int, quote: Char): Int {
    var i = start + 1
    while (i < line.length) {
        when (line[i]) {
            '\\' -> i += 2
            quote -> return i + 1
            else -> i++
        }
    }
    return line.length
}

/**
 * End (exclusive) of a numeric literal starting at [start]. Handles `0x`/`0b` prefixes, a fraction, an
 * exponent, and trailing suffix letters / a `%` (so CSS units and `1.5f`/`10L` are absorbed whole).
 * Bounded and total — it always advances past at least one character.
 */
internal fun numberEnd(line: String, start: Int): Int {
    val n = line.length
    var i = start
    if (line[i] == '0' && i + 1 < n && (line[i + 1] == 'x' || line[i + 1] == 'X' || line[i + 1] == 'b' || line[i + 1] == 'B')) {
        i += 2
        while (i < n && (line[i].isLetterOrDigit() || line[i] == '_')) i++
        return i
    }
    while (i < n && (line[i].isDigit() || line[i] == '_')) i++
    if (i < n && line[i] == '.') {
        i++
        while (i < n && (line[i].isDigit() || line[i] == '_')) i++
    }
    if (i < n && (line[i] == 'e' || line[i] == 'E')) {
        var j = i + 1
        if (j < n && (line[j] == '+' || line[j] == '-')) j++
        if (j < n && line[j].isDigit()) {
            i = j
            while (i < n && line[i].isDigit()) i++
        }
    }
    while (i < n && (line[i].isLetter() || line[i] == '%')) i++ // unit / type suffix (px, %, f, L, …)
    return if (i > start) i else start + 1
}

/** Punctuation characters the generic scanner emits as their own [TokenKind.PUNCTUATION] token. */
internal fun isPunct(c: Char): Boolean = c in "{}[]()<>:;,.=+-*/%!&|^~?@"

/** Split [text] into plain (uncolored) lines — the honest fallback for an unknown resource type. */
internal fun plainLines(text: String): List<CodeLine> =
    text.split('\n').mapIndexed { i, raw ->
        val line = raw.trimEnd('\r')
        CodeLine(i + 1, if (line.isEmpty()) emptyList() else listOf(CodeToken(line, TokenKind.PLAIN)))
    }
