package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.client.CodeLine
import com.jadxmp.ui.client.CodeToken
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.TokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure per-line find over a [CodeDocument]'s plain text — the testable heart of the Ctrl+F bar. */
class DocumentFindTest {

    @Test
    fun findsEveryOccurrenceCaseInsensitivelyWithColumns() {
        // line1 plain: "class Foo {"   line2 plain: "  foo(); Foo();"
        val doc = doc(
            listOf("class ", "Foo", " {"),
            listOf("  ", "foo", "(); ", "Foo", "();"),
        )
        val matches = DocumentFind.find(doc, "foo", matchCase = false)
        assertEquals(
            listOf(
                FindMatch(line = 1, start = 6, end = 9), // "Foo" in "class Foo {"
                FindMatch(line = 2, start = 2, end = 5), // "foo" in "  foo(); Foo();"
                FindMatch(line = 2, start = 9, end = 12), // "Foo"
            ),
            matches,
        )
    }

    @Test
    fun matchCaseDistinguishesCasing() {
        val doc = doc(
            listOf("class ", "Foo", " {"),
            listOf("  ", "foo", "(); ", "Foo", "();"),
        )
        val matches = DocumentFind.find(doc, "foo", matchCase = true)
        assertEquals(listOf(FindMatch(line = 2, start = 2, end = 5)), matches)
    }

    @Test
    fun spansTokenBoundaries() {
        // "foo" + "bar" flatten to "foobar"; "oba" straddles both tokens (cols 2..5).
        val doc = doc(listOf("foo", "bar"))
        assertEquals(listOf(FindMatch(1, 2, 5)), DocumentFind.find(doc, "oba", matchCase = false))
    }

    @Test
    fun occurrencesAreNonOverlapping() {
        val doc = doc(listOf("aaaa"))
        assertEquals(
            listOf(FindMatch(1, 0, 2), FindMatch(1, 2, 4)),
            DocumentFind.find(doc, "aa", matchCase = false),
        )
    }

    @Test
    fun emptyQueryAndNoMatchYieldNothing() {
        val doc = doc(listOf("hello world"))
        assertTrue(DocumentFind.find(doc, "", matchCase = false).isEmpty())
        assertTrue(DocumentFind.find(doc, "zzz", matchCase = false).isEmpty())
    }

    private fun doc(vararg lines: List<String>): CodeDocument =
        CodeDocument(
            nodeId = NodeId("cls:T"),
            title = "T",
            view = CodeView.JAVA,
            lines = lines.mapIndexed { i, toks -> CodeLine(i + 1, toks.map { CodeToken(it, TokenKind.PLAIN) }) },
        )
}
