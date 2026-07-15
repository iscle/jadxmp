package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.client.CodeLine
import com.jadxmp.ui.client.CodeToken
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.client.DecompilerClient
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.NodeKind
import com.jadxmp.ui.client.OpenRequest
import com.jadxmp.ui.client.RenameOutcome
import com.jadxmp.ui.client.RenameQuery
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
 * View-model tests for the Rename flow: the dialog prefills the clicked name, a success closes it and
 * refreshes the tree + active document (so the new name shows), a rejection keeps it open with the reason,
 * and a blank name is rejected locally without touching the engine. Driven with a fake [DecompilerClient]
 * on a [runTest] scheduler — no Compose. Mirrors [WorkbenchStateTest]'s harness.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchRenameTest {

    private val node = NodeId("cls:HelloWorld")

    /** A fake engine that returns a scripted [outcome] from [rename] and counts the calls it received. */
    private class RenameFake(private val outcome: RenameOutcome) : DecompilerClient {
        private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
        override val session: StateFlow<SessionState> = _session.asStateFlow()
        var renameCalls = 0
        var codeCalls = 0

        override suspend fun open(request: OpenRequest) {
            _session.value = SessionState.Ready(request.name, classCount = 1)
        }

        override suspend fun rootNodes(tree: TreeKind): List<TreeNode> =
            if (tree == TreeKind.CLASSES) {
                listOf(TreeNode(NodeId("cls:HelloWorld"), "HelloWorld", NodeKind.CLASS, hasChildren = false))
            } else {
                emptyList()
            }

        override suspend fun childNodes(parent: NodeId): List<TreeNode> = emptyList()
        override suspend fun classNodes(): List<TreeNode> = emptyList()
        override fun availableViews(node: NodeId): List<CodeView> = listOf(CodeView.JAVA)

        override suspend fun code(node: NodeId, view: CodeView): CodeDocument {
            codeCalls++
            val title = node.value.removePrefix("cls:").substringAfterLast('.')
            return CodeDocument(node, title, view, listOf(CodeLine(1, listOf(CodeToken("class $title", TokenKind.PLAIN)))))
        }

        override suspend fun search(query: SearchQuery): SearchResults = SearchResults(query, emptyList())

        override suspend fun rename(target: RenameQuery, newName: String): RenameOutcome {
            renameCalls++
            return outcome
        }
    }

    private fun typeToken(text: String) = CodeToken(text, TokenKind.TYPE)

    @Test
    fun openingTheDialogPrefillsTheClickedSymbolName() = runTest {
        val state = WorkbenchState(RenameFake(RenameOutcome.Applied("X")), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openRenameDialog(node, CodeView.JAVA, line = 1, token = typeToken("HelloWorld"))

        val dialog = state.ui.value.rename
        assertTrue(dialog != null, "the dialog opens")
        assertEquals("HelloWorld", dialog.originalName)
        assertEquals("HelloWorld", dialog.input, "the field is prefilled with the current name")
        assertEquals("HelloWorld", dialog.target.token)
        assertEquals(TokenKind.TYPE, dialog.target.tokenKind)
        assertEquals(node, dialog.target.classNode)
    }

    @Test
    fun aSuccessfulRenameClosesTheDialogAndRefreshesTreeAndActiveDoc() = runTest {
        val fake = RenameFake(RenameOutcome.Applied("Greeter"))
        val state = WorkbenchState(fake, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openDocument(node)
        advanceUntilIdle()
        val codeCallsBefore = fake.codeCalls

        state.openRenameDialog(node, CodeView.JAVA, 1, typeToken("HelloWorld"))
        state.submitRename()
        advanceUntilIdle()

        assertEquals(1, fake.renameCalls, "the engine rename ran once")
        assertNull(state.ui.value.rename, "the dialog closes on success")
        assertTrue(
            state.ui.value.roots[TreeKind.CLASSES]?.isNotEmpty() == true,
            "the tree roots were re-fetched (rename-aware labels)",
        )
        assertTrue(fake.codeCalls > codeCallsBefore, "the active document is reloaded so the new name shows")
    }

    @Test
    fun aRejectedRenameKeepsTheDialogOpenWithTheReasonAndAppliesNothing() = runTest {
        val fake = RenameFake(RenameOutcome.Rejected("'int' is a Java reserved word"))
        val state = WorkbenchState(fake, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openRenameDialog(node, CodeView.JAVA, 1, typeToken("HelloWorld"))
        state.setRenameInput("int")
        state.submitRename()
        advanceUntilIdle()

        val dialog = state.ui.value.rename
        assertTrue(dialog != null, "the dialog stays open on rejection")
        assertEquals("'int' is a Java reserved word", dialog.error, "the engine's reason is shown inline")
        assertEquals(false, dialog.busy, "the busy state is cleared so the user can edit + retry")
        assertEquals(1, fake.renameCalls)
    }

    @Test
    fun aBlankNameIsRejectedLocallyWithoutHittingTheEngine() = runTest {
        val fake = RenameFake(RenameOutcome.Applied("X"))
        val state = WorkbenchState(fake, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openRenameDialog(node, CodeView.JAVA, 1, typeToken("HelloWorld"))
        state.setRenameInput("   ")
        state.submitRename()
        advanceUntilIdle()

        assertEquals(0, fake.renameCalls, "a blank name never reaches the engine")
        assertTrue(state.ui.value.rename?.error != null, "the dialog shows a local error and stays open")
    }

    @Test
    fun setRenameInputClearsAPriorError() = runTest {
        val state = WorkbenchState(RenameFake(RenameOutcome.Rejected("bad")), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openRenameDialog(node, CodeView.JAVA, 1, typeToken("HelloWorld"))
        state.submitRename()
        advanceUntilIdle()
        assertTrue(state.ui.value.rename?.error != null)

        state.setRenameInput("Greeter")
        assertNull(state.ui.value.rename?.error, "editing the name clears the stale rejection")
        assertEquals("Greeter", state.ui.value.rename?.input)
    }

    @Test
    fun closingTheDialogClearsIt() = runTest {
        val state = WorkbenchState(RenameFake(RenameOutcome.Applied("X")), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openRenameDialog(node, CodeView.JAVA, 1, typeToken("HelloWorld"))
        assertTrue(state.ui.value.rename != null)
        state.closeRenameDialog()
        assertNull(state.ui.value.rename)
    }

    @Test
    fun renameKindLabelMapsTheTokenKind() {
        assertEquals("class", renameKindLabel(TokenKind.TYPE))
        assertEquals("method", renameKindLabel(TokenKind.METHOD))
        assertEquals("field", renameKindLabel(TokenKind.FIELD))
        assertEquals("symbol", renameKindLabel(TokenKind.PLAIN))
    }
}
