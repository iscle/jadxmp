package com.jadxmp.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.jadxmp.ui.client.Visibility
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.MonoFontFamily
import com.jadxmp.ui.theme.WorkbenchColors
import kotlin.math.cos
import kotlin.math.sin

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

/** A small filled bookmark ribbon (pennant with a bottom notch) — the per-tab "bookmarked" badge (P1#9). */
@Composable
fun BookmarkGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(7.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.15f, 0f)
            lineTo(w * 0.85f, 0f)
            lineTo(w * 0.85f, h)
            lineTo(w * 0.50f, h * 0.62f)
            lineTo(w * 0.15f, h)
            close()
        }
        drawPath(path, tint)
    }
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

/** Settings gear/cog: a toothed ring around a hollow hub, stroke-drawn. */
@Composable
fun GearGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(15.dp)) {
        val s = 1.4.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val ring = size.minDimension * 0.30f
        val tooth = size.minDimension * 0.44f
        // Eight radial teeth around the body.
        for (i in 0 until 8) {
            val a = (i * 45f) * (3.14159265f / 180f)
            val dx = cos(a)
            val dy = sin(a)
            drawLine(
                tint,
                Offset(cx + dx * ring, cy + dy * ring),
                Offset(cx + dx * tooth, cy + dy * tooth),
                strokeWidth = s,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(tint, radius = ring, center = Offset(cx, cy), style = Stroke(width = s))
        drawCircle(tint, radius = ring * 0.42f, center = Offset(cx, cy), style = Stroke(width = s))
    }
}

/** Document/file glyph with a folded corner and text lines — the "Open AndroidManifest.xml" affordance. */
@Composable
fun ManifestGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val s = 1.3.dp.toPx()
        val fold = w * 0.28f
        val body = Path().apply {
            moveTo(w * 0.22f, h * 0.14f)
            lineTo(w * 0.66f, h * 0.14f)
            lineTo(w * 0.80f, h * 0.14f + fold)
            lineTo(w * 0.80f, h * 0.86f)
            lineTo(w * 0.22f, h * 0.86f)
            close()
        }
        drawPath(body, tint, style = Stroke(width = s, join = StrokeJoin.Round))
        // Folded corner.
        val corner = Path().apply {
            moveTo(w * 0.66f, h * 0.14f)
            lineTo(w * 0.66f, h * 0.14f + fold)
            lineTo(w * 0.80f, h * 0.14f + fold)
        }
        drawPath(corner, tint, style = Stroke(width = s, join = StrokeJoin.Round))
        // Two text lines.
        for (fy in listOf(0.54f, 0.70f)) {
            drawLine(tint, Offset(w * 0.34f, h * fy), Offset(w * 0.68f, h * fy), strokeWidth = s, cap = StrokeCap.Round)
        }
    }
}

/** Crosshair/target glyph — the "Jump to main activity" affordance. */
@Composable
fun TargetGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(15.dp)) {
        val s = 1.4.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension * 0.34f
        drawCircle(tint, radius = r, center = Offset(cx, cy), style = Stroke(width = s))
        drawCircle(tint, radius = r * 0.26f, center = Offset(cx, cy), style = Stroke(width = s))
        val outer = size.minDimension * 0.48f
        // Four crosshair ticks reaching past the ring.
        drawLine(tint, Offset(cx, cy - outer), Offset(cx, cy - r * 0.9f), strokeWidth = s, cap = StrokeCap.Round)
        drawLine(tint, Offset(cx, cy + r * 0.9f), Offset(cx, cy + outer), strokeWidth = s, cap = StrokeCap.Round)
        drawLine(tint, Offset(cx - outer, cy), Offset(cx - r * 0.9f, cy), strokeWidth = s, cap = StrokeCap.Round)
        drawLine(tint, Offset(cx + r * 0.9f, cy), Offset(cx + outer, cy), strokeWidth = s, cap = StrokeCap.Round)
    }
}

/**
 * jadx-style node marker. Leaf nodes (classes/members/resources) get a compact tinted letter badge
 * whose hue encodes the category (mockup: cyan class, violet interface, blue method, pink field);
 * containers (packages/dirs) get an outline folder, matching the mockup's tree exactly.
 *
 * Two refinements over the plain category badge:
 *  - **[visibility]** paints a small corner dot encoding access (public/protected/private/package),
 *    so a tree of members reads its visibility at a glance (jadx-gui parity). `null` → no dot.
 *  - **[fileName]** lets a resource/file row pick a glyph by extension (image/audio/json/…), instead
 *    of the generic `<>`; unknown extensions keep the generic badge.
 */
@Composable
fun NodeKindBadge(
    kind: NodeKind,
    modifier: Modifier = Modifier,
    visibility: Visibility? = null,
    fileName: String? = null,
) {
    val colors = JadxTheme.colors
    val faint = colors.onSurfaceFaint
    if (kind == NodeKind.PACKAGE || kind == NodeKind.DIRECTORY) {
        Box(modifier.size(JadxTheme.spacing.badgeSize), contentAlignment = Alignment.Center) {
            FolderGlyph(tint = faint)
        }
        return
    }
    val (glyph, color) = badgeGlyph(kind, fileName, colors)
    Box(modifier.size(JadxTheme.spacing.badgeSize)) {
        Box(
            Modifier
                .fillMaxSize()
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
        if (visibility != null) {
            VisibilityDot(color = visibilityColor(visibility), modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** Glyph + hue for a leaf badge: a resource/file row keys off its [fileName] extension; else the kind. */
private fun badgeGlyph(kind: NodeKind, fileName: String?, colors: WorkbenchColors): Pair<String, Color> {
    if ((kind == NodeKind.RESOURCE || kind == NodeKind.FILE) && fileName != null) {
        resourceGlyph(fileName, colors)?.let { return it }
    }
    return when (kind) {
        NodeKind.CLASS -> "C" to colors.cyan
        NodeKind.INTERFACE -> "I" to colors.accentSecondary
        NodeKind.ENUM -> "E" to colors.warning
        NodeKind.ANNOTATION_CLASS -> "@" to colors.pink
        NodeKind.METHOD -> "m" to colors.info
        NodeKind.FIELD -> "f" to colors.pink
        NodeKind.RESOURCE -> "<>" to colors.warning
        NodeKind.FILE -> "<>" to colors.warning
        NodeKind.IMAGE -> "▦" to colors.accentSecondary
        else -> "•" to colors.onSurfaceFaint
    }
}

/** A distinct glyph/hue for a known resource file extension, or `null` to fall back to the kind badge. */
private fun resourceGlyph(fileName: String, colors: WorkbenchColors): Pair<String, Color>? =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg" -> "▦" to colors.accentSecondary
        "mp3", "ogg", "wav", "m4a", "aac", "flac", "opus" -> "♪" to colors.info
        "mp4", "webm", "mkv", "avi", "mov", "3gp" -> "▶" to colors.info
        "ttf", "otf", "woff", "woff2" -> "F" to colors.pink
        "json" -> "{}" to colors.warning
        "xml" -> "<>" to colors.warning
        "html", "htm" -> "<>" to colors.cyan
        "css" -> "#" to colors.accentSecondary
        "js", "mjs", "cjs" -> "JS" to colors.warning
        "sql" -> "DB" to colors.info
        "arsc" -> "▤" to colors.cyan
        "zip", "jar", "apk", "aar", "dex" -> "▣" to colors.onSurfaceFaint
        "txt", "md", "properties", "ini", "yaml", "yml", "cfg", "pro", "pem" -> "≡" to colors.info
        else -> null
    }

/** Access-visibility → dot color: green public, amber protected, red private, faint package-private. */
@Composable
private fun visibilityColor(v: Visibility): Color = when (v) {
    Visibility.PUBLIC -> JadxTheme.colors.success
    Visibility.PROTECTED -> JadxTheme.colors.warning
    Visibility.PRIVATE -> MaterialTheme.colorScheme.error
    Visibility.PACKAGE_PRIVATE -> JadxTheme.colors.onSurfaceFaint
}

/** A tiny filled dot with a surface halo so it stays legible when overlaid on a colored badge corner. */
@Composable
private fun VisibilityDot(color: Color, modifier: Modifier = Modifier) {
    val halo = MaterialTheme.colorScheme.surface
    Canvas(modifier.size(7.dp)) {
        drawCircle(halo, radius = size.minDimension * 0.5f)
        drawCircle(color, radius = size.minDimension * 0.34f)
    }
}

/** A 2×2 rounded-square "apps" grid — the "Go to Application class" toolbar affordance. */
@Composable
fun AppClassGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(15.dp)) {
        val s = size.minDimension
        val cell = s * 0.30f
        val gap = s * 0.12f
        val x0 = s * 0.15f
        val y0 = s * 0.15f
        val stroke = Stroke(width = 1.4.dp.toPx(), join = StrokeJoin.Round)
        for (r in 0..1) {
            for (c in 0..1) {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(x0 + c * (cell + gap), y0 + r * (cell + gap)),
                    size = Size(cell, cell),
                    cornerRadius = CornerRadius(cell * 0.3f),
                    style = stroke,
                )
            }
        }
    }
}
