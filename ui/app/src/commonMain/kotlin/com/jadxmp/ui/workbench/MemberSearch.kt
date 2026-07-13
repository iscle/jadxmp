package com.jadxmp.ui.workbench

import androidx.compose.runtime.Immutable
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.NodeKind
import com.jadxmp.ui.client.TreeNode
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException

/**
 * Member-content search: the whole-program scan behind the "Methods" and "Fields" search scopes.
 *
 * Unlike [CodeSearch], this never decompiles — it enumerates each class's declared members (cheap, from
 * the loaded model) and matches the query against a member's display name / signature, filtered to the
 * requested [NodeKind]s (METHOD for Methods — covering constructors, which also render as method rows;
 * FIELD for Fields). It reuses the exact discipline of the code scan so it inherits the same
 * single-threaded-wasm safety and cancellation guarantees:
 *
 * - [scan] is `suspend`, yields after every class (prompt cancellation, live streaming), never blocks.
 * - Matches stream through [ScanSink] as found, capped at [maxResults]; hitting the cap stops early and
 *   flags [ScanSummary.truncated] — never a silent cap.
 * - A class whose member enumeration returns null / throws is skipped and counted in [ScanSummary.failed]
 *   — fault isolation, no one bad class aborts the search.
 * - A `CancellationException` from [membersOf] is rethrown, never swallowed as a skip, so a superseded
 *   scan cannot keep streaming stale matches into the new query's state.
 *
 * Kept free of Compose and the client so the matching, filtering, capping and cancellation rules are
 * asserted directly with a fake [membersOf].
 */
object MemberSearch {

    /** Default upper bound on collected matches. Member scans are light, so this can be generous. */
    const val DEFAULT_MAX_RESULTS: Int = 500

    /** A class to scan: its node id plus the display parts used to render a match row's owner. */
    @Immutable
    data class ClassRef(val nodeId: NodeId, val simpleName: String, val packageName: String)

    /** A compiled query. [useRegex]+invalid pattern degrades to "matches nothing" (never throws). */
    @Immutable
    data class Query(val text: String, val ignoreCase: Boolean = true, val useRegex: Boolean = false) {
        val isBlank: Boolean get() = text.isBlank()

        internal fun matcher(): (String) -> Boolean {
            if (useRegex) {
                val regex = runCatching {
                    Regex(text, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
                }.getOrNull() ?: return { false }
                return { candidate -> regex.containsMatchIn(candidate) }
            }
            return { candidate -> candidate.contains(text, ignoreCase = ignoreCase) }
        }
    }

    /** Receives streamed matches and progress. Called on the scanning coroutine (the UI dispatcher). */
    interface ScanSink {
        fun onMatch(match: MemberMatch)
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
     * Scan [classes] for members whose kind is in [kinds] and whose name/signature matches [query],
     * enumerating each class's members via [membersOf] (the client's `childNodes(classId)` in
     * production — the member rows it already builds).
     *
     * [membersOf] returns the member [TreeNode]s of a class, or null if they could not be produced (the
     * class is skipped and counted failed). Streams to [sink]; returns a [ScanSummary]. Cooperatively
     * cancelable via [yield] between classes.
     */
    suspend fun scan(
        classes: List<ClassRef>,
        kinds: Set<NodeKind>,
        query: Query,
        sink: ScanSink,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        membersOf: suspend (NodeId) -> List<TreeNode>?,
    ): ScanSummary {
        val total = classes.size
        sink.onProgress(0, total)
        if (query.isBlank || maxResults <= 0 || kinds.isEmpty()) {
            return ScanSummary(scanned = 0, total = total, matches = 0, failed = 0, truncated = false)
        }
        val matches = query.matcher()
        var emitted = 0
        var failed = 0
        var truncated = false
        var scanned = 0

        loop@ for (cls in classes) {
            val members = try {
                membersOf(cls.nodeId)
            } catch (e: CancellationException) {
                // Cancellation is control flow, not a fault — must propagate (see class doc), never be
                // caught by the fault-isolation branch as a skipped class.
                throw e
            } catch (e: Exception) {
                null
            }
            scanned++
            if (members == null) {
                failed++
            } else {
                for (member in members) {
                    if (member.kind !in kinds) continue
                    val signature = member.secondary.orEmpty()
                    if (!matches(member.label) && !matches(signature)) continue
                    if (emitted >= maxResults) {
                        truncated = true
                        break@loop
                    }
                    sink.onMatch(
                        MemberMatch(
                            nodeId = member.id,
                            displayName = member.label,
                            signature = signature,
                            ownerSimpleName = cls.simpleName,
                            ownerPackage = cls.packageName,
                        ),
                    )
                    emitted++
                }
            }
            sink.onProgress(scanned, total)
            yield()
        }
        return ScanSummary(scanned = scanned, total = total, matches = emitted, failed = failed, truncated = truncated)
    }
}

/** One member-search hit: a class member whose name or signature matched. */
@Immutable
data class MemberMatch(
    val nodeId: NodeId,
    val displayName: String,
    val signature: String,
    val ownerSimpleName: String,
    val ownerPackage: String,
)

/**
 * Observable state of a member-content search, streamed into the workbench UI as the scan runs. Mirrors
 * [CodeSearchUiState]: [running] while classes are still being scanned, [truncated] when the cap was
 * hit, [failed] counts classes skipped because their members could not be enumerated.
 */
@Immutable
data class MemberSearchUiState(
    val query: String = "",
    val matches: List<MemberMatch> = emptyList(),
    val scanned: Int = 0,
    val total: Int = 0,
    val running: Boolean = false,
    val truncated: Boolean = false,
    val failed: Int = 0,
) {
    val hasResults: Boolean get() = matches.isNotEmpty()
    val fraction: Float get() = if (total == 0) 0f else scanned.toFloat() / total
}
