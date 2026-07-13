package com.jadxmp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
                onDrag = { deltaPx -> fraction = (clamped + deltaPx / totalPx).coerceIn(0f, 1f) },
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
                onDrag = { deltaPx -> fraction = (clamped + deltaPx / totalPx).coerceIn(0f, 1f) },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) { second() }
        }
    }
}

@Composable
private fun ResizeHandleV(onDrag: (Float) -> Unit) {
    Box(
        Modifier
            .width(6.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x)
                }
            },
    ) {
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .align(androidx.compose.ui.Alignment.Center)
                .background(androidx.compose.material3.MaterialTheme.colorScheme.outline),
        )
    }
}

@Composable
private fun ResizeHandleH(onDrag: (Float) -> Unit) {
    Box(
        Modifier
            .height(6.dp)
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.y)
                }
            },
    ) {
        Box(
            Modifier
                .height(1.dp)
                .fillMaxWidth()
                .align(androidx.compose.ui.Alignment.Center)
                .background(androidx.compose.material3.MaterialTheme.colorScheme.outline),
        )
    }
}
