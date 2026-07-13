package com.jadxmp.ui.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StubHighlighterTest {

    private fun kindsOf(line: String) = StubHighlighter.highlightLine(line).associate { it.text to it.kind }

    @Test
    fun classifiesKeywordsTypesAndPlainIdentifiers() {
        val kinds = kindsOf("public String value")
        assertEquals(TokenKind.KEYWORD, kinds["public"])
        assertEquals(TokenKind.TYPE, kinds["String"])
        assertEquals(TokenKind.PLAIN, kinds["value"])
    }

    @Test
    fun classifiesStrings() {
        val tokens = StubHighlighter.highlightLine("return \"hello world\";")
        assertTrue(tokens.any { it.kind == TokenKind.STRING && it.text == "\"hello world\"" })
    }

    @Test
    fun classifiesLineComments() {
        val tokens = StubHighlighter.highlightLine("    // TODO: fix")
        assertTrue(tokens.any { it.kind == TokenKind.COMMENT && it.text.contains("TODO") })
    }

    @Test
    fun classifiesNumbersAndAnnotations() {
        assertTrue(StubHighlighter.highlightLine("int x = 42;").any { it.kind == TokenKind.NUMBER && it.text == "42" })
        assertTrue(StubHighlighter.highlightLine("@Override").any { it.kind == TokenKind.ANNOTATION && it.text == "@Override" })
    }

    @Test
    fun identifierBeforeParenIsMethod() {
        val tokens = StubHighlighter.highlightLine("setupViews();")
        assertTrue(tokens.any { it.kind == TokenKind.METHOD && it.text == "setupViews" })
    }

    @Test
    fun highlightPreservesLineTextExactly() {
        val src = "public void f() {\n    return;\n}"
        val doc = StubHighlighter.highlight(src)
        assertEquals(3, doc.size)
        assertEquals(src.split("\n"), doc.map { line -> line.tokens.joinToString("") { it.text } })
    }
}
