package com.jadxmp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Type system. The mockup uses two Google faces: **Geist** for UI chrome and **Geist Mono** for
 * code and the tool's own numeric readouts (line/col, counts, offsets). Reusing the mono face for
 * chrome metrics is the identity move — the instrument speaks in the typeface of the thing it shows.
 *
 * TODO(fonts): bundle the real Geist / Geist Mono TTFs as `composeResources` and point
 * [UiFontFamily]/[MonoFontFamily] at `FontFamily(Font(Res.font.geist_*, …))`. That is a resource +
 * (potentially) a small build change, so until the font files are vendored we fall back to the
 * platform sans / monospace — which keeps the module asset-free and wasm-safe. Every call site reads
 * these two aliases (or the [Typography]/[CodeTextStyle] styles), so the swap is a one-file change
 * with no call-site churn.
 */
val UiFontFamily: FontFamily = FontFamily.Default // Geist (system fallback until bundled)
val MonoFontFamily: FontFamily = FontFamily.Monospace // Geist Mono (system fallback until bundled)

/**
 * Chrome typography. Sizes follow the mockup: a large marketing/dialog title, tight section titles,
 * a 13sp body as the workbench default, and small dense labels for status/badges.
 */
val JadxTypography: Typography = Typography(
    // Titles / dialogs / start page
    titleLarge = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.02).em),
    titleMedium = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    // Body / labels — the workbench chrome default is bodyMedium at 13sp
    bodyLarge = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

/** The code viewer base style. 13/21 matches the mockup's editor line rhythm. */
val CodeTextStyle: TextStyle = TextStyle(
    fontFamily = MonoFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 21.sp,
)

/** Small monospace style for instrument readouts (status bar, line/col, counters). */
val ReadoutTextStyle: TextStyle = TextStyle(
    fontFamily = MonoFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 11.5.sp,
    lineHeight = 14.sp,
)

/** An uppercase, letter-spaced section-label style (e.g. "PROJECT", "RECENT", "Fields · 2"). */
val SectionLabelStyle: TextStyle = TextStyle(
    fontFamily = UiFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.1.em,
)
