package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.client.CodeLine
import com.jadxmp.ui.client.CodeToken
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.client.DecompilerClient
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.NodeKind
import com.jadxmp.ui.client.OpenRequest
import com.jadxmp.ui.client.SearchQuery
import com.jadxmp.ui.client.SearchResult
import com.jadxmp.ui.client.SearchResults
import com.jadxmp.ui.client.SearchScope
import com.jadxmp.ui.client.SessionState
import com.jadxmp.ui.client.TokenKind
import com.jadxmp.ui.client.TreeKind
import com.jadxmp.ui.client.TreeNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Async-exception-hygiene guards (rule 4 — one throwing class must never crash the workbench). These cover
 * the three fixes in this batch:
 *  1. a throwing `code()` (via openDocument → ensureDocument) must NOT take down the workbench scope;
 *  2. the class-name search must be epoch-guarded, so a search superseded by an openProject can't write
 *     its stale results into the fresh project; and
 *  3. the scope-level [workbenchBackstopScope] must keep one launch's uncaught throw from cancelling a
 *     sibling launch (SupervisorJob + CoroutineExceptionHandler backstop).
 *
 * Driven with fake [DecompilerClient]s on a [runTest] scheduler — no Compose — mirroring
 * [WorkbenchStateTest]'s `aSupersededScan…` style.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchAsyncHygieneTest {

    // ── Finding 1: a throwing decompile must not kill the workbench scope ──────────────────────────

    /** A client whose [code] throws for [throwFor] and returns a trivial `"ok"` document for anything else. */
    private class ThrowingCodeClient(private val throwFor: NodeId) : DecompilerClient {
        private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
        override val session: StateFlow<SessionState> = _session.asStateFlow()

        override suspend fun open(request: OpenRequest) {
            _session.value = SessionState.Ready(request.name, classCount = 0)
        }

        override suspend fun rootNodes(tree: TreeKind): List<TreeNode> = emptyList()
        override suspend fun childNodes(parent: NodeId): List<TreeNode> = emptyList()
        override suspend fun classNodes(): List<TreeNode> = emptyList()
        override fun availableViews(node: NodeId): List<CodeView> = listOf(CodeView.JAVA)

        override suspend fun code(node: NodeId, view: CodeView): CodeDocument {
            if (node == throwFor) error("boom in code()")
            return CodeDocument(node, node.value, view, listOf(CodeLine(1, listOf(CodeToken("ok", TokenKind.PLAIN)))))
        }

        override suspend fun search(query: SearchQuery): SearchResults = SearchResults(query, emptyList())
    }

    /**
     * The load-bearing Finding-1 test. Opening a class whose `code()` throws must not cancel the workbench
     * scope: without the ensureDocument guard, the uncaught throw cancels the (plain-Job) scope and every
     * later launch dies — so the follow-up `openDocument` below would never load. With the guard the throw
     * is absorbed (honest status, no stuck spinner) and the scope survives.
     */
    @Test
    fun aThrowingDecompileDoesNotKillTheWorkbenchScope() = runTest {
        val boom = NodeId("cls:Boom")
        val ok = NodeId("cls:Ok")
        val state = WorkbenchState(ThrowingCodeClient(throwFor = boom), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // Open the class whose decompile throws — ensureDocument must catch it.
        state.openDocument(boom)
        advanceUntilIdle()
        assertFalse(state.ui.value.busy, "the spinner is cleared after a failed load — no stuck busy")
        assertEquals("Could not open Boom", state.ui.value.status, "an honest status is shown, not a crash")
        assertFalse(DocKey(boom, CodeView.JAVA) in state.ui.value.documents, "no document cached for the failed load")

        // The scope SURVIVED, so a subsequent open still runs (this is the crash-isolation assertion).
        state.openDocument(ok)
        advanceUntilIdle()
        assertEquals("ok", state.ui.value.activeDocument?.plainText(), "scope survived; a later load still runs")
    }

    // ── Finding 2: the class-name search must be epoch-guarded ─────────────────────────────────────

    /**
     * A client whose first [search] parks (uncancelably) until [searchGate] is released, then returns one
     * match. [searchStarted] fires once the search is parked. `open`/`rootNodes` resolve immediately so an
     * openProject can supersede the parked search.
     */
    private class GatedSearchClient : DecompilerClient {
        private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
        override val session: StateFlow<SessionState> = _session.asStateFlow()
        val searchStarted = CompletableDeferred<Unit>()
        val searchGate = CompletableDeferred<Unit>()
        private var gateConsumed = false

        override suspend fun open(request: OpenRequest) {
            _session.value = SessionState.Ready(request.name, classCount = 0)
        }

        override suspend fun rootNodes(tree: TreeKind): List<TreeNode> = emptyList()
        override suspend fun childNodes(parent: NodeId): List<TreeNode> = emptyList()
        override suspend fun classNodes(): List<TreeNode> = emptyList()
        override fun availableViews(node: NodeId): List<CodeView> = listOf(CodeView.JAVA)
        override suspend fun code(node: NodeId, view: CodeView): CodeDocument =
            CodeDocument(node, node.value, view, emptyList())

        override suspend fun search(query: SearchQuery): SearchResults {
            if (!gateConsumed) {
                gateConsumed = true
                searchStarted.complete(Unit)
                // Uncancelable park: the search "completes" late even though openProject moved on — exactly
                // the window the epoch guard must cover.
                withContext(NonCancellable) { searchGate.await() }
            }
            // A single unmistakable match, so any stale write would be visible.
            return SearchResults(query, listOf(SearchResult(NodeId("cls:Stale"), "Stale", "pkg", NodeKind.CLASS)))
        }
    }

    /**
     * A class-name search superseded by an openProject must not land its results in the new project. The
     * search parks; an openProject bumps the epoch and installs a fresh (empty) state; releasing the old
     * search then reaches its write, which the epoch guard must drop. Fails without the guard (the stale
     * "Stale" match and "1 matches" status overwrite the fresh project).
     */
    @Test
    fun aSupersededNameSearchDoesNotWriteStaleResultsIntoTheNewProject() = runTest {
        val client = GatedSearchClient()
        val state = WorkbenchState(client, CoroutineScope(StandardTestDispatcher(testScheduler)))

        state.runSearch(SearchQuery("foo", setOf(SearchScope.CLASS)))
        client.searchStarted.await() // the search is now parked inside client.search()

        // Supersede it: openProject bumps openEpoch and resets the UI state (searchResults = null).
        state.openProject(OpenRequest("new.apk"))
        advanceUntilIdle()
        assertEquals("Ready", state.ui.value.status, "the fresh project is in place before the old search resumes")

        // Release the OLD search; its late write must be dropped by the epoch guard.
        client.searchGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(null, state.ui.value.searchResults, "a superseded search must not write into the new project")
        assertEquals("Ready", state.ui.value.status, "the new project's status is untouched by the stale search")
    }

    // ── Finding 1/4: the scope-level backstop isolates a launch's uncaught throw ────────────────────

    /**
     * The [workbenchBackstopScope]'s SupervisorJob + CoroutineExceptionHandler must keep one launch's
     * uncaught throw from cancelling a sibling launch or the scope itself. Under a plain `Job` scope the
     * throw would cancel the parent and every sibling; the backstop absorbs it. This backstops any future
     * untracked launch (Finding 4) as well as the decompile path (Finding 1).
     */
    @Test
    fun anUncaughtThrowInOneLaunchDoesNotCancelASibling() = runTest {
        val scope = workbenchBackstopScope(UnconfinedTestDispatcher(testScheduler))
        val siblingRan = CompletableDeferred<Unit>()

        scope.launch { error("boom") } // uncaught — the SupervisorJob + handler must absorb it
        scope.launch { siblingRan.complete(Unit) } // sibling — must still run
        advanceUntilIdle()

        assertTrue(siblingRan.isCompleted, "a sibling launch survives another launch's uncaught throw")
        assertTrue(scope.isActive, "the workbench scope itself is not cancelled by a child's uncaught throw")

        scope.cancel() // tidy: don't leak the independent scope
    }
}
