package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.client.CodeLine
import com.jadxmp.ui.client.CodeToken
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.client.DecompilerClient
import com.jadxmp.ui.client.MemberLocation
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * View-model tests for the Methods/Fields streaming scan and member navigation. Driven with fake
 * [DecompilerClient]s on a [runTest] scheduler — no Compose. Mirrors the code-scope supersession test so
 * the cancellation/generation-guard race is covered here too (a swallowed CancellationException would
 * fail [aSupersededMemberScanCannotResurrectStaleMatches]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchMemberSearchTest {

    private fun method(owner: String, name: String, sig: String) =
        TreeNode(NodeId("mbr:$owner#$owner#M:$sig"), name, NodeKind.METHOD, hasChildren = false, secondary = sig)

    /** A fake engine serving a fixed class list, per-class members, and member locations. */
    private class FakeClient(
        private val classList: List<TreeNode> = emptyList(),
        private val members: Map<NodeId, List<TreeNode>> = emptyMap(),
        private val locations: Map<NodeId, MemberLocation> = emptyMap(),
    ) : DecompilerClient {
        private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
        override val session: StateFlow<SessionState> = _session.asStateFlow()

        override suspend fun open(request: OpenRequest) {
            _session.value = SessionState.Ready(request.name, classList.size)
        }
        override suspend fun rootNodes(tree: TreeKind): List<TreeNode> = emptyList()
        override suspend fun childNodes(parent: NodeId): List<TreeNode> = members[parent].orEmpty()
        override suspend fun classNodes(): List<TreeNode> = classList
        override fun availableViews(node: NodeId): List<CodeView> = listOf(CodeView.JAVA)
        override suspend fun code(node: NodeId, view: CodeView): CodeDocument =
            CodeDocument(node, node.value, view, listOf(CodeLine(1, listOf(CodeToken(node.value, TokenKind.PLAIN)))))
        override suspend fun search(query: SearchQuery): SearchResults = SearchResults(query, emptyList())
        override suspend fun memberLocation(memberNodeId: NodeId): MemberLocation? = locations[memberNodeId]
    }

    private fun memberClient(): FakeClient {
        val a = TreeNode(NodeId("cls:A"), "A", NodeKind.CLASS, hasChildren = true, secondary = "pkg")
        val b = TreeNode(NodeId("cls:B"), "B", NodeKind.CLASS, hasChildren = true, secondary = "pkg")
        return FakeClient(
            classList = listOf(a, b),
            members = mapOf(
                a.id to listOf(method("A", "run", "run(): void"), method("A", "stop", "stop(): void")),
                b.id to listOf(method("B", "run", "run(): int")),
            ),
        )
    }

    @Test
    fun memberSearchStreamsMatchesAndFinishes() = runTest {
        val state = WorkbenchState(memberClient(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.runMemberSearch("run", setOf(NodeKind.METHOD))
        advanceUntilIdle()

        val ms = state.ui.value.memberSearch!!
        assertFalse(ms.running, "scan must finish")
        assertEquals(2, ms.total)
        assertEquals(listOf("A", "B"), ms.matches.map { it.ownerSimpleName }, "run() in A and B")
        assertFalse(ms.truncated)
    }

    @Test
    fun memberSearchSupersedesThePreviousQuery() = runTest {
        val state = WorkbenchState(memberClient(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.runMemberSearch("run", setOf(NodeKind.METHOD))
        state.runMemberSearch("stop", setOf(NodeKind.METHOD))
        advanceUntilIdle()

        val ms = state.ui.value.memberSearch!!
        assertEquals("stop", ms.query, "only the latest scan's state survives")
        assertEquals(listOf("stop"), ms.matches.map { it.displayName })
    }

    @Test
    fun runningAMemberSearchClearsCodeAndNameResults() = runTest {
        val state = WorkbenchState(memberClient(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.runCodeSearch("run")
        advanceUntilIdle()
        assertTrue(state.ui.value.codeSearch != null)

        state.runMemberSearch("run", setOf(NodeKind.METHOD))
        advanceUntilIdle()
        assertNull(state.ui.value.codeSearch, "switching to a member search drops the code scan state")
        assertTrue(state.ui.value.memberSearch != null)
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    @Test
    fun openMemberOpensOwningClassAndScrollsToResolvedLine() = runTest {
        val memberId = NodeId("mbr:A#A#M:run(): void")
        val client = FakeClient(locations = mapOf(memberId to MemberLocation(NodeId("cls:A"), line = 7)))
        val state = WorkbenchState(client, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val nonceBefore = state.ui.value.codeNavNonce

        state.openMember(memberId)
        advanceUntilIdle()

        val active = state.ui.value.tabs.active!!
        assertEquals(NodeId("cls:A"), active.nodeId, "the owning class tab is opened")
        assertEquals(7, active.caret, "caret placed on the resolved definition line")
        assertTrue(state.ui.value.codeNavNonce > nonceBefore, "the viewer is re-armed to scroll")
    }

    @Test
    fun openMemberWithoutAResolvedLineOpensTheClassWithoutScrolling() = runTest {
        val memberId = NodeId("mbr:A#A#S:static { … }")
        // A static initializer resolves to its class but has no definition line yet.
        val client = FakeClient(locations = mapOf(memberId to MemberLocation(NodeId("cls:A"), line = null)))
        val state = WorkbenchState(client, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val nonceBefore = state.ui.value.codeNavNonce

        state.openMember(memberId)
        advanceUntilIdle()

        assertEquals(NodeId("cls:A"), state.ui.value.tabs.active?.nodeId, "class still opens")
        assertEquals(0, state.ui.value.tabs.active?.caret, "caret untouched (no scroll)")
        assertEquals(nonceBefore, state.ui.value.codeNavNonce, "no scroll re-arm for an unresolved member")
    }

    @Test
    fun openMemberOnAnUnknownIdDoesNothing() = runTest {
        val client = FakeClient()
        val state = WorkbenchState(client, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openMember(NodeId("mbr:X#X#M:gone()"))
        advanceUntilIdle()
        assertTrue(state.ui.value.tabs.isEmpty, "an unrecognized member id opens nothing, never crashes")
    }

    // ── Cancellation / supersession race ────────────────────────────────────────

    /**
     * The MUST-FIX regression, mirrored for member search: a superseded scan parked inside an
     * uncancelable member enumeration must not resurrect its stale matches over the new query. Fails if
     * [MemberSearch] swallowed the CancellationException or the generation guard were absent.
     */
    @Test
    fun aSupersededMemberScanCannotResurrectStaleMatches() = runTest {
        val a = TreeNode(NodeId("cls:A"), "A", NodeKind.CLASS, hasChildren = true, secondary = "pkg")
        val b = TreeNode(NodeId("cls:B"), "B", NodeKind.CLASS, hasChildren = true, secondary = "pkg")
        val client = GatedMemberClient(
            classesList = listOf(a, b),
            members = mapOf(
                a.id to listOf(method("A", "stale", "stale(): void")),
                b.id to listOf(method("B", "clean", "clean(): void")),
            ),
            gatedNode = a.id,
        )
        val state = WorkbenchState(client, CoroutineScope(StandardTestDispatcher(testScheduler)))

        // Query 1 matches only A ("stale"), whose enumeration parks uncancelably at the gate.
        state.runMemberSearch("stale", setOf(NodeKind.METHOD))
        client.gatedStarted.await()

        // Supersede with a query that matches NOTHING; this cancels job 1 (still parked in A).
        state.runMemberSearch("absent", setOf(NodeKind.METHOD))
        advanceUntilIdle()

        val afterFresh = state.ui.value.memberSearch!!
        assertEquals("absent", afterFresh.query)
        assertTrue(afterFresh.matches.isEmpty())

        // Release the OLD enumeration. Its scan resumes, finds "stale" in A, reaches its callbacks —
        // which the generation guard must reject so the current (empty) results are untouched.
        client.gate.complete(Unit)
        advanceUntilIdle()

        val finalState = state.ui.value.memberSearch!!
        assertEquals("absent", finalState.query, "the superseded scan must not overwrite the query")
        assertTrue(finalState.matches.isEmpty(), "no stale 'stale' match may be resurrected")
        assertFalse(finalState.running)
    }

    /** A client whose [childNodes] parks the first enumeration of [gatedNode] until [gate] completes. */
    private class GatedMemberClient(
        private val classesList: List<TreeNode>,
        private val members: Map<NodeId, List<TreeNode>>,
        private val gatedNode: NodeId,
    ) : DecompilerClient {
        private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
        override val session: StateFlow<SessionState> = _session.asStateFlow()
        val gate = CompletableDeferred<Unit>()
        val gatedStarted = CompletableDeferred<Unit>()
        private var gateConsumed = false

        override suspend fun open(request: OpenRequest) { _session.value = SessionState.Ready(request.name, classesList.size) }
        override suspend fun rootNodes(tree: TreeKind): List<TreeNode> = emptyList()
        override suspend fun classNodes(): List<TreeNode> = classesList
        override fun availableViews(node: NodeId): List<CodeView> = listOf(CodeView.JAVA)
        override suspend fun code(node: NodeId, view: CodeView): CodeDocument =
            CodeDocument(node, node.value, view, emptyList())
        override suspend fun search(query: SearchQuery): SearchResults = SearchResults(query, emptyList())

        override suspend fun childNodes(parent: NodeId): List<TreeNode> {
            if (parent == gatedNode && !gateConsumed) {
                gateConsumed = true
                gatedStarted.complete(Unit)
                withContext(NonCancellable) { gate.await() }
            }
            return members[parent].orEmpty()
        }
    }
}
