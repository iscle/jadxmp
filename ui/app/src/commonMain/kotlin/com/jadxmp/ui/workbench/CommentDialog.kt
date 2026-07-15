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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.jadxmp.ui.client.CommentQuery
import com.jadxmp.ui.component.CloseGlyph
import com.jadxmp.ui.component.PrimaryButton
import com.jadxmp.ui.component.SecondaryButton
import com.jadxmp.ui.component.ToolbarButton
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.MonoFontFamily

/**
 * Observable state of the Comment dialog. `null` in [WorkbenchUiState.comment] means the dialog is hidden
 * (mirroring [RenameDialogUiState]). [target] is the clicked code position the comment applies to (resolved
 * to the engine symbol at submit time, exactly as rename/find-usages resolve); [symbolName] is the symbol's
 * name (shown for context); [existingComment] is the note currently on that symbol (`null` when none — it
 * selects the "Add" vs "Edit" wording and gates the Remove affordance); [input] is the raw, possibly
 * multi-line text the user is editing (seeded from [existingComment]); [busy] is true while the — re-decompiling
 * — set/remove is in flight. There is no `error`: a comment is free text the engine sanitizes, never rejected.
 */
@Immutable
data class CommentDialogUiState(
    val target: CommentQuery,
    val symbolName: String,
    val existingComment: String?,
    val input: String,
    val busy: Boolean = false,
)

/**
 * The label for the comment action in the code-area menu and the dialog header: "Edit comment" when the
 * symbol already carries a note, else "Add comment". Pure — unit-tested directly (mirrors [renameKindLabel]).
 */
internal fun commentActionLabel(hasComment: Boolean): String = if (hasComment) "Edit comment" else "Add comment"

/**
 * The Comment dialog (jadx-gui's Add/Edit comment): a small centered modal over a scrim, with the symbol
 * name shown for context, a **multi-line** text area prefilled with the existing comment, a Save action, a
 * Remove action (only when a comment exists), and Cancel. Differs from [RenameDialog] only where comments do:
 * the field is multi-line (Enter inserts a newline; Ctrl/Cmd+Enter commits), it is prefilled with the current
 * note, **submitting blank removes** it, and there is no inline rejection (any text is accepted — the engine
 * sanitizes it to always-valid `//` line(s)). The scrim/Cancel dismiss, and Esc is handled globally by the
 * workbench key layer. Purely presentational: it edits [state] and raises intents; the resolution + set/remove
 * run in the client. Compose-Multiplatform only — wasm-safe.
 */
@Composable
internal fun CommentDialog(
    state: CommentDialogUiState,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = JadxTheme.spacing
    val focus = remember { FocusRequester() }
    // Seed the editable field once per open (keyed on the stable target). Unlike rename (which selects the
    // whole name for overtyping), the caret is parked at the END so the user can append to an existing note
    // without wiping it. Edits update this local value and push the text up so [state.input] — read at submit
    // — stays in sync.
    var commentField by remember(state.target) {
        mutableStateOf(TextFieldValue(state.input, selection = TextRange(state.input.length)))
    }
    LaunchedEffect(state.target) { runCatching { focus.requestFocus() } }

    // A blank field with an existing comment is a valid submit (it removes); a blank field with no existing
    // comment has nothing to do, so Save is disabled there (the Remove button is absent too).
    val canSubmit = !state.busy && (commentField.text.isNotBlank() || state.existingComment != null)

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
                .width(420.dp)
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
                    commentActionLabel(state.existingComment != null),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ToolbarButton(onClick = onCancel, square = true) { tint -> CloseGlyph(tint = tint) }
            }

            Text(
                state.symbolName,
                fontFamily = MonoFontFamily,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            // The multi-line note field: a top-aligned text area (Enter is a newline; Ctrl/Cmd+Enter commits).
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 108.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(scheme.background)
                    .border(1.dp, scheme.outline, MaterialTheme.shapes.small)
                    .padding(horizontal = spacing.sm, vertical = spacing.sm),
            ) {
                BasicTextField(
                    value = commentField,
                    onValueChange = {
                        commentField = it
                        onInput(it.text)
                    },
                    enabled = !state.busy,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { ev ->
                            // Ctrl/Cmd+Enter commits; a plain Enter falls through so it inserts a newline
                            // (multi-line). Esc is a global shortcut handled by the workbench.
                            val isEnter = ev.key == Key.Enter || ev.key == Key.NumPadEnter
                            val accel = ev.isCtrlPressed || ev.isMetaPressed
                            if (ev.type == KeyEventType.KeyDown && isEnter && accel && canSubmit) {
                                onSubmit()
                                true
                            } else {
                                false
                            }
                        },
                )
            }

            Text(
                "Rendered as // line(s) before the definition. Blank removes it. ⌘/Ctrl+Enter to save.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Remove is offered only when a note already exists; it submits a removal (blank).
                if (state.existingComment != null) {
                    SecondaryButton("Remove", onClick = onRemove, enabled = !state.busy)
                }
                Box(Modifier.weight(1f))
                SecondaryButton("Cancel", onClick = onCancel)
                PrimaryButton(if (state.busy) "Saving…" else "Save", onClick = onSubmit, enabled = canSubmit)
            }
        }
    }
}
