package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.client.CodeLine
import com.jadxmp.ui.client.CodeToken
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.client.CommentOutcome
import com.jadxmp.ui.client.CommentQuery
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * View-model tests for the Comment flow: the dialog prefills the symbol's EXISTING comment (async), a Save
 * closes it and reloads the active document (so the note shows), a blank Save and the Remove action both
 * clear the note, and closing drops the dialog. Driven with a fake [DecompilerClient] on a [runTest]
 * scheduler — no Compose. Mirrors [WorkbenchRenameTest]'s harness; comments differ in prefill (from the
 * engine, not the token), multi-line free text, and blank == remove (never a rejection).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchCommentTest {

    private val node = NodeId("cls:HelloWorld")

    /**
     * A fake engine that scripts [existing] as the current comment and records the [setComment] calls (count
     * + the last text it received), so the tests can assert the resolve → set/remove → refresh flow.
     */
    private class CommentFake(private val existing: String?) : DecompilerClient {
        private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
        override val session: StateFlow<SessionState> = _session.asStateFlow()
        var setCalls = 0
        var lastText: String? = null
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

        override suspend fun commentFor(target: CommentQuery): String? = existing

        override suspend fun setComment(target: CommentQuery, text: String): CommentOutcome {
            setCalls++
            lastText = text
            return CommentOutcome.Applied(removed = text.isBlank())
        }
    }

    private fun typeToken(text: String) = CodeToken(text, TokenKind.TYPE)

    @Test
    fun openingTheDialogPrefillsTheExistingComment() = runTest {
        val state = WorkbenchState(CommentFake("TODO: refactor"), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openCommentDialog(node, CodeView.JAVA, line = 1, token = typeToken("HelloWorld"))
        advanceUntilIdle()

        val dialog = state.ui.value.comment
        assertTrue(dialog != null, "the dialog opens")
        assertEquals("HelloWorld", dialog.symbolName)
        assertEquals("TODO: refactor", dialog.existingComment, "the engine's current comment is captured")
        assertEquals("TODO: refactor", dialog.input, "the field is prefilled with the existing comment")
        assertEquals("HelloWorld", dialog.target.token)
        assertEquals(TokenKind.TYPE, dialog.target.tokenKind)
        assertEquals(node, dialog.target.classNode)
    }

    @Test
    fun openingWithNoExistingCommentPrefillsEmpty() = runTest {
        val state = WorkbenchState(CommentFake(null), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openCommentDialog(node, CodeView.JAVA, 1, typeToken("HelloWorld"))
        advanceUntilIdle()

        val dialog = state.ui.value.comment
        assertTrue(dialog != null, "the dialog opens with no prior comment")
        assertNull(dialog.existingComment, "no existing comment → the Add wording / no Remove")
        assertEquals("", dialog.input, "the field starts empty")
    }

    @Test
    fun aSuccessfulSaveClosesTheDialogAndReloadsTheActiveDoc() = runTest {
        val fake = CommentFake(null)
        val state = WorkbenchState(fake, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openDocument(node)
        advanceUntilIdle()
        val codeCallsBefore = fake.codeCalls

        state.openCommentDialog(node, CodeView.JAVA, 1, typeToken("HelloWorld"))
        advanceUntilIdle()
        state.setCommentInput("explain this class")
        state.submitComment()
        advanceUntilIdle()

        assertEquals(1, fake.setCalls, "the engine setComment ran once")
        assertEquals("explain this class", fake.lastText, "the typed note was sent")
        assertNull(state.ui.value.comment, "the dialog closes on success")
        assertTrue(fake.codeCalls > codeCallsBefore, "the active document is reloaded so the note shows")
    }

    @Test
    fun submittingBlankRemovesTheComment() = runTest {
        val fake = CommentFake("stale note")
        val state = WorkbenchState(fake, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openCommentDialog(node, CodeView.JAVA, 1, typeToken("HelloWorld"))
        advanceUntilIdle()
        state.setCommentInput("   ")
        state.submitComment()
        advanceUntilIdle()

        assertEquals(1, fake.setCalls, "a blank Save reaches the engine as a removal")
        assertTrue(fake.lastText.isNullOrBlank(), "the removal is a blank text")
        assertNull(state.ui.value.comment, "the dialog closes")
    }

    @Test
    fun removeSendsABlankTextAndClosesTheDialog() = runTest {
        val fake = CommentFake("delete me")
        val state = WorkbenchState(fake, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openCommentDialog(node, CodeView.JAVA, 1, typeToken("HelloWorld"))
        advanceUntilIdle()
        state.removeComment()
        advanceUntilIdle()

        assertEquals(1, fake.setCalls)
        assertEquals("", fake.lastText, "Remove submits an empty text so the engine clears the note")
        assertNull(state.ui.value.comment, "the dialog closes after removing")
    }

    @Test
    fun setCommentInputUpdatesTheTypedNote() = runTest {
        val state = WorkbenchState(CommentFake("a"), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openCommentDialog(node, CodeView.JAVA, 1, typeToken("HelloWorld"))
        advanceUntilIdle()

        state.setCommentInput("first line\nsecond line")
        assertEquals("first line\nsecond line", state.ui.value.comment?.input, "multi-line text is kept verbatim")
    }

    @Test
    fun closingTheDialogClearsIt() = runTest {
        val state = WorkbenchState(CommentFake("x"), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openCommentDialog(node, CodeView.JAVA, 1, typeToken("HelloWorld"))
        advanceUntilIdle()
        assertTrue(state.ui.value.comment != null)
        state.closeCommentDialog()
        assertNull(state.ui.value.comment)
    }

    @Test
    fun commentActionLabelMapsHasComment() {
        assertEquals("Edit comment", commentActionLabel(hasComment = true))
        assertEquals("Add comment", commentActionLabel(hasComment = false))
    }
}
