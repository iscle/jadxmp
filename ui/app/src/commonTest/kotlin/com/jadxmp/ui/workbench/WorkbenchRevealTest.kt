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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * View-model tests for the tree wave: [WorkbenchState.revealInTree] (expand ancestors + select + arm the
 * scroll nonce) and [WorkbenchState.expandSubtree]/[WorkbenchState.collapseSubtree]. Driven with a fake
 * lazily-loaded tree on a [runTest] scheduler — no Compose. The scroll itself is a composable concern
 * (TreePane keys a [androidx.compose.foundation.lazy.LazyListState] off the nonce) and isn't tested here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchRevealTest {

    // com › example › app › MainActivity › onCreate — every hop loaded lazily on demand.
    private val com = TreeNode(NodeId("pkg:com"), "com", NodeKind.PACKAGE, hasChildren = true)
    private val example = TreeNode(NodeId("pkg:com.example"), "example", NodeKind.PACKAGE, hasChildren = true)
    private val app = TreeNode(NodeId("pkg:com.example.app"), "app", NodeKind.PACKAGE, hasChildren = true)
    private val main = TreeNode(NodeId("cls:com.example.app.MainActivity"), "MainActivity", NodeKind.CLASS, hasChildren = true)
    private val onCreate = TreeNode(
        NodeId("mbr:com.example.app.MainActivity#com.example.app.MainActivity#M:onCreate()"),
        "onCreate",
        NodeKind.METHOD,
        hasChildren = false,
    )

    private fun fake() = FakeClient(
        roots = listOf(com),
        children = mapOf(
            com.id to listOf(example),
            example.id to listOf(app),
            app.id to listOf(main),
            main.id to listOf(onCreate),
        ),
    )

    @Test
    fun revealExpandsEveryAncestorSelectsAndArmsTheScrollNonce() = runTest {
        val state = WorkbenchState(fake(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openProject(OpenRequest("x"))
        advanceUntilIdle()
        val before = state.ui.value.revealNonce

        state.revealInTree(main.id)
        advanceUntilIdle()

        val ui = state.ui.value
        assertTrue(
            com.id in ui.tree.expanded && example.id in ui.tree.expanded && app.id in ui.tree.expanded,
            "every ancestor package is expanded",
        )
        assertEquals(main.id, ui.tree.selected, "the target is selected")
        assertEquals(main.id, ui.revealTarget)
        assertTrue(ui.revealNonce > before, "the scroll nonce is bumped exactly for the reveal")
        // The chain is loaded, so the target is now a visible flattened row (what the scroll will find).
        val rows = buildVisibleRows(ui.roots[TreeKind.CLASSES].orEmpty(), { ui.children(it) }, ui.tree.expanded)
        assertTrue(rows.any { it.node.id == main.id }, "target is a visible row after the reveal")
    }

    @Test
    fun revealSwitchesToTheResourcesTreeForAResourceTarget() = runTest {
        val state = WorkbenchState(fake(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openProject(OpenRequest("x"))
        advanceUntilIdle()

        state.revealInTree(NodeId("res:res/layout/activity_main.xml"))
        advanceUntilIdle()

        assertEquals(TreeKind.RESOURCES, state.ui.value.tree.kind)
        assertEquals(NodeId("res:res/layout/activity_main.xml"), state.ui.value.tree.selected)
    }

    @Test
    fun revealTabInTreeRevealsTheTabsNode() = runTest {
        val state = WorkbenchState(fake(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openProject(OpenRequest("x"))
        advanceUntilIdle()
        state.openDocument(main.id, "MainActivity", kind = NodeKind.CLASS)
        advanceUntilIdle()

        state.revealTabInTree(state.ui.value.tabs.activeIndex)
        advanceUntilIdle()

        assertEquals(main.id, state.ui.value.tree.selected)
        assertTrue(app.id in state.ui.value.tree.expanded)
    }

    @Test
    fun expandSubtreeLoadsAndExpandsEveryExpandableDescendant() = runTest {
        val state = WorkbenchState(fake(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openProject(OpenRequest("x"))
        advanceUntilIdle()

        state.expandSubtree(com)
        advanceUntilIdle()

        val expanded = state.ui.value.tree.expanded
        assertTrue(
            com.id in expanded && example.id in expanded && app.id in expanded && main.id in expanded,
            "the whole subtree (packages + class with members) is expanded",
        )
    }

    @Test
    fun collapseSubtreeClearsTheWholeExpandedChain() = runTest {
        val state = WorkbenchState(fake(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openProject(OpenRequest("x"))
        advanceUntilIdle()
        state.expandSubtree(com)
        advanceUntilIdle()
        assertTrue(app.id in state.ui.value.tree.expanded)

        state.collapseSubtree(com)
        advanceUntilIdle()

        val expanded = state.ui.value.tree.expanded
        assertTrue(
            com.id !in expanded && example.id !in expanded && app.id !in expanded && main.id !in expanded,
            "collapsing the subtree drops the root and every loaded descendant",
        )
    }

    /** Minimal fake serving a fixed lazily-loaded tree; everything else is inert. */
    private class FakeClient(
        private val roots: List<TreeNode>,
        private val children: Map<NodeId, List<TreeNode>>,
    ) : DecompilerClient {
        private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
        override val session: StateFlow<SessionState> = _session.asStateFlow()

        override suspend fun open(request: OpenRequest) {
            _session.value = SessionState.Ready(request.name, roots.size)
        }

        override suspend fun rootNodes(tree: TreeKind): List<TreeNode> =
            if (tree == TreeKind.CLASSES) roots else emptyList()

        override suspend fun childNodes(parent: NodeId): List<TreeNode> = children[parent].orEmpty()

        override suspend fun classNodes(): List<TreeNode> = emptyList()

        override fun availableViews(node: NodeId): List<CodeView> = listOf(CodeView.JAVA)

        override suspend fun code(node: NodeId, view: CodeView): CodeDocument =
            CodeDocument(node, node.value, view, listOf(CodeLine(1, listOf(CodeToken("x", TokenKind.PLAIN)))))

        override suspend fun search(query: SearchQuery): SearchResults = SearchResults(query, emptyList())
    }
}
