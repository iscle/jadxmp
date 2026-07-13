package com.jadxmp.ui.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [JavaLexer] — the new syntactic risk surface. Covers the two invariants the client
 * relies on (whole-string coverage + exact contiguous offsets) plus the adversarial cases that could
 * make a naive scanner hang or mis-offset: unterminated literals/comments, escapes, numeric forms,
 * annotations, EOF edges, and CRLF.
 */
class JavaLexerTest {

    /** Concatenating every token must reproduce the source exactly (no dropped/duplicated chars). */
    private fun assertCoversAndContiguous(src: String) {
        val tokens = JavaLexer.lex(src)
        assertEquals(src, tokens.joinToString("") { it.text }, "tokens must reconstruct the source")
        var pos = 0
        for (t in tokens) {
            assertEquals(pos, t.offset, "token offset must be contiguous at $pos")
            assertTrue(t.text.isNotEmpty(), "no empty tokens")
            pos += t.text.length
        }
        assertEquals(src.length, pos, "offsets must span the whole source")
    }

    private fun kinds(src: String): List<Pair<String, TokenKind>> =
        JavaLexer.lex(src).map { it.text to it.kind }

    @Test
    fun coversRepresentativeSource() {
        assertCoversAndContiguous(
            """
            package a.b;
            @Override
            public final class C extends D { // trailing
                /* block
                   comment */
                String s = "hi\n\"x\"";
                char c = '\'';
                long n = 0xFF_00L;
                double d = 1_000.5e-3;
            }
            """.trimIndent(),
        )
    }

    @Test
    fun emptyAndWhitespaceAndCrlf() {
        assertCoversAndContiguous("")
        assertCoversAndContiguous("   \t  ")
        assertCoversAndContiguous("a\r\nb\r\n")
        // A lone identifier surrounded by CRLF stays a single identifier token.
        val idents = JavaLexer.lex("\r\nfoo\r\n").filter { it.isIdentifier }.map { it.text }
        assertEquals(listOf("foo"), idents)
    }

    @Test
    fun unterminatedStringDoesNotHangAndStopsAtNewline() {
        assertCoversAndContiguous("String s = \"oops\n next;")
        val str = JavaLexer.lex("\"oops\n").first { it.kind == TokenKind.STRING }
        assertEquals("\"oops", str.text, "unterminated string bails at the newline")
    }

    @Test
    fun unterminatedCharAndBlockCommentAtEof() {
        assertCoversAndContiguous("char c = 'a")
        assertCoversAndContiguous("x /* never closed")
        val comment = JavaLexer.lex("/* to eof").single { it.kind == TokenKind.COMMENT }
        assertEquals("/* to eof", comment.text)
    }

    @Test
    fun lineCommentAtEofHasNoTrailingNewline() {
        val comment = JavaLexer.lex("x // note").single { it.kind == TokenKind.COMMENT }
        assertEquals("// note", comment.text)
    }

    @Test
    fun blockCommentDoesNotNest() {
        // Java block comments do not nest: the first */ closes it; the rest is ordinary tokens.
        val tokens = JavaLexer.lex("/* a /* b */ c")
        assertEquals("/* a /* b */", tokens.first { it.kind == TokenKind.COMMENT }.text)
        assertTrue(tokens.any { it.isIdentifier && it.text == "c" })
    }

    @Test
    fun stringAndCharEscapes() {
        val str = JavaLexer.lex(""""a\"b\\c"""").single { it.kind == TokenKind.STRING }
        assertEquals(""""a\"b\\c"""", str.text, "escaped quote/backslash stay inside the string")
        val ch = JavaLexer.lex("'\\''").single { it.kind == TokenKind.STRING }
        assertEquals("'\\''", ch.text, "escaped quote inside a char literal")
    }

    @Test
    fun numericLiteralForms() {
        for (n in listOf("0", "42", "0xFF_00L", "1_000", "3.14", "1_000.5e-3", "0b1010", "1.0f", "10L")) {
            val num = JavaLexer.lex("x = $n;").firstOrNull { it.kind == TokenKind.NUMBER }
            assertEquals(n, num?.text, "numeric literal '$n' should lex as one NUMBER token")
        }
    }

    @Test
    fun annotationTokenIncludesAtSign() {
        val ann = JavaLexer.lex("@Override void m(){}").single { it.kind == TokenKind.ANNOTATION }
        assertEquals("@Override", ann.text)
    }

    @Test
    fun keywordsAndIdentifiers() {
        val ks = kinds("public static int x")
        assertTrue("public" to TokenKind.KEYWORD in ks)
        assertTrue("static" to TokenKind.KEYWORD in ks)
        assertTrue("int" to TokenKind.KEYWORD in ks)
        // A bare identifier stays PLAIN and flagged for metadata refinement.
        val x = JavaLexer.lex("public static int x").single { it.text == "x" }
        assertEquals(TokenKind.PLAIN, x.kind)
        assertTrue(x.isIdentifier)
    }

    @Test
    fun dollarAndUnderscoreAreIdentifierChars() {
        val id = JavaLexer.lex("Outer\$Inner _v").filter { it.isIdentifier }.map { it.text }
        assertEquals(listOf("Outer\$Inner", "_v"), id)
    }
}
