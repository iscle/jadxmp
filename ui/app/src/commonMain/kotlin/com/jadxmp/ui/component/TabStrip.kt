package com.jadxmp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.workbench.EditorTab
import com.jadxmp.ui.workbench.TabsState

/**
 * Editor tab strip (mockup "Tabbed Classic"). The active tab is a rounded-top card whose fill matches
 * the editor beneath it — the "connected card" metaphor — topped by a thin accent bar. Each tab
 * carries a small square "view type" marker (cyan Java, amber Smali/XML, violet Kotlin). Inactive
 * tabs are quiet text. Horizontally scrollable so many open tabs stay reachable.
 */
@Composable
fun EditorTabStrip(
    tabs: TabsState,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onTogglePin: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .height(JadxTheme.spacing.tabStripHeight)
            .background(scheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = JadxTheme.spacing.md),
        verticalAlignment = Alignment.Bottom,
    ) {
        tabs.tabs.forEachIndexed { index, tab ->
            EditorTabView(
                tab = tab,
                active = index == tabs.activeIndex,
                onSelect = { onSelect(index) },
                onClose = { onClose(index) },
                onTogglePin = { onTogglePin(index) },
            )
            Spacer(Modifier.width(2.dp))
        }
    }
}

private fun viewColor(view: CodeView, colors: com.jadxmp.ui.theme.WorkbenchColors): Color = when (view) {
    CodeView.JAVA -> colors.cyan
    CodeView.SMALI -> colors.warning
    CodeView.KOTLIN -> colors.accentSecondary
}

@Composable
private fun EditorTabView(
    tab: EditorTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val colors = JadxTheme.colors
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val topRounded = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    val background = when {
        active -> scheme.background // merge with the editor surface beneath
        hovered -> colors.treeHoverBackground
        else -> Color.Transparent
    }
    Box {
        Row(
            Modifier
                .height(JadxTheme.spacing.tabHeight)
                .clip(topRounded)
                .background(background)
                .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
                .padding(start = JadxTheme.spacing.lg, end = JadxTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tab.pinned) {
                PinDot(tint = scheme.primary, modifier = Modifier.padding(end = JadxTheme.spacing.sm))
            } else {
                SquareDot(
                    tint = viewColor(tab.view, colors).copy(alpha = if (active) 1f else 0.5f),
                    modifier = Modifier.padding(end = JadxTheme.spacing.sm),
                )
            }
            Text(
                tab.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(JadxTheme.spacing.md))
            Box(
                Modifier
                    .size(JadxTheme.spacing.iconSize)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (hovered || active) CloseGlyph(tint = scheme.onSurfaceVariant)
            }
            Box(
                Modifier
                    .size(JadxTheme.spacing.iconSize)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onTogglePin,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if ((hovered || active) && !tab.pinned) {
                    PinDot(tint = scheme.onSurfaceVariant.copy(alpha = 0.55f))
                }
            }
        }
        if (active) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(colors.tabActiveIndicator),
            )
        }
    }
}
