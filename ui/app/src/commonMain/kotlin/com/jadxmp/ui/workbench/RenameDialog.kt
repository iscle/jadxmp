package com.jadxmp.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.jadxmp.ui.client.RenameQuery
import com.jadxmp.ui.client.TokenKind
import com.jadxmp.ui.component.CloseGlyph
import com.jadxmp.ui.component.PrimaryButton
import com.jadxmp.ui.component.SecondaryButton
import com.jadxmp.ui.component.ToolbarButton
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.MonoFontFamily

/**
 * Observable state of the Rename dialog. `null` in [WorkbenchUiState.rename] means the dialog is hidden
 * (mirroring how [FindUiState]/[GoToLineUiState] model their bars). [target] is the clicked code position
 * the rename applies to (resolved to the engine symbol at submit time, exactly as find-usages resolves);
 * [originalName] is the symbol's current name (shown for context and prefilled into the field); [input] is
 * the raw text the user is editing; [error] is the engine's rejection reason to show inline (null while
 * editing / on success); [busy] is true while the — possibly re-decompiling — rename is in flight.
 */
@Immutable
data class RenameDialogUiState(
    val target: RenameQuery,
    val originalName: String,
    val input: String,
    val error: String? = null,
    val busy: Boolean = false,
)

/** A human label for the kind of symbol being renamed, from the clicked token's [TokenKind]. Pure. */
internal fun renameKindLabel(kind: TokenKind): String = when (kind) {
    TokenKind.TYPE -> "class"
    TokenKind.METHOD -> "method"
    TokenKind.FIELD -> "field"
    else -> "symbol"
}

/**
 * The Rename dialog (jadx-gui's Rename): a small centered modal over a scrim, with the current name shown
 * for context, a prefilled + auto-selected name field, an inline rejection reason (illegal/reserved name,
 * a within-scope collision, an unrenamable target — surfaced verbatim from the engine), and Cancel/Rename
 * actions. Enter commits, the scrim/Cancel dismiss, and Esc is handled globally by the workbench key layer.
 * Purely presentational: it edits [state] and raises intents; the resolution + rename run in the client.
 * Compose-Multiplatform only — wasm-safe.
 */
@Composable
internal fun RenameDialog(
    state: RenameDialogUiState,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = JadxTheme.spacing
    val focus = remember { FocusRequester() }
    // Seed the editable field once per open (keyed on the stable target), selecting the whole current name
    // so the user can overtype immediately (jadx-gui prefills + selects). Edits update this local value and
    // push the text up so [state.input] — read at submit — stays in sync; a rejection keeps what was typed.
    var nameField by remember(state.target) {
        mutableStateOf(TextFieldValue(state.input, selection = TextRange(0, state.input.length)))
    }
    LaunchedEffect(state.target) { runCatching { focus.requestFocus() } }

    val canSubmit = !state.busy && nameField.text.isNotBlank()

    // Full-screen scrim; a click outside the card cancels. The card swallows clicks so a click inside it
    // never dismisses. Centered so the dialog reads as modal (distinct from the top-right tool overlays).
    Box(
        modifier
            .fillMaxSize()
            .background(scheme.scrim.copy(alpha = 0.42f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(360.dp)
                .clip(MaterialTheme.shapes.large)
                .background(scheme.surface)
                .border(1.dp, scheme.outline, MaterialTheme.shapes.large)
                // Absorb clicks on the card so they don't reach the scrim's cancel handler.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Rename ${renameKindLabel(state.target.tokenKind)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ToolbarButton(onClick = onCancel, square = true) { tint -> CloseGlyph(tint = tint) }
            }

            Text(
                state.originalName,
                fontFamily = MonoFontFamily,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            // The name field: red border on a rejected rename, mirroring FindBar/GoToLineBar's invalid look.
            val fieldBorder = if (state.error != null) scheme.error else scheme.outline
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(scheme.background)
                    .border(1.dp, fieldBorder, MaterialTheme.shapes.small)
                    .padding(horizontal = spacing.sm, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = nameField,
                    onValueChange = {
                        nameField = it
                        onInput(it.text)
                    },
                    singleLine = true,
                    enabled = !state.busy,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { ev ->
                            // Enter commits (Esc is a global shortcut handled by the workbench).
                            val isEnter = ev.key == Key.Enter || ev.key == Key.NumPadEnter
                            if (ev.type == KeyEventType.KeyDown && isEnter && canSubmit) {
                                onSubmit()
                                true
                            } else {
                                false
                            }
                        },
                )
            }

            if (state.error != null) {
                Text(state.error, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryButton("Cancel", onClick = onCancel)
                PrimaryButton(if (state.busy) "Renaming…" else "Rename", onClick = onSubmit, enabled = canSubmit)
            }
        }
    }
}
