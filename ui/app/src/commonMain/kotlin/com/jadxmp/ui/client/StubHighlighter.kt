package com.jadxmp.ui.client

/**
 * A tiny, dependency-free syntax classifier used ONLY to populate the stub with plausibly-highlighted
 * code so the code viewer renders something real before the engine exists. It is deliberately naive
 * (a regex splitter, not a lexer). The production path does NOT re-lex: highlighting is driven by the
 * engine's per-offset CodeMetadata, which this [CodeToken] shape stands in for. Do not grow this into
 * a real lexer — replace it with the engine feed.
 */
object StubHighlighter {

    private val javaKeywords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null", "var",
    )

    // Order matters: earlier alternatives win. Comment/string before identifiers/numbers.
    private val tokenPattern = Regex(
        buildString {
            append("(?<comment>//[^\\n]*)")
            append("|(?<string>\"(?:\\\\.|[^\"\\\\])*\")")
            append("|(?<annotation>@[A-Za-z_][A-Za-z0-9_]*)")
            append("|(?<number>\\b\\d[A-Za-z0-9_.]*\\b)")
            append("|(?<ident>[A-Za-z_][A-Za-z0-9_]*)")
            append("|(?<ws>[ \\t]+)")
            append("|(?<other>.)")
        },
    )

    /** Classify a single source line into tokens. Newlines are handled by the caller. */
    fun highlightLine(line: String): List<CodeToken> {
        val raw = ArrayList<CodeToken>()
        for (m in tokenPattern.findAll(line)) {
            val g = m.groups
            val token = when {
                g["comment"] != null -> CodeToken(m.value, TokenKind.COMMENT)
                g["string"] != null -> CodeToken(m.value, TokenKind.STRING)
                g["annotation"] != null -> CodeToken(m.value, TokenKind.ANNOTATION)
                g["number"] != null -> CodeToken(m.value, TokenKind.NUMBER)
                g["ident"] != null -> classifyIdentifier(m.value)
                g["ws"] != null -> CodeToken(m.value, TokenKind.PLAIN)
                else -> CodeToken(m.value, TokenKind.PUNCTUATION)
            }
            raw.add(token)
        }
        return reclassifyCalls(raw)
    }

    private fun classifyIdentifier(text: String): CodeToken = when {
        text in javaKeywords -> CodeToken(text, TokenKind.KEYWORD)
        text.first().isUpperCase() -> CodeToken(text, TokenKind.TYPE)
        else -> CodeToken(text, TokenKind.PLAIN)
    }

    /** A lowercase identifier immediately followed by '(' reads as a method. */
    private fun reclassifyCalls(tokens: List<CodeToken>): List<CodeToken> {
        if (tokens.isEmpty()) return tokens
        val out = tokens.toMutableList()
        for (i in out.indices) {
            val t = out[i]
            if (t.kind != TokenKind.PLAIN || t.text.isEmpty() || !t.text.first().isLetter()) continue
            val next = out.getOrNull(i + 1) ?: continue
            if (next.text.startsWith("(")) {
                out[i] = t.copy(kind = TokenKind.METHOD)
            }
        }
        return out
    }

    /** Convenience: highlight a whole multi-line snippet into [CodeLine]s (1-based line numbers). */
    fun highlight(source: String): List<CodeLine> =
        source.split("\n").mapIndexed { index, line -> CodeLine(index + 1, highlightLine(line)) }
}
