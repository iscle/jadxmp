package com.jadxmp.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jadxmp.ui.client.UsageResults
import com.jadxmp.ui.client.UsageSiteRow
import com.jadxmp.ui.component.NodeKindBadge
import com.jadxmp.ui.component.ToolbarTextButton
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.MonoFontFamily

/**
 * The "Find usages" results panel (jadx-gui's Find Usages), mirroring [SearchPanel]'s floating container
 * and result-row idioms. Its three states track [UsagesUiState]:
 *
 *  - **loading** ([UsagesUiState.running]) — the first query for a format decompiles the whole app to build
 *    the inverse index, so a spinner + "Finding usages…" stands in until it lands (never a frozen blank).
 *  - **results** — a scrollable list of referring sites (class + enclosing member + line); clicking one
 *    reuses the go-to-definition jump ([WorkbenchState.openUsageSite]) to open the class and center the line.
 *  - **empty** — a resolved symbol nothing references shows "No usages found"; a token that did not resolve
 *    to a symbol at all (`results == null` once settled) says so honestly (rule 4).
 *
 * Presentational only: it renders the already-resolved [UsageResults] and raises intents; all engine work
 * (resolution + the whole-program inversion) happened in the client. Compose-Multiplatform only — wasm-safe.
 */
@Composable
fun UsagesPanel(
    state: UsagesUiState,
    onOpenSite: (UsageSiteRow) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val results = state.results
    Column(
        modifier
            .width(460.dp)
            .clip(MaterialTheme.shapes.large)
            .background(scheme.surfaceVariant)
            .border(1.dp, scheme.outline, MaterialTheme.shapes.large)
            .padding(JadxTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(JadxTheme.spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xs),
        ) {
            Text("Usages", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.weight(1f))
            if (results != null) {
                Text(
                    "${results.sites.size} ${if (results.sites.size == 1) "usage" else "usages"}",
                    fontFamily = MonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            ToolbarTextButton("Close", onClick = onClose)
        }

        // The resolved symbol (its badge + label), or the clicked token while we resolve / if unresolved.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm),
        ) {
            if (results != null) NodeKindBadge(results.kind)
            Text(
                text = results?.symbol ?: state.token,
                fontFamily = MonoFontFamily,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when {
            state.running -> LoadingRow()
            results == null -> Hint("Couldn't resolve a symbol for \"${state.token}\".")
            results.sites.isEmpty() -> Hint("No usages found.")
            else -> UsageList(results.sites, onOpenSite)
        }
    }
}

@Composable
private fun UsageList(sites: List<UsageSiteRow>, onOpen: (UsageSiteRow) -> Unit) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xxs),
    ) {
        // Two references can share a class+line, so the row index makes the key unique.
        itemsIndexed(sites, key = { index, s -> "${s.classNode.value}#${s.line}#$index" }) { _, site ->
            UsageRow(site, onOpen = { onOpen(site) })
        }
    }
}

@Composable
private fun UsageRow(site: UsageSiteRow, onOpen: () -> Unit) {
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
        // The referring site's own kind (method / field / class scope), already resolved by the client —
        // a method usage must show a method badge, not a blanket class badge.
        NodeKindBadge(site.kind)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xxs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(site.classLabel, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    ":${site.line}",
                    fontFamily = MonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = JadxTheme.colors.onSurfaceFaint,
                )
            }
            Text(
                // The enclosing member signature, or an honest marker for a class-scope use (no method body).
                site.memberLabel ?: "class scope",
                fontFamily = MonoFontFamily,
                style = MaterialTheme.typography.bodySmall,
                color = JadxTheme.colors.onSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The loading state: a small spinner + an honest note that the first query decompiles the whole app. */
@Composable
private fun LoadingRow() {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.sm),
        modifier = Modifier.padding(vertical = JadxTheme.spacing.sm),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = scheme.primary)
        Text(
            "Finding usages… (the first query decompiles the whole app)",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
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
