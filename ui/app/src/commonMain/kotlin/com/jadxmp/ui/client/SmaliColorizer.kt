package com.jadxmp.ui.client

/**
 * Minimal, line-based colorizer for the smali (bytecode) view. Smali has no engine [CodeMetadata]
 * (it is disassembled straight from the input model, bypassing the pipeline), so there is nothing to
 * override identifiers with and no jump-to-definition — coloring is purely syntactic:
 *
 *  - a whole `# …` comment line/segment → [TokenKind.COMMENT]
 *  - a leading `.directive` (`.class`, `.method`, `.registers`, `.catch`, …) → [TokenKind.KEYWORD]
 *  - `:label` targets → [TokenKind.ANNOTATION] (visually distinct, not navigable)
 *  - `"…"` string literals → [TokenKind.STRING]
 *  - everything else → [TokenKind.PLAIN]
 *
 * A dedicated smali lexer (opcode/register/type coloring, and eventually navigable type/method refs)
 * is a tracked follow-up; this keeps the view readable without over-investing. Pure Kotlin — wasm-safe.
 */
internal object SmaliColorizer {

    fun colorize(smali: String): List<CodeLine> =
        smali.split('\n').mapIndexed { i, line -> CodeLine(i + 1, tokensOf(line)) }

    private fun tokensOf(line: String): List<CodeToken> {
        if (line.isEmpty()) return emptyList()
        val tokens = ArrayList<CodeToken>()
        val pending = StringBuilder()
        fun flushPlain() {
            if (pending.isNotEmpty()) {
                tokens += CodeToken(pending.toString(), TokenKind.PLAIN)
                pending.clear()
            }
        }

        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                // Comment runs to end of line.
                c == '#' -> {
                    flushPlain()
                    tokens += CodeToken(line.substring(i), TokenKind.COMMENT)
                    return tokens
                }
                // String literal (respects backslash escaping so an escaped quote doesn't end it).
                c == '"' -> {
                    flushPlain()
                    val end = stringEnd(line, i)
                    tokens += CodeToken(line.substring(i, end), TokenKind.STRING)
                    i = end
                }
                // A leading directive token, or a label token, both starting a "word".
                (c == '.' || c == ':') && isWordStart(line, i) -> {
                    flushPlain()
                    val end = wordEnd(line, i)
                    val kind = if (c == '.') TokenKind.KEYWORD else TokenKind.ANNOTATION
                    tokens += CodeToken(line.substring(i, end), kind)
                    i = end
                }
                else -> {
                    pending.append(c)
                    i++
                }
            }
        }
        flushPlain()
        return tokens
    }

    /** A `.`/`:` starts a directive/label token only at a token boundary (start of line or after space). */
    private fun isWordStart(line: String, at: Int): Boolean {
        val prev = if (at == 0) ' ' else line[at - 1]
        return prev == ' ' || prev == '\t'
    }

    private fun wordEnd(line: String, start: Int): Int {
        var i = start + 1
        while (i < line.length && !line[i].isWhitespace() && line[i] != ',') i++
        return i
    }

    private fun stringEnd(line: String, start: Int): Int {
        var i = start + 1
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i += 2 // skip the escaped char
                '"' -> return i + 1
                else -> i++
            }
        }
        return line.length // unterminated: color to end
    }
}
