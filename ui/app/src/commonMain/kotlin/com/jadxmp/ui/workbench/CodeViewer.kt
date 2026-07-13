package com.jadxmp.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.client.CodeLine
import com.jadxmp.ui.client.CodeToken
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.TokenKind
import com.jadxmp.ui.theme.CodeTextStyle
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.SyntaxColors

/** Lines of leading context to keep above a jumped-to line, so it isn't pinned to the very top. */
private const val SCROLL_CONTEXT_LINES = 3

/**
 * The custom code viewer. Compose has no RSyntaxTextArea, so this renders the engine's per-token
 * metadata directly: color comes from [TokenKind] and click-to-navigate from [CodeToken.definition]
 * — highlighting is *driven by metadata, not a re-lexer*. Lines are virtualized in a [LazyColumn] so
 * it scales to large files, and a shared horizontal scroll keeps the gutter aligned with long lines.
 *
 * (In this scaffold the metadata comes from the stub; the real engine feeds the same [CodeDocument]
 * shape from core:codegen.)
 */
@Composable
fun CodeViewer(
    document: CodeDocument,
    modifier: Modifier = Modifier,
    onNavigate: (NodeId) -> Unit = {},
    onCaretLine: (Int) -> Unit = {},
    /** 1-based line to place the caret on when this document first appears (restored per-tab). */
    initialCaretLine: Int = 1,
    /**
     * One-shot navigation token. When it changes (a code-search hit was clicked), the viewer scrolls to
     * and highlights [initialCaretLine] — even if the document identity is unchanged (already-open tab).
     */
    scrollNonce: Int = 0,
) {
    val syntax = JadxTheme.colors.syntax
    val scheme = MaterialTheme.colorScheme
    val hScroll = rememberScrollState()
    // Keyed on the document identity so re-selecting a tab restores that tab's saved caret line
    // rather than snapping back to line 1.
    var currentLine by remember(document.nodeId, document.view) {
        mutableIntStateOf(initialCaretLine.coerceIn(1, maxOf(1, document.lineCount)))
    }

    val digits = document.lineCount.toString().length
    val gutterWidth = maxOf(JadxTheme.spacing.gutterMinWidth, (digits * 9 + 24).dp)

    val listState = rememberLazyListState()
    // Move the caret line into view + highlight it when this document first appears (per-tab caret
    // restore) OR when a fresh navigation arrives ([scrollNonce] changed — e.g. a code-search hit,
    // including into an already-open tab). It deliberately does NOT key on [initialCaretLine] alone, so
    // a manual line click (which updates the caret) never yanks the viewport.
    LaunchedEffect(document.nodeId, document.view, scrollNonce) {
        val target = initialCaretLine.coerceIn(1, maxOf(1, document.lineCount))
        currentLine = target
        if (target > 1) {
            // Bias a few lines above the hit so it lands with context, not pinned to the very top.
            listState.scrollToItem((target - 1 - SCROLL_CONTEXT_LINES).coerceAtLeast(0))
        }
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize().background(scheme.background)) {
        items(document.lines, key = { it.number }) { line ->
            CodeLineRow(
                line = line,
                syntax = syntax,
                gutterWidth = gutterWidth,
                isCurrent = line.number == currentLine,
                hScroll = hScroll,
                onNavigate = onNavigate,
                onSelect = {
                    currentLine = line.number
                    onCaretLine(line.number)
                },
            )
        }
    }
}

@Composable
private fun CodeLineRow(
    line: CodeLine,
    syntax: SyntaxColors,
    gutterWidth: androidx.compose.ui.unit.Dp,
    isCurrent: Boolean,
    hScroll: androidx.compose.foundation.ScrollState,
    onNavigate: (NodeId) -> Unit,
    onSelect: () -> Unit,
) {
    val colors = JadxTheme.colors
    val scheme = MaterialTheme.colorScheme
    val rowBackground = if (isCurrent) colors.currentLineBackground else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .height(21.dp)
            .background(rowBackground)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Gutter — fixed width, right-aligned line number in the code face.
        Box(
            Modifier
                .width(gutterWidth)
                .height(21.dp)
                .background(scheme.background)
                .padding(end = JadxTheme.spacing.lg),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                line.number.toString(),
                style = CodeTextStyle,
                color = if (isCurrent) colors.gutterActiveText else colors.gutterText,
                textAlign = TextAlign.End,
            )
        }
        // Code — shared horizontal scroll so all lines pan together and stay gutter-aligned.
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(hScroll)
                .padding(start = JadxTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((index, token) in line.tokens.withIndex()) {
                TokenSpan(token = token, syntax = syntax, onNavigate = onNavigate, key = index)
            }
        }
    }
}

@Composable
private fun TokenSpan(
    token: CodeToken,
    syntax: SyntaxColors,
    onNavigate: (NodeId) -> Unit,
    key: Int,
) {
    val color = tokenColor(token.kind, syntax)
    val definition = token.definition
    val base = Modifier
    val clickable = if (definition != null) {
        base.clickable(
            interactionSource = remember(key) { MutableInteractionSource() },
            indication = null,
            onClick = { onNavigate(definition) },
        )
    } else {
        base
    }
    Text(
        text = token.text,
        style = CodeTextStyle,
        color = color,
        softWrap = false,
        modifier = clickable,
    )
}

private fun tokenColor(kind: TokenKind, syntax: SyntaxColors): Color = when (kind) {
    TokenKind.PLAIN -> syntax.plain
    TokenKind.KEYWORD -> syntax.keyword
    TokenKind.TYPE -> syntax.type
    TokenKind.STRING -> syntax.string
    TokenKind.NUMBER -> syntax.number
    TokenKind.COMMENT -> syntax.comment
    TokenKind.ANNOTATION -> syntax.annotation
    TokenKind.FIELD -> syntax.field
    TokenKind.METHOD -> syntax.method
    TokenKind.PUNCTUATION -> syntax.punctuation
}
