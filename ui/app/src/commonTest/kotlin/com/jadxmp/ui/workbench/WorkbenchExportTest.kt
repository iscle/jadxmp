package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.client.DecompilerClient
import com.jadxmp.ui.client.ExportFile
import com.jadxmp.ui.client.ExportRequest
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.OpenRequest
import com.jadxmp.ui.client.ProjectExporter
import com.jadxmp.ui.client.SearchQuery
import com.jadxmp.ui.client.SearchResults
import com.jadxmp.ui.client.SessionState
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
 * The "Export decompiled sources" wiring in [WorkbenchState]: with a project open it must decompile the
 * whole project through the client and hand the files to the injected [ProjectExporter], including a
 * `toZip` that produces a real (re-readable) ZIP; and it must be a safe no-op with no exporter or no
 * project open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchExportTest {

    /** Engine fake: opens to Ready, and exports two fixed files (a class + a resource). */
    private class ExportClient : DecompilerClient {
        private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
        override val session: StateFlow<SessionState> = _session.asStateFlow()
        var lastView: CodeView? = null

        override suspend fun open(request: OpenRequest) { _session.value = SessionState.Ready(request.name, 2) }
        override suspend fun rootNodes(tree: TreeKind): List<TreeNode> = emptyList()
        override suspend fun childNodes(parent: NodeId): List<TreeNode> = emptyList()
        override suspend fun classNodes(): List<TreeNode> = emptyList()
        override fun availableViews(node: NodeId): List<CodeView> = listOf(CodeView.JAVA)
        override suspend fun code(node: NodeId, view: CodeView): CodeDocument =
            CodeDocument(node, node.value, view, emptyList())
        override suspend fun search(query: SearchQuery): SearchResults = SearchResults(query, emptyList())
        override suspend fun exportProject(view: CodeView?): List<ExportFile> {
            lastView = view
            return listOf(
                ExportFile("com/foo/Bar.java", "class Bar {}\n".encodeToByteArray()),
                ExportFile("resources/AndroidManifest.xml", "<manifest/>\n".encodeToByteArray()),
            )
        }
    }

    private class CapturingExporter : ProjectExporter {
        var request: ExportRequest? = null
        override suspend fun export(request: ExportRequest): Boolean {
            this.request = request
            return true
        }
    }

    private fun scope(testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    @Test
    fun exportProjectHandsAllFilesAndAValidZipToTheExporter() = runTest {
        val client = ExportClient()
        val exporter = CapturingExporter()
        val state = WorkbenchState(client, scope(testScheduler), projectExporter = exporter)
        assertTrue(state.hasExporter)

        state.openProject(OpenRequest("sample-app.apk"))
        advanceUntilIdle()
        state.exportProject()
        advanceUntilIdle()

        val request = exporter.request
        assertTrue(request != null, "the exporter must be invoked once a project is open")
        assertEquals("sample-app.apk", request.projectName)
        assertEquals(
            listOf("com/foo/Bar.java", "resources/AndroidManifest.xml"),
            request.files.map { it.path },
            "every exported file must be handed to the exporter",
        )
        assertEquals("sample-app-sources.zip", request.zipName)

        // toZip lazily builds a real archive: the bytes start with the ZIP local-header magic "PK".
        val zip = request.toZip()
        assertTrue(zip.size >= 4, "the zip must have content")
        assertEquals(0x50.toByte(), zip[0])
        assertEquals(0x4B.toByte(), zip[1])
        assertEquals(0x03.toByte(), zip[2])
        assertEquals(0x04.toByte(), zip[3])

        assertEquals("Exported sample-app.apk", state.ui.value.status)
    }

    @Test
    fun exportUsesTheCurrentPreferredViewAsTheOutputFormat() = runTest {
        val client = ExportClient()
        val state = WorkbenchState(client, scope(testScheduler), projectExporter = CapturingExporter())
        state.openProject(OpenRequest("x.apk"))
        advanceUntilIdle()
        state.setPreferredView(CodeView.KOTLIN)

        state.exportProject()
        advanceUntilIdle()
        assertEquals(CodeView.KOTLIN, client.lastView, "export should honor the current preferred view")
    }

    @Test
    fun exportIsANoOpWithoutAnExporterOrWithoutAProject() = runTest {
        // No exporter wired: hasExporter false, calling export must not throw.
        val noExporter = WorkbenchState(ExportClient(), scope(testScheduler))
        assertFalse(noExporter.hasExporter)
        noExporter.exportProject()
        advanceUntilIdle()

        // Exporter wired but no project open (session Empty): the exporter is never invoked.
        val exporter = CapturingExporter()
        val state = WorkbenchState(ExportClient(), scope(testScheduler), projectExporter = exporter)
        state.exportProject()
        advanceUntilIdle()
        assertNull(exporter.request, "nothing to export with no project open")
    }

    @Test
    fun exportZipNameStripsExtensionAndFallsBackSafely() {
        assertEquals("sample-app-sources.zip", exportZipName("sample-app.apk"))
        assertEquals("classes-sources.zip", exportZipName("classes.dex"))
        assertEquals("noext-sources.zip", exportZipName("noext"))
        assertEquals("project-sources.zip", exportZipName(""))
    }
}
