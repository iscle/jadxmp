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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * View-model tests for the in-editor Find bar: open/seed, incremental matching over the active document,
 * next/prev wrap-around driving the scroll nonce, the no-match state, and re-sync on a tab switch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchFindTest {

    private val a = NodeId("cls:A")
    private val b = NodeId("cls:B")

    private class FindFake(private val sources: Map<NodeId, String>) : DecompilerClient {
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
    fun openSeedsFromSelectionAndFindsMatches() = runTest {
        val state = WorkbenchState(FindFake(mapOf(a to "foo\nbar foo\nbaz")), scope())
        state.openDocument(a)
        advanceUntilIdle()

        state.noteSelectionSeed("foo")
        state.openFind()
        advanceUntilIdle()

        val find = state.ui.value.find!!
        assertEquals("foo", find.query, "an empty Find bar seeds from the last clicked token")
        assertEquals(2, find.count, "two 'foo' occurrences (line 1 and line 2)")
        assertEquals(0, find.activeIndex)
    }

    @Test
    fun nextAndPrevWrapAndDriveScroll() = runTest {
        val state = WorkbenchState(FindFake(mapOf(a to "foo\nbar foo\nbaz")), scope())
        state.openDocument(a)
        advanceUntilIdle()
        state.setFindQueryOpen("foo")
        advanceUntilIdle()

        val nonce0 = state.ui.value.codeNavNonce
        state.findNext()
        assertEquals(1, state.ui.value.find!!.activeIndex)
        assertEquals(2, state.ui.value.tabs.active!!.caret, "the active match's line becomes the caret line")
        assertTrue(state.ui.value.codeNavNonce > nonce0, "stepping re-arms the viewer scroll")

        state.findNext()
        assertEquals(0, state.ui.value.find!!.activeIndex, "next wraps back to the first match")

        state.findPrev()
        assertEquals(1, state.ui.value.find!!.activeIndex, "prev wraps to the last match")
    }

    @Test
    fun noMatchIsFlaggedAndCloseClears() = runTest {
        val state = WorkbenchState(FindFake(mapOf(a to "foo\nbar")), scope())
        state.openDocument(a)
        advanceUntilIdle()
        state.setFindQueryOpen("zzz")
        advanceUntilIdle()

        val find = state.ui.value.find!!
        assertEquals(0, find.count)
        assertTrue(find.noMatch, "a non-empty query with no hits flags the red field")

        state.closeFind()
        assertNull(state.ui.value.find, "closing drops the Find state (and its highlight)")
    }

    @Test
    fun toggleOpensThenCloses() = runTest {
        val state = WorkbenchState(FindFake(mapOf(a to "foo")), scope())
        state.openDocument(a)
        advanceUntilIdle()

        state.toggleFind()
        assertTrue(state.ui.value.find != null)
        state.toggleFind()
        assertNull(state.ui.value.find)
    }

    @Test
    fun switchingTabsRecomputesMatchesForTheNewDocument() = runTest {
        val state = WorkbenchState(FindFake(mapOf(a to "foo\nfoo", b to "foo")), scope())
        state.openDocument(a)
        advanceUntilIdle()
        state.setFindQueryOpen("foo")
        advanceUntilIdle()
        assertEquals(2, state.ui.value.find!!.count, "two hits in A")

        state.openDocument(b)
        advanceUntilIdle()
        assertEquals(1, state.ui.value.find!!.count, "recomputed to B's single hit — no stale A matches")
        assertFalse(state.ui.value.find!!.noMatch)
    }

    /** Open the Find bar (if closed) and set the query — mirrors typing into the bar. */
    private fun WorkbenchState.setFindQueryOpen(text: String) {
        if (ui.value.find == null) openFind()
        setFindQuery(text)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun kotlinx.coroutines.test.TestScope.scope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler))
}
