package com.jadxmp.ui.workbench

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.MonoFontFamily
import kotlin.math.ceil

/** Checkerboard cell size behind a previewed image (so transparency reads clearly, jadx-gui style). */
private val CHECKER_CELL = 10.dp

/**
 * Preview a raster-image resource (PNG/JPEG/GIF/WebP/BMP). The encoded [bytes] are decoded once (via the
 * platform [decodeImageBitmap]) and shown centered, scaled to fit while preserving aspect ratio, over a
 * checkerboard so transparency is visible. A caption states the format, pixel dimensions and byte size.
 *
 * Fault isolation (rule 4): the decoder returns `null` for anything it cannot handle (a corrupt image, or
 * the honest Android limits) instead of throwing, and this falls back to a "cannot preview" note plus the
 * raw [HexViewer] — the bytes are always shown *somehow*, never a crash or a blank pane.
 *
 * @param formatLabel short format name for the caption (e.g. "PNG"), from the caller's magic-byte sniff.
 */
@Composable
internal fun ImageViewer(
    bytes: ByteArray,
    formatLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = JadxTheme.colors
    // Decode once per distinct byte payload; a null result drives the graceful fallback below.
    val bitmap = remember(bytes) { decodeImageBitmap(bytes) }

    if (bitmap == null) {
        Column(modifier.fillMaxSize().background(scheme.background)) {
            Text(
                "Cannot preview image — showing raw bytes",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                fontFamily = MonoFontFamily,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surface)
                    .padding(horizontal = JadxTheme.spacing.lg, vertical = JadxTheme.spacing.sm),
            )
            HorizontalDivider(color = scheme.outline)
            HexViewer(bytes, Modifier.weight(1f))
        }
        return
    }

    val checkerLight = scheme.surface
    val checkerDark = scheme.surfaceContainerHighest
    Column(modifier.fillMaxSize().background(scheme.background)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .checkerboard(checkerLight, checkerDark),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "resource image preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(JadxTheme.spacing.xl),
            )
        }
        HorizontalDivider(color = scheme.outline)
        Text(
            text = imageCaption(formatLabel, bitmap.width, bitmap.height, bytes.size),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            fontFamily = MonoFontFamily,
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surface)
                .padding(horizontal = JadxTheme.spacing.lg, vertical = JadxTheme.spacing.sm),
        )
    }
}

/** Paint a two-tone checkerboard behind content. Base fill + alternate squares (half the draw ops). */
private fun Modifier.checkerboard(light: Color, dark: Color): Modifier = drawBehind {
    drawRect(color = light)
    val cell = CHECKER_CELL.toPx()
    if (cell <= 0f) return@drawBehind
    val cols = ceil(size.width / cell).toInt()
    val rows = ceil(size.height / cell).toInt()
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            if ((r + c) and 1 == 1) {
                drawRect(color = dark, topLeft = Offset(c * cell, r * cell), size = Size(cell, cell))
            }
        }
    }
}

/** Caption line for a decoded image: `PNG  ·  128 × 128  ·  4096 bytes`. Pure — unit-tested directly. */
internal fun imageCaption(formatLabel: String?, width: Int, height: Int, byteCount: Int): String =
    buildString {
        if (!formatLabel.isNullOrBlank()) append(formatLabel).append("  ·  ")
        append(width).append(" × ").append(height)
        append("  ·  ").append(binarySizeCaption(byteCount))
    }
