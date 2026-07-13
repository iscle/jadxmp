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

    override suspend fun search(query: SearchQuery): SearchResults {
        delay(20)
        return StubData.search(query)
    }

    override suspend fun memberLocation(memberNodeId: NodeId): MemberLocation? {
        delay(10)
        return StubData.memberLocation(memberNodeId)
    }
}
