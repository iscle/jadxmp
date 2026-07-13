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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jadxmp.ui.theme.JadxTheme

/**
 * Compact filter/search input built on [BasicTextField] so it can be styled to the dense IDE look
 * (a Material [androidx.compose.material3.OutlinedTextField] is far too tall for a tree/toolbar).
 * A leading magnifier and inline placeholder keep it self-describing.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Filter…",
    onSubmit: (() -> Unit)? = null,
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
