package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.CodeLine
import com.jadxmp.ui.client.CodeToken
import com.jadxmp.ui.client.TokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Caret-word occurrence highlight (P1#13): which token text a click adopts as the "current word", and the
 * whole-word matching that follows. Matching is whole-token equality, which is inherently whole-word — the
 * point of these tests is that identifiers qualify, non-identifiers don't, and a substring never matches.
 */
class CaretWordTest {

    @Test
    fun identifiersAreHighlightableWords() {
        assertTrue(isHighlightableWord("count"))
        assertTrue(isHighlightableWord("Foo"))
        assertTrue(isHighlightableWord("_private"))
        assertTrue(isHighlightableWord("\$r0"))
        assertTrue(isHighlightableWord("a1b2"))
    }

    @Test
    fun nonIdentifiersAreNotWords() {
        assertFalse(isHighlightableWord(""), "empty")
        assertFalse(isHighlightableWord("   "), "whitespace")
        assertFalse(isHighlightableWord("1abc"), "leading digit")
        assertFalse(isHighlightableWord("123"), "number")
        assertFalse(isHighlightableWord("a.b"), "dotted")
        assertFalse(isHighlightableWord("+"), "operator")
        assertFalse(isHighlightableWord("=="), "operator")
    }

    @Test
    fun onlyIdentifierKindsSeedTheCaretWord() {
        assertEquals("count", caretWordFor(TokenKind.PLAIN, "count"))
        assertEquals("Foo", caretWordFor(TokenKind.TYPE, "Foo"))
        assertEquals("run", caretWordFor(TokenKind.METHOD, "run"))
        assertEquals("size", caretWordFor(TokenKind.FIELD, "size"))
        // Keywords, strings, numbers, punctuation and comments are not highlighted on click.
        assertNull(caretWordFor(TokenKind.KEYWORD, "return"))
        assertNull(caretWordFor(TokenKind.STRING, "\"count\""))
        assertNull(caretWordFor(TokenKind.NUMBER, "42"))
        assertNull(caretWordFor(TokenKind.PUNCTUATION, "{"))
        assertNull(caretWordFor(TokenKind.COMMENT, "// count"))
    }

    @Test
    fun wholeWordMatchingHitsEveryOccurrenceAndNothingElse() {
        // A tiny document: the word "count" appears as a field and a local, plus the decoys "counter"
        // (superstring) and a "count" inside a string literal — neither is a whole-token match.
        val lines = listOf(
            CodeLine(1, listOf(CodeToken("int ", TokenKind.KEYWORD), CodeToken("count", TokenKind.FIELD), CodeToken(";", TokenKind.PUNCTUATION))),
            CodeLine(2, listOf(CodeToken("var ", TokenKind.KEYWORD), CodeToken("counter", TokenKind.PLAIN))),
            CodeLine(3, listOf(CodeToken("log(", TokenKind.PLAIN), CodeToken("\"count\"", TokenKind.STRING), CodeToken(")", TokenKind.PUNCTUATION))),
            CodeLine(4, listOf(CodeToken("count", TokenKind.PLAIN), CodeToken("++;", TokenKind.PUNCTUATION))),
        )
        val word = caretWordFor(TokenKind.FIELD, "count")
        // The render predicate is `token.text == caretWord`; matches are the two whole "count" tokens.
        val matchedLines = lines.filter { line -> line.tokens.any { it.text == word } }.map { it.number }
        assertEquals(listOf(1, 4), matchedLines)
    }
}
