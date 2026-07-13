package com.jadxmp.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale on a 4dp base, plus the named chrome dimensions taken from the design mockup
 * (`reference/design/Jadx-UI.dc.html`). Kept as an object (not per-theme) because geometry does not
 * change between light/dark. The workbench is a "decompiler workbench": dense rows, generous but
 * calm panels.
 */
@Immutable
object Spacing {
    val hairline: Dp = 1.dp
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 6.dp
    val md: Dp = 8.dp
    val lg: Dp = 12.dp
    val xl: Dp = 16.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp

    // Chrome heights/dimensions — mirror the mockup's "Tabbed Classic" (1a) layout.
    val toolbarHeight: Dp = 54.dp // primary toolbar: icon groups + centered search + right cluster
    val paneHeaderHeight: Dp = 44.dp // pane section headers ("PROJECT", "Preview", …)
    val tabStripHeight: Dp = 42.dp // the tab strip; tabs themselves are shorter cards
    val tabHeight: Dp = 33.dp // an individual tab card
    val breadcrumbHeight: Dp = 38.dp // package › class › member + Java/Smali toggle
    val treeRowHeight: Dp = 27.dp
    val statusBarHeight: Dp = 28.dp
    val controlHeight: Dp = 34.dp // buttons, filter fields, segmented controls
    val railWidth: Dp = 54.dp // activity rail (command/split layout)
    val gutterMinWidth: Dp = 52.dp
    val treeIndent: Dp = 16.dp
    val iconSize: Dp = 16.dp
    val iconButtonSize: Dp = 34.dp
    val badgeSize: Dp = 16.dp
}

/**
 * Supplementary color tokens that Material's [androidx.compose.material3.ColorScheme] does not model.
 * These come straight from the mockup's CSS custom properties: the second accent (`--acc2`), the
 * faint tertiary text (`--tx3`), a few chrome tints, the semantic status colors, and the source
 * syntax palette — the seam the engine's `CodeMetadata` drives. Provided per-theme through
 * [LocalWorkbenchColors]; access via `JadxTheme.colors`.
 */
@Immutable
data class WorkbenchColors(
    val isDark: Boolean,
    // Extra palette roles the mockup uses that Material3 has no slot for.
    val accentSecondary: Color, // --acc2 (violet); pairs with primary for the brand gradient
    val onSurfaceFaint: Color, // --tx3: gutter numbers, section labels, dimmed metadata
    // Chrome
    val gutterText: Color,
    val gutterActiveText: Color,
    val currentLineBackground: Color,
    val treeSelectionBackground: Color,
    val treeHoverBackground: Color,
    val tabActiveIndicator: Color,
    val scrollbarThumb: Color,
    // Two radial tints painted over the window base for the mockup's soft "workbench" glow.
    val windowTintTop: Color,
    val windowTintRight: Color,
    // Semantic status (--ok / --warn / --acc / --pink / --cyan)
    val success: Color,
    val warning: Color,
    val info: Color,
    val pink: Color,
    val cyan: Color,
    // Source syntax palette (a stub renderer uses this now; engine metadata drives it later)
    val syntax: SyntaxColors,
) {
    /** The two-stop brand gradient (145°) used on the logo, progress bars, and app-icon chips. */
    val accentGradient: List<Color> get() = listOf(info, accentSecondary)
}

@Immutable
data class SyntaxColors(
    val plain: Color,
    val keyword: Color,
    val type: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color,
    val field: Color,
    val method: Color,
    val punctuation: Color,
)

val LocalWorkbenchColors = staticCompositionLocalOf<WorkbenchColors> {
    error("WorkbenchColors not provided; wrap content in JadxTheme { }")
}
