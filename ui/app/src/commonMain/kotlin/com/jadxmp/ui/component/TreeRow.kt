package com.jadxmp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import com.jadxmp.ui.client.NodeKind
import com.jadxmp.ui.client.TreeNode
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.workbench.TreeRowItem
import com.jadxmp.ui.workbench.qualifiedNodeName

/**
 * A single tree row: indent + disclosure triangle + node marker + label (+ dimmed secondary).
 * Selection/hover are painted as a rounded, inset accent band (mockup style), not a full-bleed bar.
 * The chevron takes its own click (expand) so it does not also trigger row activation (open).
 */
@Composable
fun TreeRow(
    item: TreeRowItem,
    selected: Boolean,
    onActivate: () -> Unit,
    onToggle: () -> Unit,
    onExpandSubtree: () -> Unit = {},
    onCollapseSubtree: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val node = item.node
    val colors = JadxTheme.colors
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val band = when {
        selected -> colors.treeSelectionBackground
        hovered -> colors.treeHoverBackground
        else -> Color.Transparent
    }
    val isContainer = node.kind == NodeKind.PACKAGE || node.kind == NodeKind.DIRECTORY
    // Right-click context menu (jadx-gui parity): anchored under the cursor in this row's local space.
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    var menuAnchor by remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier
            .fillMaxWidth()
            .height(JadxTheme.spacing.treeRowHeight)
            .padding(horizontal = JadxTheme.spacing.md, vertical = JadxTheme.spacing.hairline)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        // Secondary (right) press opens the menu; primary presses fall through untouched to
                        // the row's clickable (open) and the chevron hit-box (expand).
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            menuAnchor = event.changes.first().position
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.small)
                .background(band)
                .clickable(interactionSource = interaction, indication = null, onClick = onActivate)
                .padding(start = JadxTheme.spacing.xs + JadxTheme.spacing.treeIndent * item.depth, end = JadxTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Disclosure area (fixed width so markers/labels align across depths).
            Box(Modifier.size(JadxTheme.spacing.lg), contentAlignment = Alignment.Center) {
                if (item.node.hasChildren) {
                    Box(
                        Modifier
                            .size(JadxTheme.spacing.lg)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
                        contentAlignment = Alignment.Center,
                    ) {
                        ExpandChevron(
                            expanded = item.expanded,
                            tint = if (item.expanded && isContainer) scheme.primary else colors.onSurfaceFaint,
                        )
                    }
                }
            }
            Spacer(Modifier.width(JadxTheme.spacing.xs))
            // Visibility overlay (class/member access) + a resource-type glyph keyed off the file name.
            NodeKindBadge(item.node.kind, visibility = item.node.visibility, fileName = item.node.label)
            Spacer(Modifier.width(JadxTheme.spacing.sm))
            Text(
                item.node.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary = item.node.secondary
            if (secondary != null) {
                Spacer(Modifier.width(JadxTheme.spacing.sm))
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val anchor = menuAnchor
        if (anchor != null) {
            ContextMenu(
                expanded = true,
                offset = with(density) { DpOffset(anchor.x.toDp(), anchor.y.toDp()) },
                onDismiss = { menuAnchor = null },
                items = treeRowMenuItems(
                    node = node,
                    isContainer = isContainer,
                    onOpen = onActivate,
                    onExpandSubtree = onExpandSubtree,
                    onCollapseSubtree = onCollapseSubtree,
                    onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
                ),
            )
        }
    }
}

/**
 * Context-menu actions for a tree [node], tailored to its kind (jadx-gui parity). A container (package/
 * directory) offers subtree expand/collapse + copy-name; a class/member/file offers open + copy-name +
 * copy-qualified-name, plus subtree expand/collapse when it has children. Actions that don't apply to a
 * kind simply aren't listed.
 */
private fun treeRowMenuItems(
    node: TreeNode,
    isContainer: Boolean,
    onOpen: () -> Unit,
    onExpandSubtree: () -> Unit,
    onCollapseSubtree: () -> Unit,
    onCopy: (String) -> Unit,
): List<ContextMenuItem> = buildList {
    if (isContainer) {
        if (node.hasChildren) {
            add(ContextMenuItem("Expand subtree") { onExpandSubtree() })
            add(ContextMenuItem("Collapse subtree") { onCollapseSubtree() })
        }
        add(ContextMenuItem("Copy name", enabled = node.label.isNotEmpty()) { onCopy(node.label) })
    } else {
        add(ContextMenuItem("Open") { onOpen() })
        add(ContextMenuItem("Copy name", enabled = node.label.isNotEmpty()) { onCopy(node.label) })
        val fqn = qualifiedNodeName(node)
        add(ContextMenuItem("Copy qualified name", enabled = fqn.isNotEmpty()) { onCopy(fqn) })
        if (node.hasChildren) {
            add(ContextMenuItem("Expand subtree") { onExpandSubtree() })
            add(ContextMenuItem("Collapse subtree") { onCollapseSubtree() })
        }
    }
}
