package com.jadxmp.ui.workbench

import androidx.compose.runtime.Immutable
import com.jadxmp.ui.client.NodeId
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException

/**
 * Code-content search: the whole-program scan that backs the "Code" search scope.
 *
 * jadx-gui can search the *decompiled source text* of every class; this is that feature. Because there
 * is no code index (a future engine capability, see docs/UI-DESIGN.md §4.5), the only way to answer is
 * to decompile each class and grep its text — inherently O(classes). The design here is built around
 * the constraints that makes on a **single-threaded wasm** UI:
 *
 * - The orchestration ([scan]) is a `suspend` function that [yield]s after every class so Compose can
 *   recompose (progress + streamed results stay live) and so cancellation is prompt. There is no
 *   blocking and no thread.
 * - Matches are reported **incrementally** via [ScanSink] as they are found, not batched at the end.
 * - Results are **capped** ([maxResults]); on hitting the cap the scan stops early and flags
 *   [ScanSummary.truncated] so the UI can say so — never a silent cap.
 * - A class that fails to decompile (its `sourceOf` returns null or throws) is **skipped**, counted in
 *   [ScanSummary.failed], and the scan continues — fault isolation, no one bad class aborts the search.
 *
 * The per-line matching ([lineMatches]) and the orchestration are deliberately separated from Compose
 * and from [com.jadxmp.ui.client.DecompilerClient] so both are unit-testable with a fake source.
 */
object CodeSearch {

    /** Default upper bound on collected matches. Beyond this the scan stops and reports truncation. */
    const val DEFAULT_MAX_RESULTS: Int = 300

    /** A class to scan: its node id plus the display parts used to render a match row. */
    @Immutable
    data class ClassRef(val nodeId: NodeId, val simpleName: String, val packageName: String)

    /** A compiled query. [useRegex]+invalid pattern degrades to "matches nothing" (never throws). */
    @Immutable
    data class Query(val text: String, val ignoreCase: Boolean = true, val useRegex: Boolean = false) {
        val isBlank: Boolean get() = text.isBlank()

        /** Pre-compiled matcher over a single source line. */
        internal fun matcher(): (String) -> Boolean {
            if (useRegex) {
                val regex = runCatching {
                    Regex(text, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
                }.getOrNull() ?: return { false }
                return { line -> regex.containsMatchIn(line) }
            }
            return { line -> line.contains(text, ignoreCase = ignoreCase) }
        }
    }

    /** Receives streamed matches and progress. Called on the scanning coroutine (the UI dispatcher). */
    interface ScanSink {
        /** A new match was found. */
        fun onMatch(match: CodeMatch)

        /** [scanned] of [total] classes processed. Called after each class (and once at the start). */
        fun onProgress(scanned: Int, total: Int)
    }

    /** Outcome of a completed (or truncated) scan. */
    @Immutable
    data class ScanSummary(
        val scanned: Int,
        val total: Int,
        val matches: Int,
        val failed: Int,
        val truncated: Boolean,
    )

    /**
     * Scan [classes] for lines matching [query], decompiling each via [sourceOf].
     *
     * [sourceOf] returns the plain decompiled text of a class, or null if it could not be produced
     * (the class is then skipped and counted as failed). In production it delegates to the client's
     * cached `code(...)` path, so a class already viewed is not decompiled twice.
     *
     * Streams to [sink] and returns a [ScanSummary]. Cooperatively cancelable: a cancel of the calling
     * coroutine surfaces as a `CancellationException` from [yield] and unwinds the scan.
     */
    suspend fun scan(
        classes: List<ClassRef>,
        query: Query,
        sink: ScanSink,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        sourceOf: suspend (NodeId) -> String?,
    ): ScanSummary {
        val total = classes.size
        sink.onProgress(0, total)
        if (query.isBlank || maxResults <= 0) {
            return ScanSummary(scanned = 0, total = total, matches = 0, failed = 0, truncated = false)
        }
        val matches = query.matcher()
        var emitted = 0
        var failed = 0
        var truncated = false
        var scanned = 0

        loop@ for (cls in classes) {
            val source = try {
                sourceOf(cls.nodeId)
            } catch (e: CancellationException) {
                // Cancellation is control flow, not a decompile fault — must propagate, never be
                // swallowed as a skipped class (else a superseded scan keeps running and can stream
                // stale results into the new query's state). Rethrow before the fault-isolation catch.
                throw e
            } catch (e: Exception) {
                // Fault isolation: a class that blows up decompiling must not abort the whole scan.
                null
            }
            scanned++
            if (source == null) {
                failed++
            } else {
                for ((lineNumber, snippet) in lineMatches(source, matches)) {
                    if (emitted >= maxResults) {
                        truncated = true
                        break@loop
                    }
                    sink.onMatch(
                        CodeMatch(
                            nodeId = cls.nodeId,
                            simpleName = cls.simpleName,
                            packageName = cls.packageName,
                            line = lineNumber,
                            snippet = snippet,
                        ),
                    )
                    emitted++
                }
            }
            sink.onProgress(scanned, total)
            // Hand the (single) dispatcher back so the results/progress recompose and cancel is prompt.
            yield()
        }
        return ScanSummary(scanned = scanned, total = total, matches = emitted, failed = failed, truncated = truncated)
    }

    /**
     * Every line of [source] that satisfies [matcher], as (1-based line number, trimmed snippet).
     * Pure — the heart of the scan and the easiest piece to assert on.
     */
    internal fun lineMatches(source: String, matcher: (String) -> Boolean): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var lineNumber = 0
        for (line in source.lineSequence()) {
            lineNumber++
            if (matcher(line)) out += lineNumber to line.trim()
        }
        return out
    }
}

/** One code-search hit: a line of a class's decompiled source that matched. */
@Immutable
data class CodeMatch(
    val nodeId: NodeId,
    val simpleName: String,
    val packageName: String,
    /** 1-based line number within the class's decompiled source. */
    val line: Int,
    /** The trimmed matching line, shown as the result's context. */
    val snippet: String,
)

/**
 * Observable state of a code-content search, streamed into the workbench UI as the scan runs.
 * [running] is true while classes are still being scanned; [truncated] is set if the result cap was
 * hit; [failed] counts classes skipped because they could not be decompiled.
 */
@Immutable
data class CodeSearchUiState(
    val query: String = "",
    val matches: List<CodeMatch> = emptyList(),
    val scanned: Int = 0,
    val total: Int = 0,
    val running: Boolean = false,
    val truncated: Boolean = false,
    val failed: Int = 0,
) {
    val hasResults: Boolean get() = matches.isNotEmpty()
    val fraction: Float get() = if (total == 0) 0f else scanned.toFloat() / total
}

// ── Result-row match highlighting (pure; unit-tested) ─────────────────────────
//
// These back the search panel's *presentation* (which characters of a result row to emphasise, how many
// rows to reveal) rather than the scan itself, but they belong here beside the matcher they mirror so the
// two can never drift: the highlight has to land on exactly what [CodeSearch.Query.matcher] found. Kept
// free of Compose so the span math and paging math are asserted directly (see CodeSearchTest).

/** A half-open `[start, end)` span of a matched substring within a result row's display text. */
@Immutable
internal data class MatchSpan(val start: Int, val end: Int)

/**
 * Every non-overlapping span of [query] within [text], honouring [ignoreCase] / [useRegex] — the slices a
 * result row paints amber to show *why* it matched. Deliberately mirrors the scan's own matcher so a row's
 * highlight always agrees with the hit that produced it:
 *
 * - A blank query, an invalid regex, or simply no occurrence all yield an empty list and never throw
 *   (rule 4) — exactly as [CodeSearch.Query] degrades a bad pattern to "matches nothing".
 * - Plain matches advance by the query length, so `"aa"` over `"aaaa"` highlights `[0,2)` and `[2,4)`, never
 *   overlapping.
 * - Regex zero-width hits (e.g. `a*` matching between characters) are dropped, so a row can never paint an
 *   empty range and [findAll]'s own advance rules keep it from looping.
 *
 * Pure — the easiest piece to assert on.
 */
internal fun matchSpans(text: String, query: String, ignoreCase: Boolean, useRegex: Boolean): List<MatchSpan> {
    if (query.isBlank() || text.isEmpty()) return emptyList()
    if (useRegex) {
        val regex = runCatching {
            Regex(query, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        }.getOrNull() ?: return emptyList()
        return regex.findAll(text)
            .mapNotNull { m -> if (m.range.isEmpty()) null else MatchSpan(m.range.first, m.range.last + 1) }
            .toList()
    }
    val out = ArrayList<MatchSpan>()
    val step = query.length // query is non-blank here, so step >= 1 → the loop always advances
    var idx = text.indexOf(query, startIndex = 0, ignoreCase = ignoreCase)
    while (idx >= 0) {
        out += MatchSpan(idx, idx + query.length)
        idx = text.indexOf(query, startIndex = idx + step, ignoreCase = ignoreCase)
    }
    return out
}

// ── Result pagination (pure; unit-tested) ─────────────────────────────────────

/** Default reveal window: how many collected results a scope shows before offering "Show more". */
internal const val RESULT_PAGE_SIZE: Int = 60

/**
 * The window of collected results to reveal plus an honest count label. [collected] is how many matches are
 * in hand right now; [limit] the current reveal window (grown by "Show more"); [capped] is true when the
 * scan itself stopped at its hard cap, so *more than [collected]* may exist — rendered as "N+" so the count
 * is never a silent lie (rule 4: no silent loss). [hasMore] means there are already-collected rows beyond
 * the window that "Show more" can reveal instantly; the cap-driven "+" is surfaced separately by the panel.
 * Pure.
 */
@Immutable
internal data class ResultPage(val shown: Int, val hasMore: Boolean, val label: String)

internal fun resultPage(collected: Int, limit: Int, capped: Boolean): ResultPage {
    val shown = collected.coerceIn(0, maxOf(0, limit))
    val total = if (capped) "$collected+" else "$collected"
    return ResultPage(shown = shown, hasMore = shown < collected, label = "showing $shown of $total")
}
