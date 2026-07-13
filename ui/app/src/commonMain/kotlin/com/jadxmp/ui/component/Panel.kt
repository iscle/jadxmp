package com.jadxmp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.SectionLabelStyle

/**
 * A workbench panel: a flat surface bounded by a hairline, not a shadow. Panels are separated by
 * their borders (the instrument look), so elevation stays at zero throughout the shell.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surface),
        content = content,
    )
}

/**
 * A dense header strip for a panel (title on the left, optional actions on the right), closed by a
 * bottom hairline. Height matches the toolbar so panes line up across the shell.
 */
@Composable
fun PaneHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = JadxTheme.spacing.paneHeaderHeight)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = JadxTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xs),
        ) {
            Text(
                title.uppercase(),
                style = SectionLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
    }
}

/** An inline uppercase, letter-spaced section label (e.g. "RECENT", "Methods · 3"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = SectionLabelStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** A full-width hairline used to separate stacked regions. */
@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = MaterialTheme.colorScheme.outline)
}

/** Centered placeholder for empty states (no selection, empty results). */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Box(modifier.background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xs),
            modifier = Modifier.padding(JadxTheme.spacing.xl),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

/** Spacer with a fixed height token, for readable vertical rhythm in headers. */
@Composable
fun HeaderSpacer() {
    Box(Modifier.height(JadxTheme.spacing.toolbarHeight))
}
