package com.jadxmp.ui.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [DecompilerClient] backed by [StubData]. Lets the whole shell render, navigate, and
 * search before `core:api` exists. The small [delay] calls simulate async engine work so the
 * loading/progress chrome is exercised; they are the only reason methods here suspend.
 */
class StubDecompilerClient : DecompilerClient {

    private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
    override val session: StateFlow<SessionState> = _session.asStateFlow()

    override suspend fun open(request: OpenRequest) {
        _session.value = SessionState.Loading("Reading ${request.name}", progress = 0.2f)
        delay(120)
        _session.value = SessionState.Loading("Decompiling classes", progress = 0.7f)
        delay(120)
        _session.value = SessionState.Ready(StubData.PROJECT_NAME, classCount = StubData.allNodes().count { it.kind.isType })
    }

    override suspend fun rootNodes(tree: TreeKind): List<TreeNode> {
        delay(10)
        return StubData.roots(tree)
    }

    override suspend fun childNodes(parent: NodeId): List<TreeNode> {
        delay(10)
        return StubData.childrenOf(parent)
    }

    override suspend fun classNodes(): List<TreeNode> {
        delay(10)
        return StubData.allNodes().filter { it.kind.isType }.sortedBy { it.id.value }
    }

    override fun availableViews(node: NodeId): List<CodeView> = StubData.availableViews(node)

    override suspend fun code(node: NodeId, view: CodeView): CodeDocument {
        delay(20)
        return StubData.document(node, view)
    }

    /** Raw bytes for the sample image / binary resource nodes, so the image + hex viewers are live in the stub. */
    override suspend fun resourceBytes(node: NodeId): ByteArray? {
        delay(10)
        return StubData.resourceBytes(node)
    }

    override suspend fun search(query: SearchQuery): SearchResults {
        delay(20)
        return StubData.search(query)
    }

    override suspend fun memberLocation(memberNodeId: NodeId): MemberLocation? {
        delay(10)
        return StubData.memberLocation(memberNodeId)
    }

    /**
     * Export the sample project by rendering each class node's Java text to a `<package>/<Simple>.java`
     * file — enough for the Export action to produce a real (sample) archive on shells backed by the stub
     * (android, previews). Fault-isolated: a node that won't render is skipped, never a crash.
     */
    override suspend fun exportProject(view: CodeView?): List<ExportFile> =
        classNodes().mapNotNull { node ->
            val fqn = node.id.value.removePrefix("cls:")
            val text = runCatching { code(node.id, CodeView.JAVA).plainText() }.getOrNull() ?: return@mapNotNull null
            ExportFile(fqn.replace('.', '/') + ".java", text.encodeToByteArray())
        }
}
