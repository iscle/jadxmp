package com.jadxmp.ui.client

/**
 * Extension-driven syntax highlighting for the Resources view. jadx-gui colors many resource file types
 * (not just XML); this dispatches a decoded resource's text to a small, wasm-safe colorizer chosen by
 * the file extension, mirroring the streaming [XmlColorizer] design. Every colorizer degrades to plain
 * tokens on malformed input rather than throwing (rule 4); an unknown extension is shown plain.
 *
 * Extension → colorizer:
 *  - `.xml`, `.htm`, `.html`  → [XmlColorizer] (HTML markup is colored with the XML tokenizer)
 *  - `.json`                  → [JsonColorizer]
 *  - `.properties`, `.ini`, `.cfg` → [PropertiesColorizer]
 *  - `.yaml`, `.yml`          → [YamlColorizer]
 *  - `.sql`                   → [SqlColorizer]
 *  - `.css`                   → [CssColorizer]
 *  - `.js`, `.mjs`, `.cjs`    → [JsColorizer]
 *  - anything else            → plain tokens ([plainLines])
 */
internal object ResourceColorizers {

    /** Colorize [text] by the extension of [path] (a resource path or file name). Never throws. */
    fun colorize(path: String, text: String): List<CodeLine> = when (extensionOf(path)) {
        "xml", "html", "htm" -> XmlColorizer.colorize(text)
        "json" -> JsonColorizer.colorize(text)
        "properties", "ini", "cfg", "prop" -> PropertiesColorizer.colorize(text)
        "yaml", "yml" -> YamlColorizer.colorize(text)
        "sql" -> SqlColorizer.colorize(text)
        "css" -> CssColorizer.colorize(text)
        "js", "mjs", "cjs" -> JsColorizer.colorize(text)
        else -> plainLines(text)
    }

    /** True when [path] maps to the markup ([XmlColorizer]) family (so callers can pick a comment style). */
    fun isMarkup(path: String): Boolean = extensionOf(path) in MARKUP_EXTS

    private val MARKUP_EXTS = setOf("xml", "html", "htm")

    /** Lower-cased extension of [path] (after the last `.` of the last path segment), or "" if none. */
    private fun extensionOf(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot < 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }
}

/**
 * JSON / JSONC colorizer. Object keys (a string immediately before a `:`) are colored as
 * [TokenKind.ANNOTATION] to stand out from string values ([TokenKind.STRING]), mirroring how
 * [XmlColorizer] distinguishes attribute names from values. `true`/`false`/`null` are keywords,
 * numbers are numbers, and line + block comments are tolerated (JSON-with-comments).
 */
internal object JsonColorizer {
    private val LEXICON = Lexicon(
        lineComments = listOf("//"),
        blockOpen = "/*",
        blockClose = "*/",
        stringQuotes = "\"",
        keywords = setOf("true", "false", "null"),
    )

    fun colorize(json: String): List<CodeLine> =
        SyntaxScanner.scan(json, LEXICON).map { line -> line.copy(tokens = markKeys(line.tokens)) }

    /** Re-color a STRING token as a key (ANNOTATION) when the next non-blank token on the line is `:`. */
    private fun markKeys(tokens: List<CodeToken>): List<CodeToken> {
        if (tokens.none { it.kind == TokenKind.STRING }) return tokens
        return tokens.mapIndexed { i, tok ->
            if (tok.kind == TokenKind.STRING && nextSignificant(tokens, i)?.text == ":") {
                tok.copy(kind = TokenKind.ANNOTATION)
            } else {
                tok
            }
        }
    }

    private fun nextSignificant(tokens: List<CodeToken>, from: Int): CodeToken? {
        for (j in from + 1 until tokens.size) if (tokens[j].text.isNotBlank()) return tokens[j]
        return null
    }
}

/** SQL colorizer: case-insensitive keywords, `'…'`/`"…"` strings, line (`--`) and block comments, numbers. */
internal object SqlColorizer {
    private val KEYWORDS = setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
        "table", "drop", "alter", "add", "column", "primary", "key", "foreign", "references", "join",
        "inner", "left", "right", "outer", "full", "cross", "on", "using", "group", "by", "order",
        "having", "limit", "offset", "as", "and", "or", "not", "null", "is", "in", "like", "between",
        "distinct", "union", "intersect", "except", "all", "any", "some", "exists", "index", "view",
        "trigger", "database", "schema", "default", "constraint", "unique", "check", "cascade", "begin",
        "commit", "rollback", "transaction", "case", "when", "then", "else", "end", "count", "sum",
        "avg", "min", "max", "asc", "desc", "with", "cast", "integer", "int", "bigint", "smallint",
        "boolean", "varchar", "char", "text", "blob", "real", "float", "double", "numeric", "decimal",
        "date", "time", "datetime", "timestamp", "autoincrement", "if", "replace", "pragma", "returning",
    )
    private val LEXICON = Lexicon(
        lineComments = listOf("--"),
        blockOpen = "/*",
        blockClose = "*/",
        stringQuotes = "'\"`",
        keywords = KEYWORDS,
        ignoreKeywordCase = true,
    )

    fun colorize(sql: String): List<CodeLine> = SyntaxScanner.scan(sql, LEXICON)
}

/** JavaScript colorizer: keywords, `"`/`'`/`` ` `` strings, line + block comments, numbers, upper-cased types. */
internal object JsColorizer {
    private val KEYWORDS = setOf(
        "var", "let", "const", "function", "return", "if", "else", "for", "while", "do", "switch",
        "case", "default", "break", "continue", "new", "delete", "typeof", "instanceof", "void",
        "this", "super", "class", "extends", "import", "from", "export", "try", "catch", "finally",
        "throw", "async", "await", "yield", "in", "of", "null", "undefined", "true", "false", "static",
        "get", "set", "with", "debugger", "NaN", "Infinity",
    )
    private val LEXICON = Lexicon(
        lineComments = listOf("//"),
        blockOpen = "/*",
        blockClose = "*/",
        stringQuotes = "\"'`",
        keywords = KEYWORDS,
        upperWordKind = TokenKind.TYPE,
    )

    fun colorize(js: String): List<CodeLine> = SyntaxScanner.scan(js, LEXICON)
}

/**
 * `.properties` / `.ini` colorizer (line-oriented). `#`/`!`/`;` comment lines → COMMENT; an `[section]`
 * header → KEYWORD; the key before the first unescaped `=`/`:` → ANNOTATION, the separator → PUNCTUATION,
 * and the remainder → the value. A line whose predecessor ended with an odd trailing `\` is a wrapped
 * value continuation (colored plain). Never throws — a line with no separator is shown plain.
 */
internal object PropertiesColorizer {
    fun colorize(text: String): List<CodeLine> {
        val result = ArrayList<CodeLine>()
        var continuation = false
        for ((idx, raw) in text.split('\n').withIndex()) {
            val line = raw.trimEnd('\r')
            result += CodeLine(idx + 1, tokensOf(line, continuation))
            continuation = !isComment(line) && endsWithOddBackslash(line)
        }
        return result
    }

    private fun tokensOf(line: String, continuation: Boolean): List<CodeToken> {
        if (line.isEmpty()) return emptyList()
        if (continuation) return listOf(CodeToken(line, TokenKind.STRING))
        val trimmedStart = line.indexOfFirst { !it.isWhitespace() }
        if (trimmedStart >= 0) {
            val first = line[trimmedStart]
            if (first == '#' || first == '!' || first == ';') {
                return listOf(CodeToken(line, TokenKind.COMMENT))
            }
            if (first == '[' && line.trimEnd().endsWith("]")) {
                return listOf(CodeToken(line, TokenKind.KEYWORD)) // ini [section] header
            }
        }
        val sep = firstSeparator(line)
        if (sep < 0) return listOf(CodeToken(line, TokenKind.PLAIN))
        val tokens = ArrayList<CodeToken>(3)
        tokens += CodeToken(line.substring(0, sep), TokenKind.ANNOTATION) // key (keeps leading indent)
        tokens += CodeToken(line[sep].toString(), TokenKind.PUNCTUATION) // '=' or ':'
        if (sep + 1 < line.length) tokens += CodeToken(line.substring(sep + 1), TokenKind.STRING) // value
        return tokens
    }

    private fun isComment(line: String): Boolean {
        val i = line.indexOfFirst { !it.isWhitespace() }
        if (i < 0) return false
        val c = line[i]
        return c == '#' || c == '!' || c == ';'
    }

    /** Index of the first unescaped `=` or `:` separator, or -1. */
    private fun firstSeparator(line: String): Int {
        var i = 0
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i += 2
                '=', ':' -> return i
                else -> i++
            }
        }
        return -1
    }

    private fun endsWithOddBackslash(line: String): Boolean {
        var count = 0
        var i = line.length - 1
        while (i >= 0 && line[i] == '\\') { count++; i-- }
        return count % 2 == 1
    }
}

/**
 * YAML colorizer (line-oriented, best-effort). `#` comments (at line start or after whitespace) → COMMENT;
 * a `key:` mapping key → ANNOTATION and its `:` → PUNCTUATION; `- ` sequence markers and `---`/`...`
 * document markers → PUNCTUATION; scalar values are colored (quoted → STRING, numbers → NUMBER,
 * `true`/`false`/`null`/… → KEYWORD, anchors/aliases `&a`/`*a` → ANNOTATION). Never throws.
 */
internal object YamlColorizer {
    private val CONST_WORDS = setOf(
        "true", "false", "null", "yes", "no", "on", "off", "~",
        "True", "False", "Null", "TRUE", "FALSE", "NULL", "Yes", "No",
    )

    fun colorize(text: String): List<CodeLine> =
        text.split('\n').mapIndexed { i, raw -> CodeLine(i + 1, tokensOf(raw.trimEnd('\r'))) }

    private fun tokensOf(line: String): List<CodeToken> {
        if (line.isEmpty()) return emptyList()
        val tokens = ArrayList<CodeToken>()
        var i = 0
        // Leading indentation (kept as plain so the value coloring aligns).
        val indentEnd = line.indexOfFirst { !it.isWhitespace() }
        if (indentEnd < 0) return listOf(CodeToken(line, TokenKind.PLAIN))
        if (indentEnd > 0) { tokens += CodeToken(line.substring(0, indentEnd), TokenKind.PLAIN); i = indentEnd }

        // Whole-line document markers.
        val trimmed = line.substring(i)
        if (trimmed == "---" || trimmed == "...") {
            tokens += CodeToken(trimmed, TokenKind.PUNCTUATION)
            return tokens
        }
        // A comment can occupy the whole remainder.
        if (line[i] == '#') { tokens += CodeToken(line.substring(i), TokenKind.COMMENT); return tokens }

        // Sequence item marker "- " (possibly several, e.g. "- - x").
        while (i < line.length && line[i] == '-' && (i + 1 == line.length || line[i + 1] == ' ')) {
            tokens += CodeToken("-", TokenKind.PUNCTUATION)
            i++
            if (i < line.length && line[i] == ' ') { tokens += CodeToken(" ", TokenKind.PLAIN); i++ }
        }

        // "key:" mapping key — a run up to the first ": " / trailing ":" that is not quoted.
        val keyEnd = mappingKeyEnd(line, i)
        if (keyEnd > i) {
            tokens += CodeToken(line.substring(i, keyEnd), TokenKind.ANNOTATION)
            tokens += CodeToken(":", TokenKind.PUNCTUATION)
            i = keyEnd + 1
        }
        // Remainder: a scalar value (with an inline "# comment" split off).
        appendValue(line, i, tokens)
        return tokens
    }

    /** End (exclusive) of a mapping key starting at [start], or [start] if the line is not `key:`-shaped. */
    private fun mappingKeyEnd(line: String, start: Int): Int {
        if (start >= line.length) return start
        // A quoted key runs to the close quote, which must be followed by ':'.
        val first = line[start]
        if (first == '"' || first == '\'') {
            val end = stringEnd(line, start, first)
            return if (end < line.length && line[end] == ':') end else start
        }
        var i = start
        while (i < line.length) {
            val c = line[i]
            if (c == ':' && (i + 1 == line.length || line[i + 1] == ' ')) return i
            if (c == '#') return start // an inline comment before any ':' → not a key line
            i++
        }
        return start
    }

    private fun appendValue(line: String, start: Int, tokens: MutableList<CodeToken>) {
        var i = start
        while (i < line.length) {
            val c = line[i]
            when {
                c == ' ' || c == '\t' -> {
                    val s = i
                    while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
                    tokens += CodeToken(line.substring(s, i), TokenKind.PLAIN)
                }
                // An inline comment must be preceded by whitespace (guaranteed by the branch above).
                c == '#' -> { tokens += CodeToken(line.substring(i), TokenKind.COMMENT); return }
                c == '"' || c == '\'' -> {
                    val end = stringEnd(line, i, c)
                    tokens += CodeToken(line.substring(i, end), TokenKind.STRING)
                    i = end
                }
                c == '&' || c == '*' -> { // anchor / alias
                    val end = wordEnd(line, i + 1)
                    tokens += CodeToken(line.substring(i, end), TokenKind.ANNOTATION)
                    i = end
                }
                c.isDigit() || (c == '-' && i + 1 < line.length && line[i + 1].isDigit()) -> {
                    val end = numberEnd(line, if (c == '-') i + 1 else i)
                    tokens += CodeToken(line.substring(i, end), TokenKind.NUMBER)
                    i = end
                }
                isWordStart(c) -> {
                    val end = wordEnd(line, i)
                    val word = line.substring(i, end)
                    tokens += CodeToken(word, if (word in CONST_WORDS) TokenKind.KEYWORD else TokenKind.PLAIN)
                    i = end
                }
                else -> {
                    val s = i
                    while (i < line.length && !isValueBreak(line[i])) i++
                    if (i == s) i++ // always advance
                    tokens += CodeToken(line.substring(s, i), TokenKind.PLAIN)
                }
            }
        }
    }

    private fun isValueBreak(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '"' || c == '\'' || c == '#' || c == '&' || c == '*' || isWordStart(c) || c.isDigit()
}

/**
 * CSS colorizer. Cross-line block comments → COMMENT; `@media`/`@import`/… at-rules → KEYWORD;
 * `"`/`'` strings → STRING; `.class` / `#id` selectors → ANNOTATION (a `#rrggbb` color → NUMBER);
 * numeric values with units → NUMBER; structural `{ } ; : , ( )` → PUNCTUATION. Property names and
 * values are left plain (distinguishing them needs brace/colon state; kept simple + robust). Never throws.
 */
internal object CssColorizer {
    fun colorize(css: String): List<CodeLine> {
        val result = ArrayList<CodeLine>()
        var inComment = false
        for ((idx, raw) in css.split('\n').withIndex()) {
            val line = raw.trimEnd('\r')
            val (tokens, next) = tokensOf(line, inComment)
            result += CodeLine(idx + 1, tokens)
            inComment = next
        }
        return result
    }

    private fun tokensOf(line: String, incomingComment: Boolean): Pair<List<CodeToken>, Boolean> {
        val tokens = ArrayList<CodeToken>()
        val pending = StringBuilder()
        fun flush() {
            if (pending.isNotEmpty()) { tokens += CodeToken(pending.toString(), TokenKind.PLAIN); pending.clear() }
        }

        var i = 0
        if (incomingComment) {
            val end = line.indexOf("*/")
            if (end < 0) { if (line.isNotEmpty()) tokens += CodeToken(line, TokenKind.COMMENT); return tokens to true }
            tokens += CodeToken(line.substring(0, end + 2), TokenKind.COMMENT)
            i = end + 2
        }

        while (i < line.length) {
            val c = line[i]
            when {
                line.startsWith("/*", i) -> {
                    flush()
                    val end = line.indexOf("*/", i + 2)
                    if (end < 0) { tokens += CodeToken(line.substring(i), TokenKind.COMMENT); return tokens to true }
                    tokens += CodeToken(line.substring(i, end + 2), TokenKind.COMMENT)
                    i = end + 2
                }
                c == '"' || c == '\'' -> {
                    flush()
                    val end = stringEnd(line, i, c)
                    tokens += CodeToken(line.substring(i, end), TokenKind.STRING)
                    i = end
                }
                c == '@' -> { // at-rule keyword (@media, @import, @keyframes, …)
                    flush()
                    val end = wordEnd(line, i + 1)
                    tokens += CodeToken(line.substring(i, end), TokenKind.KEYWORD)
                    i = end
                }
                c == '#' -> { // #id selector, or a #rrggbb hex color
                    flush()
                    val end = wordEnd(line, i + 1)
                    val body = line.substring(i + 1, end)
                    val hexColor = body.length.let { it == 3 || it == 4 || it == 6 || it == 8 } && body.all { it.isHexDigit() }
                    val kind = if (hexColor) TokenKind.NUMBER else TokenKind.ANNOTATION
                    tokens += CodeToken(line.substring(i, end), kind)
                    i = end
                }
                c == '.' && i + 1 < line.length && isWordStart(line[i + 1]) -> { // .class selector
                    flush()
                    val end = wordEnd(line, i + 1)
                    tokens += CodeToken(line.substring(i, end), TokenKind.ANNOTATION)
                    i = end
                }
                c.isDigit() || (c == '.' && i + 1 < line.length && line[i + 1].isDigit()) -> {
                    flush()
                    val end = numberEnd(line, i)
                    tokens += CodeToken(line.substring(i, end), TokenKind.NUMBER)
                    i = end
                }
                c == '{' || c == '}' || c == ';' || c == ':' || c == ',' || c == '(' || c == ')' -> {
                    flush()
                    tokens += CodeToken(c.toString(), TokenKind.PUNCTUATION)
                    i++
                }
                else -> { pending.append(c); i++ }
            }
        }
        flush()
        return tokens to false
    }

    private fun Char.isHexDigit(): Boolean = this.isDigit() || this in 'a'..'f' || this in 'A'..'F'
}
