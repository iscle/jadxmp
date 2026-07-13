package com.jadxmp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.ReadoutTextStyle

/**
 * Bottom status bar (mockup): a hairline on top, a monospace message on the left (with an optional
 * status LED), and right-aligned "instrument readouts" (caret position, view, counts) in the code
 * face. A small spinner appears while a background job runs — the seam for engine progress/cancel.
 */
@Composable
fun WorkbenchStatusBar(
    status: String,
    busy: Boolean,
    modifier: Modifier = Modifier,
    statusColor: Color? = null,
    readouts: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = scheme.outline)
        Row(
            Modifier
                .fillMaxWidth()
                .height(JadxTheme.spacing.statusBarHeight)
                .background(scheme.surface)
                .padding(horizontal = JadxTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.md),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.5.dp,
                    color = scheme.primary,
                )
            } else if (statusColor != null) {
                StatusDot(color = statusColor, size = 7.dp)
            }
            Text(
                status,
                style = ReadoutTextStyle,
                color = if (statusColor != null) statusColor else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.weight(1f))
            readouts()
        }
    }
}

/** A single monospace readout, e.g. "Ln 12, Col 4" or "JAVA". */
@Composable
fun StatusReadout(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = JadxTheme.spacing.sm)) {
        Text(
            text,
            style = ReadoutTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
