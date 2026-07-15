package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.client.CodeLine
import com.jadxmp.ui.client.CodeToken
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.client.DecompilerClient
import com.jadxmp.ui.client.NodeId
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * View-model tests for the in-editor Go-to-line bar: it only opens over a loaded document, a committed
 * line jumps by pinning the caret + bumping the scroll nonce (reusing the go-to-definition path), an
 * out-of-range entry clamps to the last line, and an invalid entry never jumps (rule 4) and keeps the bar
 * open. Mirrors [WorkbenchFindTest]'s fake-client harness.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchGoToLineTest {

    private val a = NodeId("cls:A")

    private class Fake(private val sources: Map<NodeId, String>) : DecompilerClient {
        private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
        override val session: StateFlow<SessionState> = _session.asStateFlow()
        override suspend fun open(request: OpenRequest) {
            _session.value = SessionState.Ready(request.name, sources.size)
        }
        override suspend fun rootNodes(tree: TreeKind): List<TreeNode> = emptyList()
        override suspend fun childNodes(parent: NodeId): List<TreeNode> = emptyList()
        override suspend fun classNodes(): List<TreeNode> = emptyList()
        override fun availableViews(node: NodeId): List<CodeView> = listOf(CodeView.JAVA)
        override suspend fun code(node: NodeId, view: CodeView): CodeDocument {
            val src = sources[node] ?: "x"
            val lines = src.split("\n").mapIndexed { i, l -> CodeLine(i + 1, listOf(CodeToken(l, TokenKind.PLAIN))) }
            return CodeDocument(node, node.value, view, lines)
        }
        override suspend fun search(query: SearchQuery): SearchResults = SearchResults(query, emptyList())
    }

    @Test
    fun openRequiresAnActiveDocument() = runTest {
        val state = WorkbenchState(Fake(emptyMap()), scope())
        state.openGoToLine()
        assertNull(state.ui.value.goToLine, "with no document open the Go-to-line bar does not open")
    }

    @Test
    fun commitJumpsToLineAndCloses() = runTest {
        val state = WorkbenchState(Fake(mapOf(a to "1\n2\n3\n4\n5")), scope())
        state.openDocument(a)
        advanceUntilIdle()
        state.openGoToLine()
        assertTrue(state.ui.value.goToLine != null, "opens over a loaded document")

        val nonce0 = state.ui.value.codeNavNonce
        state.setGoToLineQuery("3")
        state.applyGoToLine()

        assertEquals(3, state.ui.value.tabs.active!!.caret, "the target line becomes the caret line")
        assertTrue(state.ui.value.codeNavNonce > nonce0, "committing re-arms the viewer scroll (nonce bump)")
        assertNull(state.ui.value.goToLine, "a successful jump closes the bar")
    }

    @Test
    fun outOfRangeClampsToLastLine() = runTest {
        val state = WorkbenchState(Fake(mapOf(a to "1\n2\n3")), scope())
        state.openDocument(a)
        advanceUntilIdle()
        state.openGoToLine()
        state.setGoToLineQuery("9999")
        state.applyGoToLine()
        assertEquals(3, state.ui.value.tabs.active!!.caret, "an over-range line clamps to the last line")
    }

    @Test
    fun invalidEntryDoesNotJumpAndKeepsBarOpen() = runTest {
        val state = WorkbenchState(Fake(mapOf(a to "1\n2\n3")), scope())
        state.openDocument(a)
        advanceUntilIdle()
        state.openGoToLine()
        val nonce0 = state.ui.value.codeNavNonce
        state.setGoToLineQuery("abc")
        state.applyGoToLine()

        assertEquals(nonce0, state.ui.value.codeNavNonce, "a non-numeric entry never scrolls (no crash, no jump)")
        assertTrue(state.ui.value.goToLine != null, "an invalid entry leaves the bar open to correct")
        assertTrue(state.ui.value.goToLine!!.invalid, "the invalid entry flags the red field")
    }

    @Test
    fun toggleOpensThenCloses() = runTest {
        val state = WorkbenchState(Fake(mapOf(a to "1\n2")), scope())
        state.openDocument(a)
        advanceUntilIdle()
        state.toggleGoToLine()
        assertTrue(state.ui.value.goToLine != null)
        state.toggleGoToLine()
        assertNull(state.ui.value.goToLine)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun kotlinx.coroutines.test.TestScope.scope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler))
}
