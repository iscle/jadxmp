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

    /**
     * A small deterministic demo so the Find-usages panel is exercised in the stub shell (android/previews):
     * report the clicked token as used once, back at its own site. A real backend inverts the whole-program
     * reference metadata (see [CoreApiDecompilerClient]); the stub has no index, so this stands in for it. A
     * blank token resolves to nothing (`null`), matching the real "couldn't resolve" path.
     */
    override suspend fun findUsages(query: UsageQuery): UsageResults? {
        delay(30)
        val token = query.token.trim()
        if (token.isEmpty()) return null
        val kind = when (query.tokenKind) {
            TokenKind.METHOD -> NodeKind.METHOD
            TokenKind.FIELD -> NodeKind.FIELD
            else -> NodeKind.CLASS
        }
        val classLabel = query.classNode.value.removePrefix("cls:").substringAfterLast('.')
        return UsageResults(
            symbol = token,
            kind = kind,
            sites = listOf(
                UsageSiteRow(
                    classNode = query.classNode,
                    classLabel = classLabel,
                    memberLabel = null,
                    line = query.line,
                    kind = kind,
                ),
            ),
        )
    }

    /**
     * A benign stand-in so the Rename dialog + refresh flow is exercised end-to-end in the stub shell
     * (android/previews): a legal-looking name is accepted, a blank/illegal one is rejected with a readable
     * reason — mirroring the real backend's accept/reject shape without a live model to mutate (the static
     * [StubData] tree does not actually change). A real rename runs through [CoreApiDecompilerClient].
     */
    override suspend fun rename(target: RenameQuery, newName: String): RenameOutcome {
        delay(20)
        val name = newName.trim()
        val illegal = name.isEmpty() ||
            !(name[0].isLetter() || name[0] == '_') ||
            name.any { !(it.isLetterOrDigit() || it == '_') }
        return if (illegal) {
            RenameOutcome.Rejected("'$newName' is not a valid Java identifier.")
        } else {
            RenameOutcome.Applied(name)
        }
    }

    /** No user renames are tracked in the stub, so clearing them is a no-op. */
    override suspend fun clearRenames() {
        delay(10)
    }
}
