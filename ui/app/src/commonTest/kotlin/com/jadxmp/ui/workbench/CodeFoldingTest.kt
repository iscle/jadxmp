package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.CodeLine
import com.jadxmp.ui.client.CodeToken
import com.jadxmp.ui.client.TokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure brace-matched fold computation (P1#10) — the piece that decides which line ranges collapse. The
 * crux is robustness: a single-line block never folds, nested blocks nest, braces inside strings/comments
 * are ignored, and any imbalance disables folding wholesale (no fold, never a crash — rule 4).
 */
class CodeFoldingTest {

    /** One line whose plain text is [text]; braces are PUNCTUATION unless a [kind] is forced for the whole line. */
    private fun line(number: Int, text: String, kind: TokenKind = TokenKind.PUNCTUATION): CodeLine =
        CodeLine(number, listOf(CodeToken(text, kind)))

    @Test
    fun aMultiLineBraceBlockFoldsFromHeaderToCloser() {
        val lines = listOf(
            line(1, "class Foo {"),
            line(2, "  int x;"),
            line(3, "}"),
        )
        assertEquals(listOf(FoldRegion(headerLine = 1, endLine = 3)), computeFoldRegions(lines))
    }

    @Test
    fun aSingleLineBlockIsNotFoldable() {
        // '{' and '}' on the same line — nothing to hide, so no region (but still balanced → not disabled).
        val lines = listOf(line(1, "int[] a = {1, 2};"))
        assertEquals(emptyList(), computeFoldRegions(lines))
    }

    @Test
    fun aTwoLineBlockIsNotFoldableSinceItHasNoInterior() {
        // header on line 1, closer on line 2: endLine(2) < headerLine(1)+2, so nothing to collapse.
        val lines = listOf(line(1, "void m() {"), line(2, "}"))
        assertEquals(emptyList(), computeFoldRegions(lines))
    }

    @Test
    fun nestedBlocksProduceNestedRegionsSortedByHeader() {
        val lines = listOf(
            line(1, "class Foo {"),
            line(2, "  void m() {"),
            line(3, "    x();"),
            line(4, "  }"),
            line(5, "}"),
        )
        assertEquals(
            listOf(FoldRegion(1, 5), FoldRegion(2, 4)),
            computeFoldRegions(lines),
        )
    }

    @Test
    fun bracesInsideStringsAndCommentsAreIgnored() {
        // The string's stray braces must not open/close structure — the real block still balances and folds.
        val lines = listOf(
            line(1, "class Foo {"),
            CodeLine(2, listOf(CodeToken("  String s = \"a { b } c\";", TokenKind.STRING))),
            CodeLine(3, listOf(CodeToken("  // trailing } brace in a comment", TokenKind.COMMENT))),
            line(4, "}"),
        )
        assertEquals(listOf(FoldRegion(1, 4)), computeFoldRegions(lines))
    }

    @Test
    fun aStrayClosingBraceDisablesFoldingWholesale() {
        val lines = listOf(line(1, "}"), line(2, "class Foo {"), line(3, "  x;"), line(4, "}"))
        assertTrue(computeFoldRegions(lines).isEmpty(), "unbalanced '}' → no folds")
    }

    @Test
    fun anUnclosedBraceDisablesFoldingWholesale() {
        val lines = listOf(line(1, "class Foo {"), line(2, "  x;"))
        assertTrue(computeFoldRegions(lines).isEmpty(), "unclosed '{' → no folds")
    }

    @Test
    fun hiddenLinesAreTheInteriorOfEachCollapsedRegion() {
        val folds = listOf(FoldRegion(1, 5), FoldRegion(2, 4))
        // Collapsing the outer block hides its whole interior (both header and closer stay visible).
        assertEquals(setOf(2, 3, 4), hiddenLineNumbers(folds, collapsedHeaders = setOf(1)))
        // Collapsing only the inner block hides just its one interior line.
        assertEquals(setOf(3), hiddenLineNumbers(folds, collapsedHeaders = setOf(2)))
        // Nothing collapsed → nothing hidden.
        assertEquals(emptySet(), hiddenLineNumbers(folds, collapsedHeaders = emptySet()))
    }
}
