package com.jadxmp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// Palette — taken verbatim from the user's design mockup (reference/design/Jadx-UI.dc.html).
// A dark, gradient-accented "decompiler workbench": deep cool-neutral surfaces, a blue→violet
// accent pair (--acc / --acc2), and warm semantic hues (ok/warn/pink/cyan) reserved for syntax and
// status. Dark is the primary theme; light is the worked variant from the mockup's Preferences (1f).
// ─────────────────────────────────────────────────────────────────────────────

// ── Dark tokens (mockup `--*`) ───────────────────────────────────────────────
private val D_Bg = Color(0xFF15171C) // --bg: window base + code editor
private val D_Panel = Color(0xFF1A1D24) // --panel: sidebars, toolbar, tab strip, status bar
private val D_Panel2 = Color(0xFF1E2229) // --panel2: dialogs, split sub-headers
private val D_Elev = Color(0xFF232A34) // --elev: chips, active pills, badge fills
private val D_Line = Color(0xFF2B313B) // --line: primary hairline
private val D_Line2 = Color(0xFF232830) // --line2: subtle hairline
private val D_Tx = Color(0xFFE6E9EF) // --tx
private val D_Tx2 = Color(0xFF9AA3B2) // --tx2
private val D_Tx3 = Color(0xFF697084) // --tx3
private val D_Acc = Color(0xFF7C8CF8) // --acc
private val D_Acc2 = Color(0xFFA78BFA) // --acc2
private val D_Ok = Color(0xFF5FD1A0) // --ok
private val D_Warn = Color(0xFFE6B566) // --warn
private val D_Pink = Color(0xFFF08FB0) // --pink
private val D_Cyan = Color(0xFF5EC6D6) // --cyan
private val D_Error = Color(0xFFF0655D) // window "close" red / error

// ── Light tokens (mockup `1f` Preferences) ───────────────────────────────────
private val L_Bg = Color(0xFFF7F8FA)
private val L_Panel = Color(0xFFFFFFFF)
private val L_Elev = Color(0xFFEEF1F5)
private val L_Line = Color(0xFFE4E8EE)
private val L_Line2 = Color(0xFFEEF1F5)
private val L_Tx = Color(0xFF1A1D24)
private val L_Tx2 = Color(0xFF59636F)
private val L_Tx3 = Color(0xFF98A0AC)
private val L_Acc = Color(0xFF5B6EF5)
private val L_Acc2 = Color(0xFF7C5CF5)
private val L_Ok = Color(0xFF1F9D63)
private val L_Warn = Color(0xFFC98A2B)
private val L_Pink = Color(0xFFC85B86)
private val L_Cyan = Color(0xFF2B93A6)
private val L_Error = Color(0xFFDB5147)

internal val JadxDarkColorScheme: ColorScheme = darkColorScheme(
    primary = D_Acc,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = D_Acc.copy(alpha = 0.16f),
    onPrimaryContainer = Color(0xFFE1E5FF),
    secondary = D_Acc2,
    onSecondary = Color(0xFFFFFFFF),
    background = D_Bg,
    onBackground = D_Tx,
    surface = D_Panel,
    onSurface = D_Tx,
    surfaceVariant = D_Panel2,
    onSurfaceVariant = D_Tx2,
    surfaceContainerLowest = D_Bg,
    surfaceContainerLow = D_Panel,
    surfaceContainer = D_Panel,
    surfaceContainerHigh = D_Panel2,
    surfaceContainerHighest = D_Elev,
    outline = D_Line,
    outlineVariant = D_Line2,
    error = D_Error,
    onError = Color(0xFF2B0605),
)

internal val JadxLightColorScheme: ColorScheme = lightColorScheme(
    primary = L_Acc,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = L_Acc.copy(alpha = 0.12f),
    onPrimaryContainer = Color(0xFF10184A),
    secondary = L_Acc2,
    onSecondary = Color(0xFFFFFFFF),
    background = L_Bg,
    onBackground = L_Tx,
    surface = L_Panel,
    onSurface = L_Tx,
    surfaceVariant = L_Elev,
    onSurfaceVariant = L_Tx2,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = L_Bg,
    surfaceContainer = L_Panel,
    surfaceContainerHigh = L_Elev,
    surfaceContainerHighest = L_Elev,
    outline = L_Line,
    outlineVariant = L_Line2,
    error = L_Error,
    onError = Color(0xFFFFFFFF),
)

internal val JadxDarkWorkbenchColors: WorkbenchColors = WorkbenchColors(
    isDark = true,
    accentSecondary = D_Acc2,
    onSurfaceFaint = D_Tx3,
    gutterText = D_Tx3,
    gutterActiveText = D_Acc,
    currentLineBackground = D_Acc.copy(alpha = 0.10f),
    treeSelectionBackground = D_Acc.copy(alpha = 0.13f),
    treeHoverBackground = D_Tx.copy(alpha = 0.05f),
    tabActiveIndicator = D_Acc,
    scrollbarThumb = D_Line,
    windowTintTop = Color(0xFF1C2029),
    windowTintRight = Color(0xFF191C26),
    success = D_Ok,
    warning = D_Warn,
    info = D_Acc,
    pink = D_Pink,
    cyan = D_Cyan,
    syntax = SyntaxColors(
        plain = D_Tx,
        keyword = D_Acc2,
        type = D_Cyan,
        string = D_Ok,
        number = D_Warn,
        comment = D_Tx3,
        annotation = D_Pink,
        field = D_Pink,
        method = D_Acc,
        punctuation = D_Tx2,
    ),
)

internal val JadxLightWorkbenchColors: WorkbenchColors = WorkbenchColors(
    isDark = false,
    accentSecondary = L_Acc2,
    onSurfaceFaint = L_Tx3,
    gutterText = L_Tx3,
    gutterActiveText = L_Acc,
    currentLineBackground = L_Acc.copy(alpha = 0.09f),
    treeSelectionBackground = L_Acc.copy(alpha = 0.11f),
    treeHoverBackground = L_Tx.copy(alpha = 0.04f),
    tabActiveIndicator = L_Acc,
    scrollbarThumb = L_Line,
    windowTintTop = L_Bg,
    windowTintRight = L_Bg,
    success = L_Ok,
    warning = L_Warn,
    info = L_Acc,
    pink = L_Pink,
    cyan = L_Cyan,
    syntax = SyntaxColors(
        plain = L_Tx,
        keyword = L_Acc2,
        type = L_Cyan,
        string = L_Ok,
        number = L_Warn,
        comment = L_Tx3,
        annotation = L_Pink,
        field = L_Pink,
        method = L_Acc,
        punctuation = L_Tx2,
    ),
)
