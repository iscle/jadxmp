package com.jadxmp.ui.workbench

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.client.DecompilerClient
import com.jadxmp.ui.client.FileOpener
import com.jadxmp.ui.client.MemberTree
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.NodeKind
import com.jadxmp.ui.client.OpenRequest
import com.jadxmp.ui.client.SearchQuery
import com.jadxmp.ui.client.SearchResults
import com.jadxmp.ui.client.SessionState
import com.jadxmp.ui.client.TreeKind
import com.jadxmp.ui.client.TreeNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Cache key for a loaded document: a node can be shown in multiple views. */
@Immutable
data class DocKey(val nodeId: NodeId, val view: CodeView)

/** How many classes may be scanned between forced progress pushes when no new match is found. */
private const val PROGRESS_PUSH_STRIDE = 12

/** Debounce before a code scan launches, so per-keystroke typing doesn't queue whole-program scans. */
private const val CODE_SEARCH_DEBOUNCE_MS = 250L

/** The full observable UI state of the workbench. Derived render lists are computed in composables. */
@Immutable
data class WorkbenchUiState(
    val tree: TreeUiState = TreeUiState(),
    val roots: Map<TreeKind, List<TreeNode>> = emptyMap(),
    val childrenCache: Map<NodeId, List<TreeNode>> = emptyMap(),
    val tabs: TabsState = TabsState(),
    val history: NavHistory = NavHistory(),
    val documents: Map<DocKey, CodeDocument> = emptyMap(),
    val status: String = "No project open",
    val busy: Boolean = false,
    /** Class-NAME search results (the instant path). Null when that scope is not showing results. */
    val searchResults: SearchResults? = null,
    /** Code-CONTENT search state (the streaming scan). Null when the Code scope is idle. */
    val codeSearch: CodeSearchUiState? = null,
    /** MEMBER search state (the streaming Methods/Fields scan). Null when those scopes are idle. */
    val memberSearch: MemberSearchUiState? = null,
    /**
     * Bumped on each "jump to a code hit" so the code viewer re-scrolls/highlights the target line
     * even when the destination tab is already open (its document identity would otherwise be
     * unchanged). A one-shot navigation token — its value is meaningless, only its changes matter.
     */
    val codeNavNonce: Int = 0,
) {
    /** Children currently known for [parent] (empty until lazily loaded on expand). */
    fun children(parent: NodeId): List<TreeNode> = childrenCache[parent].orEmpty()

    val activeDocument: CodeDocument?
        get() = tabs.active?.let { documents[DocKey(it.nodeId, it.view)] }
}

/**
 * View-model for the workbench. Owns a [WorkbenchUiState] [StateFlow], delegates all pure transitions
 * to [TabsState]/[NavHistory]/[TreeUiState], and performs the async engine calls through
 * [DecompilerClient]. Nothing blocks the UI thread; every engine call is launched on [scope] and is
 * cancelable — safe on single-threaded wasm.
 */
class WorkbenchState(
    private val client: DecompilerClient,
    private val scope: CoroutineScope,
    private val fileOpener: FileOpener? = null,
) {
    private val _ui = MutableStateFlow(WorkbenchUiState())
    val ui: StateFlow<WorkbenchUiState> = _ui.asStateFlow()
    val session: StateFlow<SessionState> = client.session

    /**
     * Generation counter bumped on every [openProject]. Async results from a superseded project
     * (an in-flight decompile or child-load whose [openProject] no longer matches [openEpoch]) are
     * dropped instead of landing in the fresh project's state — the code viewer and tree only ever
     * show the currently open file. All access is on [scope]'s (single) dispatcher, so no atomics.
     */
    private var openEpoch = 0

    /**
     * The in-flight code-content scan, if any. A new code search (or a project open, or clearing the
     * search) cancels it first, so scans never overlap and a superseded scan cannot stream stale
     * matches into the current results. Runs on [scope]'s single dispatcher — no atomics needed.
     */
    private var codeSearchJob: Job? = null

    /**
     * Monotonic id of the current code scan, bumped by [stopCodeSearch] every time a scan is cancelled
     * or superseded. Each launched scan captures its id and every callback checks it, so a stale scan's
     * *late* callback (e.g. an uncancelable class decompile that resolves on the Default pool after the
     * job was cancelled) can never write into a newer scan's state. Defense in depth alongside prompt
     * cancellation — [openEpoch] doesn't change when one code search supersedes another on the same
     * project, so it cannot distinguish the two; this can. All access is on [scope]'s single dispatcher.
     */
    private var codeSearchGeneration = 0

    /** The in-flight member (Methods/Fields) scan. Symmetric to [codeSearchJob]; see [runMemberSearch]. */
    private var memberSearchJob: Job? = null

    /** Generation guard for the member scan, symmetric to [codeSearchGeneration]. */
    private var memberSearchGeneration = 0

    /**
     * Handle an "Open" gesture. With a platform [fileOpener] wired (desktop/web/android), show its
     * picker and load the chosen file's bytes; cancelling is a no-op. Without one (stub/preview), fall
     * back to the in-memory sample project so the shell is still explorable.
     */
    fun requestOpen() {
        val opener = fileOpener
        if (opener == null) {
            openSampleProject()
            return
        }
        scope.launch { opener.choose()?.let { openProject(it) } }
    }

    /** Open the in-memory sample project (stub backend / previews). */
    fun openSampleProject() = openProject(OpenRequest("sample-app.apk"))

    /** Load [request] through the client and (re)populate the tree, resetting tabs/history/documents. */
    fun openProject(request: OpenRequest) {
        val epoch = ++openEpoch
        // A scan of the old project is meaningless now; stop it so it doesn't burn the (single) wasm
        // dispatcher decompiling classes whose results the epoch guard would drop anyway.
        stopCodeSearch()
        stopMemberSearch()
        scope.launch {
            _ui.update { it.copy(busy = true, status = "Opening ${request.name}…") }
            client.open(request)
            val classRoots = client.rootNodes(TreeKind.CLASSES)
            val resRoots = client.rootNodes(TreeKind.RESOURCES)
            // Superseded by a newer open while we were loading — discard, don't clobber the new project.
            if (epoch != openEpoch) return@launch
            // A fresh state so a newly opened file never shows the previous project's tabs/documents.
            _ui.value = WorkbenchUiState(
                status = "Ready",
                roots = mapOf(TreeKind.CLASSES to classRoots, TreeKind.RESOURCES to resRoots),
            )
        }
    }

    /** Views a node can be shown in (sync — used by the toolbar view switcher). */
    fun availableViews(nodeId: NodeId): List<CodeView> = client.availableViews(nodeId)

    fun switchTree(kind: TreeKind) {
        _ui.update { it.copy(tree = it.tree.switchTree(kind)) }
    }

    fun setFilter(text: String) {
        _ui.update { it.copy(tree = it.tree.setFilter(text)) }
        // buildVisibleRows can only match nodes present in the children cache. Lazy loading means a
        // match inside a not-yet-expanded package would be silently missed, so when a filter becomes
        // active we eagerly pull the whole subtree into the cache (yielding between loads to stay
        // responsive on single-threaded wasm). Clearing the filter cancels the walk on its own.
        if (text.isNotBlank()) scope.launch { loadSubtreeForFilter() }
    }

    fun setFlatten(value: Boolean) {
        _ui.update { it.copy(tree = it.tree.setFlatten(value)) }
    }

    fun toggleExpand(node: TreeNode) {
        if (!node.hasChildren) return
        val willExpand = node.id !in _ui.value.tree.expanded
        _ui.update { it.copy(tree = it.tree.toggleExpanded(node.id)) }
        if (willExpand && node.id !in _ui.value.childrenCache) {
            scope.launch { loadChildren(node.id) }
        }
    }

    /** A tree row was clicked: navigate members, expand containers, open documents for leaves. */
    fun onNodeActivated(node: TreeNode) {
        _ui.update { it.copy(tree = it.tree.select(node.id)) }
        when {
            // A member row (field/method/constructor/nested class) navigates to its definition — its
            // chevron (a nested class) still toggles expansion via the separate onToggle path.
            node.id.value.startsWith(MemberTree.PREFIX) -> openMember(node.id)
            node.kind == NodeKind.PACKAGE || node.kind == NodeKind.DIRECTORY -> toggleExpand(node)
            node.kind == NodeKind.IMAGE -> _ui.update { it.copy(status = "Image preview: ${node.label}") }
            else -> openDocument(node.id, node.label, kind = node.kind)
        }
    }

    /**
     * Navigate to a member: open its owning (top-level) class tab and, if the member's definition line
     * resolves, place the caret there and bump [WorkbenchUiState.codeNavNonce] so the viewer scrolls +
     * highlights it — exactly like [openCodeMatch], but the line comes from the engine's member metadata.
     * An unresolvable member (a static initializer, or a def the backend doesn't annotate yet) opens the
     * class without a scroll — honest, never an error (rule 4).
     */
    fun openMember(memberNodeId: NodeId) {
        val epoch = openEpoch
        scope.launch {
            val location = client.memberLocation(memberNodeId)
            // A newer project opened, or the id is unrecognized — nothing to open.
            if (epoch != openEpoch || location == null) return@launch
            openDocument(location.classNodeId, kind = NodeKind.CLASS)
            val line = location.line
            if (line != null && line >= 1) {
                _ui.update {
                    it.copy(
                        tabs = it.tabs.updateCaret(it.tabs.activeIndex, line),
                        codeNavNonce = it.codeNavNonce + 1,
                    )
                }
            }
        }
    }

    /** Open (or focus) a document tab and load its source for the default view. */
    fun openDocument(
        nodeId: NodeId,
        label: String = deriveLabel(nodeId),
        pushHistory: Boolean = true,
        kind: NodeKind? = null,
    ) {
        val view = client.availableViews(nodeId).firstOrNull() ?: CodeView.JAVA
        _ui.update { state ->
            state.copy(
                tabs = state.tabs.open(nodeId, label, view, kind),
                history = if (pushHistory) state.history.visit(nodeId) else state.history,
                tree = state.tree.select(nodeId),
            )
        }
        ensureDocument(nodeId, view)
    }

    fun activateTab(index: Int) {
        val tab = _ui.value.tabs.tabs.getOrNull(index) ?: return
        // Re-focusing an open tab is not a new navigation: sync the history cursor if the node is
        // already in history, but never truncate the forward stack (that only happens on real visits).
        _ui.update {
            it.copy(tabs = it.tabs.activate(index), history = it.history.moveTo(tab.nodeId), tree = it.tree.select(tab.nodeId))
        }
        ensureDocument(tab.nodeId, tab.view)
    }

    fun closeTab(index: Int) {
        _ui.update { it.copy(tabs = it.tabs.close(index)) }
    }

    fun togglePin(index: Int) {
        _ui.update { it.copy(tabs = it.tabs.togglePin(index)) }
    }

    fun updateCaret(caret: Int) {
        _ui.update { it.copy(tabs = it.tabs.updateCaret(it.tabs.activeIndex, caret)) }
    }

    /** Switch the active tab between Java / Kotlin / Smali. */
    fun setActiveView(view: CodeView) {
        val active = _ui.value.tabs.active ?: return
        _ui.update { it.copy(tabs = it.tabs.setActiveView(view)) }
        ensureDocument(active.nodeId, view)
    }

    fun goBack() = navigate { it.back() }

    fun goForward() = navigate { it.forward() }

    private fun navigate(move: (NavHistory) -> NavHistory) {
        val moved = move(_ui.value.history)
        val target = moved.current ?: return
        _ui.update {
            it.copy(
                history = moved,
                tabs = it.tabs.open(target, deriveLabel(target), it.tabs.active?.view ?: CodeView.JAVA),
                tree = it.tree.select(target),
            )
        }
        val view = _ui.value.tabs.active?.view ?: CodeView.JAVA
        ensureDocument(target, view)
    }

    /** Class-NAME search (instant). Cancels any running streaming scan and hides its results. */
    fun runSearch(query: SearchQuery) {
        stopCodeSearch()
        stopMemberSearch()
        scope.launch {
            _ui.update { it.copy(busy = true, codeSearch = null, memberSearch = null) }
            val results = client.search(query)
            _ui.update { it.copy(busy = false, searchResults = results, status = "${results.matches.size} matches") }
        }
    }

    /**
     * Code-CONTENT search (the streaming scan behind the "Code" scope). Supersedes any prior scan,
     * enumerates the project's classes, then decompiles each (through the client's cache) and streams
     * matching lines into [WorkbenchUiState.codeSearch]. Runs on [scope]; yields between classes so the
     * single-threaded wasm UI keeps recomposing. A blank query just clears the state.
     */
    fun runCodeSearch(text: String, ignoreCase: Boolean = true, useRegex: Boolean = false) {
        // Supersede any prior scan and invalidate its late callbacks (bumps the generation).
        stopCodeSearch()
        stopMemberSearch()
        val query = CodeSearch.Query(text, ignoreCase = ignoreCase, useRegex = useRegex)
        if (query.isBlank) {
            _ui.update { it.copy(codeSearch = null) }
            return
        }
        // Class-name results belong to the other scope; drop them so the panel shows one result set.
        val epoch = openEpoch
        val generation = codeSearchGeneration
        codeSearchJob = scope.launch {
            // Debounce: a rapid keystroke supersedes this before the delay elapses (stopCodeSearch
            // cancels the job), so only the settled query pays for a whole-program scan.
            delay(CODE_SEARCH_DEBOUNCE_MS)
            val classNodes = client.classNodes()
            if (generation != codeSearchGeneration || epoch != openEpoch) return@launch
            val classes = classNodes.map { node ->
                CodeSearch.ClassRef(
                    nodeId = node.id,
                    simpleName = node.label,
                    packageName = node.secondary
                        ?: node.id.value.removePrefix("cls:").substringBeforeLast('.', missingDelimiterValue = ""),
                )
            }
            _ui.update {
                it.copy(searchResults = null, memberSearch = null, codeSearch = CodeSearchUiState(query = text, total = classes.size, running = true))
            }
            val collected = ArrayList<CodeMatch>()
            var lastPushedScan = 0
            val sink = object : CodeSearch.ScanSink {
                override fun onMatch(match: CodeMatch) {
                    collected += match
                }

                override fun onProgress(scanned: Int, total: Int) {
                    // A superseded/cancelled scan must never write into the current scan's state, even
                    // if a late callback slips through after cancellation (see codeSearchGeneration).
                    if (generation != codeSearchGeneration || epoch != openEpoch) return
                    // Throttle state pushes: on new matches, or every few classes, or at the very end —
                    // enough to keep progress/results live without a recomposition per class.
                    val matchesGrew = collected.size != _ui.value.codeSearch?.matches?.size
                    if (!matchesGrew && scanned != total && scanned - lastPushedScan < PROGRESS_PUSH_STRIDE) return
                    lastPushedScan = scanned
                    val snapshot = collected.toList()
                    _ui.update { s ->
                        val cs = s.codeSearch ?: return@update s
                        s.copy(codeSearch = cs.copy(matches = snapshot, scanned = scanned, total = total))
                    }
                }
            }
            val summary = CodeSearch.scan(classes, query, sink, sourceOf = { node ->
                // Reuse the client's cached decompile; plainText() flattens tokens back to raw source.
                client.code(node, CodeView.JAVA).plainText()
            })
            if (generation != codeSearchGeneration || epoch != openEpoch) return@launch
            _ui.update { s ->
                val cs = s.codeSearch ?: return@update s
                s.copy(
                    codeSearch = cs.copy(
                        matches = collected.toList(),
                        scanned = summary.scanned,
                        total = summary.total,
                        running = false,
                        truncated = summary.truncated,
                        failed = summary.failed,
                    ),
                    status = codeSearchStatus(summary),
                )
            }
        }
    }

    /** Stop an in-flight code scan (Cancel affordance), keeping whatever matches were already found. */
    fun cancelCodeSearch() {
        stopCodeSearch()
        _ui.update { s -> s.codeSearch?.let { s.copy(codeSearch = it.copy(running = false)) } ?: s }
    }

    /**
     * MEMBER search (the streaming scan behind the "Methods"/"Fields" scopes). [kinds] selects which
     * member rows count ([NodeKind.METHOD] for Methods — constructors also render as method rows;
     * [NodeKind.FIELD] for Fields). Supersedes any prior scan, enumerates each class's members through
     * the client (cheap; no decompilation) and streams matches into [WorkbenchUiState.memberSearch].
     * Structurally identical to [runCodeSearch]: debounced, generation-guarded, epoch-guarded, yields
     * between classes — safe and cancelable on single-threaded wasm. A blank query just clears the state.
     */
    fun runMemberSearch(
        text: String,
        kinds: Set<NodeKind>,
        ignoreCase: Boolean = true,
        useRegex: Boolean = false,
    ) {
        stopCodeSearch()
        stopMemberSearch()
        val query = MemberSearch.Query(text, ignoreCase = ignoreCase, useRegex = useRegex)
        if (query.isBlank) {
            _ui.update { it.copy(memberSearch = null) }
            return
        }
        val epoch = openEpoch
        val generation = memberSearchGeneration
        memberSearchJob = scope.launch {
            delay(CODE_SEARCH_DEBOUNCE_MS)
            val classNodes = client.classNodes()
            if (generation != memberSearchGeneration || epoch != openEpoch) return@launch
            val classes = classNodes.map { node ->
                MemberSearch.ClassRef(
                    nodeId = node.id,
                    simpleName = node.label,
                    packageName = node.secondary
                        ?: node.id.value.removePrefix("cls:").substringBeforeLast('.', missingDelimiterValue = ""),
                )
            }
            _ui.update {
                it.copy(
                    searchResults = null,
                    codeSearch = null,
                    memberSearch = MemberSearchUiState(query = text, total = classes.size, running = true),
                )
            }
            val collected = ArrayList<MemberMatch>()
            var lastPushedScan = 0
            val sink = object : MemberSearch.ScanSink {
                override fun onMatch(match: MemberMatch) {
                    collected += match
                }

                override fun onProgress(scanned: Int, total: Int) {
                    if (generation != memberSearchGeneration || epoch != openEpoch) return
                    val matchesGrew = collected.size != _ui.value.memberSearch?.matches?.size
                    if (!matchesGrew && scanned != total && scanned - lastPushedScan < PROGRESS_PUSH_STRIDE) return
                    lastPushedScan = scanned
                    val snapshot = collected.toList()
                    _ui.update { s ->
                        val ms = s.memberSearch ?: return@update s
                        s.copy(memberSearch = ms.copy(matches = snapshot, scanned = scanned, total = total))
                    }
                }
            }
            val summary = MemberSearch.scan(classes, kinds, query, sink, membersOf = { node -> client.childNodes(node) })
            if (generation != memberSearchGeneration || epoch != openEpoch) return@launch
            _ui.update { s ->
                val ms = s.memberSearch ?: return@update s
                s.copy(
                    memberSearch = ms.copy(
                        matches = collected.toList(),
                        scanned = summary.scanned,
                        total = summary.total,
                        running = false,
                        truncated = summary.truncated,
                        failed = summary.failed,
                    ),
                    status = memberSearchStatus(summary),
                )
            }
        }
    }

    /** Stop an in-flight member scan (Cancel affordance), keeping whatever matches were already found. */
    fun cancelMemberSearch() {
        stopMemberSearch()
        _ui.update { s -> s.memberSearch?.let { s.copy(memberSearch = it.copy(running = false)) } ?: s }
    }

    /**
     * Open a code-search hit and place the caret on the matching line so the viewer scrolls to it.
     * [WorkbenchUiState.codeNavNonce] is bumped so the code viewer treats this as a fresh navigation
     * and scrolls/highlights the line **even when the target tab is already open** (re-focusing an open
     * tab does not change the document identity the viewer keys its scroll off, so the nonce is what
     * drives it).
     */
    fun openCodeMatch(match: CodeMatch) {
        openDocument(match.nodeId, match.simpleName, kind = NodeKind.CLASS)
        // openDocument made this tab active (new or existing); pin the caret to the hit line. For a
        // brand-new tab the doc is still loading — by the time the viewer composes it reads this caret.
        _ui.update {
            it.copy(
                tabs = it.tabs.updateCaret(it.tabs.activeIndex, match.line),
                codeNavNonce = it.codeNavNonce + 1,
            )
        }
    }

    fun clearSearch() {
        stopCodeSearch()
        stopMemberSearch()
        _ui.update { it.copy(searchResults = null, codeSearch = null, memberSearch = null) }
    }

    /** Cancel any in-flight scan and invalidate its still-pending callbacks (see [codeSearchGeneration]). */
    private fun stopCodeSearch() {
        codeSearchJob?.cancel()
        codeSearchJob = null
        codeSearchGeneration++
    }

    /** Cancel any in-flight member scan and invalidate its still-pending callbacks (see [memberSearchGeneration]). */
    private fun stopMemberSearch() {
        memberSearchJob?.cancel()
        memberSearchJob = null
        memberSearchGeneration++
    }

    private fun codeSearchStatus(summary: CodeSearch.ScanSummary): String {
        val base = "${summary.matches} code ${if (summary.matches == 1) "match" else "matches"}"
        val trunc = if (summary.truncated) " (capped)" else ""
        return base + trunc
    }

    private fun memberSearchStatus(summary: MemberSearch.ScanSummary): String {
        val base = "${summary.matches} member ${if (summary.matches == 1) "match" else "matches"}"
        val trunc = if (summary.truncated) " (capped)" else ""
        return base + trunc
    }

    // ── internals ──────────────────────────────────────────────────────────────
    private suspend fun loadChildren(parent: NodeId) {
        val epoch = openEpoch
        val kids = client.childNodes(parent)
        // Drop children resolved against a project that has since been replaced.
        if (epoch != openEpoch) return
        _ui.update { it.copy(childrenCache = it.childrenCache + (parent to kids)) }
    }

    /**
     * Breadth-first load every descendant of the active tree's roots into [WorkbenchUiState.childrenCache]
     * so a substring filter can reach matches inside packages the user never expanded. Bails the moment
     * the project is superseded or the filter is cleared, and yields between loads so a large tree never
     * stalls the (single) UI dispatcher.
     */
    private suspend fun loadSubtreeForFilter() {
        val epoch = openEpoch
        val kind = _ui.value.tree.kind
        // Only descend package/directory containers. A class now reports hasChildren (its members), but
        // the tree filter is a class/package NAME filter — eagerly enumerating every class's members
        // would be needless work and would widen the filter's meaning. Members are reached by expanding
        // a class, or via the Methods/Fields search scopes.
        val queue = ArrayDeque(_ui.value.roots[kind].orEmpty().filter { isFilterContainer(it) })
        while (queue.isNotEmpty()) {
            if (epoch != openEpoch || _ui.value.tree.filter.isBlank()) return
            val node = queue.removeFirst()
            val kids = _ui.value.childrenCache[node.id] ?: run {
                val loaded = client.childNodes(node.id)
                if (epoch != openEpoch) return
                _ui.update { it.copy(childrenCache = it.childrenCache + (node.id to loaded)) }
                loaded
            }
            kids.filterTo(queue) { isFilterContainer(it) }
            yield()
        }
    }

    private fun ensureDocument(nodeId: NodeId, view: CodeView) {
        val key = DocKey(nodeId, view)
        if (key in _ui.value.documents) return
        val epoch = openEpoch
        scope.launch {
            _ui.update { it.copy(busy = true) }
            val doc = client.code(nodeId, view)
            // A newer project opened while we decompiled — this document belongs to the old one.
            if (epoch != openEpoch) return@launch
            _ui.update { state ->
                // Adopt the engine-provided title for the tab if it is more accurate.
                val tabs = state.tabs.tabs.map { t ->
                    if (t.nodeId == nodeId && t.view == view && t.title != doc.title) t.copy(title = doc.title) else t
                }
                state.copy(
                    busy = false,
                    documents = state.documents + (key to doc),
                    tabs = state.tabs.copy(tabs = tabs),
                    status = doc.title,
                )
            }
        }
    }

    /** A tree container worth eagerly loading for the name filter: an expandable package/directory. */
    private fun isFilterContainer(node: TreeNode): Boolean =
        node.hasChildren && (node.kind == NodeKind.PACKAGE || node.kind == NodeKind.DIRECTORY)

    private fun deriveLabel(nodeId: NodeId): String {
        val v = nodeId.value
        return when {
            '#' in v -> v.substringAfterLast('#')
            v.startsWith("cls:") -> v.removePrefix("cls:").substringAfterLast('.')
            v.startsWith("res:") -> v.substringAfterLast('/')
            else -> v.substringAfterLast(':')
        }
    }
}

/** Create a [WorkbenchState] scoped to the composition. */
@Composable
fun rememberWorkbenchState(client: DecompilerClient, fileOpener: FileOpener? = null): WorkbenchState {
    val scope = rememberCoroutineScope()
    return remember(client, fileOpener) { WorkbenchState(client, scope, fileOpener) }
}
