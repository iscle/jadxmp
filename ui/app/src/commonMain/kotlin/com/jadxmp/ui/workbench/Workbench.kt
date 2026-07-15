package com.jadxmp.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.client.DecompilerClient
import com.jadxmp.ui.client.FileDropController
import com.jadxmp.ui.client.FileOpener
import com.jadxmp.ui.client.FileSaver
import com.jadxmp.ui.client.NodeKind
import com.jadxmp.ui.client.SessionState
import com.jadxmp.ui.client.SettingsStore
import com.jadxmp.ui.client.StubDecompilerClient
import com.jadxmp.ui.client.resolveDark
import com.jadxmp.ui.component.BrandMark
import com.jadxmp.ui.component.EditorTabStrip
import com.jadxmp.ui.component.EmptyState
import com.jadxmp.ui.component.GearGlyph
import com.jadxmp.ui.component.HorizontalSplitPane
import com.jadxmp.ui.component.Kbd
import com.jadxmp.ui.component.AppClassGlyph
import com.jadxmp.ui.component.ManifestGlyph
import com.jadxmp.ui.component.SettingsPanel
import com.jadxmp.ui.component.TargetGlyph
import com.jadxmp.ui.component.SearchGlyph
import com.jadxmp.ui.component.SegmentedToggle
import com.jadxmp.ui.component.StatusDot
import com.jadxmp.ui.component.StatusReadout
import com.jadxmp.ui.component.ToolbarButton
import com.jadxmp.ui.component.ToolbarTextButton
import com.jadxmp.ui.component.VDivider
import com.jadxmp.ui.component.WorkbenchStatusBar
import com.jadxmp.ui.component.DirectionCaret
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.MonoFontFamily

/**
 * Top-level application composable. Owns the [WorkbenchState] and the light/dark selection, and hosts
 * the [Workbench] inside [JadxTheme]. This is the single entry point the platform shells
 * (desktopApp/webApp/androidApp) call. Injecting a [SettingsStore] makes theme / flatten / preferred
 * view persist across restarts (P0#2); injecting a [FileSaver] enables the "Save file" action (P0#7).
 *
 * The theme is resolved from the *persisted* [com.jadxmp.ui.client.ThemeMode] (seeded synchronously by
 * [WorkbenchState] on init) folded with the platform's [isSystemInDarkTheme], so the very first frame
 * already honours a stored choice instead of always starting from the system default. State is created
 * here — above [JadxTheme] — precisely so the stored theme can drive [JadxTheme] before the workbench
 * body composes.
 */
@Composable
fun JadxWorkbenchApp(
    client: DecompilerClient = remember { StubDecompilerClient() },
    fileOpener: FileOpener? = null,
    dropController: FileDropController? = null,
    settingsStore: SettingsStore? = null,
    fileSaver: FileSaver? = null,
) {
    val systemDark = isSystemInDarkTheme()
    val state = rememberWorkbenchState(client, fileOpener, settingsStore, fileSaver)
    val ui by state.ui.collectAsState()
    val dark = ui.themeMode.resolveDark(systemDark)

    JadxTheme(darkTheme = dark) {
        Workbench(
            state = state,
            dropController = dropController,
            dark = dark,
            onToggleTheme = { state.toggleTheme(systemDark) },
        )
    }
}

@Composable
fun Workbench(
    state: WorkbenchState,
    dark: Boolean,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
    dropController: FileDropController? = null,
) {
    val ui by state.ui.collectAsState()
    val session by state.session.collectAsState()
    var showSearch by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // Seed for the global search field, injected by the code-area "Search selection" action; cleared
    // whenever the panel is opened from the toolbar so a toolbar-open always starts blank.
    var searchSeed by remember { mutableStateOf("") }

    fun openSearch(seed: String) {
        searchSeed = seed
        showSearch = true
    }

    // Global keyboard-shortcut dispatch (jadx-gui parity P0#1). A root preview handler sees every key
    // before the focused child, so these fire regardless of where focus sits; unmapped keys fall through
    // (so typing in a field is untouched). Ctrl and Cmd are both accepted (see [resolveShortcut]).
    fun onKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean =
        when (resolveShortcut(event)) {
            ShortcutAction.OpenFile -> { state.requestOpen(); true }
            ShortcutAction.FindInFile ->
                if (ui.tabs.active != null) { state.toggleFind(); true } else false
            ShortcutAction.GlobalSearch -> { openSearch(""); true }
            ShortcutAction.CloseTab -> {
                val i = ui.tabs.activeIndex
                if (i >= 0) { state.closeTab(i); true } else false
            }
            // Ctrl+Tab: jump to the last-used tab. Consume it (stops focus traversal) only when there is
            // another tab to switch to, so plain Tab keeps traversing focus with 0/1 tabs open.
            ShortcutAction.LastUsedTab ->
                if (ui.tabs.tabs.size > 1) { state.switchToLastUsedTab(); true } else false
            // Consume Ctrl/Cmd+S whenever a saver is wired (also stops the browser's "save page" dialog
            // on web); save only when a document is actually loaded. Exact-modifier match keeps plain
            // "s" typing and Ctrl+Shift+S untouched (see [resolveShortcut]).
            ShortcutAction.SaveFile ->
                if (state.hasSaver) { if (ui.activeDocument != null) state.saveActiveDocument(); true } else false
            ShortcutAction.GoBack -> if (ui.history.canGoBack) { state.goBack(); true } else false
            ShortcutAction.GoForward -> if (ui.history.canGoForward) { state.goForward(); true } else false
            ShortcutAction.Escape -> when {
                ui.find != null -> { state.closeFind(); true }
                showSearch -> { showSearch = false; state.clearSearch(); true }
                ui.history.canGoBack -> { state.goBack(); true }
                else -> false
            }
            null -> false
        }

    // Hold focus at the root whenever no overlay owns it — at startup, and again after the Find bar or
    // search panel (which grab focus for their fields) close, so global shortcuts keep working without a
    // click. Keyed on the "no overlay open" state so it only fires on those transitions, not every click.
    val rootFocus = remember { FocusRequester() }
    val noOverlayFocus = ui.find == null && !showSearch
    LaunchedEffect(noOverlayFocus) {
        if (noOverlayFocus) runCatching { rootFocus.requestFocus() }
    }

    // Route a shell-delivered file drop through the same open path a picked file takes. Installed at
    // composition, long before any user drag, so no drop can be missed. A null controller (stub/
    // preview) contributes an inert flow, so the highlight stays off and nothing is collected.
    val dragFallback = remember { MutableStateFlow(false) }
    val dragActive by (dropController?.dragActive ?: dragFallback).collectAsState()
    LaunchedEffect(dropController, state) {
        dropController?.drops?.collect { state.openProject(it) }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent(::onKey),
    ) {
        Column(Modifier.fillMaxSize()) {
            WorkbenchToolbar(
                projectLabel = (session as? SessionState.Ready)?.projectName ?: "no project",
                canBack = ui.history.canGoBack,
                canForward = ui.history.canGoForward,
                onBack = state::goBack,
                onForward = state::goForward,
                onOpen = state::requestOpen,
                canSave = state.hasSaver && ui.activeDocument != null,
                onSave = state::saveActiveDocument,
                hasManifest = ui.manifestNode != null,
                onOpenManifest = state::openManifest,
                onJumpMainActivity = state::jumpToMainActivity,
                onJumpApplication = state::jumpToApplicationClass,
                searchActive = showSearch,
                onToggleSearch = { if (showSearch) showSearch = false else openSearch("") },
                settingsActive = showSettings,
                onToggleSettings = { showSettings = !showSettings },
                deobfuscated = session is SessionState.Ready,
                dark = dark,
                onToggleTheme = onToggleTheme,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (ui.roots.isEmpty()) {
                    StartPage(
                        loading = ui.busy || session is SessionState.Loading,
                        message = (session as? SessionState.Loading)?.message
                            ?: (session as? SessionState.Failed)?.message,
                        onOpen = state::requestOpen,
                        dragActive = dragActive,
                    )
                } else {
                    HorizontalSplitPane(
                        first = {
                            TreePane(
                                state = ui,
                                onSwitchTree = state::switchTree,
                                onFilter = state::setFilter,
                                onToggleFlatten = { state.setFlatten(!ui.tree.flattenPackages) },
                                onActivate = state::onNodeActivated,
                                onToggle = state::toggleExpand,
                                onEnsureChildrenLoaded = state::ensureChildrenLoaded,
                            )
                        },
                        second = { EditorArea(state, ui, onSearchSelection = ::openSearch) },
                    )
                }

                if (showSearch) {
                    Box(
                        Modifier.fillMaxSize().padding(JadxTheme.spacing.lg),
                        contentAlignment = Alignment.TopEnd,
                    ) {
                        SearchPanel(
                            results = ui.searchResults,
                            codeSearch = ui.codeSearch,
                            memberSearch = ui.memberSearch,
                            onRunNames = state::runSearch,
                            onRunCode = state::runCodeSearch,
                            onRunMembers = { text, fields, ignoreCase, useRegex ->
                                val kinds = if (fields) setOf(NodeKind.FIELD) else setOf(NodeKind.METHOD)
                                state.runMemberSearch(text, kinds, ignoreCase, useRegex)
                            },
                            onCancelCode = state::cancelCodeSearch,
                            onCancelMembers = state::cancelMemberSearch,
                            onOpenNode = { state.openDocument(it) },
                            onOpenCode = state::openCodeMatch,
                            onOpenMember = { state.openMember(it.nodeId) },
                            onClose = { showSearch = false; state.clearSearch() },
                            initialQuery = searchSeed,
                        )
                    }
                }

                if (showSettings) {
                    Box(
                        Modifier.fillMaxSize().padding(JadxTheme.spacing.lg),
                        contentAlignment = Alignment.TopEnd,
                    ) {
                        SettingsPanel(
                            dark = dark,
                            onToggleTheme = onToggleTheme,
                            flattenPackages = ui.tree.flattenPackages,
                            onFlattenChange = state::setFlatten,
                            defaultView = ui.preferredView,
                            onDefaultViewChange = state::setPreferredView,
                            onClose = { showSettings = false },
                        )
                    }
                }
            }

            val ready = session as? SessionState.Ready
            WorkbenchStatusBar(
                status = if (ready != null) "decompiled" else ui.status,
                busy = ui.busy,
                statusColor = if (ready != null && !ui.busy) JadxTheme.colors.success else null,
            ) {
                ui.tabs.active?.view?.let { StatusReadout(viewLabel(it).uppercase()) }
                ready?.let { StatusReadout("${it.classCount} classes") }
                StatusReadout("UTF-8")
            }
        }
    }
}

@Composable
private fun WorkbenchToolbar(
    projectLabel: String,
    canBack: Boolean,
    canForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onOpen: () -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
    hasManifest: Boolean,
    onOpenManifest: () -> Unit,
    onJumpMainActivity: () -> Unit,
    onJumpApplication: () -> Unit,
    searchActive: Boolean,
    onToggleSearch: () -> Unit,
    settingsActive: Boolean,
    onToggleSettings: () -> Unit,
    deobfuscated: Boolean,
    dark: Boolean,
    onToggleTheme: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(JadxTheme.spacing.toolbarHeight)
            .background(scheme.surface)
            .padding(horizontal = JadxTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm),
    ) {
        BrandMark(size = JadxTheme.spacing.iconButtonSize)
        Text(projectLabel, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, fontFamily = MonoFontFamily)
        VDivider()
        ToolbarTextButton("Open", onClick = onOpen)
        // Save the active document's rendered text (P0#7). Disabled with no document open / no saver.
        ToolbarTextButton("Save", onClick = onSave, enabled = canSave)
        VDivider()
        ToolbarButton(onClick = onBack, enabled = canBack, square = true) { tint -> DirectionCaret(pointsLeft = true, tint = tint) }
        ToolbarButton(onClick = onForward, enabled = canForward, square = true) { tint -> DirectionCaret(pointsLeft = false, tint = tint) }
        VDivider()
        // jadx quick actions: open the manifest, jump to the launcher activity, jump to the Application
        // class (all read the manifest, so all are disabled when the container carries none).
        ToolbarButton(onClick = onOpenManifest, enabled = hasManifest, square = true) { tint -> ManifestGlyph(tint = tint) }
        ToolbarButton(onClick = onJumpMainActivity, enabled = hasManifest, square = true) { tint -> TargetGlyph(tint = tint) }
        ToolbarButton(onClick = onJumpApplication, enabled = hasManifest, square = true) { tint -> AppClassGlyph(tint = tint) }

        // Centered command/search box.
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(scheme.background)
                    .border(1.dp, if (searchActive) scheme.primary else scheme.outline, MaterialTheme.shapes.small)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggleSearch)
                    .padding(horizontal = JadxTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.md),
            ) {
                SearchGlyph(tint = scheme.onSurfaceVariant)
                Text(
                    "Search classes, methods, strings…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Kbd("⌘K")
            }
        }

        // Right cluster: deobfuscation state + theme toggle.
        if (deobfuscated) {
            Row(
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(scheme.surfaceContainerHighest)
                    .padding(horizontal = JadxTheme.spacing.lg, vertical = JadxTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm),
            ) {
                StatusDot(color = JadxTheme.colors.success)
                Text("Deobf", style = MaterialTheme.typography.labelMedium, color = scheme.onSurface, fontWeight = FontWeight.Medium)
            }
        }
        ToolbarTextButton(if (dark) "Light" else "Dark", onClick = onToggleTheme)
        ToolbarButton(onClick = onToggleSettings, selected = settingsActive, square = true) { tint -> GearGlyph(tint = tint) }
    }
}

@Composable
private fun EditorArea(
    state: WorkbenchState,
    ui: WorkbenchUiState,
    onSearchSelection: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (ui.tabs.isEmpty) {
            EmptyState(
                message = "No file open",
                detail = "Select a class or resource from the tree to view its source.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        EditorTabStrip(
            tabs = ui.tabs,
            onSelect = state::activateTab,
            onClose = state::closeTab,
            onTogglePin = state::togglePin,
            onToggleBookmark = state::toggleBookmark,
            onCloseOthers = state::closeOtherTabs,
            onCloseToLeft = state::closeTabsToLeft,
            onCloseToRight = state::closeTabsToRight,
            onCloseAll = state::closeAllTabs,
            onSelectInTree = state::selectTabInTree,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        val active = ui.tabs.active
        if (active != null) {
            BreadcrumbBar(
                segments = breadcrumbSegments(active.nodeId, active.kind),
                views = state.availableViews(active.nodeId),
                currentView = active.view,
                onSetView = state::setActiveView,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val doc = ui.activeDocument
            if (doc != null) {
                CodeViewer(
                    document = doc,
                    onNavigate = { state.openDocument(it) },
                    onCaretLine = { state.updateCaret(it) },
                    initialCaretLine = active?.caret?.coerceAtLeast(1) ?: 1,
                    scrollNonce = ui.codeNavNonce,
                    activeFindMatch = ui.find?.activeMatch,
                    onSelectionSeed = state::noteSelectionSeed,
                    onSearchSelection = onSearchSelection,
                    // "Save file" context-menu action — only offered when a saver is wired.
                    onSaveFile = if (state.hasSaver) state::saveActiveDocument else null,
                )
            } else {
                EmptyState(message = "Loading…", modifier = Modifier.fillMaxSize())
            }

            // In-editor Find bar (Ctrl+F): a floating toolbar over the active document, top-right.
            val find = ui.find
            if (find != null) {
                Box(
                    Modifier.fillMaxWidth().padding(JadxTheme.spacing.sm),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    FindBar(
                        state = find,
                        onQueryChange = state::setFindQuery,
                        onMatchCaseChange = state::setFindMatchCase,
                        onNext = state::findNext,
                        onPrev = state::findPrev,
                        onClose = state::closeFind,
                    )
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbBar(
    segments: List<BreadcrumbSegment>,
    views: List<CodeView>,
    currentView: CodeView,
    onSetView: (CodeView) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = JadxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(JadxTheme.spacing.breadcrumbHeight)
            .background(scheme.background)
            .padding(horizontal = JadxTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm),
    ) {
        segments.forEachIndexed { index, seg ->
            if (index > 0) Text("›", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceFaint)
            Text(
                seg.text,
                fontFamily = MonoFontFamily,
                style = MaterialTheme.typography.bodySmall,
                color = when (seg.emphasis) {
                    CrumbEmphasis.TYPE -> colors.cyan
                    CrumbEmphasis.MEMBER -> scheme.primary
                    CrumbEmphasis.PLAIN -> scheme.onSurfaceVariant
                },
            )
        }
        Box(Modifier.weight(1f))
        if (views.size > 1) {
            SegmentedToggle(
                options = views.map { viewLabel(it) },
                selectedIndex = views.indexOf(currentView).coerceAtLeast(0),
                onSelect = { onSetView(views[it]) },
            )
        }
    }
}

@Composable
private fun StartPage(
    loading: Boolean,
    message: String?,
    onOpen: () -> Unit,
    dragActive: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = JadxTheme.colors
    // The zone lights up while an OS drag hovers the window (driven by the shell via FileDropController).
    val armed = dragActive && !loading
    Box(Modifier.fillMaxSize().background(scheme.background), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(JadxTheme.spacing.md),
            modifier = Modifier.widthIn(max = 560.dp).padding(JadxTheme.spacing.xxxl),
        ) {
            BrandMark(size = 46.dp)
            Box(Modifier.height(JadxTheme.spacing.xs))
            Text("Open a file to begin", style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
            Text(
                "Decompile Android and Java binaries to readable source.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Box(Modifier.height(JadxTheme.spacing.sm))
            // Drop zone: dropping a file anywhere in the window opens it (wired per-platform in the
            // shells via FileDropController); clicking still browses. The border/label reflect an
            // active drag when [armed].
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(if (armed) scheme.surfaceContainerHighest else scheme.surface)
                    .border(1.5.dp, if (armed) scheme.primary else scheme.outline, MaterialTheme.shapes.large)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !loading, onClick = onOpen)
                    .padding(JadxTheme.spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(JadxTheme.spacing.md),
            ) {
                Text(
                    when {
                        loading -> "Opening…"
                        armed -> "Release to open"
                        else -> "Drop a file here"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (armed) scheme.primary else scheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Text("or click to browse", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceFaint)
                Row(horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm)) {
                    for (ext in listOf(".apk", ".dex", ".jar", ".aab", ".aar")) FormatChip(ext)
                }
            }
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceFaint)
            }
        }
    }
}

@Composable
private fun FormatChip(label: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(scheme.surfaceContainerHighest)
            .padding(horizontal = JadxTheme.spacing.md, vertical = JadxTheme.spacing.xs),
    ) {
        Text(label, fontFamily = MonoFontFamily, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
}

internal fun viewLabel(view: CodeView): String = when (view) {
    CodeView.JAVA -> "Java"
    CodeView.KOTLIN -> "Kotlin"
    CodeView.SMALI -> "Smali"
}
