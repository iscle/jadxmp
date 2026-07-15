package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.client.CodeLine
import com.jadxmp.ui.client.CodeToken
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.client.DecompilerClient
import com.jadxmp.ui.client.FileSaver
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.OpenRequest
import com.jadxmp.ui.client.SearchQuery
import com.jadxmp.ui.client.SearchResults
import com.jadxmp.ui.client.SessionState
import com.jadxmp.ui.client.SettingsStore
import com.jadxmp.ui.client.ThemeMode
import com.jadxmp.ui.client.TokenKind
import com.jadxmp.ui.client.TreeKind
import com.jadxmp.ui.client.TreeNode
import com.jadxmp.ui.client.UiSettings
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
import kotlin.test.assertTrue

/**
 * The settings-persistence + save wiring in [WorkbenchState]: it must seed from the injected
 * [SettingsStore] on init, persist on every theme/flatten/view change, carry those preferences across a
 * project open, and route "Save file" through the injected [FileSaver] with the derived name + bytes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchSettingsTest {

    /** Minimal engine: one-line docs, empty trees — enough to open a document and read it back. */
    private class EmptyClient : DecompilerClient {
        private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
        override val session: StateFlow<SessionState> = _session.asStateFlow()
        override suspend fun open(request: OpenRequest) { _session.value = SessionState.Ready(request.name, 0) }
        override suspend fun rootNodes(tree: TreeKind): List<TreeNode> = emptyList()
        override suspend fun childNodes(parent: NodeId): List<TreeNode> = emptyList()
        override suspend fun classNodes(): List<TreeNode> = emptyList()
        override fun availableViews(node: NodeId): List<CodeView> = listOf(CodeView.JAVA)
        override suspend fun code(node: NodeId, view: CodeView): CodeDocument =
            CodeDocument(node, node.value, view, listOf(CodeLine(1, listOf(CodeToken("x", TokenKind.PLAIN)))))
        override suspend fun search(query: SearchQuery): SearchResults = SearchResults(query, emptyList())
    }

    /** A store that echoes back what was written, recording each save. */
    private class RecordingStore(private var current: UiSettings = UiSettings()) : SettingsStore {
        val saves = mutableListOf<UiSettings>()
        override fun load(): UiSettings = current
        override fun save(settings: UiSettings) { current = settings; saves += settings }
        fun latest(): UiSettings = current
    }

    private fun scope(testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    @Test
    fun seedsFromTheStoreOnInit() = runTest {
        val store = RecordingStore(UiSettings(ThemeMode.DARK, flattenPackages = true, preferredView = CodeView.SMALI))
        val state = WorkbenchState(EmptyClient(), scope(testScheduler), settingsStore = store)
        val ui = state.ui.value
        assertEquals(ThemeMode.DARK, ui.themeMode)
        assertTrue(ui.tree.flattenPackages)
        assertEquals(CodeView.SMALI, ui.preferredView)
        assertTrue(store.saves.isEmpty(), "loading must not trigger a save")
    }

    @Test
    fun everyPreferenceChangePersistsAllThreeTogether() = runTest {
        val store = RecordingStore()
        val state = WorkbenchState(EmptyClient(), scope(testScheduler), settingsStore = store)

        state.toggleTheme(systemDark = false) // SYSTEM (effectively light) -> DARK
        assertEquals(ThemeMode.DARK, store.latest().themeMode)

        state.setFlatten(true)
        assertTrue(store.latest().flattenPackages)

        state.setPreferredView(CodeView.KOTLIN)
        assertEquals(UiSettings(ThemeMode.DARK, flattenPackages = true, preferredView = CodeView.KOTLIN), store.latest())
    }

    @Test
    fun preferencesSurviveAProjectOpen() = runTest {
        val store = RecordingStore(UiSettings(ThemeMode.DARK, flattenPackages = true, preferredView = CodeView.KOTLIN))
        val state = WorkbenchState(EmptyClient(), scope(testScheduler), settingsStore = store)

        state.openProject(OpenRequest("x"))
        advanceUntilIdle()

        val ui = state.ui.value
        assertEquals(ThemeMode.DARK, ui.themeMode, "theme survives project open")
        assertTrue(ui.tree.flattenPackages, "flatten survives project open")
        assertEquals(CodeView.KOTLIN, ui.preferredView, "preferred view survives project open")
    }

    @Test
    fun saveActiveDocumentSendsDerivedNameAndBytesToTheSaver() = runTest {
        var savedName: String? = null
        var savedText: String? = null
        val saver = FileSaver { name, bytes ->
            savedName = name
            savedText = bytes.decodeToString()
            true
        }
        val state = WorkbenchState(EmptyClient(), scope(testScheduler), fileSaver = saver)
        assertTrue(state.hasSaver)

        state.openDocument(NodeId("cls:com.foo.Bar"))
        advanceUntilIdle()
        state.saveActiveDocument()
        advanceUntilIdle()

        assertEquals("Bar.java", savedName)
        assertEquals("x", savedText)
    }

    @Test
    fun saveIsANoOpWithoutASaverOrDocument() = runTest {
        val noSaver = WorkbenchState(EmptyClient(), scope(testScheduler))
        assertFalse(noSaver.hasSaver)
        noSaver.saveActiveDocument() // no saver, no document — must not throw
        advanceUntilIdle()

        var called = false
        val saver = FileSaver { _, _ -> called = true; true }
        val withSaver = WorkbenchState(EmptyClient(), scope(testScheduler), fileSaver = saver)
        withSaver.saveActiveDocument() // saver present but no document open
        advanceUntilIdle()
        assertFalse(called, "nothing to save with no active document")
    }
}
