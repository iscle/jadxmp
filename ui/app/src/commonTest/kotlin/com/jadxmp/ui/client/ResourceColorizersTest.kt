package com.jadxmp.ui.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure tests for the by-extension resource colorizers ([JsonColorizer], [PropertiesColorizer],
 * [YamlColorizer], [SqlColorizer], [CssColorizer], [JsColorizer]) and the [ResourceColorizers]
 * dispatch. Each language gets a representative snippet (pinning the chosen [TokenKind] mapping) and a
 * malformed snippet (proving it never throws and never drops characters — the round-trip invariant, so
 * the viewer always shows the file verbatim just recolored). wasm/js/jvm-safe, no Compose.
 */
class ResourceColorizersTest {

    private fun tokens(lines: List<CodeLine>): List<CodeToken> = lines.flatMap { it.tokens }

    private fun kindOf(tokens: List<CodeToken>, text: String): TokenKind =
        tokens.first { it.text == text }.kind

    /** The colorized text must equal the input verbatim (\r stripped) — coloring never edits characters. */
    private fun assertRoundTrips(input: String, lines: List<CodeLine>) {
        val rebuilt = lines.joinToString("\n") { line -> line.tokens.joinToString("") { it.text } }
        assertEquals(input.replace("\r", ""), rebuilt)
    }

    // ── JSON ──────────────────────────────────────────────────────────────────

    @Test
    fun jsonKeysValuesLiteralsNumbers() {
        val src = """{"name": "value", "count": 42, "ok": true, "nil": null}"""
        val toks = tokens(JsonColorizer.colorize(src))
        assertEquals(TokenKind.ANNOTATION, kindOf(toks, "\"name\""), "an object key is highlighted distinctly")
        assertEquals(TokenKind.STRING, kindOf(toks, "\"value\""), "a string value stays a string")
        assertEquals(TokenKind.NUMBER, kindOf(toks, "42"))
        assertEquals(TokenKind.KEYWORD, kindOf(toks, "true"))
        assertEquals(TokenKind.KEYWORD, kindOf(toks, "null"))
        assertEquals(TokenKind.PUNCTUATION, kindOf(toks, "{"))
        assertRoundTrips(src, JsonColorizer.colorize(src))
    }

    @Test
    fun jsonMultiLineBlockCommentCarriesState() {
        val src = "{\n  /* a\n  comment */\n  \"a\": 1\n}"
        val lines = JsonColorizer.colorize(src)
        // Line 1 keeps its indentation as a plain token, then opens the block comment (COMMENT).
        assertEquals(TokenKind.COMMENT, lines[1].tokens.last().kind)
        // Line 2 is wholly inside the block comment (state carried across the newline).
        assertEquals(TokenKind.COMMENT, lines[2].tokens.single().kind)
        assertRoundTrips(src, lines)
    }

    @Test
    fun jsonMalformedNeverThrows() {
        val junk = "{\"a\": \"unterminated\n  broken , : ] } 3.1.4 /* open"
        val lines = JsonColorizer.colorize(junk)
        assertTrue(tokens(lines).isNotEmpty())
        assertRoundTrips(junk, lines)
    }

    // ── properties / ini ──────────────────────────────────────────────────────

    @Test
    fun propertiesKeyValueCommentSection() {
        val src = "# a comment\n! also comment\n[section]\nkey = value\nother:v2"
        val lines = PropertiesColorizer.colorize(src)
        assertEquals(TokenKind.COMMENT, lines[0].tokens.single().kind)
        assertEquals(TokenKind.COMMENT, lines[1].tokens.single().kind)
        assertEquals(TokenKind.KEYWORD, lines[2].tokens.single().kind) // [section]
        assertEquals(TokenKind.ANNOTATION, lines[3].tokens.first().kind) // key
        assertEquals(TokenKind.PUNCTUATION, kindOf(lines[3].tokens, "="))
        assertEquals(TokenKind.PUNCTUATION, kindOf(lines[4].tokens, ":"))
        assertRoundTrips(src, lines)
    }

    @Test
    fun propertiesBackslashContinuationColorsWrappedValue() {
        val src = "key = first \\\ncontinued"
        val lines = PropertiesColorizer.colorize(src)
        // The wrapped second physical line is a value continuation, not a new key.
        assertEquals(TokenKind.STRING, lines[1].tokens.single().kind)
        assertRoundTrips(src, lines)
    }

    @Test
    fun propertiesLineWithoutSeparatorAndMalformedNeverThrow() {
        val src = "justtext\n   \n\\weird=\\"
        val lines = PropertiesColorizer.colorize(src)
        assertEquals(TokenKind.PLAIN, lines[0].tokens.single().kind)
        assertRoundTrips(src, lines)
    }

    // ── YAML ──────────────────────────────────────────────────────────────────

    @Test
    fun yamlMappingSequenceCommentsScalars() {
        val src = "# top\nname: app\nenabled: true\ncount: 42\ntitle: \"hi\"\nitems:\n  - one\n  - two"
        val lines = YamlColorizer.colorize(src)
        assertEquals(TokenKind.COMMENT, lines[0].tokens.single().kind)
        assertEquals(TokenKind.ANNOTATION, lines[1].tokens.first().kind) // key "name"
        assertEquals(TokenKind.PUNCTUATION, kindOf(lines[1].tokens, ":"))
        assertEquals(TokenKind.KEYWORD, kindOf(lines[2].tokens, "true"))
        assertEquals(TokenKind.NUMBER, kindOf(lines[3].tokens, "42"))
        assertEquals(TokenKind.STRING, kindOf(lines[4].tokens, "\"hi\""))
        assertEquals(TokenKind.PUNCTUATION, lines[6].tokens.first { it.text == "-" }.kind)
        assertRoundTrips(src, lines)
    }

    @Test
    fun yamlDocumentMarkerAndInlineCommentAndMalformed() {
        val src = "---\nkey: value # trailing\n:::garbage: :"
        val lines = YamlColorizer.colorize(src)
        assertEquals(TokenKind.PUNCTUATION, lines[0].tokens.single().kind) // ---
        assertEquals(TokenKind.COMMENT, lines[1].tokens.last().kind) // inline # comment
        assertRoundTrips(src, lines)
    }

    // ── SQL ───────────────────────────────────────────────────────────────────

    @Test
    fun sqlKeywordsCaseInsensitiveStringsNumbersComments() {
        val src = "SELECT id FROM users WHERE name = 'bob' AND age > 21; -- note\nselect 1"
        val lines = SqlColorizer.colorize(src)
        val toks = tokens(lines)
        assertEquals(TokenKind.KEYWORD, kindOf(toks, "SELECT"))
        assertEquals(TokenKind.KEYWORD, kindOf(toks, "FROM"))
        assertEquals(TokenKind.KEYWORD, kindOf(toks, "select"), "keywords are case-insensitive")
        assertEquals(TokenKind.STRING, kindOf(toks, "'bob'"))
        assertEquals(TokenKind.NUMBER, kindOf(toks, "21"))
        assertEquals(TokenKind.COMMENT, toks.first { it.text.startsWith("-- note") }.kind)
        assertRoundTrips(src, lines)
    }

    @Test
    fun sqlBlockCommentAndUnterminatedStringNeverThrow() {
        val src = "/* multi\nline */ SELECT 'oops"
        val lines = SqlColorizer.colorize(src)
        assertEquals(TokenKind.COMMENT, lines[0].tokens.single().kind)
        assertEquals(TokenKind.STRING, tokens(lines).first { it.text.startsWith("'oops") }.kind)
        assertRoundTrips(src, lines)
    }

    // ── CSS ───────────────────────────────────────────────────────────────────

    @Test
    fun cssSelectorsColorsUnitsAtRules() {
        val src = ".btn { color: #ff0088; width: 12px; } @media screen { }\n#header {}"
        val lines = CssColorizer.colorize(src)
        val toks = tokens(lines)
        assertEquals(TokenKind.ANNOTATION, kindOf(toks, ".btn"))
        assertEquals(TokenKind.NUMBER, kindOf(toks, "#ff0088"), "a hex color reads as a number")
        assertEquals(TokenKind.NUMBER, kindOf(toks, "12px"), "a value with a unit reads as a number")
        assertEquals(TokenKind.KEYWORD, kindOf(toks, "@media"))
        assertEquals(TokenKind.ANNOTATION, kindOf(toks, "#header"), "a non-hex #id is a selector, not a color")
        assertEquals(TokenKind.PUNCTUATION, kindOf(toks, "{"))
        assertRoundTrips(src, lines)
    }

    @Test
    fun cssMultiLineCommentAndStringAndMalformedNeverThrow() {
        val src = "/* a\ncomment */ .x { content: \"y\"; } /* dangling"
        val lines = CssColorizer.colorize(src)
        assertEquals(TokenKind.COMMENT, lines[0].tokens.single().kind)
        assertEquals(TokenKind.STRING, tokens(lines).first { it.text == "\"y\"" }.kind)
        assertRoundTrips(src, lines)
    }

    // ── JavaScript ────────────────────────────────────────────────────────────

    @Test
    fun jsKeywordsStringsNumbersTypesComments() {
        val src = "const x = \"hi\"; // c\nfunction f() { return Math.max(1, 2.5); }"
        val lines = JsColorizer.colorize(src)
        val toks = tokens(lines)
        assertEquals(TokenKind.KEYWORD, kindOf(toks, "const"))
        assertEquals(TokenKind.KEYWORD, kindOf(toks, "function"))
        assertEquals(TokenKind.KEYWORD, kindOf(toks, "return"))
        assertEquals(TokenKind.STRING, kindOf(toks, "\"hi\""))
        assertEquals(TokenKind.NUMBER, kindOf(toks, "2.5"))
        assertEquals(TokenKind.TYPE, kindOf(toks, "Math"), "an upper-cased identifier reads as a type")
        assertEquals(TokenKind.COMMENT, toks.first { it.text.startsWith("// c") }.kind)
        assertRoundTrips(src, lines)
    }

    @Test
    fun jsTemplateStringAndUnterminatedNeverThrow() {
        val src = "const a = `template`;\nconst b = \"unterminated"
        val lines = JsColorizer.colorize(src)
        assertEquals(TokenKind.STRING, tokens(lines).first { it.text == "`template`" }.kind)
        assertEquals(TokenKind.STRING, tokens(lines).first { it.text.startsWith("\"unterminated") }.kind)
        assertRoundTrips(src, lines)
    }

    // ── dispatch ──────────────────────────────────────────────────────────────

    @Test
    fun dispatchPicksColorizerByExtension() {
        // JSON path → JSON colorizer (a key becomes ANNOTATION, which the plain fallback never produces).
        assertTrue(tokens(ResourceColorizers.colorize("data.json", "{\"a\": 1}")).any { it.kind == TokenKind.ANNOTATION })
        // XML/HTML → the XML colorizer (a tag name becomes KEYWORD).
        assertEquals(TokenKind.KEYWORD, tokens(ResourceColorizers.colorize("res/layout/a.xml", "<View/>")).first { it.text == "View" }.kind)
        assertEquals(TokenKind.KEYWORD, tokens(ResourceColorizers.colorize("page.html", "<div/>")).first { it.text == "div" }.kind)
        // A nested path resolves by the final segment's extension.
        assertTrue(tokens(ResourceColorizers.colorize("a/b/c/q.sql", "SELECT 1")).any { it.kind == TokenKind.KEYWORD })
    }

    @Test
    fun unknownExtensionFallsBackToPlain() {
        val lines = ResourceColorizers.colorize("blob.dat", "some raw text 123 <not a tag>")
        assertTrue(tokens(lines).all { it.kind == TokenKind.PLAIN }, "an unknown type is shown plain, never miscolored")
        assertRoundTrips("some raw text 123 <not a tag>", lines)
    }

    @Test
    fun isMarkupFlagsOnlyTheXmlFamily() {
        assertTrue(ResourceColorizers.isMarkup("a.xml"))
        assertTrue(ResourceColorizers.isMarkup("a.html"))
        assertTrue(!ResourceColorizers.isMarkup("a.json"))
        assertTrue(!ResourceColorizers.isMarkup("a.css"))
    }
}
