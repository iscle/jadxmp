package com.jadxmp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * The Preferences window (matching [com.jadxmp.ui.workbench.SearchPanel]'s overlay treatment: an
 * elevated surface bounded by a hairline and the large shape). Purely presentational — every control is
 * wired straight to the callback the coordinator supplies; the panel holds no state of its own, so
 * changes apply live through [com.jadxmp.ui.workbench.WorkbenchState] and persist via its SettingsStore.
 *
 * Grouped into **Appearance** and **Editor** sections, each an uppercase-labelled block of aligned
 * label+control rows. Only UI-presentational settings live here (theme, package compaction, default
 * view, line numbers, current-line highlight, word-wrap, font size); decompiler-engine options are out
 * of scope. The body scrolls when the sections grow taller than the card's capped height.
 */
@Composable
fun SettingsPanel(
    dark: Boolean,
    onToggleTheme: () -> Unit,
    flattenPackages: Boolean,
    onFlattenChange: (Boolean) -> Unit,
    defaultView: CodeView,
    onDefaultViewChange: (CodeView) -> Unit,
    showLineNumbers: Boolean,
    onShowLineNumbersChange: (Boolean) -> Unit,
    highlightCurrentLine: Boolean,
    onHighlightCurrentLineChange: (Boolean) -> Unit,
    wordWrap: Boolean,
    onWordWrapChange: (Boolean) -> Unit,
    codeFontSize: Float,
    onCodeFontSizeChange: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .widthIn(max = 420.dp)
            // Cap the card so a tall list scrolls inside it rather than overflowing a short window; the
            // incoming max constraint still wins, so it never exceeds the available height.
            .heightIn(max = 600.dp)
            .clip(MaterialTheme.shapes.large)
            .background(scheme.surfaceVariant)
            .border(1.dp, scheme.outline, MaterialTheme.shapes.large)
            .padding(JadxTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(JadxTheme.spacing.lg),
    ) {
        // PaneHeader-style title row with a close affordance, pinned above the scroll region.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xs)) {
            Text(
                "Preferences",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            ToolbarButton(onClick = onClose, square = true) { tint -> CloseGlyph(tint) }
        }

        // Scrollable body: sections that outgrow the capped height scroll rather than clip.
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(JadxTheme.spacing.lg),
        ) {
            // ── Appearance ────────────────────────────────────────────────────────
            SettingsSection("Appearance") {
                LabeledRow("Theme", caption = "Light or dark UI") {
                    SegmentedToggle(
                        options = listOf("Light", "Dark"),
                        selectedIndex = if (dark) 1 else 0,
                        onSelect = { index -> if ((index == 1) != dark) onToggleTheme() },
                    )
                }
                LabeledRow("Compact middle packages", caption = "Collapse single-child package chains") {
                    OnOffToggle(flattenPackages, onFlattenChange)
                }
            }

            // ── Editor ────────────────────────────────────────────────────────────
            SettingsSection("Editor") {
                LabeledRow("Default view", caption = "View opened for new tabs") {
                    val views = listOf(CodeView.JAVA, CodeView.KOTLIN, CodeView.SMALI)
                    SegmentedToggle(
                        options = views.map { it.label() },
                        selectedIndex = views.indexOf(defaultView).coerceAtLeast(0),
                        onSelect = { index -> onDefaultViewChange(views[index]) },
                    )
                }
                LabeledRow("Show line numbers", caption = "Gutter with line numbers") {
                    OnOffToggle(showLineNumbers, onShowLineNumbersChange)
                }
                LabeledRow("Highlight current line", caption = "Wash the caret's row") {
                    OnOffToggle(highlightCurrentLine, onHighlightCurrentLineChange)
                }
                LabeledRow("Word wrap", caption = "Wrap long lines instead of panning") {
                    OnOffToggle(wordWrap, onWordWrapChange)
                }
                LabeledRow("Font size", caption = "Code editor text size (sp)") {
                    FontSizeStepper(codeFontSize, onCodeFontSizeChange)
                }
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

/** A binary Off/On segmented toggle bound to a boolean setting. */
@Composable
private fun OnOffToggle(value: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedToggle(
        options = listOf("Off", "On"),
        selectedIndex = if (value) 1 else 0,
        onSelect = { index -> onChange(index == 1) },
    )
}

/** Increment for the font-size stepper; the workbench clamps the result into its legible range. */
private const val FONT_STEP_SP: Float = 1f

/**
 * A −/value/+ stepper over the shared code-font-size setting. The workbench clamps out-of-range
 * requests (so the buttons stay live and a click past a bound is simply a no-op), which keeps this
 * control free of the workbench's min/max constants and thus layering-clean.
 */
@Composable
private fun FontSizeStepper(size: Float, onChange: (Float) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surface)
            .border(1.dp, scheme.outline, MaterialTheme.shapes.small)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton(onClick = { onChange(size - FONT_STEP_SP) }, square = true) { tint ->
            Text("−", style = MaterialTheme.typography.titleSmall, color = tint)
        }
        Box(Modifier.widthIn(min = 30.dp), contentAlignment = Alignment.Center) {
            Text(fontSizeLabel(size), style = MaterialTheme.typography.labelMedium, color = scheme.onSurface)
        }
        ToolbarButton(onClick = { onChange(size + FONT_STEP_SP) }, square = true) { tint ->
            Text("+", style = MaterialTheme.typography.titleSmall, color = tint)
        }
    }
}

/** Whole sizes render without a decimal (13); a persisted fractional size keeps it (18.5). */
private fun fontSizeLabel(size: Float): String =
    if (size % 1f == 0f) size.toInt().toString() else size.toString()

private fun CodeView.label(): String = when (this) {
    CodeView.JAVA -> "Java"
    CodeView.KOTLIN -> "Kotlin"
    CodeView.SMALI -> "Smali"
}
