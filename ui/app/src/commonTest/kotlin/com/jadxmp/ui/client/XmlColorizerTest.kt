package com.jadxmp.ui.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure tests for [XmlColorizer] — the syntactic, wasm-safe XML highlighter used by the Resources view.
 * They pin the chosen [TokenKind] mapping (tag names → KEYWORD, attribute names → ANNOTATION, values →
 * STRING, brackets/`=`/`/` → PUNCTUATION, comments/PI → COMMENT, text/CDATA → PLAIN, entities → KEYWORD),
 * cross-line state (multi-line comments, CDATA, wrapped tags), round-trip fidelity, and no-throw on junk.
 */
class XmlColorizerTest {

    private fun lines(xml: String): List<CodeLine> = XmlColorizer.colorize(xml)

    private fun tokens(xml: String): List<CodeToken> = lines(xml).flatMap { it.tokens }

    /** The whole colorized text must equal the input verbatim — coloring never drops or rewrites chars. */
    private fun assertRoundTrips(xml: String) {
        val rebuilt = lines(xml).joinToString("\n") { line -> line.tokens.joinToString("") { it.text } }
        assertEquals(xml.replace("\r", ""), rebuilt)
    }

    private fun kindOf(tokens: List<CodeToken>, text: String): TokenKind =
        tokens.first { it.text == text }.kind

    @Test
    fun tagNamesAreKeywordsAndBracketsPunctuation() {
        val toks = tokens("<LinearLayout></LinearLayout>")
        assertEquals(TokenKind.PUNCTUATION, kindOf(toks, "<"))
        assertEquals(TokenKind.KEYWORD, toks.first { it.text == "LinearLayout" }.kind)
        assertEquals(TokenKind.PUNCTUATION, kindOf(toks, ">"))
        assertEquals(TokenKind.PUNCTUATION, kindOf(toks, "</"))
        assertRoundTrips("<LinearLayout></LinearLayout>")
    }

    @Test
    fun selfClosingTagUsesSlashGtPunctuation() {
        val toks = tokens("<View/>")
        assertEquals(TokenKind.KEYWORD, toks.first { it.text == "View" }.kind)
        assertEquals(TokenKind.PUNCTUATION, kindOf(toks, "/>"))
        assertRoundTrips("<View/>")
    }

    @Test
    fun attributesAndValuesAndEquals() {
        val src = "<TextView android:id=\"@+id/title\" android:text='Hi' />"
        val toks = tokens(src)
        assertEquals(TokenKind.KEYWORD, toks.first { it.text == "TextView" }.kind)
        assertEquals(TokenKind.ANNOTATION, kindOf(toks, "android:id"))
        assertEquals(TokenKind.PUNCTUATION, kindOf(toks, "="))
        assertEquals(TokenKind.STRING, kindOf(toks, "\"@+id/title\""))
        // Single-quoted value respects its quote char.
        assertEquals(TokenKind.STRING, kindOf(toks, "'Hi'"))
        assertEquals(TokenKind.PUNCTUATION, kindOf(toks, "/>"))
        assertRoundTrips(src)
    }

    @Test
    fun prologIsComment() {
        val toks = tokens("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        assertEquals(1, toks.size)
        assertEquals(TokenKind.COMMENT, toks[0].kind)
        assertEquals("<?xml version=\"1.0\" encoding=\"utf-8\"?>", toks[0].text)
    }

    @Test
    fun singleLineComment() {
        val toks = tokens("<a/><!-- note --><b/>")
        assertEquals(TokenKind.COMMENT, kindOf(toks, "<!-- note -->"))
        assertRoundTrips("<a/><!-- note --><b/>")
    }

    @Test
    fun multiLineCommentCarriesStateAcrossLines() {
        val src = "<a>\n<!-- line1\nstill comment\nend -->\n<b/>"
        val ls = lines(src)
        assertEquals(5, ls.size)
        // Lines 2, 3, and the first token of line 4 are all inside the comment.
        assertEquals(TokenKind.COMMENT, ls[1].tokens.single().kind)
        assertEquals(TokenKind.COMMENT, ls[2].tokens.single().kind)
        assertEquals(TokenKind.COMMENT, ls[3].tokens.first().kind)
        assertTrue(ls[3].tokens.first().text.endsWith("-->"))
        // Line 5 is back to normal markup.
        assertEquals(TokenKind.KEYWORD, ls[4].tokens.first { it.text == "b" }.kind)
        assertRoundTrips(src)
    }

    @Test
    fun multiLineCdataIsPlainAndCarriesState() {
        val src = "<script><![CDATA[\nif (a < b && c > d) {}\n]]></script>"
        val ls = lines(src)
        assertEquals(3, ls.size)
        // The `<` and `>` inside CDATA are NOT treated as markup.
        assertTrue(ls[1].tokens.all { it.kind == TokenKind.PLAIN })
        assertTrue(ls[1].tokens.joinToString("") { it.text }.contains("a < b && c > d"))
        assertRoundTrips(src)
    }

    @Test
    fun wrappedTagKeepsAttributesAcrossLines() {
        val src = "<View\n    android:id=\"@+id/x\"\n    android:layout_width=\"match_parent\" />"
        val ls = lines(src)
        assertEquals(3, ls.size)
        assertEquals(TokenKind.ANNOTATION, ls[1].tokens.first { it.text == "android:id" }.kind)
        assertEquals(TokenKind.STRING, ls[1].tokens.first { it.text == "\"@+id/x\"" }.kind)
        assertEquals(TokenKind.PUNCTUATION, ls[2].tokens.first { it.text == "/>" }.kind)
        assertRoundTrips(src)
    }

    @Test
    fun textContentIsPlainAndEntitiesAreKeyword() {
        val toks = tokens("<p>Tom &amp; Jerry &#169;</p>")
        assertEquals(TokenKind.KEYWORD, kindOf(toks, "&amp;"))
        assertEquals(TokenKind.KEYWORD, kindOf(toks, "&#169;"))
        assertEquals(TokenKind.PLAIN, toks.first { it.text.contains("Tom") }.kind)
        assertRoundTrips("<p>Tom &amp; Jerry &#169;</p>")
    }

    @Test
    fun unterminatedValueColorsToEndOfLine() {
        val toks = tokens("<a b=\"unclosed")
        assertEquals(TokenKind.STRING, kindOf(toks, "\"unclosed"))
        assertRoundTrips("<a b=\"unclosed")
    }

    @Test
    fun malformedInputNeverThrowsAndRoundTrips() {
        // A pile of broken markup: stray brackets, bare ampersand, unclosed tag/comment/cdata.
        val junk = "< not a tag > & <<< </> <!-- open\n<![CDATA[ dangling\n<a b= c=\"x"
        // Must not throw, and every character must survive.
        assertRoundTrips(junk)
        assertTrue(tokens(junk).isNotEmpty())
    }

    @Test
    fun emptyAndBlankLines() {
        val ls = lines("")
        assertEquals(1, ls.size)
        assertTrue(ls[0].tokens.isEmpty())
        assertEquals(1, ls[0].number)
    }

    @Test
    fun lineNumbersAreOneBased() {
        val ls = lines("<a/>\n<b/>\n<c/>")
        assertEquals(listOf(1, 2, 3), ls.map { it.number })
    }
}
