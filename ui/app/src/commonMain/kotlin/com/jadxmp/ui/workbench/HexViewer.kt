package com.jadxmp.ui.workbench

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.jadxmp.ui.theme.JadxTheme
import com.jadxmp.ui.theme.MonoFontFamily

/**
 * Pure, Compose-free hex-dump row math for [HexViewer], kept separate so the formatting (offset width,
 * hex-pair column, ASCII gutter, row windowing) is unit-testable without composing anything.
 *
 * A row is the classic 16-bytes-per-line layout: an 8-hex-digit offset, sixteen space-separated hex
 * pairs split 8 + 8 by a double space, then a printable-ASCII gutter (`.` for non-printable). The hex
 * column is padded to a constant width on a short final row so the ASCII gutter stays column-aligned
 * across every row of a [LazyColumn].
 */
internal object HexDump {

    /** Bytes shown per row — the conventional hex-dump width. */
    const val BYTES_PER_ROW: Int = 16

    /**
     * Hard cap on rows built for one blob (rule 1). [HexViewer] windows to the first [MAX_ROWS] and shows
     * a "truncated" footer beyond it, so a pathologically large resource can never mint an unbounded row
     * count. 200k rows ≈ 3.2 MB, far more than anyone scrolls, yet bounded.
     */
    const val MAX_ROWS: Int = 200_000

    private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

    /** Number of 16-byte rows needed for [byteCount] bytes (0 for empty; the final row may be partial). */
    fun rowCount(byteCount: Int): Int = if (byteCount <= 0) 0 else (byteCount + BYTES_PER_ROW - 1) / BYTES_PER_ROW

    /** The 8-hex-digit, upper-case offset label for a byte position. */
    fun offsetLabel(offset: Int): String {
        val out = CharArray(8)
        var v = offset
        for (i in 7 downTo 0) {
            out[i] = HEX_DIGITS[v and 0xF]
            v = v ushr 4
        }
        return out.concatToString()
    }

    /**
     * The hex-pair column for the row at [rowIndex], padded to a constant width even on a short final row
     * (missing bytes render as two spaces) so the ASCII gutter aligns down the whole dump. Bytes 0–7 and
     * 8–15 are separated by a double space.
     */
    fun hexColumn(bytes: ByteArray, rowIndex: Int): String {
        val start = rowIndex * BYTES_PER_ROW
        val sb = StringBuilder(BYTES_PER_ROW * 3 + 1)
        for (k in 0 until BYTES_PER_ROW) {
            if (k > 0) sb.append(' ')
            if (k == BYTES_PER_ROW / 2) sb.append(' ') // extra gap at the 8-byte midpoint
            val idx = start + k
            if (idx < bytes.size) {
                val b = bytes[idx].toInt() and 0xFF
                sb.append(HEX_DIGITS[b ushr 4]).append(HEX_DIGITS[b and 0xF])
            } else {
                sb.append("  ")
            }
        }
        return sb.toString()
    }

    /** The printable-ASCII gutter for the row at [rowIndex]: bytes 0x20–0x7E verbatim, anything else `.`. */
    fun asciiColumn(bytes: ByteArray, rowIndex: Int): String {
        val start = rowIndex * BYTES_PER_ROW
        val end = minOf(start + BYTES_PER_ROW, bytes.size)
        if (start >= end) return ""
        val sb = StringBuilder(end - start)
        for (idx in start until end) {
            val b = bytes[idx].toInt() and 0xFF
            sb.append(if (b in 0x20..0x7E) b.toChar() else '.')
        }
        return sb.toString()
    }
}

/**
 * A read-only hex dump of arbitrary [bytes] — the fallback viewer for binary / non-text resources
 * (jadx-gui offers the same for unknown content). Rows are virtualized in a [LazyColumn] so only the
 * visible slice is ever formatted, and the row count is windowed to [HexDump.MAX_ROWS] with a
 * "truncated" footer, so a multi-MB blob neither freezes the (single-threaded) UI nor allocates the
 * whole dump up front (rule 1). Never decodes or interprets the bytes — it cannot fail on hostile input
 * (rule 4).
 *
 * A thin header strip states the size; the body is a shared-horizontal-scroll grid of
 * offset | hex pairs | ASCII, all in the monospace code face. Wrapped in a [SelectionContainer] so the
 * materialized rows are drag-selectable / copyable (nice-to-have; only visible rows participate, the
 * same tradeoff the code viewer makes).
 */
@Composable
internal fun HexViewer(bytes: ByteArray, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = JadxTheme.colors
    val totalRows = HexDump.rowCount(bytes.size)
    val shownRows = minOf(totalRows, HexDump.MAX_ROWS)
    val truncated = shownRows < totalRows
    val hScroll = rememberScrollState()
    val listState = rememberLazyListState()
    // The monospace face; a hair smaller than the editor since a hex row is information-dense.
    val cellStyle = remember { TextStyle(fontFamily = MonoFontFamily, fontSize = 12.sp, lineHeight = 18.sp) }

    Column(modifier.fillMaxSize().background(scheme.background)) {
        // Header strip: honest size readout (the dump has no other chrome).
        Text(
            text = binarySizeCaption(bytes.size) + if (truncated) "  ·  showing first ${shownRows * HexDump.BYTES_PER_ROW}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            fontFamily = MonoFontFamily,
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surface)
                .padding(horizontal = JadxTheme.spacing.lg, vertical = JadxTheme.spacing.sm),
        )
        HorizontalDivider(color = scheme.outline)

        if (shownRows == 0) {
            Text(
                "(empty)",
                style = cellStyle,
                color = colors.onSurfaceFaint,
                modifier = Modifier.padding(JadxTheme.spacing.lg),
            )
            return@Column
        }

        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(shownRows) { rowIndex ->
                    HexRow(bytes = bytes, rowIndex = rowIndex, cellStyle = cellStyle, hScroll = hScroll)
                }
                if (truncated) {
                    item {
                        Text(
                            "… truncated — ${bytes.size} bytes total",
                            style = cellStyle,
                            color = colors.onSurfaceFaint,
                            modifier = Modifier.padding(horizontal = JadxTheme.spacing.lg, vertical = JadxTheme.spacing.sm),
                        )
                    }
                }
            }
        }
    }
}

/** One dump line: offset gutter | hex pairs | ASCII, panned together by a shared [hScroll]. */
@Composable
private fun HexRow(
    bytes: ByteArray,
    rowIndex: Int,
    cellStyle: TextStyle,
    hScroll: ScrollState,
) {
    val colors = JadxTheme.colors
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(hScroll)
            .padding(horizontal = JadxTheme.spacing.lg),
    ) {
        Text(
            HexDump.offsetLabel(rowIndex * HexDump.BYTES_PER_ROW),
            style = cellStyle,
            color = colors.gutterText,
            textAlign = TextAlign.End,
        )
        Text(
            "   " + HexDump.hexColumn(bytes, rowIndex) + "   ",
            style = cellStyle,
            color = scheme.onSurface,
        )
        Text(
            HexDump.asciiColumn(bytes, rowIndex),
            style = cellStyle,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** Human-ish byte-size caption: raw byte count plus a KiB/MiB hint for larger blobs. Pure. */
internal fun binarySizeCaption(byteCount: Int): String = when {
    byteCount < 1024 -> "$byteCount bytes"
    byteCount < 1024 * 1024 -> "$byteCount bytes (${byteCount / 1024} KiB)"
    else -> "$byteCount bytes (${byteCount / (1024 * 1024)} MiB)"
}
