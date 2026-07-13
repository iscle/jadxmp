package com.jadxmp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * The jadxmp design system entry point. Theme-aware by default (follows the system light/dark
 * setting per CONVENTIONS) but overridable for previews and an explicit in-app theme switch.
 *
 * Provides Material3's [MaterialTheme] plus the supplementary [WorkbenchColors] (syntax + chrome
 * tokens) via [LocalWorkbenchColors]. Read those through the [JadxTheme] accessor object.
 */
@Composable
fun JadxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) JadxDarkColorScheme else JadxLightColorScheme
    val workbenchColors = if (darkTheme) JadxDarkWorkbenchColors else JadxLightWorkbenchColors

    CompositionLocalProvider(LocalWorkbenchColors provides workbenchColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = JadxTypography,
            shapes = JadxShapes,
            content = content,
        )
    }
}

/** Ergonomic accessors for the design tokens from within composables. */
object JadxTheme {
    val colors: WorkbenchColors
        @Composable @ReadOnlyComposable get() = LocalWorkbenchColors.current

    val spacing: Spacing get() = Spacing
}
