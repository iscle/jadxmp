package com.jadxmp.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jadxmp.ui.client.NodeKind
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.MonoFontFamily

// Self-contained vector glyphs drawn with Canvas — no icon-font or drawable dependency, so the module
// stays asset-free and wasm-safe. These are the small set the chrome needs; richer icons can arrive
// later via composeResources without touching call sites.

/** Small right/down disclosure triangle (mockup ▸ / ▾). */
@Composable
fun ExpandChevron(expanded: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(10.dp)) {
        rotate(if (expanded) 90f else 0f) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.34f, h * 0.22f)
                lineTo(w * 0.68f, h * 0.50f)
                lineTo(w * 0.34f, h * 0.78f)
                close()
            }
            drawPath(path, tint)
        }
    }
}

/** A close "×". */
@Composable
fun CloseGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(11.dp)) {
        val s = 1.4.dp.toPx()
        val p = size.width * 0.22f
        drawLine(tint, Offset(p, p), Offset(size.width - p, size.height - p), s, StrokeCap.Round)
        drawLine(tint, Offset(size.width - p, p), Offset(p, size.height - p), s, StrokeCap.Round)
    }
}

/** A small filled dot used as the "pinned" indicator or a status LED. */
@Composable
fun PinDot(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(8.dp)) {
        drawCircle(tint, radius = size.minDimension * 0.32f)
    }
}

/** A small filled rounded square — the mockup's per-tab "view type" marker (cyan Java, amber XML). */
@Composable
fun SquareDot(tint: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).clip(MaterialTheme.shapes.extraSmall).background(tint))
}

/** Magnifier glyph for search fields. */
@Composable
fun SearchGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        val s = 1.5.dp.toPx()
        val r = size.minDimension * 0.30f
        val cx = size.width * 0.42f
        val cy = size.height * 0.42f
        drawCircle(tint, radius = r, center = Offset(cx, cy), style = Stroke(width = s))
        drawLine(
            tint,
            Offset(cx + r * 0.72f, cy + r * 0.72f),
            Offset(size.width * 0.88f, size.height * 0.88f),
            strokeWidth = s,
            cap = StrokeCap.Round,
        )
    }
}

/** Directional caret used for the back/forward navigation buttons. */
@Composable
fun DirectionCaret(pointsLeft: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        rotate(if (pointsLeft) 180f else 0f) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.44f, h * 0.24f)
                lineTo(w * 0.64f, h * 0.50f)
                lineTo(w * 0.44f, h * 0.76f)
            }
            drawPath(path, tint, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Outline folder glyph — used for package / directory rows (the mockup does not badge containers). */
@Composable
fun FolderGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.12f, h * 0.30f)
            lineTo(w * 0.40f, h * 0.30f)
            lineTo(w * 0.50f, h * 0.42f)
            lineTo(w * 0.88f, h * 0.42f)
            lineTo(w * 0.88f, h * 0.80f)
            lineTo(w * 0.12f, h * 0.80f)
            close()
        }
        drawPath(path, tint, style = Stroke(width = 1.3.dp.toPx(), join = StrokeJoin.Round))
    }
}

/**
 * jadx-style node marker. Leaf nodes (classes/members/resources) get a compact tinted letter badge
 * whose hue encodes the category (mockup: cyan class, violet interface, blue method, pink field);
 * containers (packages/dirs) get an outline folder, matching the mockup's tree exactly.
 */
@Composable
fun NodeKindBadge(kind: NodeKind, modifier: Modifier = Modifier) {
    val colors = JadxTheme.colors
    val faint = colors.onSurfaceFaint
    if (kind == NodeKind.PACKAGE || kind == NodeKind.DIRECTORY) {
        Box(modifier.size(JadxTheme.spacing.badgeSize), contentAlignment = Alignment.Center) {
            FolderGlyph(tint = faint)
        }
        return
    }
    val (glyph, color) = when (kind) {
        NodeKind.CLASS -> "C" to colors.cyan
        NodeKind.INTERFACE -> "I" to colors.accentSecondary
        NodeKind.ENUM -> "E" to colors.warning
        NodeKind.ANNOTATION_CLASS -> "@" to colors.pink
        NodeKind.METHOD -> "m" to colors.info
        NodeKind.FIELD -> "f" to colors.pink
        NodeKind.RESOURCE -> "<>" to colors.warning
        NodeKind.FILE -> "<>" to colors.warning
        NodeKind.IMAGE -> "▦" to colors.accentSecondary
        else -> "•" to faint
    }
    Box(
        modifier
            .size(JadxTheme.spacing.badgeSize)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = color,
            fontFamily = MonoFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (glyph.length > 1) 8.sp else 10.sp,
        )
    }
}
