package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.NodeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the pure code-content scan ([CodeSearch]) — the seam kept free of Compose and the
 * client so the matching, capping, fault-isolation, and cancellation rules can be asserted directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodeSearchTest {

    private fun ref(name: String) = CodeSearch.ClassRef(NodeId("cls:$name"), name, "pkg")

    /** A sink that accumulates everything for assertions. */
    private class RecordingSink : CodeSearch.ScanSink {
        val matches = mutableListOf<CodeMatch>()
        val progress = mutableListOf<Pair<Int, Int>>()
        override fun onMatch(match: CodeMatch) { matches += match }
        override fun onProgress(scanned: Int, total: Int) { progress += scanned to total }
    }

    @Test
    fun lineMatchesReportsOneBasedLinesAndTrimsSnippets() {
        val source = "package p;\n    int token = 1;\nvoid f() {}\n  return token;\n"
        val hits = CodeSearch.lineMatches(source) { it.contains("token") }
        assertEquals(listOf(2 to "int token = 1;", 4 to "return token;"), hits)
    }

    @Test
    fun scanStreamsMatchesAcrossClasses() = runTest {
        val classes = listOf(ref("A"), ref("B"))
        val sources = mapOf(
            "cls:A" to "class A {\n  token();\n}",
            "cls:B" to "class B {\n  // token here too\n  x();\n}",
        )
        val sink = RecordingSink()
        val summary = CodeSearch.scan(classes, CodeSearch.Query("token"), sink) { sources[it.value] }

        assertEquals(2, summary.scanned)
        assertEquals(2, summary.matches)
        assertEquals(0, summary.failed)
        assertFalse(summary.truncated)
        assertEquals(NodeId("cls:A"), sink.matches[0].nodeId)
        assertEquals(2, sink.matches[0].line)
        assertEquals(NodeId("cls:B"), sink.matches[1].nodeId)
        assertEquals(2, sink.matches[1].line)
        assertEquals("// token here too", sink.matches[1].snippet)
    }

    @Test
    fun scanIsCaseSensitiveWhenIgnoreCaseIsOff() = runTest {
        val classes = listOf(ref("A"))
        val src = mapOf("cls:A" to "Token\ntoken\nTOKEN")
        val insensitive = RecordingSink()
        CodeSearch.scan(classes, CodeSearch.Query("token", ignoreCase = true), insensitive) { src[it.value] }
        assertEquals(3, insensitive.matches.size)

        val sensitive = RecordingSink()
        CodeSearch.scan(classes, CodeSearch.Query("token", ignoreCase = false), sensitive) { src[it.value] }
        assertEquals(listOf(2), sensitive.matches.map { it.line })
    }

    @Test
    fun scanCapsResultsAndFlagsTruncation() = runTest {
        // 10 classes, each with 5 matching lines = 50 potential hits; cap at 7.
        val classes = (1..10).map { ref("C$it") }
        val body = (1..5).joinToString("\n") { "hit $it" }
        val sink = RecordingSink()
        val summary = CodeSearch.scan(classes, CodeSearch.Query("hit"), sink, maxResults = 7) { body }

        assertEquals(7, sink.matches.size, "must not emit past the cap")
        assertEquals(7, summary.matches)
        assertTrue(summary.truncated, "hitting the cap must be reported, never silent")
        assertTrue(summary.scanned < 10, "scan stops early once capped")
    }

    @Test
    fun scanSkipsClassesThatFailToDecompileAndKeepsGoing() = runTest {
        val classes = listOf(ref("A"), ref("Bad"), ref("Throws"), ref("C"))
        val sink = RecordingSink()
        val summary = CodeSearch.scan(classes, CodeSearch.Query("hit"), sink) { node ->
            when (node.value) {
                "cls:Bad" -> null // could not decompile
                "cls:Throws" -> throw IllegalStateException("boom")
                else -> "hit"
            }
        }
        assertEquals(4, summary.scanned, "every class is visited")
        assertEquals(2, summary.failed, "the null and the throwing class are both counted as failed")
        assertEquals(2, summary.matches, "A and C still matched despite the two bad classes")
        assertEquals(listOf(NodeId("cls:A"), NodeId("cls:C")), sink.matches.map { it.nodeId })
    }

    @Test
    fun blankQueryMatchesNothing() = runTest {
        val sink = RecordingSink()
        val summary = CodeSearch.scan(listOf(ref("A")), CodeSearch.Query("  "), sink) { "anything" }
        assertEquals(0, summary.matches)
        assertEquals(0, summary.scanned)
    }

    @Test
    fun invalidRegexMatchesNothingRatherThanThrowing() = runTest {
        val sink = RecordingSink()
        val summary = CodeSearch.scan(listOf(ref("A")), CodeSearch.Query("[unclosed", useRegex = true), sink) { "[unclosed here" }
        assertEquals(0, summary.matches, "a broken pattern degrades to no matches")
    }

    @Test
    fun scanPropagatesCancellationFromSourceOfWithoutSwallowingIt() = runTest {
        // A CancellationException out of sourceOf (a decompile whose coroutine was cancelled) must
        // abort the scan immediately — NOT be caught by the fault-isolation branch and treated as a
        // skipped class, which would let a superseded scan keep reporting progress.
        val sink = RecordingSink()
        assertFailsWith<CancellationException> {
            CodeSearch.scan(listOf(ref("A"), ref("B")), CodeSearch.Query("x"), sink) {
                throw CancellationException("job cancelled mid-decompile")
            }
        }
        // Only the initial onProgress(0, total) ran; class A's cancellation stopped everything before
        // any per-class progress/matches, and class B was never visited.
        assertEquals(listOf(0 to 2), sink.progress)
        assertTrue(sink.matches.isEmpty())
    }

    @Test
    fun scanIsCooperativelyCancelable() = runTest {
        val classes = (1..200).map { ref("C$it") }
        val sink = RecordingSink()
        // A per-class delay makes virtual time meaningful, so we can cancel partway through.
        val job = launch {
            CodeSearch.scan(classes, CodeSearch.Query("x"), sink) { delay(10); "no match here" }
        }
        advanceTimeBy(35) // ~3 classes processed
        job.cancel()
        advanceUntilIdle()
        val furthest = sink.progress.maxOf { it.first }
        assertTrue(furthest in 1 until 200, "cancel stops the scan mid-way (reached $furthest of 200)")
    }

    // ── Result-row match highlighting (matchSpans) ──────────────────────────────

    @Test
    fun matchSpansFindsEveryNonOverlappingHit() {
        assertEquals(
            listOf(MatchSpan(0, 5), MatchSpan(8, 13)),
            matchSpans("token = token;", "token", ignoreCase = true, useRegex = false),
        )
    }

    @Test
    fun matchSpansAvoidsOverlappingHits() {
        // "aa" over "aaaa" advances by the query length, so the two hits abut, never overlap.
        assertEquals(
            listOf(MatchSpan(0, 2), MatchSpan(2, 4)),
            matchSpans("aaaa", "aa", ignoreCase = true, useRegex = false),
        )
    }

    @Test
    fun matchSpansHonoursCaseSensitivity() {
        val text = "Token token TOKEN"
        assertEquals(
            listOf(MatchSpan(6, 11)),
            matchSpans(text, "token", ignoreCase = false, useRegex = false),
            "case-sensitive matches only the exact-case occurrence",
        )
        assertEquals(
            listOf(MatchSpan(0, 5), MatchSpan(6, 11), MatchSpan(12, 17)),
            matchSpans(text, "token", ignoreCase = true, useRegex = false),
            "case-insensitive matches all three casings",
        )
    }

    @Test
    fun matchSpansReturnsEmptyForNoMatchBlankOrEmpty() {
        assertTrue(matchSpans("abc", "z", ignoreCase = true, useRegex = false).isEmpty(), "no occurrence")
        assertTrue(matchSpans("abc", "", ignoreCase = true, useRegex = false).isEmpty(), "empty query")
        assertTrue(matchSpans("abc", "   ", ignoreCase = true, useRegex = false).isEmpty(), "blank query")
        assertTrue(matchSpans("", "abc", ignoreCase = true, useRegex = false).isEmpty(), "empty text")
    }

    @Test
    fun matchSpansSupportsRegexIncludingIgnoreCase() {
        assertEquals(
            listOf(MatchSpan(1, 3), MatchSpan(4, 5)),
            matchSpans("a12b3", "\\d+", ignoreCase = true, useRegex = true),
        )
        assertEquals(
            listOf(MatchSpan(0, 3)),
            matchSpans("ABC", "abc", ignoreCase = true, useRegex = true),
            "regex honours the ignore-case flag",
        )
    }

    @Test
    fun matchSpansInvalidRegexHighlightsNothingRatherThanThrowing() {
        assertTrue(
            matchSpans("[unclosed here", "[", ignoreCase = true, useRegex = true).isEmpty(),
            "a broken pattern degrades to no spans (mirrors the scan's matcher)",
        )
    }

    @Test
    fun matchSpansRegexDropsZeroWidthMatches() {
        // "a*" matches an empty string between characters; those zero-width hits must be dropped (and must
        // not loop), leaving only the real "a".
        assertEquals(
            listOf(MatchSpan(1, 2)),
            matchSpans("bab", "a*", ignoreCase = true, useRegex = true),
        )
    }

    // ── Result pagination (resultPage) ──────────────────────────────────────────

    @Test
    fun resultPageUnderLimitShowsAllWithNoMore() {
        val page = resultPage(collected = 10, limit = 60, capped = false)
        assertEquals(10, page.shown)
        assertFalse(page.hasMore)
        assertEquals("showing 10 of 10", page.label)
    }

    @Test
    fun resultPageOverLimitOffersShowMore() {
        val page = resultPage(collected = 200, limit = 60, capped = false)
        assertEquals(60, page.shown)
        assertTrue(page.hasMore, "there are collected rows beyond the window to reveal")
        assertEquals("showing 60 of 200", page.label)
    }

    @Test
    fun resultPageCappedRendersAPlusOnceAllRevealed() {
        val page = resultPage(collected = 300, limit = 300, capped = true)
        assertEquals(300, page.shown)
        assertFalse(page.hasMore, "everything collected is shown; more exist only beyond the scan's cap")
        assertEquals("showing 300 of 300+", page.label, "the cap is surfaced as N+, never a silent total")
    }

    @Test
    fun resultPageCappedStillPagesTheCollectedWindow() {
        val page = resultPage(collected = 300, limit = 60, capped = true)
        assertEquals(60, page.shown)
        assertTrue(page.hasMore)
        assertEquals("showing 60 of 300+", page.label)
    }

    @Test
    fun resultPageEmptyIsZeroOfZero() {
        val page = resultPage(collected = 0, limit = 60, capped = false)
        assertEquals(0, page.shown)
        assertFalse(page.hasMore)
        assertEquals("showing 0 of 0", page.label)
    }
}
