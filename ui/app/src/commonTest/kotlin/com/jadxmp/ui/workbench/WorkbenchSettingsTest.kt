package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.client.CodeLine
import com.jadxmp.ui.client.CodeToken
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.client.DEFAULT_CODE_FONT_SIZE_SP
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

    @Test
    fun fontSizeClampsIntoTheLegibleRange() {
        assertEquals(DEFAULT_CODE_FONT_SIZE_SP, clampCodeFontSize(DEFAULT_CODE_FONT_SIZE_SP))
        assertEquals(MIN_CODE_FONT_SIZE_SP, clampCodeFontSize(2f), "below the floor clamps up")
        assertEquals(MAX_CODE_FONT_SIZE_SP, clampCodeFontSize(99f), "above the ceiling clamps down")
        assertEquals(MIN_CODE_FONT_SIZE_SP, clampCodeFontSize(MIN_CODE_FONT_SIZE_SP))
        assertEquals(MAX_CODE_FONT_SIZE_SP, clampCodeFontSize(MAX_CODE_FONT_SIZE_SP))
    }

    @Test
    fun editorViewPreferencesSeedFromTheStore() = runTest {
        val store = RecordingStore(UiSettings(wordWrap = true, codeFontSize = 20f))
        val state = WorkbenchState(EmptyClient(), scope(testScheduler), settingsStore = store)
        assertTrue(state.ui.value.wordWrap)
        assertEquals(20f, state.ui.value.codeFontSize)
    }

    @Test
    fun zoomAndWordWrapPersistAndClamp() = runTest {
        val store = RecordingStore()
        val state = WorkbenchState(EmptyClient(), scope(testScheduler), settingsStore = store)

        state.toggleWordWrap()
        assertTrue(store.latest().wordWrap, "word-wrap persists")

        state.setCodeFontSize(999f) // way past the ceiling
        assertEquals(MAX_CODE_FONT_SIZE_SP, state.ui.value.codeFontSize, "clamped in state")
        assertEquals(MAX_CODE_FONT_SIZE_SP, store.latest().codeFontSize, "clamped value persisted")

        state.resetCodeZoom()
        assertEquals(DEFAULT_CODE_FONT_SIZE_SP, state.ui.value.codeFontSize, "reset returns to default")
    }

    @Test
    fun lineNumberAndCurrentLineTogglesSeedAndPersist() = runTest {
        // Seed the non-default (off) state from the store and confirm it lands in the live UI state.
        val store = RecordingStore(UiSettings(showLineNumbers = false, highlightCurrentLine = false))
        val state = WorkbenchState(EmptyClient(), scope(testScheduler), settingsStore = store)
        assertFalse(state.ui.value.showLineNumbers)
        assertFalse(state.ui.value.highlightCurrentLine)

        // Each setter flips the live state and persists through the store.
        state.setShowLineNumbers(true)
        assertTrue(state.ui.value.showLineNumbers)
        assertTrue(store.latest().showLineNumbers, "line-number toggle persists")

        state.setHighlightCurrentLine(true)
        assertTrue(state.ui.value.highlightCurrentLine)
        assertTrue(store.latest().highlightCurrentLine, "current-line toggle persists")
    }
}
