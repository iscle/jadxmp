package com.jadxmp.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.jadxmp.ui.component.CloseGlyph
import com.jadxmp.ui.component.ToolbarButton
import com.jadxmp.ui.theme.JadxTheme

/**
 * Observable state of the in-editor Go-to-line bar for the active tab. `null` in
 * [WorkbenchUiState.goToLine] means the bar is hidden — mirroring how [FindUiState] models the Find bar.
 * [query] is the raw text the user is typing; parsing + clamping to the document's line range happens on
 * commit in [WorkbenchState.applyGoToLine] (see [parseGoToLine]), so the bar itself stays presentational.
 */
@Immutable
data class GoToLineUiState(
    val query: String = "",
) {
    /** A non-empty entry that isn't an integer — paints the field red (mirrors [FindUiState.noMatch]). */
    val invalid: Boolean get() = query.isNotEmpty() && query.trim().toLongOrNull() == null
}

/**
 * Parse a user-typed go-to-line [text] into the 1-based line to jump to, clamped into `[1, lastLine]`, or
 * null when [text] is blank / not an integer (the bar then flags its invalid state and does not jump).
 * Parsed as a [Long] so an out-of-Int entry like "99999999999" still clamps to the last line instead of
 * overflowing; text past even [Long] range, or any non-digit, yields null. [lastLine] is floored at 1 so
 * an empty document can't invert the clamp range. Pure and total — never throws (rule 4) — so it is
 * unit-tested directly.
 */
internal fun parseGoToLine(text: String, lastLine: Int): Int? {
    val n = text.trim().toLongOrNull() ?: return null
    return n.coerceIn(1L, maxOf(1, lastLine).toLong()).toInt()
}

/**
 * The compact Go-to-line input shown over the active document (Ctrl/Cmd+G), mirroring [FindBar]'s look and
 * placement. A single number field (red when the entry isn't a number) plus a close button; Enter commits
 * the jump (see [WorkbenchState.applyGoToLine]), Esc is handled globally by the workbench key layer.
 * Auto-focuses its field on open. [lastLine] drives the "Line (1–N)" placeholder hint.
 */
@Composable
internal fun GoToLineBar(
    state: GoToLineUiState,
    lastLine: Int,
    onQueryChange: (String) -> Unit,
    onCommit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = JadxTheme.spacing
    val focus = remember { FocusRequester() }
    // Focus the field the moment the bar appears so the user can type immediately (mirrors FindBar).
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Row(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surface)
            .border(1.dp, scheme.outline, MaterialTheme.shapes.small)
            .padding(horizontal = spacing.sm, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        // Red field border on a non-numeric entry — the subtle invalid indication, mirroring FindBar's noMatch.
        val fieldBorder = if (state.invalid) scheme.error else scheme.outline
        Row(
            Modifier
                .width(160.dp)
                .clip(MaterialTheme.shapes.small)
                .background(scheme.background)
                .border(1.dp, fieldBorder, MaterialTheme.shapes.small)
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = state.query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { ev ->
                        // Enter commits the jump. (Esc is a global shortcut handled by the workbench.)
                        val isEnter = ev.key == Key.Enter || ev.key == Key.NumPadEnter
                        if (ev.type == KeyEventType.KeyDown && isEnter) {
                            onCommit()
                            true
                        } else {
                            false
                        }
                    },
                decorationBox = { inner ->
                    Box {
                        if (state.query.isEmpty()) {
                            Text(
                                "Line (1–$lastLine)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        inner()
                    }
                },
            )
        }
        ToolbarButton(onClick = onClose, square = true) { tint -> CloseGlyph(tint = tint) }
    }
}
