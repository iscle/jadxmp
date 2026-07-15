package com.jadxmp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jadxmp.ui.theme.JadxTheme

/**
 * Compact filter/search input built on [BasicTextField] so it can be styled to the dense IDE look
 * (a Material [androidx.compose.material3.OutlinedTextField] is far too tall for a tree/toolbar).
 * A leading magnifier and inline placeholder keep it self-describing.
 *
 * [onSubmit] fires on Enter (activate the current selection); [onMoveDown] / [onMoveUp] fire on the arrow
 * keys so a field driving a results list can move the highlighted row without the mouse. All three are
 * optional and default to null — a plain filter field (e.g. the tree filter) passes none and keeps its
 * original behaviour. Enter/arrow presses are handled in a preview handler so they are consumed *here*
 * rather than reaching the platform text field (a singleline field's Enter would otherwise fire the IME
 * action, and Up/Down are inert in it anyway).
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Filter…",
    onSubmit: (() -> Unit)? = null,
    /** Move the driven list's selection down one row (Down arrow); null = the field drives no list. */
    onMoveDown: (() -> Unit)? = null,
    /** Move the driven list's selection up one row (Up arrow); null = the field drives no list. */
    onMoveUp: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = JadxTheme.spacing.controlHeight)
            .background(scheme.background, MaterialTheme.shapes.small)
            .border(1.dp, scheme.outline, MaterialTheme.shapes.small)
            .onPreviewKeyEvent { ev ->
                // Consume Enter / arrows only when a handler wants them; otherwise fall through untouched so
                // a plain filter field (no handlers) behaves exactly as before, and Left/Right still edit.
                if (ev.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (ev.key) {
                        Key.DirectionDown -> onMoveDown?.let { it(); true } ?: false
                        Key.DirectionUp -> onMoveUp?.let { it(); true } ?: false
                        Key.Enter, Key.NumPadEnter -> onSubmit?.let { it(); true } ?: false
                        else -> false
                    }
                }
            }
            .padding(horizontal = JadxTheme.spacing.lg, vertical = JadxTheme.spacing.sm),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit?.invoke() }),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchGlyph(tint = scheme.onSurfaceVariant, modifier = Modifier.padding(end = JadxTheme.spacing.sm))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                    inner()
                }
            }
        },
    )
}
