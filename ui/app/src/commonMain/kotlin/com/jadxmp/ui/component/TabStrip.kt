package com.jadxmp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
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
 *
 * Ergonomics (jadx-gui parity P1#7 / P1#9 / P2#14): right-click opens a per-tab [ContextMenu];
 * middle-click closes a tab; a mouse-wheel over the strip cycles the active tab; bookmarked tabs show a
 * small ribbon badge.
 */
@Composable
fun EditorTabStrip(
    tabs: TabsState,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onTogglePin: (Int) -> Unit,
    onToggleBookmark: (Int) -> Unit,
    onCloseOthers: (Int) -> Unit,
    onCloseToLeft: (Int) -> Unit,
    onCloseToRight: (Int) -> Unit,
    onCloseAll: () -> Unit,
    onSelectInTree: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf<TabContextMenu?>(null) }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // rememberUpdatedState so the never-restarting wheel handler always reads the live tab model.
    val currentTabs by rememberUpdatedState(tabs)
    val currentOnSelect by rememberUpdatedState(onSelect)

    Box(
        modifier
            .fillMaxWidth()
            .height(JadxTheme.spacing.tabStripHeight)
            .background(scheme.surface)
            .onGloballyPositioned { rootCoords = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Initial pass so a wheel notch cycles tabs BEFORE horizontalScroll pans the strip.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Scroll) continue
                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        val count = currentTabs.tabs.size
                        if (dy != 0f && count > 1) {
                            val next = (currentTabs.activeIndex + if (dy > 0f) 1 else -1).mod(count)
                            currentOnSelect(next)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxSize()
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
                    onMiddleClick = { onClose(index) },
                    onContext = { anchor -> menu = TabContextMenu(index, anchor) },
                    rootCoords = { rootCoords },
                )
                Spacer(Modifier.width(2.dp))
            }
        }

        // Right-click tab menu (P1#7), anchored under the cursor in the strip's coordinate space so it
        // lands correctly even when the strip is horizontally scrolled. Every action is pure UI/state.
        val m = menu
        val tab = m?.let { tabs.tabs.getOrNull(it.index) }
        if (m != null && tab != null) {
            val lastIndex = tabs.tabs.lastIndex
            val multiple = tabs.tabs.size > 1
            ContextMenu(
                expanded = true,
                offset = with(density) { DpOffset(m.anchor.x.toDp(), m.anchor.y.toDp()) },
                onDismiss = { menu = null },
                items = listOf(
                    ContextMenuItem("Copy name", enabled = tab.title.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(tab.title))
                    },
                    ContextMenuItem(if (tab.pinned) "Unpin" else "Pin") { onTogglePin(m.index) },
                    ContextMenuItem(if (tab.bookmarked) "Remove bookmark" else "Bookmark") { onToggleBookmark(m.index) },
                    ContextMenuItem("Select in tree") { onSelectInTree(m.index) },
                    ContextMenuItem("Close") { onClose(m.index) },
                    ContextMenuItem("Close others", enabled = multiple) { onCloseOthers(m.index) },
                    ContextMenuItem("Close to the left", enabled = m.index > 0) { onCloseToLeft(m.index) },
                    ContextMenuItem("Close to the right", enabled = m.index < lastIndex) { onCloseToRight(m.index) },
                    ContextMenuItem("Close all", enabled = multiple) { onCloseAll() },
                ),
            )
        }
    }
}

/** A pending tab context menu: which tab index it targets and where (strip-space) to anchor it. */
private data class TabContextMenu(val index: Int, val anchor: Offset)

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
    onMiddleClick: () -> Unit,
    onContext: (Offset) -> Unit,
    rootCoords: () -> LayoutCoordinates?,
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
    // Secondary (right) and tertiary (middle) button handling. Primary taps fall through to the row's
    // clickable (select) and the close/pin hit-boxes untouched — those buttons ignore non-primary presses.
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentRoot by rememberUpdatedState(rootCoords)
    val currentOnContext by rememberUpdatedState(onContext)
    val currentOnMiddle by rememberUpdatedState(onMiddleClick)
    Box(
        Modifier
            .onGloballyPositioned { coords = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        when {
                            event.buttons.isSecondaryPressed -> {
                                val local = event.changes.first().position
                                val root = currentRoot()
                                val tc = coords
                                val anchor = if (root != null && tc != null) root.localPositionOf(tc, local) else local
                                currentOnContext(anchor)
                                event.changes.forEach { it.consume() }
                            }
                            event.buttons.isTertiaryPressed -> {
                                currentOnMiddle()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
    ) {
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
        // Bookmark badge (P1#9): a small ribbon tucked into the tab's top-start corner, over the rounded
        // edge and to the left of the view marker — visible whether or not the tab is active/hovered.
        if (tab.bookmarked) {
            BookmarkGlyph(
                tint = colors.warning,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp, top = 1.dp),
            )
        }
    }
}
