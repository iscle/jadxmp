package com.jadxmp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draggable two-pane horizontal split. Position is stored as a fraction (not a fixed dp) so it stays
 * sensible when the window resizes — important for the tablet-adaptive goal. Each side has a minimum
 * width; the divider is a hairline with a wider invisible hit area.
 */
@Composable
fun HorizontalSplitPane(
    modifier: Modifier = Modifier,
    initialFraction: Float = 0.26f,
    minFirst: Dp = 200.dp,
    minSecond: Dp = 280.dp,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        val density = LocalDensity.current
        val totalPx = with(density) { totalWidth.toPx() }
        var fraction by remember { mutableFloatStateOf(initialFraction) }

        val minFirstPx = with(density) { minFirst.toPx() }
        val minSecondPx = with(density) { minSecond.toPx() }
        val lowerBound = (minFirstPx / totalPx).coerceIn(0f, 1f)
        val upperBound = (1f - minSecondPx / totalPx).coerceIn(0f, 1f)
        val clamped = if (lowerBound <= upperBound) fraction.coerceIn(lowerBound, upperBound) else 0.5f
        val firstWidth = totalWidth * clamped

        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(firstWidth).fillMaxHeight()) { first() }
            ResizeHandleV(
                onDrag = { deltaPx ->
                    // Accumulate onto the live stored fraction (not the display-coerced `clamped`), so a
                    // continuous drag tracks the pointer instead of snapping back to the start position.
                    val next = fraction + deltaPx / totalPx
                    fraction = if (lowerBound <= upperBound) {
                        next.coerceIn(lowerBound, upperBound)
                    } else {
                        next.coerceIn(0f, 1f)
                    }
                },
            )
            Box(Modifier.weight(1f).fillMaxHeight()) { second() }
        }
    }
}

/**
 * Draggable two-pane vertical split (e.g. editor over a results/log panel). Same fraction model.
 */
@Composable
fun VerticalSplitPane(
    modifier: Modifier = Modifier,
    initialFraction: Float = 0.7f,
    minFirst: Dp = 160.dp,
    minSecond: Dp = 120.dp,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val totalHeight = maxHeight
        val density = LocalDensity.current
        val totalPx = with(density) { totalHeight.toPx() }
        var fraction by remember { mutableFloatStateOf(initialFraction) }

        val minFirstPx = with(density) { minFirst.toPx() }
        val minSecondPx = with(density) { minSecond.toPx() }
        val lowerBound = (minFirstPx / totalPx).coerceIn(0f, 1f)
        val upperBound = (1f - minSecondPx / totalPx).coerceIn(0f, 1f)
        val clamped = if (lowerBound <= upperBound) fraction.coerceIn(lowerBound, upperBound) else 0.5f
        val firstHeight = totalHeight * clamped

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.height(firstHeight).fillMaxWidth()) { first() }
            ResizeHandleH(
                onDrag = { deltaPx ->
                    // Accumulate onto the live stored fraction (not the display-coerced `clamped`), so a
                    // continuous drag tracks the pointer instead of snapping back to the start position.
                    val next = fraction + deltaPx / totalPx
                    fraction = if (lowerBound <= upperBound) {
                        next.coerceIn(lowerBound, upperBound)
                    } else {
                        next.coerceIn(0f, 1f)
                    }
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) { second() }
        }
    }
}

@Composable
private fun ResizeHandleV(onDrag: (Float) -> Unit) {
    // rememberUpdatedState keeps the drag callback fresh even though pointerInput(Unit) never restarts,
    // so each event uses the latest totalPx/bounds and reads the live fraction.
    val currentOnDrag by rememberUpdatedState(onDrag)
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        Modifier
            .width(10.dp) // wide invisible hit area around the hairline; easy to grab
            .fillMaxHeight()
            .hoverable(interactionSource)
            // No horizontal-resize PointerIcon exists in Compose Multiplatform commonMain; Hand is the
            // best cross-platform signal that the divider is interactive (no-op on web, compiles for all).
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount.x)
                }
            },
    ) {
        Box(
            Modifier
                .width(if (hovered) 3.dp else 1.dp)
                .fillMaxHeight()
                .align(Alignment.Center)
                .background(
                    if (hovered) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                ),
        )
    }
}

@Composable
private fun ResizeHandleH(onDrag: (Float) -> Unit) {
    // rememberUpdatedState keeps the drag callback fresh even though pointerInput(Unit) never restarts,
    // so each event uses the latest totalPx/bounds and reads the live fraction.
    val currentOnDrag by rememberUpdatedState(onDrag)
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        Modifier
            .height(10.dp) // wide invisible hit area around the hairline; easy to grab
            .fillMaxWidth()
            .hoverable(interactionSource)
            // No vertical-resize PointerIcon exists in Compose Multiplatform commonMain; Hand is the
            // best cross-platform signal that the divider is interactive (no-op on web, compiles for all).
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount.y)
                }
            },
    ) {
        Box(
            Modifier
                .height(if (hovered) 3.dp else 1.dp)
                .fillMaxWidth()
                .align(Alignment.Center)
                .background(
                    if (hovered) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                ),
        )
    }
}
