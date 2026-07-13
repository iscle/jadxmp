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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * results list. Two scopes are wired:
 *
 * - **Class names** — the instant name filter ([onRunNames]); results are class nodes.
 * - **Code** — a streaming content scan of every class's decompiled source ([onRunCode]); results
 *   arrive incrementally with a live "scanned N/total" progress readout and a Cancel button, are
 *   capped (with a visible "capped" notice), and each hit jumps to the matching line ([onOpenCode]).
 *
 * Methods/Fields/Resources are shown disabled until the engine exposes the indexes they need.
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
) {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var ignoreCase by remember { mutableStateOf(true) }
    var scope by remember { mutableStateOf(PanelScope.CLASS_NAMES) }

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
            placeholder = when (scope) {
                PanelScope.CODE -> "Search decompiled code…"
                PanelScope.METHODS -> "Search method names…"
                PanelScope.FIELDS -> "Search field names…"
                else -> "Search class names…"
            },
            onSubmit = { run() },
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
        when (scope) {
            PanelScope.CODE -> CodeResults(text, codeSearch, onCancelCode, onOpenCode)
            PanelScope.METHODS, PanelScope.FIELDS ->
                MemberResults(text, scope == PanelScope.FIELDS, memberSearch, onCancelMembers, onOpenMember)
            else -> NameResults(text, results, onOpenNode)
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
private fun NameResults(text: String, results: SearchResults?, onOpen: (NodeId) -> Unit) {
    val matches = results?.matches.orEmpty()
    if (matches.isEmpty()) {
        Hint(if (text.isBlank()) "Type to search class names." else "No matches.")
        return
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
        items(matches, key = { it.nodeId.value }) { result ->
            SearchResultRow(result, onOpen = { onOpen(result.nodeId) })
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResult, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (hovered) JadxTheme.colors.treeHoverBackground else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(vertical = JadxTheme.spacing.sm, horizontal = JadxTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm),
    ) {
        NodeKindBadge(result.kind)
        Text(result.title, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            result.subtitle,
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
    onCancel: () -> Unit,
    onOpen: (CodeMatch) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    if (state == null) {
        Hint(if (text.isBlank()) "Type to search decompiled code." else "Preparing scan…")
        return
    }
    // Progress row: "scanned N/total", a thin determinate track, and Cancel while running.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm)) {
        Text(
            "scanned ${state.scanned}/${state.total}",
            fontFamily = MonoFontFamily,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        ProgressTrack(state.fraction, Modifier.weight(1f))
        if (state.running) ToolbarTextButton("Cancel", onClick = onCancel)
    }
    if (state.truncated) {
        Hint("Showing the first ${state.matches.size} matches (capped). Refine your query to narrow it.")
    }
    if (state.failed > 0) {
        Hint("${state.failed} class${if (state.failed == 1) "" else "es"} skipped (could not decompile).")
    }
    if (state.matches.isEmpty()) {
        Hint(if (state.running) "Scanning…" else "No code matches.")
        return
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        items(state.matches, key = { "${it.nodeId.value}#${it.line}" }) { match ->
            CodeMatchRow(match, onOpen = { onOpen(match) })
        }
    }
}

@Composable
private fun CodeMatchRow(match: CodeMatch, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (hovered) JadxTheme.colors.treeHoverBackground else Color.Transparent)
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
                match.snippet,
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
    onCancel: () -> Unit,
    onOpen: (MemberMatch) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val noun = if (fields) "fields" else "methods"
    if (state == null) {
        Hint(if (text.isBlank()) "Type to search $noun." else "Preparing scan…")
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm)) {
        Text(
            "scanned ${state.scanned}/${state.total}",
            fontFamily = MonoFontFamily,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        ProgressTrack(state.fraction, Modifier.weight(1f))
        if (state.running) ToolbarTextButton("Cancel", onClick = onCancel)
    }
    if (state.truncated) {
        Hint("Showing the first ${state.matches.size} matches (capped). Refine your query to narrow it.")
    }
    if (state.failed > 0) {
        Hint("${state.failed} class${if (state.failed == 1) "" else "es"} skipped (members unavailable).")
    }
    if (state.matches.isEmpty()) {
        Hint(if (state.running) "Scanning…" else "No $noun match.")
        return
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        items(state.matches, key = { it.nodeId.value }) { match ->
            MemberMatchRow(match, fields, onOpen = { onOpen(match) })
        }
    }
}

@Composable
private fun MemberMatchRow(match: MemberMatch, fields: Boolean, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (hovered) JadxTheme.colors.treeHoverBackground else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(vertical = JadxTheme.spacing.sm, horizontal = JadxTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm),
    ) {
        NodeKindBadge(if (fields) NodeKind.FIELD else NodeKind.METHOD)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xxs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(match.displayName, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                match.signature,
                fontFamily = MonoFontFamily,
                style = MaterialTheme.typography.bodySmall,
                color = JadxTheme.colors.onSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
