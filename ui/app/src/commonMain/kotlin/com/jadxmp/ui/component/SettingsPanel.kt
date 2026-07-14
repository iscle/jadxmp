package com.jadxmp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.theme.JadxTheme

/**
 * A floating settings card (matching [com.jadxmp.ui.workbench.SearchPanel]'s overlay treatment: an
 * elevated surface bounded by a hairline and the large shape). Purely presentational — every control
 * is wired straight to the callback the coordinator supplies; the panel holds no state of its own.
 *
 * Grouped into Appearance / Tree / Decompiler sections, each a labelled row with a real control.
 */
@Composable
fun SettingsPanel(
    dark: Boolean,
    onToggleTheme: () -> Unit,
    flattenPackages: Boolean,
    onFlattenChange: (Boolean) -> Unit,
    defaultView: CodeView,
    onDefaultViewChange: (CodeView) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .widthIn(max = 420.dp)
            .clip(MaterialTheme.shapes.large)
            .background(scheme.surfaceVariant)
            .border(1.dp, scheme.outline, MaterialTheme.shapes.large)
            .padding(JadxTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(JadxTheme.spacing.lg),
    ) {
        // PaneHeader-style title row with a close affordance.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xs)) {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            ToolbarButton(onClick = onClose, square = true) { tint -> CloseGlyph(tint) }
        }

        // ── Appearance ────────────────────────────────────────────────────────
        SettingsSection("Appearance") {
            LabeledRow("Theme", caption = "Light or dark UI") {
                SegmentedToggle(
                    options = listOf("Light", "Dark"),
                    selectedIndex = if (dark) 1 else 0,
                    onSelect = { index -> if ((index == 1) != dark) onToggleTheme() },
                )
            }
        }

        // ── Tree ──────────────────────────────────────────────────────────────
        SettingsSection("Tree") {
            LabeledRow("Compact middle packages", caption = "Collapse single-child package chains") {
                SegmentedToggle(
                    options = listOf("Off", "On"),
                    selectedIndex = if (flattenPackages) 1 else 0,
                    onSelect = { index -> onFlattenChange(index == 1) },
                )
            }
        }

        // ── Decompiler ────────────────────────────────────────────────────────
        SettingsSection("Decompiler") {
            LabeledRow("Default view", caption = "View opened for new tabs") {
                val views = listOf(CodeView.JAVA, CodeView.KOTLIN, CodeView.SMALI)
                SegmentedToggle(
                    options = views.map { it.label() },
                    selectedIndex = views.indexOf(defaultView).coerceAtLeast(0),
                    onSelect = { index -> onDefaultViewChange(views[index]) },
                )
            }
        }
    }
}

/** A grouped settings section: an uppercase label header over its rows. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xs),
    ) {
        SectionLabel(title)
        content()
    }
}

private fun CodeView.label(): String = when (this) {
    CodeView.JAVA -> "Java"
    CodeView.KOTLIN -> "Kotlin"
    CodeView.SMALI -> "Smali"
}
