package com.jadxmp.ui.client

/**
 * Minimal, streaming colorizer for the resource/manifest XML view (decoded binary-XML and text XML).
 * Like [SmaliColorizer], there is no engine [CodeMetadata] to override identifiers with — coloring is
 * purely syntactic. A small state machine carries state across lines so multi-line constructs
 * (comments and CDATA sections, and tags whose attributes wrap onto several lines) colorize correctly.
 *
 * Token-kind mapping (chosen from the available [TokenKind] palette, documented so the choice is stable):
 *  - `<!-- … -->` comments — may span lines → [TokenKind.COMMENT]
 *  - `<?xml … ?>` prolog / processing instructions, and `<!DOCTYPE …>` declarations → [TokenKind.COMMENT]
 *    (de-emphasized like a comment; a single consistent choice for all `<? … ?>` / `<! … >` markers)
 *  - element tag **names** in `<tag …>`, `</tag>`, `<tag/>` → [TokenKind.KEYWORD]
 *  - the angle brackets `<` `>` and the self-close / close `/` → [TokenKind.PUNCTUATION]
 *  - attribute **names** (incl. namespaced `android:id`) → [TokenKind.ANNOTATION] (visually distinct)
 *  - attribute **values** `"…"` / `'…'` → [TokenKind.STRING] (quote-char aware; unterminated → to EOL)
 *  - `=` → [TokenKind.PUNCTUATION]
 *  - entities `&amp;` `&#10;` `&#x1F;` in text content → [TokenKind.KEYWORD]
 *  - `<![CDATA[ … ]]>` sections and all other text content → [TokenKind.PLAIN]
 *
 * Robustness (rule 4, fault isolation): malformed XML never throws. An unterminated comment/CDATA colors
 * to end of line and continues on the next; a stray `<` or `&` that starts nothing recognizable degrades
 * to [TokenKind.PLAIN]; an unclosed tag simply stays in tag state. Pure Kotlin — wasm/js/jvm-safe.
 */
internal object XmlColorizer {

    /** Cross-line lexer state. TAG persists across lines so wrapped attribute lists colorize correctly. */
    private enum class State { NORMAL, TAG, COMMENT, CDATA }

    fun colorize(xml: String): List<CodeLine> {
        val result = ArrayList<CodeLine>()
        var state = State.NORMAL
        for ((idx, rawLine) in xml.split('\n').withIndex()) {
            val line = rawLine.trimEnd('\r')
            val (tokens, next) = tokenizeLine(line, state)
            result += CodeLine(idx + 1, tokens)
            state = next
        }
        return result
    }

    private fun tokenizeLine(line: String, incoming: State): Pair<List<CodeToken>, State> {
        val tokens = ArrayList<CodeToken>()
        val pending = StringBuilder()
        var state = incoming
        fun flushPlain() {
            if (pending.isNotEmpty()) {
                tokens += CodeToken(pending.toString(), TokenKind.PLAIN)
                pending.clear()
            }
        }

        var i = 0
        while (i < line.length) {
            when (state) {
                State.COMMENT -> {
                    val end = line.indexOf("-->", i)
                    if (end < 0) {
                        tokens += CodeToken(line.substring(i), TokenKind.COMMENT)
                        i = line.length
                    } else {
                        tokens += CodeToken(line.substring(i, end + 3), TokenKind.COMMENT)
                        i = end + 3
                        state = State.NORMAL
                    }
                }
                State.CDATA -> {
                    val end = line.indexOf("]]>", i)
                    if (end < 0) {
                        tokens += CodeToken(line.substring(i), TokenKind.PLAIN)
                        i = line.length
                    } else {
                        tokens += CodeToken(line.substring(i, end + 3), TokenKind.PLAIN)
                        i = end + 3
                        state = State.NORMAL
                    }
                }
                State.TAG -> {
                    val c = line[i]
                    when {
                        c == '>' -> { flushPlain(); tokens += punct(">"); i++; state = State.NORMAL }
                        c == '/' && i + 1 < line.length && line[i + 1] == '>' -> {
                            flushPlain(); tokens += punct("/>"); i += 2; state = State.NORMAL
                        }
                        c == '/' -> { flushPlain(); tokens += punct("/"); i++ }
                        c == '=' -> { flushPlain(); tokens += punct("="); i++ }
                        c == '"' || c == '\'' -> {
                            flushPlain()
                            val end = quoteEnd(line, i, c)
                            tokens += CodeToken(line.substring(i, end), TokenKind.STRING)
                            i = end
                        }
                        isNameStart(c) -> {
                            flushPlain()
                            val end = nameEnd(line, i)
                            tokens += CodeToken(line.substring(i, end), TokenKind.ANNOTATION)
                            i = end
                        }
                        else -> { pending.append(c); i++ } // whitespace and any stray char inside the tag
                    }
                }
                State.NORMAL -> {
                    val c = line[i]
                    when {
                        c == '<' && line.startsWith("<!--", i) -> { flushPlain(); state = State.COMMENT }
                        c == '<' && line.startsWith("<![CDATA[", i) -> { flushPlain(); state = State.CDATA }
                        c == '<' && line.startsWith("<?", i) -> {
                            flushPlain()
                            val end = line.indexOf("?>", i)
                            val stop = if (end < 0) line.length else end + 2
                            tokens += CodeToken(line.substring(i, stop), TokenKind.COMMENT)
                            i = stop
                        }
                        c == '<' && i + 1 < line.length && line[i + 1] == '!' -> {
                            // Declaration such as <!DOCTYPE …> — treat like a de-emphasized marker.
                            flushPlain()
                            val end = line.indexOf('>', i)
                            val stop = if (end < 0) line.length else end + 1
                            tokens += CodeToken(line.substring(i, stop), TokenKind.COMMENT)
                            i = stop
                        }
                        c == '<' && i + 1 < line.length && line[i + 1] == '/' -> {
                            flushPlain()
                            tokens += punct("</")
                            i += 2
                            if (i < line.length && isNameStart(line[i])) {
                                val end = nameEnd(line, i)
                                tokens += CodeToken(line.substring(i, end), TokenKind.KEYWORD)
                                i = end
                            }
                            state = State.TAG
                        }
                        c == '<' && i + 1 < line.length && isNameStart(line[i + 1]) -> {
                            flushPlain()
                            tokens += punct("<")
                            i += 1
                            val end = nameEnd(line, i)
                            tokens += CodeToken(line.substring(i, end), TokenKind.KEYWORD)
                            i = end
                            state = State.TAG
                        }
                        c == '&' -> {
                            val end = entityEnd(line, i)
                            if (end > i) {
                                flushPlain()
                                tokens += CodeToken(line.substring(i, end), TokenKind.KEYWORD)
                                i = end
                            } else {
                                pending.append(c); i++
                            }
                        }
                        else -> { pending.append(c); i++ } // text content, or a stray '<' that starts nothing
                    }
                }
            }
        }
        flushPlain()
        return tokens to state
    }

    private fun punct(text: String): CodeToken = CodeToken(text, TokenKind.PUNCTUATION)

    /** XML name start: a letter, `_`, or `:` (namespace prefix). */
    private fun isNameStart(c: Char): Boolean = c.isLetter() || c == '_' || c == ':'

    /** End (exclusive) of an XML name starting at [start]: letters, digits, and `_ : - .`. */
    private fun nameEnd(line: String, start: Int): Int {
        var i = start
        while (i < line.length) {
            val c = line[i]
            if (c.isLetterOrDigit() || c == '_' || c == ':' || c == '-' || c == '.') i++ else break
        }
        return i
    }

    /** End (exclusive, past the closing quote) of a `[quote]…[quote]` value; the line length if unterminated. */
    private fun quoteEnd(line: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < line.length) {
            if (line[i] == quote) return i + 1
            i++
        }
        return line.length
    }

    /**
     * End (exclusive, past the `;`) of an entity reference `&name;`, `&#123;`, or `&#xAB;` starting at
     * [start] (which must be `&`). Returns [start] unchanged when the text is not a well-formed entity, so
     * a bare `&` degrades to plain text.
     */
    private fun entityEnd(line: String, start: Int): Int {
        var i = start + 1
        if (i >= line.length) return start
        if (line[i] == '#') {
            i++
            if (i < line.length && (line[i] == 'x' || line[i] == 'X')) {
                i++
                val digitsStart = i
                while (i < line.length && isHex(line[i])) i++
                if (i == digitsStart) return start
            } else {
                val digitsStart = i
                while (i < line.length && line[i].isDigit()) i++
                if (i == digitsStart) return start
            }
        } else {
            val nameStart = i
            while (i < line.length && line[i].isLetterOrDigit()) i++
            if (i == nameStart) return start
        }
        return if (i < line.length && line[i] == ';') i + 1 else start
    }

    private fun isHex(c: Char): Boolean = c.isDigit() || c in 'a'..'f' || c in 'A'..'F'
}
