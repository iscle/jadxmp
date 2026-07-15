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
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.component.CloseGlyph
import com.jadxmp.ui.component.DirectionCaret
import com.jadxmp.ui.component.ToolbarButton
import com.jadxmp.ui.component.ToolbarTextButton
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.MonoFontFamily

/**
 * One occurrence of the find query in the active document: the 1-based [line] and the `[start, end)`
 * character columns within that line's plain text. The columns let the code viewer paint the exact
 * match span (see `CodeViewer`); the line drives the scroll-to-match reuse of the existing nonce path.
 */
@Immutable
data class FindMatch(val line: Int, val start: Int, val end: Int)

/**
 * Pure, incremental find over a [CodeDocument]'s plain text — the heart of the Ctrl+F bar, kept free of
 * Compose so it is unit-tested directly. Matches are found **per line** (each line's tokens flattened to
 * text) which is what an editor find bar does and what makes span painting tractable; occurrences are
 * non-overlapping. Capped at [MAX_MATCHES] so a pathological single-character query on a huge file can
 * never blow up the single-threaded wasm UI.
 */
object DocumentFind {

    /** Upper bound on collected matches — a runaway 1-char query is bounded, not fatal. */
    const val MAX_MATCHES: Int = 5000

    /** Every occurrence of [query] in [document], left-to-right, top-to-bottom. Empty query → none. */
    fun find(document: CodeDocument, query: String, matchCase: Boolean): List<FindMatch> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<FindMatch>()
        val step = query.length // non-overlapping advance; query is non-empty so step >= 1
        for (line in document.lines) {
            val text = line.tokens.joinToString(separator = "") { it.text }
            var idx = text.indexOf(query, startIndex = 0, ignoreCase = !matchCase)
            while (idx >= 0) {
                out += FindMatch(line.number, idx, idx + query.length)
                if (out.size >= MAX_MATCHES) return out
                idx = text.indexOf(query, startIndex = idx + step, ignoreCase = !matchCase)
            }
        }
        return out
    }
}

/**
 * Observable state of the in-editor Find bar for the active tab. `null` in [WorkbenchUiState.find] means
 * the bar is hidden. [matches] is recomputed by [WorkbenchState] whenever the query, case mode, or
 * active document changes; [activeIndex] is the currently-focused match.
 */
@Immutable
data class FindUiState(
    val query: String = "",
    val matchCase: Boolean = false,
    val matches: List<FindMatch> = emptyList(),
    val activeIndex: Int = 0,
) {
    val count: Int get() = matches.size
    val activeMatch: FindMatch? get() = matches.getOrNull(activeIndex)

    /** A non-empty query that matched nothing — paints the field red. */
    val noMatch: Boolean get() = query.isNotEmpty() && matches.isEmpty()
}

/** "3/17", "0/0" for a fruitless query, or "" for an empty query. */
internal fun findCountLabel(state: FindUiState): String = when {
    state.query.isEmpty() -> ""
    state.count == 0 -> "0/0"
    else -> "${state.activeIndex + 1}/${state.count}"
}

/**
 * The incremental Find toolbar shown over the active document (distinct from the global search panel).
 * A query field (red when no match), a live "current/total" count, previous/next steppers, a match-case
 * toggle, and a close button. Enter jumps to the next match, Shift+Enter to the previous; Esc is handled
 * globally by the workbench key layer. Auto-focuses its field on open.
 */
@Composable
internal fun FindBar(
    state: FindUiState,
    onQueryChange: (String) -> Unit,
    onMatchCaseChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = JadxTheme.spacing
    val focus = remember { FocusRequester() }
    // Focus the field the moment the bar appears so the user can type immediately.
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
        val fieldBorder = if (state.noMatch) scheme.error else scheme.outline
        Row(
            Modifier
                .width(200.dp)
                .clip(MaterialTheme.shapes.small)
                .background(scheme.background)
                .border(1.dp, fieldBorder, MaterialTheme.shapes.small)
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
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
                        // Enter → next, Shift+Enter → previous. (Esc is a global shortcut.)
                        val isEnter = ev.key == Key.Enter || ev.key == Key.NumPadEnter
                        if (ev.type == KeyEventType.KeyDown && isEnter) {
                            if (ev.isShiftPressed) onPrev() else onNext()
                            true
                        } else {
                            false
                        }
                    },
                decorationBox = { inner ->
                    Box {
                        if (state.query.isEmpty()) {
                            Text(
                                "Find…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        inner()
                    }
                },
            )
            Text(
                findCountLabel(state),
                fontFamily = MonoFontFamily,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        ToolbarButton(onClick = onPrev, enabled = state.count > 0, square = true, tooltip = "Previous match (Shift+Enter)") { tint ->
            DirectionCaret(pointsLeft = true, tint = tint)
        }
        ToolbarButton(onClick = onNext, enabled = state.count > 0, square = true, tooltip = "Next match (Enter)") { tint ->
            DirectionCaret(pointsLeft = false, tint = tint)
        }
        ToolbarTextButton("Aa", onClick = { onMatchCaseChange(!state.matchCase) }, selected = state.matchCase, tooltip = "Match case")
        ToolbarButton(onClick = onClose, square = true, tooltip = "Close (Esc)") { tint -> CloseGlyph(tint = tint) }
    }
}
