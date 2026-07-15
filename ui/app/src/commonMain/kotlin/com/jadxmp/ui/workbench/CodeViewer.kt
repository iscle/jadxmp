package com.jadxmp.ui.workbench

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jadxmp.ui.client.CodeDocument
import com.jadxmp.ui.client.CodeLine
import com.jadxmp.ui.client.CodeToken
import com.jadxmp.ui.client.DEFAULT_CODE_FONT_SIZE_SP
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

/** Line height as a multiple of font size — the editor's 13sp/21sp rhythm, preserved under zoom. */
private const val CODE_LINE_HEIGHT_RATIO = 21f / 13f

/** Width of the fold-gutter margin (chevron column) shown left of the code when a document has folds. */
private val FOLD_MARGIN_WIDTH = 16.dp

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
    /** "Save file" — write this document's rendered text to disk. Null hides the menu item (no saver). */
    onSaveFile: (() -> Unit)? = null,
    /** Word-wrap toggle (P1#11): when true, lines wrap instead of panning under a horizontal scroll. */
    wordWrap: Boolean = false,
    /** Flip word-wrap (code-area context menu). */
    onToggleWordWrap: () -> Unit = {},
    /** Code-font size in sp (P1#12 zoom); scales the whole editor face, not the surrounding UI chrome. */
    codeFontSize: Float = DEFAULT_CODE_FONT_SIZE_SP,
    /** Ctrl/Cmd+wheel-up over the code — enlarge the font (keyboard zoom is handled by the workbench). */
    onZoomIn: () -> Unit = {},
    /** Ctrl/Cmd+wheel-down over the code — shrink the font. */
    onZoomOut: () -> Unit = {},
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
    // A subtle neutral wash behind every occurrence of the caret's word (IDE-style), distinct from the
    // amber find hit and the accent current-line row so the three can stack without muddling.
    val occurrenceHighlight = scheme.onSurface.copy(alpha = 0.14f)
    // The code face scaled by the zoom size (P1#12). Row height + line height track it so vertical
    // rhythm and gutter alignment stay correct at any zoom; the original 13/21 ratio is preserved.
    val lineHeightSp = codeFontSize * CODE_LINE_HEIGHT_RATIO
    val codeStyle = remember(codeFontSize) { CodeTextStyle.copy(fontSize = codeFontSize.sp, lineHeight = lineHeightSp.sp) }
    val rowHeight = with(density) { lineHeightSp.sp.toDp() }
    // The word under the caret (last clicked identifier token), whose siblings get [occurrenceHighlight].
    var caretWord by remember(document.nodeId, document.view) { mutableStateOf<String?>(null) }
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
    // Roughly one mono glyph (~0.62em) per digit plus padding; scales with the zoom so numbers never clip.
    val gutterWidth = maxOf(JadxTheme.spacing.gutterMinWidth, (digits * (codeFontSize * 0.62f) + 22f).dp)

    // ── Code folding (P1#10): brace-matched regions, and the set currently collapsed by the user ──
    val folds = remember(document.nodeId, document.view, document.lines) { computeFoldRegions(document.lines) }
    // One chevron per header line; when two blocks open on one line, the outer (largest) span wins.
    val headerRegions: Map<Int, FoldRegion> = remember(folds) {
        folds.groupBy { it.headerLine }.mapValues { (_, rs) -> rs.maxByOrNull { it.endLine }!! }
    }
    var collapsed by remember(document.nodeId, document.view) { mutableStateOf<Set<Int>>(emptySet()) }
    val hidden = remember(folds, collapsed) { hiddenLineNumbers(folds, collapsed) }
    val visibleLines = remember(document.lines, hidden) {
        if (hidden.isEmpty()) document.lines else document.lines.filterNot { it.number in hidden }
    }

    val listState = rememberLazyListState()
    // Move the caret line into view + highlight it when this document first appears (per-tab caret
    // restore) OR when a fresh navigation arrives ([scrollNonce] changed — e.g. a code-search hit or a
    // Find step, including into an already-open tab). It deliberately does NOT key on [initialCaretLine]
    // alone, so a manual line click (which updates the caret) never yanks the viewport.
    LaunchedEffect(document.nodeId, document.view, scrollNonce) {
        val target = initialCaretLine.coerceIn(1, maxOf(1, document.lineCount))
        currentLine = target
        // Navigating into folded code reveals it: expand any collapsed block that hides the target.
        val reveal = folds.filter { it.headerLine in collapsed && target in (it.headerLine + 1) until it.endLine }
        if (reveal.isNotEmpty()) collapsed = collapsed - reveal.mapTo(HashSet()) { it.headerLine }
        if (target > 1) {
            // Bias a few lines above the hit so it lands with context, not pinned to the very top. The
            // target's item index is its position among the still-visible lines (== target-1 with no folds).
            val idx = visibleIndexOfLine(document.lines, hiddenLineNumbers(folds, collapsed), target)
            listState.scrollToItem((idx - SCROLL_CONTEXT_LINES).coerceAtLeast(0))
        }
    }

    // rememberUpdatedState so the never-restarting wheel pointerInput(Unit) always calls the live zoom lambdas.
    val zoomIn by rememberUpdatedState(onZoomIn)
    val zoomOut by rememberUpdatedState(onZoomOut)

    // The code area is a Box so the horizontal scrollbar can be pinned as a thin overlay at the
    // bottom without disturbing the virtualized list layout.
    Box(
        modifier
            .fillMaxSize()
            .background(scheme.background)
            .onGloballyPositioned { rootCoords = it }
            // Ctrl/Cmd+mouse-wheel zooms the code font (P1#12). Intercept on the Initial pass so we can
            // consume it *before* the LazyColumn scrolls; a plain wheel (no accelerator) falls through.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Scroll) {
                            val accel = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (accel && dy != 0f) {
                                if (dy < 0f) zoomIn() else zoomOut()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
    ) {
        // SelectionContainer makes the rendered token text drag-selectable (and Ctrl+A / Ctrl+C
        // copyable) on every target. Because it lives outside the virtualized LazyColumn, only the
        // currently-materialized lines participate in a selection — the accepted tradeoff for keeping
        // large files performant.
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(visibleLines, key = { it.number }) { line ->
                    // Columns of the active Find hit that fall on this line (null = nothing to paint).
                    val findRange = activeFindMatch
                        ?.takeIf { it.line == line.number }
                        ?.let { it.start until it.end }
                    // Fold state for this line: null = not a header; true/false = collapsed/expanded header.
                    val region = headerRegions[line.number]
                    val foldCollapsed = if (region != null) line.number in collapsed else null
                    CodeLineRow(
                        line = line,
                        syntax = syntax,
                        codeStyle = codeStyle,
                        rowHeight = rowHeight,
                        gutterWidth = gutterWidth,
                        isCurrent = line.number == currentLine,
                        wrap = wordWrap,
                        hScroll = hScroll,
                        findRange = findRange,
                        findHighlight = findHighlight,
                        caretWord = caretWord,
                        occurrenceHighlight = occurrenceHighlight,
                        showFoldMargin = folds.isNotEmpty(),
                        foldCollapsed = foldCollapsed,
                        onToggleFold = {
                            if (region != null) {
                                collapsed = if (line.number in collapsed) collapsed - line.number else collapsed + line.number
                            }
                        },
                        onNavigate = onNavigate,
                        onSelect = {
                            currentLine = line.number
                            onCaretLine(line.number)
                        },
                        onSeed = onSelectionSeed,
                        onPrimaryPress = { token -> caretWord = caretWordFor(token.kind, token.text) },
                        onContext = { anchor, token ->
                            // Right-clicking a token adopts it as the current selection seed + caret word
                            // and opens the menu against it. Done here (an event) not in composition.
                            onSelectionSeed(token.text)
                            caretWord = caretWordFor(token.kind, token.text)
                            menu = CodeMenu(anchor, token, line.number)
                        },
                        rootCoords = { rootCoords },
                    )
                }
            }
        }
        // Visible horizontal scrollbar for long lines — only shown when the content actually overflows.
        // It spans the code area (offset past the gutter) so it stays aligned with the pannable text.
        // Hidden in word-wrap mode, where there is no horizontal pan.
        if (!wordWrap && hScroll.maxValue > 0) {
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
                ) + listOfNotNull(
                    // "Save file" (Ctrl/Cmd+S) — appended only when a FileSaver is wired.
                    onSaveFile?.let { save -> ContextMenuItem("Save file") { save() } },
                ) + listOf(
                    // Editor-view toggles (P1#11 word-wrap, P1#10 folding).
                    ContextMenuItem(if (wordWrap) "Word wrap: on" else "Word wrap: off") { onToggleWordWrap() },
                ) + if (folds.isNotEmpty()) {
                    listOf(
                        ContextMenuItem("Fold all") { collapsed = headerRegions.keys.toSet() },
                        ContextMenuItem("Expand all") { collapsed = emptySet() },
                    )
                } else {
                    emptyList()
                },
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
    codeStyle: TextStyle,
    rowHeight: Dp,
    gutterWidth: Dp,
    isCurrent: Boolean,
    wrap: Boolean,
    hScroll: ScrollState,
    findRange: IntRange?,
    findHighlight: Color,
    caretWord: String?,
    occurrenceHighlight: Color,
    showFoldMargin: Boolean,
    foldCollapsed: Boolean?,
    onToggleFold: () -> Unit,
    onNavigate: (NodeId) -> Unit,
    onSelect: () -> Unit,
    onSeed: (String) -> Unit,
    onPrimaryPress: (CodeToken) -> Unit,
    onContext: (Offset, CodeToken) -> Unit,
    rootCoords: () -> LayoutCoordinates?,
) {
    val colors = JadxTheme.colors
    val scheme = MaterialTheme.colorScheme
    val rowBackground = if (isCurrent) colors.currentLineBackground else Color.Transparent
    // The tokens of this line, shared by the wrap (FlowRow) and no-wrap (horizontal-scroll Row) paths so
    // each token keeps its exact click-to-def / right-click / find + occurrence highlight either way.
    val tokenContent: @Composable () -> Unit = {
        var column = 0
        for ((index, token) in line.tokens.withIndex()) {
            TokenSpan(
                token = token,
                columnStart = column,
                codeStyle = codeStyle,
                wrap = wrap,
                findRange = findRange,
                findHighlight = findHighlight,
                caretWord = caretWord,
                occurrenceHighlight = occurrenceHighlight,
                syntax = syntax,
                onNavigate = onNavigate,
                onSelect = onSelect,
                onSeed = onSeed,
                onPrimaryPress = onPrimaryPress,
                onContext = onContext,
                rootCoords = rootCoords,
                key = index,
            )
            column += token.text.length
        }
        if (foldCollapsed == true) {
            // Signal the hidden body; the matching '}' stays visible on its own line just below.
            DisableSelection {
                Text("  …", style = codeStyle, color = colors.onSurfaceFaint, softWrap = false)
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = rowHeight)
            .background(rowBackground)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSelect),
        verticalAlignment = Alignment.Top,
    ) {
        // Gutter — fixed width, right-aligned line number in the code face. Top-aligned so a wrapped line
        // keeps its number beside the first visual row (single lines look centered since lineHeight == rowHeight).
        Box(
            Modifier
                .width(gutterWidth)
                .heightIn(min = rowHeight)
                .background(scheme.background)
                .padding(end = JadxTheme.spacing.lg),
            contentAlignment = Alignment.TopEnd,
        ) {
            // Line numbers are excluded from a copied selection.
            DisableSelection {
                Text(
                    line.number.toString(),
                    style = codeStyle,
                    color = if (isCurrent) colors.gutterActiveText else colors.gutterText,
                    textAlign = TextAlign.End,
                )
            }
        }
        // Fold margin (P1#10): a chevron on header lines, empty elsewhere. Only present when the document
        // has folds, so non-folding views (resources, smali) render identically to before.
        if (showFoldMargin) {
            val foldInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .width(FOLD_MARGIN_WIDTH)
                    .height(rowHeight)
                    .then(
                        if (foldCollapsed != null) {
                            Modifier.clickable(interactionSource = foldInteraction, indication = null, onClick = onToggleFold)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (foldCollapsed != null) {
                    DisableSelection { FoldChevron(collapsed = foldCollapsed, tint = colors.gutterText, glyphSize = 11.dp) }
                }
            }
        }
        // Code — word-wrap flows tokens to the next visual line; otherwise a shared horizontal scroll pans
        // all lines together and keeps them gutter-aligned.
        if (wrap) {
            FlowRow(Modifier.weight(1f).padding(start = JadxTheme.spacing.xs)) { tokenContent() }
        } else {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(hScroll)
                    .padding(start = JadxTheme.spacing.xs),
                verticalAlignment = Alignment.Top,
            ) { tokenContent() }
        }
    }
}

@Composable
private fun TokenSpan(
    token: CodeToken,
    columnStart: Int,
    codeStyle: TextStyle,
    wrap: Boolean,
    findRange: IntRange?,
    findHighlight: Color,
    caretWord: String?,
    occurrenceHighlight: Color,
    syntax: SyntaxColors,
    onNavigate: (NodeId) -> Unit,
    onSelect: () -> Unit,
    onSeed: (String) -> Unit,
    onPrimaryPress: (CodeToken) -> Unit,
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
    val currentOnPrimaryPress by rememberUpdatedState(onPrimaryPress)
    val currentRoot by rememberUpdatedState(rootCoords)
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    var mod: Modifier = Modifier
        .onGloballyPositioned { coords = it }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Press) continue
                    when {
                        // Secondary (right) button: open the context menu against this token (and consume it).
                        event.buttons.isSecondaryPressed -> {
                            val local = event.changes.first().position
                            val root = currentRoot()
                            val tc = coords
                            val anchor = if (root != null && tc != null) root.localPositionOf(tc, local) else local
                            currentOnSelect()
                            currentOnContext(anchor, currentToken)
                            event.changes.forEach { it.consume() }
                        }
                        // Primary (left) button: adopt this token as the caret word for occurrence highlight.
                        // NOT consumed — the navigation click and SelectionContainer drag-select stay untouched.
                        event.buttons.isPrimaryPressed -> currentOnPrimaryPress(currentToken)
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

    // Compose the token backgrounds bottom→top: the caret-word occurrence wash over the whole token, then
    // the Find hit over just its overlapping slice — so a token that is both shows Find on the slice and
    // occurrence elsewhere. The common (no-highlight) case keeps the plain-string fast path.
    val isOccurrence = caretWord != null && token.text == caretWord
    val tokenEndExclusive = columnStart + token.text.length
    val overlapStart = if (findRange != null) maxOf(findRange.first, columnStart) else 0
    val overlapEndInclusive = if (findRange != null) minOf(findRange.last, tokenEndExclusive - 1) else -1
    val hasFind = findRange != null && overlapStart <= overlapEndInclusive
    if (!isOccurrence && !hasFind) {
        Text(text = token.text, style = codeStyle, color = color, softWrap = wrap, modifier = mod)
    } else {
        Text(
            text = buildAnnotatedString {
                append(token.text)
                if (isOccurrence) addStyle(SpanStyle(background = occurrenceHighlight), 0, token.text.length)
                if (hasFind) {
                    addStyle(SpanStyle(background = findHighlight), overlapStart - columnStart, overlapEndInclusive - columnStart + 1)
                }
            },
            style = codeStyle,
            color = color,
            softWrap = wrap,
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

// ── Caret-word occurrence highlight (P1#13) ──────────────────────────────────

/** Token kinds whose text is an identifier the editor will highlight all occurrences of on click. */
private val CARET_WORD_KINDS = setOf(TokenKind.PLAIN, TokenKind.TYPE, TokenKind.METHOD, TokenKind.FIELD)

/**
 * True when [text] is a single identifier word — letters/digits/`_`/`$`, not starting with a digit and
 * of a sane length. Matching whole tokens by equality is inherently "whole-word", so this only has to
 * reject non-words (operators, whitespace, numbers) as the caret target. Pure — unit-tested directly.
 */
internal fun isHighlightableWord(text: String): Boolean {
    if (text.isEmpty() || text.length > 128) return false
    val first = text[0]
    if (!(first.isLetter() || first == '_' || first == '$')) return false
    return text.all { it.isLetterOrDigit() || it == '_' || it == '$' }
}

/** The caret "word" a click adopts for occurrence highlighting, or null when the token isn't an identifier. */
internal fun caretWordFor(kind: TokenKind, text: String): String? =
    if (kind in CARET_WORD_KINDS && isHighlightableWord(text)) text else null

// ── Code folding (P1#10) ─────────────────────────────────────────────────────

/**
 * A brace-delimited foldable block: [headerLine] holds the `{`, [endLine] the matching `}`. When folded,
 * the interior lines `(headerLine, endLine)` are hidden while the header and closing-brace lines stay
 * visible (so the braces still read as balanced). Only blocks with at least one interior line fold.
 */
@Immutable
internal data class FoldRegion(val headerLine: Int, val endLine: Int)

/**
 * Brace-matched foldable regions over the token stream. `{` opens a block, the matching `}` closes it;
 * braces inside string/comment tokens are ignored (they aren't structure). If the braces don't balance
 * — a stray `}` or an unclosed `{` — folding is disabled wholesale (empty list) rather than guessing:
 * no fold, never a crash (rule 4). Pure — unit-tested directly.
 */
internal fun computeFoldRegions(lines: List<CodeLine>): List<FoldRegion> {
    val open = ArrayDeque<Int>()
    val out = ArrayList<FoldRegion>()
    for (line in lines) {
        for (token in line.tokens) {
            if (token.kind == TokenKind.STRING || token.kind == TokenKind.COMMENT) continue
            for (c in token.text) {
                when (c) {
                    '{' -> open.addLast(line.number)
                    '}' -> {
                        val start = open.removeLastOrNull() ?: return emptyList() // unbalanced '}'
                        if (line.number >= start + 2) out += FoldRegion(start, line.number)
                    }
                }
            }
        }
    }
    if (open.isNotEmpty()) return emptyList() // unclosed '{'
    out.sortBy { it.headerLine }
    return out
}

/** Line numbers hidden when [collapsedHeaders] are folded — the interior (exclusive) of each collapsed region. */
internal fun hiddenLineNumbers(folds: List<FoldRegion>, collapsedHeaders: Set<Int>): Set<Int> {
    if (collapsedHeaders.isEmpty() || folds.isEmpty()) return emptySet()
    val hidden = HashSet<Int>()
    for (f in folds) {
        if (f.headerLine in collapsedHeaders) {
            for (n in (f.headerLine + 1) until f.endLine) hidden += n
        }
    }
    return hidden
}

/** Index of [target] within the lines left after [hidden] are removed — the LazyColumn item to scroll to. */
private fun visibleIndexOfLine(lines: List<CodeLine>, hidden: Set<Int>, target: Int): Int {
    if (hidden.isEmpty()) return (target - 1).coerceAtLeast(0)
    var idx = 0
    for (line in lines) {
        if (line.number == target) return idx
        if (line.number !in hidden) idx++
    }
    return idx
}

/** Fold gutter affordance: a small chevron pointing right when [collapsed], down when expanded. */
@Composable
private fun FoldChevron(collapsed: Boolean, tint: Color, glyphSize: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(glyphSize)) {
        rotate(if (collapsed) 0f else 90f) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.40f, h * 0.30f)
                lineTo(w * 0.62f, h * 0.50f)
                lineTo(w * 0.40f, h * 0.70f)
            }
            drawPath(path, tint, style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
