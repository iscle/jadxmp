package com.jadxmp.ui.workbench

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.client.CodeLine
import com.jadxmp.ui.client.CodeToken
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.TokenKind
import com.jadxmp.ui.component.ContextMenu
import com.jadxmp.ui.component.ContextMenuItem
import com.jadxmp.ui.theme.CodeTextStyle
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.SyntaxColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
 *
 * Interaction layers on top of the render:
 *  - [activeFindMatch] paints the current Find hit's span (per-token background over the matched cols).
 *  - a secondary (right) click on a token opens a [ContextMenu] targeting that token (Copy / Copy
 *    reference / Search selection / …) and pins the caret to its line.
 *  - [onSelectionSeed] reports the clicked token so the Find bar / global search can seed from it.
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
    /** The active in-editor Find match to paint, or null when the Find bar is hidden / has no match. */
    activeFindMatch: FindMatch? = null,
    /** Reports the text of a clicked/right-clicked token as the "current selection" seed. */
    onSelectionSeed: (String) -> Unit = {},
    /** "Search selection" — hand the clicked token's text to the global search panel. */
    onSearchSelection: (String) -> Unit = {},
) {
    val syntax = JadxTheme.colors.syntax
    val scheme = MaterialTheme.colorScheme
    // LocalClipboardManager is the cross-target (jvm/wasm/js) text clipboard; its suspend replacement
    // (LocalClipboard) exposes only the lower-level ClipEntry API, which has no clean commonMain text path.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    // A translucent amber wash behind the current Find hit — legible over every syntax color.
    val findHighlight = JadxTheme.colors.warning.copy(alpha = 0.40f)
    // Keyed on the document identity so re-selecting a tab restores that tab's saved caret line
    // rather than snapping back to line 1.
    var currentLine by remember(document.nodeId, document.view) {
        mutableIntStateOf(initialCaretLine.coerceIn(1, maxOf(1, document.lineCount)))
    }
    // Root coordinates: converts a token-local right-click position into this Box's space so the
    // context menu can be anchored exactly under the cursor. Reset the menu when the document changes.
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var menu by remember(document.nodeId, document.view) { mutableStateOf<CodeMenu?>(null) }

    val digits = document.lineCount.toString().length
    val gutterWidth = maxOf(JadxTheme.spacing.gutterMinWidth, (digits * 9 + 24).dp)

    val listState = rememberLazyListState()
    // Move the caret line into view + highlight it when this document first appears (per-tab caret
    // restore) OR when a fresh navigation arrives ([scrollNonce] changed — e.g. a code-search hit or a
    // Find step, including into an already-open tab). It deliberately does NOT key on [initialCaretLine]
    // alone, so a manual line click (which updates the caret) never yanks the viewport.
    LaunchedEffect(document.nodeId, document.view, scrollNonce) {
        val target = initialCaretLine.coerceIn(1, maxOf(1, document.lineCount))
        currentLine = target
        if (target > 1) {
            // Bias a few lines above the hit so it lands with context, not pinned to the very top.
            listState.scrollToItem((target - 1 - SCROLL_CONTEXT_LINES).coerceAtLeast(0))
        }
    }

    // The code area is a Box so the horizontal scrollbar can be pinned as a thin overlay at the
    // bottom without disturbing the virtualized list layout.
    Box(
        modifier
            .fillMaxSize()
            .background(scheme.background)
            .onGloballyPositioned { rootCoords = it },
    ) {
        // SelectionContainer makes the rendered token text drag-selectable (and Ctrl+A / Ctrl+C
        // copyable) on every target. Because it lives outside the virtualized LazyColumn, only the
        // currently-materialized lines participate in a selection — the accepted tradeoff for keeping
        // large files performant.
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(document.lines, key = { it.number }) { line ->
                    // Columns of the active Find hit that fall on this line (null = nothing to paint).
                    val findRange = activeFindMatch
                        ?.takeIf { it.line == line.number }
                        ?.let { it.start until it.end }
                    CodeLineRow(
                        line = line,
                        syntax = syntax,
                        gutterWidth = gutterWidth,
                        isCurrent = line.number == currentLine,
                        hScroll = hScroll,
                        findRange = findRange,
                        findHighlight = findHighlight,
                        onNavigate = onNavigate,
                        onSelect = {
                            currentLine = line.number
                            onCaretLine(line.number)
                        },
                        onSeed = onSelectionSeed,
                        onContext = { anchor, token ->
                            // Right-clicking a token adopts it as the current selection seed and opens
                            // the menu against it. Done here (an event) not in composition.
                            onSelectionSeed(token.text)
                            menu = CodeMenu(anchor, token, line.number)
                        },
                        rootCoords = { rootCoords },
                    )
                }
            }
        }
        // Visible horizontal scrollbar for long lines — only shown when the content actually overflows.
        // It spans the code area (offset past the gutter) so it stays aligned with the pannable text.
        if (hScroll.maxValue > 0) {
            HorizontalScrollbar(
                scrollState = hScroll,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = gutterWidth, end = JadxTheme.spacing.xs, bottom = 2.dp),
            )
        }

        // Right-click context menu, anchored under the cursor. All items are zero-engine (clipboard /
        // panel), fault-isolated: a token with no reference simply disables "Copy reference".
        menu?.let { m ->
            val reference = referenceFqn(m.token)
            val tokenText = m.token.text.trim()
            val lineText = document.lines.firstOrNull { it.number == m.line }
                ?.let { ln -> ln.tokens.joinToString(separator = "") { it.text } }
                .orEmpty()
            ContextMenu(
                expanded = true,
                offset = with(density) { DpOffset(m.anchor.x.toDp(), m.anchor.y.toDp()) },
                onDismiss = { menu = null },
                items = listOf(
                    ContextMenuItem("Copy", enabled = tokenText.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(m.token.text))
                    },
                    ContextMenuItem("Copy line", enabled = lineText.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(lineText))
                    },
                    ContextMenuItem("Copy reference", enabled = reference != null) {
                        reference?.let { clipboard.setText(AnnotatedString(it)) }
                    },
                    ContextMenuItem("Search selection", enabled = tokenText.isNotEmpty()) {
                        onSearchSelection(tokenText)
                    },
                    // Whole-document copy: the accessible stand-in for "Select all", since Compose's
                    // read-only SelectionContainer can't be programmatically selected (Ctrl+A still can).
                    ContextMenuItem("Copy all") {
                        clipboard.setText(AnnotatedString(document.plainText()))
                    },
                ),
            )
        }
    }
}

/** A pending right-click menu: where to anchor it, the token it targets, and that token's line. */
private data class CodeMenu(val anchor: Offset, val token: CodeToken, val line: Int)

/**
 * The reference (fully-qualified name) a token points at, for "Copy reference", or null when the token
 * is not navigable. A type token yields the class fqn; a method/field token yields `owner.member`
 * (`ownerClass` from the token's definition NodeId + the member name from the token text). Pure and
 * fault-tolerant — kept `internal` so it is unit-tested directly.
 */
internal fun referenceFqn(token: CodeToken): String? {
    val raw = token.definition?.value ?: return null
    val owner = when {
        raw.startsWith("cls:") -> raw.removePrefix("cls:")
        raw.startsWith("mbr:") -> raw.removePrefix("mbr:").substringBefore('#')
        else -> raw.substringAfter(':', raw)
    }.takeIf { it.isNotBlank() } ?: return null
    return when (token.kind) {
        // The definition carries the owner class; the token text is the member's own name.
        TokenKind.METHOD, TokenKind.FIELD -> "$owner.${token.text}"
        else -> owner
    }
}

/**
 * A slim, commonMain horizontal scrollbar overlay. Deliberately avoids the desktop-only
 * `androidx.compose.foundation.HorizontalScrollbar`/`ScrollbarAdapter` (JVM-only) so it compiles for
 * jvm, wasmJs and js alike. The thumb is sized/positioned from [ScrollState.value] / [ScrollState.maxValue]
 * and dragged via a [rememberDraggableState] that maps the thumb-pixel delta onto a content-pixel scroll.
 */
@Composable
private fun HorizontalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.height(8.dp)) {
        val trackWidthPx = constraints.maxWidth.toFloat()
        val maxValue = scrollState.maxValue.toFloat()
        if (trackWidthPx > 0f && maxValue > 0f) {
            // Thumb reflects the visible fraction: viewport / total content width.
            val contentWidthPx = trackWidthPx + maxValue
            val minThumbPx = with(density) { 24.dp.toPx() }
            val thumbWidthPx = (trackWidthPx * (trackWidthPx / contentWidthPx)).coerceIn(minThumbPx, trackWidthPx)
            val maxThumbTravel = (trackWidthPx - thumbWidthPx).coerceAtLeast(0f)
            val scrollFraction = (scrollState.value.toFloat() / maxValue).coerceIn(0f, 1f)
            val thumbOffsetPx = maxThumbTravel * scrollFraction
            Box(
                Modifier
                    .offset { IntOffset(thumbOffsetPx.roundToInt(), 0) }
                    .width(with(density) { thumbWidthPx.toDp() })
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(scheme.outline.copy(alpha = 0.45f))
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            if (maxThumbTravel > 0f) {
                                // Map a thumb-pixel drag onto the equivalent content-pixel scroll.
                                val contentDelta = delta * (maxValue / maxThumbTravel)
                                scope.launch { scrollState.scrollBy(contentDelta) }
                            }
                        },
                    ),
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
    hScroll: ScrollState,
    findRange: IntRange?,
    findHighlight: Color,
    onNavigate: (NodeId) -> Unit,
    onSelect: () -> Unit,
    onSeed: (String) -> Unit,
    onContext: (Offset, CodeToken) -> Unit,
    rootCoords: () -> LayoutCoordinates?,
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
            // Line numbers are excluded from a copied selection.
            DisableSelection {
                Text(
                    line.number.toString(),
                    style = CodeTextStyle,
                    color = if (isCurrent) colors.gutterActiveText else colors.gutterText,
                    textAlign = TextAlign.End,
                )
            }
        }
        // Code — shared horizontal scroll so all lines pan together and stay gutter-aligned.
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(hScroll)
                .padding(start = JadxTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var column = 0
            for ((index, token) in line.tokens.withIndex()) {
                TokenSpan(
                    token = token,
                    columnStart = column,
                    findRange = findRange,
                    findHighlight = findHighlight,
                    syntax = syntax,
                    onNavigate = onNavigate,
                    onSelect = onSelect,
                    onSeed = onSeed,
                    onContext = onContext,
                    rootCoords = rootCoords,
                    key = index,
                )
                column += token.text.length
            }
        }
    }
}

@Composable
private fun TokenSpan(
    token: CodeToken,
    columnStart: Int,
    findRange: IntRange?,
    findHighlight: Color,
    syntax: SyntaxColors,
    onNavigate: (NodeId) -> Unit,
    onSelect: () -> Unit,
    onSeed: (String) -> Unit,
    onContext: (Offset, CodeToken) -> Unit,
    rootCoords: () -> LayoutCoordinates?,
    key: Int,
) {
    val color = tokenColor(token.kind, syntax)
    val definition = token.definition
    // rememberUpdatedState so the never-restarting pointerInput(Unit) always reads the live values.
    val currentToken by rememberUpdatedState(token)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnContext by rememberUpdatedState(onContext)
    val currentRoot by rememberUpdatedState(rootCoords)
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    var mod: Modifier = Modifier
        .onGloballyPositioned { coords = it }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    // Only claim the secondary (right) button; primary taps/drags fall through to the
                    // navigation click and to SelectionContainer's drag-select untouched.
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        val local = event.changes.first().position
                        val root = currentRoot()
                        val tc = coords
                        val anchor = if (root != null && tc != null) root.localPositionOf(tc, local) else local
                        currentOnSelect()
                        currentOnContext(anchor, currentToken)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
    if (definition != null) {
        mod = mod.clickable(
            interactionSource = remember(key) { MutableInteractionSource() },
            indication = null,
            onClick = {
                onSeed(token.text)
                onNavigate(definition)
            },
        )
    }

    // Paint the Find hit's background over just the slice of this token that overlaps the match range.
    val tokenEndExclusive = columnStart + token.text.length
    val overlapStart = if (findRange != null) maxOf(findRange.first, columnStart) else 0
    val overlapEndInclusive = if (findRange != null) minOf(findRange.last, tokenEndExclusive - 1) else -1
    if (findRange != null && overlapStart <= overlapEndInclusive) {
        val from = overlapStart - columnStart
        val to = overlapEndInclusive - columnStart + 1
        Text(
            text = buildAnnotatedString {
                append(token.text)
                addStyle(SpanStyle(background = findHighlight), from, to)
            },
            style = CodeTextStyle,
            color = color,
            softWrap = false,
            modifier = mod,
        )
    } else {
        Text(
            text = token.text,
            style = CodeTextStyle,
            color = color,
            softWrap = false,
            modifier = mod,
        )
    }
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
