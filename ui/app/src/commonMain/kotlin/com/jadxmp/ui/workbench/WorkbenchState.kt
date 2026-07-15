package com.jadxmp.ui.workbench

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.client.DecompilerClient
import com.jadxmp.ui.client.FileOpener
import com.jadxmp.ui.client.FileSaver
import com.jadxmp.ui.client.MemberTree
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.NodeKind
import com.jadxmp.ui.client.OpenRequest
import com.jadxmp.ui.client.SearchQuery
import com.jadxmp.ui.client.SearchResults
import com.jadxmp.ui.client.SessionState
import com.jadxmp.ui.client.SettingsStore
import com.jadxmp.ui.client.ThemeMode
import com.jadxmp.ui.client.TreeKind
import com.jadxmp.ui.client.TreeNode
import com.jadxmp.ui.client.UiSettings
import com.jadxmp.ui.client.resolveDark
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

/** Well-known Resources-tree id of the decoded manifest (mirrors ResourceSurface's MANIFEST_ID). */
private const val MANIFEST_NODE_ID = "res:AndroidManifest.xml"

/** Locates the launcher `<activity>`/`<activity-alias>` name in decoded manifest text (best-effort). */
private val LAUNCHER_ACTIVITY_REGEX = Regex(
    """<activity(?:-alias)?\b[^>]*?android:name\s*=\s*"([^"]+)"[\s\S]*?android\.intent\.category\.LAUNCHER""",
)

/** Extracts the `<manifest package="…">` app package for resolving a relative activity/class name. */
private val MANIFEST_PACKAGE_REGEX = Regex("""<manifest\b[^>]*?package\s*=\s*"([^"]+)"""")

/** Locates the `<application android:name="…">` class name in decoded manifest text (best-effort). */
private val APPLICATION_CLASS_REGEX = Regex(
    """<application\b[^>]*?android:name\s*=\s*"([^"]+)"""",
)

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
    /** Preferred source view for newly opened class tabs (Settings → Decompiler default). Persisted. */
    val preferredView: CodeView = CodeView.JAVA,
    /** Light/dark/system selection, seeded from and persisted through the injected SettingsStore. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** In-editor Find bar state for the active document. Null = the Find bar is hidden. */
    val find: FindUiState? = null,
) {
    /** Children currently known for [parent] (empty until lazily loaded on expand). */
    fun children(parent: NodeId): List<TreeNode> = childrenCache[parent].orEmpty()

    /** The Resources-tree manifest node, when the open container carries an AndroidManifest.xml. */
    val manifestNode: TreeNode?
        get() = roots[TreeKind.RESOURCES]?.firstOrNull { it.id.value == MANIFEST_NODE_ID }

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
    private val settingsStore: SettingsStore? = null,
    private val fileSaver: FileSaver? = null,
) {
    /**
     * Persisted preferences, loaded once synchronously on construction so the very first frame already
     * reflects the stored theme / flatten / preferred view (see [SettingsStore]). Absent store =
     * defaults. Only theme/flatten/view are seeded; all other state starts fresh.
     */
    private val loadedSettings: UiSettings = settingsStore?.load() ?: UiSettings()

    private val _ui = MutableStateFlow(
        WorkbenchUiState(
            themeMode = loadedSettings.themeMode,
            preferredView = loadedSettings.preferredView,
            tree = TreeUiState(flattenPackages = loadedSettings.flattenPackages),
        ),
    )
    val ui: StateFlow<WorkbenchUiState> = _ui.asStateFlow()
    val session: StateFlow<SessionState> = client.session

    /** True when a [FileSaver] is wired, so the workbench can show/hide the "Save file" affordances. */
    val hasSaver: Boolean get() = fileSaver != null

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

    /**
     * Node ids whose child-load launched by [ensureChildrenLoaded] is still in flight. Guards against
     * relaunching the same eager load on every recomposition while the first load resolves. Accessed
     * only from the UI dispatcher (the same single dispatcher [scope] runs on), so no atomics.
     */
    private val inFlightChildLoads = mutableSetOf<NodeId>()

    /** The in-flight member (Methods/Fields) scan. Symmetric to [codeSearchJob]; see [runMemberSearch]. */
    private var memberSearchJob: Job? = null

    /** Generation guard for the member scan, symmetric to [codeSearchGeneration]. */
    private var memberSearchGeneration = 0

    /**
     * Best-effort seed for the Find bar / "Search selection", set from the last code token the user
     * clicked or right-clicked. Compose's read-only [androidx.compose.foundation.text.selection.SelectionContainer]
     * does not expose its drag-selection to app code, so this tracked token is the accessible proxy for
     * "the current selection". Accessed only on [scope]'s single dispatcher.
     */
    private var selectionSeed: String = ""

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
        // The old project's selection has no meaning in the new one; the fresh state below drops the
        // Find bar, so clear its seed too.
        selectionSeed = ""
        scope.launch {
            _ui.update { it.copy(busy = true, status = "Opening ${request.name}…") }
            client.open(request)
            val classRoots = client.rootNodes(TreeKind.CLASSES)
            val resRoots = client.rootNodes(TreeKind.RESOURCES)
            // Superseded by a newer open while we were loading — discard, don't clobber the new project.
            if (epoch != openEpoch) return@launch
            // A fresh state so a newly opened file never shows the previous project's tabs/documents,
            // but carry the persisted preferences (theme / preferred view / flatten) across the reset —
            // they are user settings, not per-project state. Tree expansion/filter/selection do reset.
            val prev = _ui.value
            _ui.value = WorkbenchUiState(
                status = "Ready",
                roots = mapOf(TreeKind.CLASSES to classRoots, TreeKind.RESOURCES to resRoots),
                themeMode = prev.themeMode,
                preferredView = prev.preferredView,
                tree = TreeUiState(flattenPackages = prev.tree.flattenPackages),
            )
            maybeAutoOpenSingleClass(epoch)
        }
    }

    /**
     * jadx-gui convenience: when a freshly opened input holds exactly one class, open it immediately so a
     * single-class jar/dex lands on its source rather than an unexpanded tree. Epoch-guarded (a superseded
     * open never auto-opens into a newer project) and fault-isolated — a failed enumeration just skips it.
     */
    private suspend fun maybeAutoOpenSingleClass(epoch: Int) {
        val classes = runCatching { client.classNodes() }.getOrNull().orEmpty()
        if (epoch != openEpoch || classes.size != 1) return
        val only = classes.single()
        openDocument(only.id, only.label, kind = only.kind)
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
        persistSettings()
    }

    /** Set the preferred default source view for newly opened class tabs (Settings). Persisted. */
    fun setPreferredView(view: CodeView) {
        _ui.update { it.copy(preferredView = view) }
        persistSettings()
    }

    /** Set the theme mode explicitly (Settings) and persist it. */
    fun setThemeMode(mode: ThemeMode) {
        _ui.update { it.copy(themeMode = mode) }
        persistSettings()
    }

    /**
     * Flip the theme from the toolbar / settings toggle. [systemDark] resolves the *current* effective
     * theme when the stored mode is [ThemeMode.SYSTEM], so the first toggle pins the opposite of what is
     * actually on screen (never a no-op). The chosen explicit LIGHT/DARK is persisted.
     */
    fun toggleTheme(systemDark: Boolean) {
        val currentlyDark = _ui.value.themeMode.resolveDark(systemDark)
        setThemeMode(if (currentlyDark) ThemeMode.LIGHT else ThemeMode.DARK)
    }

    /**
     * "Save file" (P0#7): write the ACTIVE document's rendered text to a user-chosen destination via the
     * injected [FileSaver], suggesting `<SimpleName>.<ext>` for a class or the resource's own file name.
     * A no-op when no saver is wired or no document is loaded (the affordances are disabled then). The
     * save runs on [scope] and is fault-isolated — a failed/cancelled save is swallowed (rule 4).
     */
    fun saveActiveDocument() {
        val saver = fileSaver ?: return
        val doc = _ui.value.activeDocument ?: return
        val name = suggestedFileName(doc.nodeId.value, doc.view)
        val bytes = doc.plainText().encodeToByteArray()
        scope.launch { runCatching { saver.save(name, bytes) } }
    }

    /** Persist the current preferences through the injected store (best-effort; no-op without one). */
    private fun persistSettings() {
        val store = settingsStore ?: return
        val s = _ui.value
        store.save(
            UiSettings(
                themeMode = s.themeMode,
                flattenPackages = s.tree.flattenPackages,
                preferredView = s.preferredView,
            ),
        )
    }

    /**
     * jadx "Open AndroidManifest.xml" quick action: switch to the Resources tree and open the decoded
     * manifest. A no-op when the container carries no manifest (the toolbar button is disabled then).
     */
    fun openManifest() {
        val manifest = _ui.value.manifestNode ?: return
        switchTree(TreeKind.RESOURCES)
        openDocument(manifest.id, manifest.label, kind = manifest.kind)
    }

    /**
     * jadx "Go to main activity" quick action (best-effort). Decodes the manifest, finds the launcher
     * activity's class name, resolves it to a loaded class node, and opens it. If anything can't be
     * resolved — no manifest, no launcher entry, or the class isn't in the tree — it falls back to
     * opening the manifest, never an error (rule 4). All parsing is guarded so it can't throw.
     */
    fun jumpToMainActivity() {
        val manifest = _ui.value.manifestNode ?: return
        val epoch = openEpoch
        scope.launch {
            val text = runCatching { client.code(manifest.id, CodeView.JAVA).plainText() }.getOrNull()
            if (epoch != openEpoch) return@launch
            val activity = text?.let { resolveLauncherActivity(it) }
            if (activity == null) {
                openManifest()
                return@launch
            }
            val classNode = findClassNode(activity)
            if (epoch != openEpoch) return@launch
            if (classNode != null) {
                switchTree(TreeKind.CLASSES)
                openDocument(classNode.id, classNode.label, kind = classNode.kind)
            } else {
                openManifest()
            }
        }
    }

    /**
     * jadx "Go to Application class" quick action (best-effort), mirroring [jumpToMainActivity]. Decodes
     * the manifest, finds the `<application android:name=…>` class, resolves it to a loaded class node,
     * and opens it. Anything unresolved — no manifest, no `<application>` name, or the class isn't in the
     * tree — falls back to opening the manifest, never an error (rule 4). All parsing is guarded.
     */
    fun jumpToApplicationClass() {
        val manifest = _ui.value.manifestNode ?: return
        val epoch = openEpoch
        scope.launch {
            val text = runCatching { client.code(manifest.id, CodeView.JAVA).plainText() }.getOrNull()
            if (epoch != openEpoch) return@launch
            val appClass = text?.let { resolveApplicationClassName(it) }
            if (appClass == null) {
                openManifest()
                return@launch
            }
            val classNode = findClassNode(appClass)
            if (epoch != openEpoch) return@launch
            if (classNode != null) {
                switchTree(TreeKind.CLASSES)
                openDocument(classNode.id, classNode.label, kind = classNode.kind)
            } else {
                openManifest()
            }
        }
    }

    /** Parse the launcher activity's fully-qualified class name from decoded manifest [text], or null. */
    private fun resolveLauncherActivity(text: String): String? = runCatching {
        val name = LAUNCHER_ACTIVITY_REGEX.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        val pkg = MANIFEST_PACKAGE_REGEX.find(text)?.groupValues?.getOrNull(1).orEmpty()
        qualifyManifestClassName(name, pkg)
    }.getOrNull()

    /** Find the loaded class node matching fully-qualified [fqcn] (exact `cls:` id or fq-name suffix). */
    private suspend fun findClassNode(fqcn: String): TreeNode? {
        val classes = runCatching { client.classNodes() }.getOrNull() ?: return null
        val exactId = "cls:$fqcn"
        return classes.firstOrNull { it.id.value == exactId }
            ?: classes.firstOrNull { it.id.value.removePrefix("cls:") == fqcn }
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
        val available = client.availableViews(nodeId)
        val preferred = _ui.value.preferredView
        val view = if (preferred in available) preferred else (available.firstOrNull() ?: CodeView.JAVA)
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

    // ── In-editor Find bar (Ctrl+F) ───────────────────────────────────────────

    /**
     * Record the "current selection" seed from a clicked/right-clicked code token (see [selectionSeed]).
     * Ignores blank or multi-line text so the seed is always a single, meaningful term.
     */
    fun noteSelectionSeed(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty() && '\n' !in trimmed) selectionSeed = trimmed
    }

    /** Ctrl/Cmd+F: open the Find bar (empty query seeds from the last clicked token) or close it. */
    fun toggleFind() {
        if (_ui.value.find != null) closeFind() else openFind()
    }

    /** Show the Find bar, seeding an empty query from the last clicked token, and jump to the first hit. */
    fun openFind() {
        val existing = _ui.value.find
        val seed = existing?.query?.takeIf { it.isNotEmpty() } ?: selectionSeed
        _ui.update { it.copy(find = (existing ?: FindUiState()).copy(query = seed)) }
        recomputeFind(resetIndexToCaret = true)
        scrollToActiveFind()
    }

    /** Hide the Find bar (drops the painted match highlight). */
    fun closeFind() {
        _ui.update { it.copy(find = null) }
    }

    fun setFindQuery(text: String) {
        if (_ui.value.find == null) return
        _ui.update { it.copy(find = it.find?.copy(query = text)) }
        recomputeFind(resetIndexToCaret = true)
        scrollToActiveFind()
    }

    fun setFindMatchCase(on: Boolean) {
        if (_ui.value.find == null) return
        _ui.update { it.copy(find = it.find?.copy(matchCase = on)) }
        recomputeFind(resetIndexToCaret = false)
        scrollToActiveFind()
    }

    /** Advance to the next match (wraps around); scrolls the viewer to it. */
    fun findNext() = stepFind(+1)

    /** Go to the previous match (wraps around); scrolls the viewer to it. */
    fun findPrev() = stepFind(-1)

    private fun stepFind(delta: Int) {
        val f = _ui.value.find ?: return
        if (f.matches.isEmpty()) return
        val next = (f.activeIndex + delta).mod(f.matches.size)
        _ui.update { it.copy(find = it.find?.copy(activeIndex = next)) }
        scrollToActiveFind()
    }

    /**
     * Recompute Find matches over the current active document. [resetIndexToCaret] re-anchors the active
     * match to the first hit at/after the caret (used on a query/open change); otherwise the current index
     * is clamped in place (used on a passive document/tab change). A no-op when the Find bar is hidden, so
     * it costs nothing on ordinary navigation. Synchronous ([DocumentFind.find] is pure), single dispatcher.
     */
    private fun recomputeFind(resetIndexToCaret: Boolean) {
        val f = _ui.value.find ?: return
        val doc = _ui.value.activeDocument
        val matches = if (doc != null && f.query.isNotEmpty()) DocumentFind.find(doc, f.query, f.matchCase) else emptyList()
        val index = when {
            matches.isEmpty() -> 0
            resetIndexToCaret -> {
                val caret = _ui.value.tabs.active?.caret?.takeIf { it >= 1 } ?: 1
                matches.indexOfFirst { it.line >= caret }.let { if (it < 0) 0 else it }
            }
            else -> f.activeIndex.coerceIn(0, matches.lastIndex)
        }
        _ui.update { it.copy(find = it.find?.copy(matches = matches, activeIndex = index)) }
    }

    /**
     * Scroll the viewer to the active match by reusing the go-to-definition mechanism: pin the active
     * tab's caret to the match line and bump [WorkbenchUiState.codeNavNonce] so the viewer re-scrolls even
     * when the tab is already open. A no-op when there is no active match.
     */
    private fun scrollToActiveFind() {
        val match = _ui.value.find?.activeMatch ?: return
        _ui.update {
            it.copy(
                tabs = it.tabs.updateCaret(it.tabs.activeIndex, match.line),
                codeNavNonce = it.codeNavNonce + 1,
            )
        }
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

    /**
     * Eagerly load a node's children into [WorkbenchUiState.childrenCache] **without** expanding it, so
     * the flatten toggle can compact a single-child package chain (`a.b.c`) before the user ever clicks.
     * Idempotent and safe to call on every recomposition: it no-ops when the children are already cached
     * or a load for this id is already in flight, so it never launches duplicate loads. Epoch-guarded
     * like [loadChildren] — results for a superseded project are dropped. All access is on [scope]'s
     * single dispatcher (see [inFlightChildLoads]).
     */
    fun ensureChildrenLoaded(id: NodeId) {
        if (id in _ui.value.childrenCache || id in inFlightChildLoads) return
        inFlightChildLoads += id
        val epoch = openEpoch
        scope.launch {
            try {
                val kids = client.childNodes(id)
                // Drop children resolved against a project that has since been replaced.
                if (epoch != openEpoch) return@launch
                _ui.update { it.copy(childrenCache = it.childrenCache + (id to kids)) }
            } finally {
                inFlightChildLoads -= id
            }
        }
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
        // Re-sync the Find bar against whatever is active now: a cached doc recomputes immediately; a
        // not-yet-loaded one clears to empty until the load below repopulates it. No-op when Find is off.
        recomputeFind(resetIndexToCaret = false)
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
            // The active document may have just become available — recompute Find matches over it.
            recomputeFind(resetIndexToCaret = false)
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

/**
 * Suggested save name for the active document: `<SimpleName>.<ext>` for a class (extension from the
 * shown [view]) or the resource's own file name for a resource node. Pure and fault-tolerant (blank
 * segments fall back to a generic name) so it is unit-tested directly and never throws. [nodeId] is the
 * raw [NodeId.value]; class ids are `cls:<fqn>`, resource ids `res:<path>`.
 */
internal fun suggestedFileName(nodeId: String, view: CodeView): String = when {
    nodeId.startsWith("res:") ->
        nodeId.removePrefix("res:").substringAfterLast('/').ifBlank { "resource.txt" }
    nodeId.startsWith("cls:") -> {
        val simple = nodeId.removePrefix("cls:").substringAfterLast('.').ifBlank { "Class" }
        "$simple.${view.fileExtension()}"
    }
    else -> {
        val base = nodeId.substringAfterLast(':').substringAfterLast('/').ifBlank { "file" }
        "$base.${view.fileExtension()}"
    }
}

/** File extension for a source [CodeView]: Java `.java`, Kotlin `.kt`, Smali `.smali`. */
internal fun CodeView.fileExtension(): String = when (this) {
    CodeView.JAVA -> "java"
    CodeView.KOTLIN -> "kt"
    CodeView.SMALI -> "smali"
}

/**
 * Resolve the `<application android:name=…>` class's fully-qualified name from decoded manifest [text],
 * or null when absent. Mirrors the launcher-activity resolver: a leading-dot or bare name is qualified
 * against the `<manifest package>`. Pure and fault-isolated (never throws), so it is unit-tested directly.
 */
internal fun resolveApplicationClassName(text: String): String? = runCatching {
    val name = APPLICATION_CLASS_REGEX.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        ?: return@runCatching null
    val pkg = MANIFEST_PACKAGE_REGEX.find(text)?.groupValues?.getOrNull(1).orEmpty()
    qualifyManifestClassName(name, pkg)
}.getOrNull()

/** Qualify a manifest class [name] against the app [pkg]: `.Foo`→`pkg.Foo`, bare `Foo`→`pkg.Foo`, else as-is. */
internal fun qualifyManifestClassName(name: String, pkg: String): String = when {
    name.startsWith(".") -> pkg + name
    !name.contains('.') && pkg.isNotEmpty() -> "$pkg.$name"
    else -> name
}

/** Create a [WorkbenchState] scoped to the composition. */
@Composable
fun rememberWorkbenchState(
    client: DecompilerClient,
    fileOpener: FileOpener? = null,
    settingsStore: SettingsStore? = null,
    fileSaver: FileSaver? = null,
): WorkbenchState {
    val scope = rememberCoroutineScope()
    return remember(client, fileOpener, settingsStore, fileSaver) {
        WorkbenchState(client, scope, fileOpener, settingsStore, fileSaver)
    }
}
