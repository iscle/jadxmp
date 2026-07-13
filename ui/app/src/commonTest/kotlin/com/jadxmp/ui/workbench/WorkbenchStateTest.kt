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
import com.jadxmp.ui.client.SearchResults
import com.jadxmp.ui.client.SessionState
import com.jadxmp.ui.client.TokenKind
import com.jadxmp.ui.client.TreeKind
import com.jadxmp.ui.client.TreeNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * View-model tests for the async glue that two adversarial reviews flagged: tab activation must not
 * discard forward history (M4), and setting a filter must eagerly load the subtree so deep matches are
 * reachable (M6). Driven with a fake [DecompilerClient] on a [runTest] scheduler — no Compose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchStateTest {

    private val a = NodeId("cls:A")
    private val b = NodeId("cls:B")
    private val c = NodeId("cls:C")

    /** A fake engine with a fixed class list and a fixed nested package tree, all served immediately. */
    private class FakeClient(
        private val roots: List<TreeNode> = emptyList(),
        private val children: Map<NodeId, List<TreeNode>> = emptyMap(),
        private val classList: List<TreeNode> = emptyList(),
        /** Plain decompiled source per class node, for code-content search tests. */
        private val sources: Map<NodeId, String> = emptyMap(),
    ) : DecompilerClient {
        private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
        override val session: StateFlow<SessionState> = _session.asStateFlow()
        val childRequests = mutableListOf<NodeId>()

        override suspend fun open(request: OpenRequest) {
            _session.value = SessionState.Ready(request.name, classCount = roots.size)
        }

        override suspend fun rootNodes(tree: TreeKind): List<TreeNode> =
            if (tree == TreeKind.CLASSES) roots else emptyList()

        override suspend fun childNodes(parent: NodeId): List<TreeNode> {
            childRequests += parent
            return children[parent].orEmpty()
        }

        override suspend fun classNodes(): List<TreeNode> = classList

        override fun availableViews(node: NodeId): List<CodeView> = listOf(CodeView.JAVA)

        override suspend fun code(node: NodeId, view: CodeView): CodeDocument {
            val src = sources[node] ?: "x"
            val lines = src.split("\n").mapIndexed { i, line ->
                CodeLine(i + 1, listOf(CodeToken(line, TokenKind.PLAIN)))
            }
            return CodeDocument(node, node.value, view, lines)
        }

        override suspend fun search(query: SearchQuery): SearchResults = SearchResults(query, emptyList())
    }

    @Test
    fun activatingAnOpenTabDoesNotTruncateForwardHistory() = runTest {
        val state = WorkbenchState(FakeClient(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openDocument(a)
        state.openDocument(b)
        state.openDocument(c) // history [A, B, C], tabs [A, B, C]
        advanceUntilIdle()

        state.goBack()
        state.goBack() // history cursor at A, forward = [B, C]
        advanceUntilIdle()
        assertTrue(state.ui.value.history.canGoForward)

        // Re-focus the already-open tab B. Before the fix this called visit() and dropped C.
        state.activateTab(1)
        advanceUntilIdle()

        val history = state.ui.value.history
        assertEquals(listOf(a, b, c), history.entries, "forward stack must be preserved")
        assertEquals(b, history.current)
        assertTrue(history.canGoForward, "C must still be reachable via Forward")
        assertEquals(c, history.forward().current)
    }

    @Test
    fun settingAFilterEagerlyLoadsTheWholeSubtree() = runTest {
        // com › example › app › Widget — Widget lives in a package nobody expanded.
        val com = TreeNode(NodeId("pkg:com"), "com", NodeKind.PACKAGE, hasChildren = true)
        val example = TreeNode(NodeId("pkg:com.example"), "example", NodeKind.PACKAGE, hasChildren = true)
        val app = TreeNode(NodeId("pkg:com.example.app"), "app", NodeKind.PACKAGE, hasChildren = true)
        val widget = TreeNode(NodeId("cls:com.example.app.Widget"), "Widget", NodeKind.CLASS, hasChildren = false)
        val client = FakeClient(
            roots = listOf(com),
            children = mapOf(
                com.id to listOf(example),
                example.id to listOf(app),
                app.id to listOf(widget),
            ),
        )
        val state = WorkbenchState(client, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openProject(OpenRequest("x"))
        advanceUntilIdle()
        assertFalse(com.id in state.ui.value.childrenCache, "tree starts lazy — nothing loaded yet")

        state.setFilter("Widget")
        advanceUntilIdle()

        val cache = state.ui.value.childrenCache
        assertTrue(com.id in cache && example.id in cache && app.id in cache, "whole chain must be cached")
        assertTrue(widget in cache.getValue(app.id), "the deep match is now reachable by buildVisibleRows")
    }

    // ── Java/Kotlin output-format toggle ──────────────────────────────────────

    /**
     * A fake engine that offers BOTH Java and Kotlin and returns a DISTINCT source per view, so a test
     * can prove the format toggle actually re-renders (and that both renders are cached side by side).
     */
    private class FormatFake : DecompilerClient {
        private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
        override val session: StateFlow<SessionState> = _session.asStateFlow()

        override suspend fun open(request: OpenRequest) {
            _session.value = SessionState.Ready(request.name, classCount = 1)
        }

        override suspend fun rootNodes(tree: TreeKind): List<TreeNode> = emptyList()
        override suspend fun childNodes(parent: NodeId): List<TreeNode> = emptyList()
        override suspend fun classNodes(): List<TreeNode> = emptyList()

        // JAVA first → it stays the default view a class opens in.
        override fun availableViews(node: NodeId): List<CodeView> = listOf(CodeView.JAVA, CodeView.KOTLIN)

        override suspend fun code(node: NodeId, view: CodeView): CodeDocument {
            val text = when (view) {
                CodeView.KOTLIN -> "fun main()" // Kotlin-ism, no `;`
                else -> "static void main();" // Java-ism, has `;`
            }
            return CodeDocument(node, node.value, view, listOf(CodeLine(1, listOf(CodeToken(text, TokenKind.PLAIN)))))
        }

        override suspend fun search(query: SearchQuery): SearchResults = SearchResults(query, emptyList())
    }

    @Test
    fun toggleReRendersActiveTabInKotlinAndCachesBothFormats() = runTest {
        val state = WorkbenchState(FormatFake(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val node = NodeId("cls:HelloWorld")

        // Opens in the default (first available) view = Java.
        state.openDocument(node)
        advanceUntilIdle()
        assertEquals(CodeView.JAVA, state.ui.value.tabs.active?.view)
        assertEquals("static void main();", state.ui.value.activeDocument?.plainText())

        // Toggle to Kotlin: the active tab re-renders in the new format.
        state.setActiveView(CodeView.KOTLIN)
        advanceUntilIdle()
        assertEquals(CodeView.KOTLIN, state.ui.value.tabs.active?.view)
        assertEquals("fun main()", state.ui.value.activeDocument?.plainText())

        // Both renders are cached side by side, keyed by (node, view) — no clobber.
        val docs = state.ui.value.documents
        assertEquals("static void main();", docs[DocKey(node, CodeView.JAVA)]?.plainText())
        assertEquals("fun main()", docs[DocKey(node, CodeView.KOTLIN)]?.plainText())

        // Toggling back is instant from cache and shows the Java render again.
        state.setActiveView(CodeView.JAVA)
        advanceUntilIdle()
        assertEquals("static void main();", state.ui.value.activeDocument?.plainText())
    }

    // ── Code-content search ───────────────────────────────────────────────────

    private fun codeSearchClient(): FakeClient {
        val a = TreeNode(NodeId("cls:A"), "A", NodeKind.CLASS, hasChildren = false)
        val b = TreeNode(NodeId("cls:B"), "B", NodeKind.CLASS, hasChildren = false)
        return FakeClient(
            classList = listOf(a, b),
            sources = mapOf(
                a.id to "class A {\n  void run() { token(); }\n}",
                b.id to "class B {\n  // nothing here\n}",
            ),
        )
    }

    @Test
    fun codeSearchStreamsLineMatchesAndFinishes() = runTest {
        val state = WorkbenchState(codeSearchClient(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.runCodeSearch("token")
        advanceUntilIdle()

        val cs = state.ui.value.codeSearch!!
        assertFalse(cs.running, "scan must finish")
        assertEquals(2, cs.total)
        assertEquals(1, cs.matches.size)
        val match = cs.matches.single()
        assertEquals(NodeId("cls:A"), match.nodeId)
        assertEquals(2, match.line, "1-based line of the hit inside A")
        assertEquals("void run() { token(); }", match.snippet)
        assertFalse(cs.truncated)
    }

    @Test
    fun codeSearchSupersedesThePreviousQuery() = runTest {
        val state = WorkbenchState(codeSearchClient(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.runCodeSearch("token")
        state.runCodeSearch("nothing")
        advanceUntilIdle()

        val cs = state.ui.value.codeSearch!!
        assertEquals("nothing", cs.query, "only the latest scan's state survives")
        assertEquals(1, cs.matches.size)
        assertEquals(NodeId("cls:B"), cs.matches.single().nodeId)
    }

    @Test
    fun blankCodeQueryClearsResults() = runTest {
        val state = WorkbenchState(codeSearchClient(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.runCodeSearch("token")
        advanceUntilIdle()
        assertTrue(state.ui.value.codeSearch != null)

        state.runCodeSearch("   ")
        advanceUntilIdle()
        assertEquals(null, state.ui.value.codeSearch, "a blank query clears the scan state")
    }

    @Test
    fun openingACodeMatchPlacesTheCaretOnItsLine() = runTest {
        val state = WorkbenchState(codeSearchClient(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val match = CodeMatch(NodeId("cls:A"), "A", "", line = 2, snippet = "void run() { token(); }")
        state.openCodeMatch(match)
        advanceUntilIdle()

        val active = state.ui.value.tabs.active!!
        assertEquals(NodeId("cls:A"), active.nodeId)
        assertEquals(2, active.caret, "caret restored to the matching line so the viewer scrolls there")
    }

    @Test
    fun eachCodeMatchOpenBumpsTheNavNonceEvenForTheSameOpenTab() = runTest {
        val state = WorkbenchState(codeSearchClient(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val match = CodeMatch(NodeId("cls:A"), "A", "", line = 2, snippet = "void run() { token(); }")
        state.openCodeMatch(match)
        advanceUntilIdle()
        val firstNonce = state.ui.value.codeNavNonce

        // Re-opening the already-open tab must still bump the nonce so the viewer re-scrolls/highlights.
        state.openCodeMatch(match.copy(line = 3))
        advanceUntilIdle()
        assertTrue(state.ui.value.codeNavNonce > firstNonce, "a fresh jump into an open tab must re-arm scroll")
        assertEquals(3, state.ui.value.tabs.active?.caret)
    }

    /**
     * The MUST-FIX regression: a superseded scan parked *inside an uncancelable decompile* must not
     * resurrect its stale matches over the new query. [GatedClient.code] parks the first decompile in a
     * [NonCancellable] block (modelling the CPU-bound decompile that ignores cancellation), so when the
     * old scan is superseded and later resolves, it reaches its result-reporting callbacks. The
     * generation guard must drop that late write. Fails against the pre-fix code (stale hit appears).
     */
    @Test
    fun aSupersededScanCannotResurrectStaleMatchesFromAParkedDecompile() = runTest {
        val a = TreeNode(NodeId("cls:A"), "A", NodeKind.CLASS, hasChildren = false)
        val b = TreeNode(NodeId("cls:B"), "B", NodeKind.CLASS, hasChildren = false)
        val client = GatedClient(
            classesList = listOf(a, b),
            // Only "stale" occurs (in A); the superseding query "absent" matches nothing, so any
            // stale write is unmistakable — the current result set must stay empty.
            sources = mapOf(a.id to "stale marker here", b.id to "clean line here"),
            gatedNode = a.id,
        )
        val state = WorkbenchState(client, CoroutineScope(StandardTestDispatcher(testScheduler)))

        // Query 1 matches only A, whose decompile parks (uncancelably) at the gate.
        state.runCodeSearch("stale")
        client.gatedStarted.await() // resumes once the scan is parked mid-decompile of A

        // Supersede with a query that matches NOTHING; this cancels job 1 (still parked in A).
        state.runCodeSearch("absent")
        advanceUntilIdle() // job 2 scans A (returns immediately) + B, then completes with 0 matches

        val afterFresh = state.ui.value.codeSearch!!
        assertEquals("absent", afterFresh.query)
        assertTrue(afterFresh.matches.isEmpty())

        // Release the OLD decompile. Its scan resumes, finds "stale" in A, and reaches its callbacks —
        // which the generation guard must reject so the current (empty) results are untouched.
        client.gate.complete(Unit)
        advanceUntilIdle()

        val finalState = state.ui.value.codeSearch!!
        assertEquals("absent", finalState.query, "the superseded scan must not overwrite the query")
        assertTrue(finalState.matches.isEmpty(), "no stale 'stale' match may be resurrected")
        assertFalse(finalState.running)
    }

    @Test
    fun runningANameSearchClearsAnyCodeResults() = runTest {
        val state = WorkbenchState(codeSearchClient(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.runCodeSearch("token")
        advanceUntilIdle()
        assertTrue(state.ui.value.codeSearch != null)

        state.runSearch(SearchQuery("A", setOf(com.jadxmp.ui.client.SearchScope.CLASS)))
        advanceUntilIdle()
        assertEquals(null, state.ui.value.codeSearch, "switching to a name search drops the code scan state")
    }

    /**
     * A client whose [code] parks the first decompile of [gatedNode] inside a [NonCancellable] section
     * until [gate] completes — modelling a CPU-bound decompile that finishes (and returns) even after
     * its coroutine was cancelled. [gatedStarted] fires when that decompile is entered.
     */
    private class GatedClient(
        private val classesList: List<TreeNode>,
        private val sources: Map<NodeId, String>,
        private val gatedNode: NodeId,
    ) : DecompilerClient {
        private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
        override val session: StateFlow<SessionState> = _session.asStateFlow()
        val gate = CompletableDeferred<Unit>()
        val gatedStarted = CompletableDeferred<Unit>()
        private var gateConsumed = false

        override suspend fun open(request: OpenRequest) {
            _session.value = SessionState.Ready(request.name, classesList.size)
        }

        override suspend fun rootNodes(tree: TreeKind): List<TreeNode> = emptyList()

        override suspend fun childNodes(parent: NodeId): List<TreeNode> = emptyList()

        override suspend fun classNodes(): List<TreeNode> = classesList

        override fun availableViews(node: NodeId): List<CodeView> = listOf(CodeView.JAVA)

        override suspend fun code(node: NodeId, view: CodeView): CodeDocument {
            if (node == gatedNode && !gateConsumed) {
                gateConsumed = true
                gatedStarted.complete(Unit)
                // Uncancelable wait: even a cancelled job stays here until the gate is released, so the
                // decompile "completes" late — exactly the window the generation guard must cover.
                withContext(NonCancellable) { gate.await() }
            }
            val src = sources[node] ?: ""
            val lines = src.split("\n").mapIndexed { i, line ->
                CodeLine(i + 1, listOf(CodeToken(line, TokenKind.PLAIN)))
            }
            return CodeDocument(node, node.value, view, lines)
        }

        override suspend fun search(query: SearchQuery): SearchResults = SearchResults(query, emptyList())
    }
}
