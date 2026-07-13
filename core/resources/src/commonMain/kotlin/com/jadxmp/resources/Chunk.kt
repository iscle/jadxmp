package com.jadxmp.resources

import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/** A parsed `ResChunk_header` { type: u16, headerSize: u16, size: u32 } with absolute start/end. */
internal class ChunkHeader(
    val type: Int,
    val headerSize: Int,
    val size: Long,
    val start: Int,
) {
    /** Absolute offset one past the chunk. */
    val end: Int get() = start + size.toInt()

    /** Absolute offset where the chunk body (after the fixed header) begins. */
    val bodyStart: Int get() = start + headerSize
}

/** Read a `ResChunk_header` at the current position; leaves the cursor after the 8 header bytes. */
internal fun readChunkHeader(reader: ByteReader): ChunkHeader {
    val start = reader.position
    val type = reader.readU16()
    val headerSize = reader.readU16()
    val size = reader.readU32()
    if (size < 8L) throw ByteReaderException("chunk size < 8 at $start")
    // Bound the chunk to the input; the 8 header bytes are already consumed.
    reader.requireAvailable(size - 8L)
    return ChunkHeader(type, headerSize, size, start)
}

/**
 * Iterate the chunks in `[start, limit)`, calling [body] for each. A chunk whose parsing throws is
 * recorded to [diagnostics] and we advance to its declared end; a chunk that fails to make forward
 * progress (or overruns the container) aborts the loop so corruption can never spin or read wild.
 */
internal inline fun iterateChunks(
    reader: ByteReader,
    start: Int,
    limit: Int,
    diagnostics: MutableList<String>,
    body: (ChunkHeader) -> Unit,
) {
    var pos = start
    while (pos + 8 <= limit) {
        reader.seek(pos)
        val header = try {
            readChunkHeader(reader)
        } catch (e: ByteReaderException) {
            diagnostics += "bad chunk header at $pos: ${e.message}"
            return
        }
        if (header.end <= pos || header.end > limit) {
            diagnostics += "chunk at $pos overruns container (end=${header.end}, limit=$limit)"
            return
        }
        try {
            body(header)
        } catch (e: ByteReaderException) {
            diagnostics += "chunk 0x${header.type.toString(16)} at $pos: ${e.message}"
        }
        pos = header.end
    }
}

/** Read [charCount] UTF-16LE units, stopping at NUL but always consuming all `charCount*2` bytes. */
internal fun readFixedUtf16(reader: ByteReader, charCount: Int): String {
    val sb = StringBuilder()
    var ended = false
    repeat(charCount) {
        val cu = reader.readU16()
        if (cu == 0) ended = true
        if (!ended) sb.append(cu.toChar())
    }
    return sb.toString()
}
