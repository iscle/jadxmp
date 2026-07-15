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
import kotlin.test.assertNull

/**
 * Tests for "Go to Application class" (P1#23) and single-class auto-open. The pure manifest resolver
 * ([resolveApplicationClassName] / [qualifyManifestClassName]) is asserted directly; the jump + the
 * auto-open are driven end-to-end through [WorkbenchState] with a fake [DecompilerClient] on a
 * [runTest] scheduler — no Compose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchApplicationTest {

    /** A fake engine exposing a manifest resource node (whose [code] is the given text) + a class list. */
    private class AppFake(
        private val manifestText: String,
        private val classes: List<TreeNode> = emptyList(),
    ) : DecompilerClient {
        private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
        override val session: StateFlow<SessionState> = _session.asStateFlow()
        private val manifest = TreeNode(NodeId("res:AndroidManifest.xml"), "AndroidManifest.xml", NodeKind.FILE, false)

        override suspend fun open(request: OpenRequest) {
            _session.value = SessionState.Ready(request.name, classCount = classes.size)
        }

        override suspend fun rootNodes(tree: TreeKind): List<TreeNode> =
            if (tree == TreeKind.RESOURCES) listOf(manifest) else classes

        override suspend fun childNodes(parent: NodeId): List<TreeNode> = emptyList()
        override suspend fun classNodes(): List<TreeNode> = classes
        override fun availableViews(node: NodeId): List<CodeView> = listOf(CodeView.JAVA)

        override suspend fun code(node: NodeId, view: CodeView): CodeDocument {
            val text = if (node == manifest.id) manifestText else "// ${node.value}"
            return CodeDocument(node, node.value, view, listOf(CodeLine(1, listOf(CodeToken(text, TokenKind.PLAIN)))))
        }

        override suspend fun search(query: SearchQuery): SearchResults = SearchResults(query, emptyList())
    }

    // ── pure resolver ─────────────────────────────────────────────────────────

    @Test
    fun resolveApplicationClassNameQualifiesAgainstPackage() {
        val pkg = """<manifest package="com.x">"""
        assertEquals("com.x.MyApp", resolveApplicationClassName("""$pkg<application android:name=".MyApp"/>"""))
        assertEquals("com.x.MyApp", resolveApplicationClassName("""$pkg<application android:name="MyApp"/>"""))
        assertEquals("com.full.App", resolveApplicationClassName("""$pkg<application android:name="com.full.App"/>"""))
    }

    @Test
    fun resolveApplicationClassNameReturnsNullWhenAbsentOrMalformed() {
        assertNull(resolveApplicationClassName("""<manifest package="com.x"><application/></manifest>"""))
        assertNull(resolveApplicationClassName("no manifest here at all"))
        assertNull(resolveApplicationClassName(""))
    }

    @Test
    fun qualifyManifestClassNameRules() {
        assertEquals("com.x.Foo", qualifyManifestClassName(".Foo", "com.x"))
        assertEquals("com.x.Foo", qualifyManifestClassName("Foo", "com.x"))
        assertEquals("com.y.Foo", qualifyManifestClassName("com.y.Foo", "com.x"))
        assertEquals("Foo", qualifyManifestClassName("Foo", "")) // no package → leave the bare name
    }

    // ── end-to-end jump ───────────────────────────────────────────────────────

    @Test
    fun jumpToApplicationOpensTheResolvedClass() = runTest {
        val app = TreeNode(NodeId("cls:com.x.MyApp"), "MyApp", NodeKind.CLASS, hasChildren = true)
        val other = TreeNode(NodeId("cls:com.x.Other"), "Other", NodeKind.CLASS, hasChildren = true)
        val manifest = """<manifest package="com.x"><application android:name=".MyApp"></application></manifest>"""
        val state = WorkbenchState(AppFake(manifest, listOf(app, other)), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openProject(OpenRequest("app.apk"))
        advanceUntilIdle()

        state.jumpToApplicationClass()
        advanceUntilIdle()

        assertEquals(NodeId("cls:com.x.MyApp"), state.ui.value.tabs.active?.nodeId, "the Application class opens")
        assertEquals(TreeKind.CLASSES, state.ui.value.tree.kind, "and the view switches to the classes tree")
    }

    @Test
    fun jumpToApplicationFallsBackToManifestWhenUnresolvable() = runTest {
        // Two classes (so no single-class auto-open muddies the result), but no <application> name.
        val classes = listOf(
            TreeNode(NodeId("cls:com.x.A"), "A", NodeKind.CLASS, hasChildren = true),
            TreeNode(NodeId("cls:com.x.B"), "B", NodeKind.CLASS, hasChildren = true),
        )
        val state = WorkbenchState(AppFake("""<manifest package="com.x"><application/></manifest>""", classes), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openProject(OpenRequest("app.apk"))
        advanceUntilIdle()

        state.jumpToApplicationClass()
        advanceUntilIdle()

        assertEquals(NodeId("res:AndroidManifest.xml"), state.ui.value.tabs.active?.nodeId, "falls back to the manifest")
        assertEquals(TreeKind.RESOURCES, state.ui.value.tree.kind)
    }

    // ── single-class auto-open ────────────────────────────────────────────────

    @Test
    fun singleClassInputAutoOpensThatClass() = runTest {
        val only = TreeNode(NodeId("cls:Solo"), "Solo", NodeKind.CLASS, hasChildren = true)
        val state = WorkbenchState(AppFake("<manifest/>", listOf(only)), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openProject(OpenRequest("solo.jar"))
        advanceUntilIdle()
        assertEquals(NodeId("cls:Solo"), state.ui.value.tabs.active?.nodeId, "the sole class opens automatically")
    }

    @Test
    fun multiClassInputOpensNoTabAutomatically() = runTest {
        val classes = listOf(
            TreeNode(NodeId("cls:A"), "A", NodeKind.CLASS, hasChildren = true),
            TreeNode(NodeId("cls:B"), "B", NodeKind.CLASS, hasChildren = true),
        )
        val state = WorkbenchState(AppFake("<manifest/>", classes), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.openProject(OpenRequest("x.jar"))
        advanceUntilIdle()
        assertNull(state.ui.value.tabs.active, "a multi-class input opens no tab until the user clicks")
    }
}
