package com.jadxmp.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.NodeKind
import com.jadxmp.ui.client.SearchQuery
import com.jadxmp.ui.client.SearchResult
import com.jadxmp.ui.client.SearchResults
import com.jadxmp.ui.client.SearchScope
import com.jadxmp.ui.component.Chip
import com.jadxmp.ui.component.NodeKindBadge
import com.jadxmp.ui.component.SearchField
import com.jadxmp.ui.component.ToolbarTextButton
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.MonoFontFamily

/**
 * Search scope shown in the panel. Only [CLASS_NAMES] and [CODE] are backed by a real capability
 * today; the rest are designed and disabled ("soon") because they need `core:api` surface that does
 * not exist yet (member enumeration, a resource index). See the panel's disabled chips.
 */
private enum class PanelScope(val label: String, val enabled: Boolean) {
    CLASS_NAMES("Class names", enabled = true),
    CODE("Code", enabled = true),
    METHODS("Methods", enabled = true),
    FIELDS("Fields", enabled = true),
    // Resources content search needs a separate engine index (see docs/UI-DESIGN.md §4.5) — not faked.
    RESOURCES("Resources", enabled = false),
}

/**
 * A floating search panel (mockup 1e): a query field with a scope selector, Aa / .* toggles, and a
 * results list. Wired scopes:
 *
 * - **Class names** — the instant name filter ([onRunNames]); results are class nodes.
 * - **Code** — a streaming content scan of every class's decompiled source ([onRunCode]).
 * - **Methods / Fields** — a streaming member scan ([onRunMembers]).
 *
 * On top of the streaming/scope/cancel model this panel adds jadx-gui-style depth, all UI-side:
 *
 * - **Match highlighting** — each row paints the matched substring(s) amber via [matchSpans], honouring
 *   the live Aa / .* toggles, so a hit shows *why* it matched (feature 1).
 * - **Pagination** — a scope reveals [RESULT_PAGE_SIZE] rows at a time behind a "Show more" affordance and
 *   an honest "showing N of M" (or "M+" when the scan hit its hard cap) readout, so nothing is ever
 *   silently dropped (feature 2, rule 4).
 * - **Keyboard flow** — Up/Down move a highlighted selection, Enter opens it; Esc is handled by the
 *   workbench key layer, so it is deliberately *not* rebound here (feature 5).
 *
 * Selecting a result (click or Enter) reuses the existing jump path, which scrolls + highlights the target
 * line in the code viewer (feature 6). Resources stays disabled until the engine backs it.
 */
@Composable
fun SearchPanel(
    results: SearchResults?,
    codeSearch: CodeSearchUiState?,
    memberSearch: MemberSearchUiState?,
    onRunNames: (SearchQuery) -> Unit,
    onRunCode: (text: String, ignoreCase: Boolean, useRegex: Boolean) -> Unit,
    onRunMembers: (text: String, fields: Boolean, ignoreCase: Boolean, useRegex: Boolean) -> Unit,
    onCancelCode: () -> Unit,
    onCancelMembers: () -> Unit,
    onOpenNode: (NodeId) -> Unit,
    onOpenCode: (CodeMatch) -> Unit,
    onOpenMember: (MemberMatch) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Prefill the query (e.g. the code area's "Search selection"); blank for a plain toolbar open. */
    initialQuery: String = "",
) {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(initialQuery) }
    var regex by remember { mutableStateOf(false) }
    var ignoreCase by remember { mutableStateOf(true) }
    var scope by remember { mutableStateOf(PanelScope.CLASS_NAMES) }
    val queryFocus = remember { FocusRequester() }

    // The query the currently-shown results actually correspond to (the scan/search records it), which can
    // briefly differ from the live [text] mid-debounce. Selection + reveal window are keyed on it so they
    // reset when the result *set* changes, not on every streamed batch of the same query.
    val resultQuery: String = when (scope) {
        PanelScope.CODE -> codeSearch?.query
        PanelScope.METHODS, PanelScope.FIELDS -> memberSearch?.query
        PanelScope.CLASS_NAMES -> results?.query?.text
        PanelScope.RESOURCES -> null
    } ?: text

    // Number of results the active scope currently has in hand — the selection's clamp bound.
    val resultCount: Int = when (scope) {
        PanelScope.CODE -> codeSearch?.matches?.size ?: 0
        PanelScope.METHODS, PanelScope.FIELDS -> memberSearch?.matches?.size ?: 0
        PanelScope.CLASS_NAMES -> results?.matches?.size ?: 0
        PanelScope.RESOURCES -> 0
    }

    // Keyboard selection + reveal window, reset whenever the scope or the settled query changes.
    var selectedIndex by remember(scope, resultQuery) { mutableIntStateOf(0) }
    var visibleCount by remember(scope, resultQuery) { mutableIntStateOf(RESULT_PAGE_SIZE) }

    fun run() {
        when (scope) {
            PanelScope.CLASS_NAMES ->
                onRunNames(SearchQuery(text, setOf(SearchScope.CLASS), useRegex = regex, ignoreCase = ignoreCase))
            PanelScope.CODE -> onRunCode(text, ignoreCase, regex)
            PanelScope.METHODS -> onRunMembers(text, false, ignoreCase, regex)
            PanelScope.FIELDS -> onRunMembers(text, true, ignoreCase, regex)
            PanelScope.RESOURCES -> Unit
        }
    }

    fun selectScope(next: PanelScope) {
        if (next == scope || !next.enabled) return
        scope = next
        if (text.isNotBlank()) run()
    }

    // Open the currently-highlighted result via the scope's real jump callback (scrolls + highlights the
    // target — feature 6). A no-op when there is nothing to open, so Enter on an empty result set is inert.
    fun openSelected() {
        if (resultCount == 0) return
        val i = selectedIndex.coerceIn(0, resultCount - 1)
        when (scope) {
            PanelScope.CODE -> codeSearch?.matches?.getOrNull(i)?.let(onOpenCode)
            PanelScope.METHODS, PanelScope.FIELDS -> memberSearch?.matches?.getOrNull(i)?.let(onOpenMember)
            PanelScope.CLASS_NAMES -> results?.matches?.getOrNull(i)?.let { onOpenNode(it.nodeId) }
            PanelScope.RESOURCES -> Unit
        }
    }

    // Move the highlighted row, clamped to the result set, revealing enough of the window to keep it shown.
    fun moveSelection(delta: Int) {
        if (resultCount == 0) return
        val next = (selectedIndex + delta).coerceIn(0, resultCount - 1)
        selectedIndex = next
        if (next >= visibleCount) visibleCount = next + 1
    }

    // On open: focus the query field, and if it arrived pre-seeded ("Search selection") run immediately.
    LaunchedEffect(Unit) {
        runCatching { queryFocus.requestFocus() }
        if (text.isNotBlank()) run()
    }

    Column(
        modifier
            .width(460.dp)
            .clip(MaterialTheme.shapes.large)
            .background(scheme.surfaceVariant)
            .border(1.dp, scheme.outline, MaterialTheme.shapes.large)
            .padding(JadxTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(JadxTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xs)) {
            Text("Search", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.weight(1f))
            ResultCount(scope, results, codeSearch, memberSearch)
            ToolbarTextButton("Aa", onClick = { ignoreCase = !ignoreCase; if (text.isNotBlank()) run() }, selected = !ignoreCase)
            ToolbarTextButton(".*", onClick = { regex = !regex; if (text.isNotBlank()) run() }, selected = regex)
            ToolbarTextButton("Close", onClick = onClose)
        }
        SearchField(
            value = text,
            onValueChange = { text = it; run() },
            modifier = Modifier.focusRequester(queryFocus),
            placeholder = when (scope) {
                PanelScope.CODE -> "Search decompiled code…"
                PanelScope.METHODS -> "Search method names…"
                PanelScope.FIELDS -> "Search field names…"
                else -> "Search class names…"
            },
            // Enter opens the highlighted result; arrows move the highlight. (Esc = workbench key layer.)
            onSubmit = ::openSelected,
            onMoveDown = { moveSelection(1) },
            onMoveUp = { moveSelection(-1) },
        )
        // Scope selector (single-select). Disabled scopes are inert until the engine backs them.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm),
        ) {
            for (option in PanelScope.entries) {
                Chip(
                    label = if (option.enabled) option.label else "${option.label} · soon",
                    selected = scope == option,
                    enabled = option.enabled,
                    onClick = { selectScope(option) },
                )
            }
        }
        val onSelect: (Int) -> Unit = { selectedIndex = it }
        val onShowMore: () -> Unit = { visibleCount += RESULT_PAGE_SIZE }
        when (scope) {
            PanelScope.CODE -> CodeResults(
                text = text,
                state = codeSearch,
                ignoreCase = ignoreCase,
                useRegex = regex,
                selectedIndex = selectedIndex,
                visibleCount = visibleCount,
                onSelect = onSelect,
                onShowMore = onShowMore,
                onCancel = onCancelCode,
                onOpen = onOpenCode,
            )
            PanelScope.METHODS, PanelScope.FIELDS -> MemberResults(
                text = text,
                fields = scope == PanelScope.FIELDS,
                state = memberSearch,
                ignoreCase = ignoreCase,
                useRegex = regex,
                selectedIndex = selectedIndex,
                visibleCount = visibleCount,
                onSelect = onSelect,
                onShowMore = onShowMore,
                onCancel = onCancelMembers,
                onOpen = onOpenMember,
            )
            else -> NameResults(
                text = text,
                results = results,
                selectedIndex = selectedIndex,
                visibleCount = visibleCount,
                onSelect = onSelect,
                onShowMore = onShowMore,
                onOpen = onOpenNode,
            )
        }
    }
}

@Composable
private fun ResultCount(
    scope: PanelScope,
    results: SearchResults?,
    codeSearch: CodeSearchUiState?,
    memberSearch: MemberSearchUiState?,
) {
    val scheme = MaterialTheme.colorScheme
    val label = when (scope) {
        PanelScope.CODE -> codeSearch?.let { "${it.matches.size} hits" }
        PanelScope.METHODS, PanelScope.FIELDS -> memberSearch?.let { "${it.matches.size} hits" }
        PanelScope.CLASS_NAMES -> results?.let { "${it.matches.size} results" }
        else -> null
    } ?: return
    Text(label, fontFamily = MonoFontFamily, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
}

// ── Class-name results (instant) ──────────────────────────────────────────────

@Composable
private fun NameResults(
    text: String,
    results: SearchResults?,
    selectedIndex: Int,
    visibleCount: Int,
    onSelect: (Int) -> Unit,
    onShowMore: () -> Unit,
    onOpen: (NodeId) -> Unit,
) {
    val matches = results?.matches.orEmpty()
    if (matches.isEmpty()) {
        Hint(if (text.isBlank()) "Type to search class names." else "No matches.")
        return
    }
    // The instant name search returns the whole set at once (not streamed, not capped) → capped = false.
    val page = resultPage(matches.size, visibleCount, capped = false)
    val query = results?.query
    val listState = rememberLazyListState()
    // Keep the highlighted row on screen as the user arrows through it.
    LaunchedEffect(selectedIndex) { runCatching { listState.scrollToItem(selectedIndex.coerceIn(0, maxOf(0, page.shown - 1))) } }
    PageLabel(page)
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        itemsIndexed(matches.take(page.shown), key = { _, it -> it.nodeId.value }) { index, result ->
            SearchResultRow(
                result = result,
                selected = index == selectedIndex,
                query = query,
                onOpen = { onSelect(index); onOpen(result.nodeId) },
            )
        }
    }
    if (page.hasMore) ShowMore(onShowMore)
}

@Composable
private fun SearchResultRow(result: SearchResult, selected: Boolean, query: SearchQuery?, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Highlight the class name (and its package subtitle) with whatever query produced this row.
    val q = query?.text.orEmpty()
    val ic = query?.ignoreCase ?: true
    val rx = query?.useRegex ?: false
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(rowBackground(selected, hovered))
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(vertical = JadxTheme.spacing.sm, horizontal = JadxTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm),
    ) {
        NodeKindBadge(result.kind)
        Text(highlighted(result.title, q, ic, rx), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            highlighted(result.subtitle, q, ic, rx),
            fontFamily = MonoFontFamily,
            style = MaterialTheme.typography.bodySmall,
            color = JadxTheme.colors.onSurfaceFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Code-content results (streaming scan) ─────────────────────────────────────

@Composable
private fun CodeResults(
    text: String,
    state: CodeSearchUiState?,
    ignoreCase: Boolean,
    useRegex: Boolean,
    selectedIndex: Int,
    visibleCount: Int,
    onSelect: (Int) -> Unit,
    onShowMore: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (CodeMatch) -> Unit,
) {
    if (state == null) {
        Hint(if (text.isBlank()) "Type to search decompiled code." else "Preparing scan…")
        return
    }
    ScanProgressRow(state.scanned, state.total, state.fraction, state.running, onCancel)
    if (state.failed > 0) {
        Hint("${state.failed} class${if (state.failed == 1) "" else "es"} skipped (could not decompile).")
    }
    if (state.matches.isEmpty()) {
        Hint(if (state.running) "Scanning…" else "No code matches.")
        return
    }
    val page = resultPage(state.matches.size, visibleCount, state.truncated)
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) { runCatching { listState.scrollToItem(selectedIndex.coerceIn(0, maxOf(0, page.shown - 1))) } }
    PageLabel(page)
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
        itemsIndexed(state.matches.take(page.shown), key = { _, it -> "${it.nodeId.value}#${it.line}" }) { index, match ->
            CodeMatchRow(
                match = match,
                selected = index == selectedIndex,
                query = state.query,
                ignoreCase = ignoreCase,
                useRegex = useRegex,
                onOpen = { onSelect(index); onOpen(match) },
            )
        }
    }
    if (page.hasMore) {
        ShowMore(onShowMore)
    } else if (state.truncated) {
        // Every collected match is on screen, but the scan itself stopped at its cap — say so, never a
        // silent truncation. (Raising the cap would need an engine re-scan, out of this panel's reach.)
        Hint("Reached the ${state.matches.size}-match cap. Refine your query to narrow it.")
    }
}

@Composable
private fun CodeMatchRow(
    match: CodeMatch,
    selected: Boolean,
    query: String,
    ignoreCase: Boolean,
    useRegex: Boolean,
    onOpen: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(rowBackground(selected, hovered))
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(vertical = JadxTheme.spacing.sm, horizontal = JadxTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm),
    ) {
        NodeKindBadge(NodeKind.CLASS)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xxs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(match.simpleName, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    ":${match.line}",
                    fontFamily = MonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = JadxTheme.colors.onSurfaceFaint,
                )
            }
            Text(
                highlighted(match.snippet, query, ignoreCase, useRegex),
                fontFamily = MonoFontFamily,
                style = MaterialTheme.typography.bodySmall,
                color = JadxTheme.colors.onSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Member results (streaming Methods/Fields scan) ────────────────────────────

@Composable
private fun MemberResults(
    text: String,
    fields: Boolean,
    state: MemberSearchUiState?,
    ignoreCase: Boolean,
    useRegex: Boolean,
    selectedIndex: Int,
    visibleCount: Int,
    onSelect: (Int) -> Unit,
    onShowMore: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (MemberMatch) -> Unit,
) {
    val noun = if (fields) "fields" else "methods"
    if (state == null) {
        Hint(if (text.isBlank()) "Type to search $noun." else "Preparing scan…")
        return
    }
    ScanProgressRow(state.scanned, state.total, state.fraction, state.running, onCancel)
    if (state.failed > 0) {
        Hint("${state.failed} class${if (state.failed == 1) "" else "es"} skipped (members unavailable).")
    }
    if (state.matches.isEmpty()) {
        Hint(if (state.running) "Scanning…" else "No $noun match.")
        return
    }
    val page = resultPage(state.matches.size, visibleCount, state.truncated)
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) { runCatching { listState.scrollToItem(selectedIndex.coerceIn(0, maxOf(0, page.shown - 1))) } }
    PageLabel(page)
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
        itemsIndexed(state.matches.take(page.shown), key = { _, it -> it.nodeId.value }) { index, match ->
            MemberMatchRow(
                match = match,
                fields = fields,
                selected = index == selectedIndex,
                query = state.query,
                ignoreCase = ignoreCase,
                useRegex = useRegex,
                onOpen = { onSelect(index); onOpen(match) },
            )
        }
    }
    if (page.hasMore) {
        ShowMore(onShowMore)
    } else if (state.truncated) {
        Hint("Reached the ${state.matches.size}-match cap. Refine your query to narrow it.")
    }
}

@Composable
private fun MemberMatchRow(
    match: MemberMatch,
    fields: Boolean,
    selected: Boolean,
    query: String,
    ignoreCase: Boolean,
    useRegex: Boolean,
    onOpen: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(rowBackground(selected, hovered))
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(vertical = JadxTheme.spacing.sm, horizontal = JadxTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm),
    ) {
        NodeKindBadge(if (fields) NodeKind.FIELD else NodeKind.METHOD)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xxs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(highlighted(match.displayName, query, ignoreCase, useRegex), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    match.ownerSimpleName,
                    fontFamily = MonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = JadxTheme.colors.onSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                highlighted(match.signature, query, ignoreCase, useRegex),
                fontFamily = MonoFontFamily,
                style = MaterialTheme.typography.bodySmall,
                color = JadxTheme.colors.onSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Shared result-list chrome ─────────────────────────────────────────────────

/** The "scanned N/total" readout, a thin determinate track, and Cancel while a scan runs. */
@Composable
private fun ScanProgressRow(scanned: Int, total: Int, fraction: Float, running: Boolean, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm)) {
        Text(
            "scanned $scanned/$total",
            fontFamily = MonoFontFamily,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        ProgressTrack(fraction, Modifier.weight(1f))
        if (running) ToolbarTextButton("Cancel", onClick = onCancel)
    }
}

/** "showing N of M" (or "M+" when the scan was capped) — the honest, never-silent result count. */
@Composable
private fun PageLabel(page: ResultPage) {
    Text(
        page.label,
        fontFamily = MonoFontFamily,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = JadxTheme.spacing.xxs),
    )
}

/** A full-width "Show more" affordance revealing the next window of already-collected results. */
@Composable
private fun ShowMore(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ToolbarTextButton("Show more", onClick = onClick)
    }
}

/** Row wash: the selection tint wins over hover so an arrowed-to row is unambiguous. */
@Composable
private fun rowBackground(selected: Boolean, hovered: Boolean): Color = when {
    selected -> JadxTheme.colors.treeSelectionBackground
    hovered -> JadxTheme.colors.treeHoverBackground
    else -> Color.Transparent
}

/**
 * [text] as an [AnnotatedString] with every [matchSpans] hit washed amber (the same accent the in-editor
 * Find bar uses) and bolded. A blank query / no match / invalid regex falls through to plain text, so this
 * is safe to call unconditionally from every row (no conditional composable calls). Memoised on its inputs.
 */
@Composable
private fun highlighted(text: String, query: String, ignoreCase: Boolean, useRegex: Boolean): AnnotatedString {
    val hl = JadxTheme.colors.warning.copy(alpha = 0.38f)
    return remember(text, query, ignoreCase, useRegex, hl) {
        val spans = matchSpans(text, query, ignoreCase, useRegex)
        if (spans.isEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text)
                for (s in spans) {
                    val a = s.start.coerceIn(0, text.length)
                    val b = s.end.coerceIn(a, text.length)
                    if (b > a) addStyle(SpanStyle(background = hl, fontWeight = FontWeight.Medium), a, b)
                }
            }
        }
    }
}

@Composable
private fun ProgressTrack(fraction: Float, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .height(4.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(scheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(scheme.primary),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = JadxTheme.spacing.xs),
    )
}
