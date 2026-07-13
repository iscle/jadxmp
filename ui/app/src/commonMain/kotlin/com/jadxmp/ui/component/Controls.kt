package com.jadxmp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.MonoFontFamily

/**
 * Flat toolbar affordance: transparent until hovered, tinted when selected. No ripple — the
 * instrument aesthetic prefers a quiet background shift over Material ripple. [content] draws the
 * glyph or label; use [contentTint] for the current interactive color.
 */
@Composable
fun ToolbarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    square: Boolean = false,
    content: @Composable (tint: Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scheme = MaterialTheme.colorScheme
    val background = when {
        selected -> scheme.primary.copy(alpha = 0.14f)
        hovered && enabled -> scheme.onSurface.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    val tint = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.30f)
        selected -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    val base = if (square) {
        modifier.size(JadxTheme.spacing.iconButtonSize)
    } else {
        modifier
            .defaultMinSize(minWidth = JadxTheme.spacing.iconButtonSize, minHeight = JadxTheme.spacing.iconButtonSize)
            .padding(horizontal = JadxTheme.spacing.sm)
    }
    Box(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .then(base),
        contentAlignment = Alignment.Center,
    ) {
        content(tint)
    }
}

/** Text-labelled toolbar button. */
@Composable
fun ToolbarTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    ToolbarButton(onClick, modifier, enabled, selected) { tint ->
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/** Filled primary action (e.g. "Open", "Apply"). Accent fill, white label. */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scheme = MaterialTheme.colorScheme
    val base = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.12f)
    val background = if (hovered && enabled) base.copy(alpha = 0.88f) else base
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .defaultMinSize(minHeight = JadxTheme.spacing.controlHeight)
            .padding(horizontal = JadxTheme.spacing.lg, vertical = JadxTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) scheme.onPrimary else scheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

/** Outlined secondary action (e.g. "Cancel", "Find usages"). */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (hovered && enabled) scheme.onSurface.copy(alpha = 0.05f) else Color.Transparent)
            .border(1.dp, scheme.outline, MaterialTheme.shapes.small)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .defaultMinSize(minHeight = JadxTheme.spacing.controlHeight)
            .padding(horizontal = JadxTheme.spacing.lg, vertical = JadxTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
    }
}

/**
 * The brand mark: a rounded square filled with the blue→violet gradient and mono initials, the
 * signature element from the mockup header / start page / app-info.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 34.dp, label: String = "jx") {
    val gradient = JadxTheme.colors.accentGradient
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size.value * 0.26f))
            .background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            fontFamily = MonoFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.44f).sp,
        )
    }
}

/**
 * A segmented pill toggle (mockup: Java/Smali, Light/Dark/System, Callers/Callees, Source lines /
 * Bytecode / Debug). The selected segment is filled with the elevated surface; the track is bordered.
 */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surface)
            .border(1.dp, scheme.outline, MaterialTheme.shapes.small)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(if (selected) scheme.surfaceContainerHighest else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = JadxTheme.spacing.md, vertical = JadxTheme.spacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * A filter/scope chip — filled accent when selected, elevated surface otherwise. A disabled chip is
 * dimmed and inert, used for scopes that are designed but not yet backed by an engine capability.
 */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        !enabled -> scheme.surfaceContainerHighest.copy(alpha = 0.4f)
        selected -> scheme.primary
        else -> scheme.surfaceContainerHighest
    }
    val fg = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
        selected -> scheme.onPrimary
        else -> scheme.onSurfaceVariant
    }
    Box(
        modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = JadxTheme.spacing.lg, vertical = JadxTheme.spacing.sm),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.Medium)
    }
}

/** A small keyboard-hint pill (e.g. ⌘K), bordered mono text. */
@Composable
fun Kbd(text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .border(1.dp, scheme.outline, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = JadxTheme.spacing.sm, vertical = JadxTheme.spacing.xxs),
    ) {
        Text(text, fontFamily = MonoFontFamily, fontSize = 11.sp, color = scheme.onSurfaceVariant)
    }
}

/** A small status LED with an optional label (e.g. "● decompiled"). */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(percent = 50)).background(color))
}

/** A thin vertical separator for toolbars. */
@Composable
fun VDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(1.dp)
            .height(22.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

/** A horizontal group with tight spacing, the default layout for toolbar clusters. */
@Composable
fun ToolbarGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
