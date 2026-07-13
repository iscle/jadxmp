package com.jadxmp.resources

import com.jadxmp.io.ByteReader

/**
 * A decoded `ResStringPool` — the shared string table used by both binary XML and `resources.arsc`.
 *
 * The chunk stores a `stringCount`-long array of byte offsets into a string-data block, then the
 * strings themselves in either UTF-8 or UTF-16 (a flag decides). Each string is length-prefixed
 * with aapt's 1-or-2-unit varint scheme. We materialise all strings up front; the count and every
 * allocation are bounded against the chunk's own bytes via [ByteReader], so a hostile length field
 * fails with a catchable `ByteReaderException` rather than an OOM.
 *
 * jadx equivalent: `BinaryXMLStrings` + `CommonBinaryParser.parseStringPool*`.
 */
internal class StringPool private constructor(
    private val strings: List<String>,
) {
    val size: Int get() = strings.size

    /** String at [index], or `null` if out of range (mirrors jadx's lenient behaviour). */
    operator fun get(index: Int): String? = strings.getOrNull(index)

    companion object {
        const val PLACEHOLDER = "⟨STRING_DECODE_ERROR⟩"

        /**
         * Parse a string-pool chunk. [reader] must be positioned immediately after the 8-byte
         * `ResChunk_header`; [chunkStart] is that header's absolute offset and [chunkEnd] the end of
         * the chunk (`chunkStart + size`).
         */
        fun parse(reader: ByteReader, chunkStart: Int, chunkEnd: Int): StringPool {
            val stringCount = reader.readS32()
            /* styleCount = */ reader.readS32()
            val flags = reader.readS32()
            val stringsStart = reader.readU32().toInt()
            /* stylesStart = */ reader.readU32()
            val utf8 = (flags and StringPoolFlags.UTF8) != 0

            if (stringCount < 0) throw com.jadxmp.io.ByteReaderException("negative string count: $stringCount")
            // Cap allocation to what the chunk can possibly hold (4 bytes per offset entry).
            reader.requireAvailable(stringCount.toLong() * 4L)

            val offsets = IntArray(stringCount)
            for (i in 0 until stringCount) {
                offsets[i] = reader.readS32()
            }

            val dataStart = chunkStart + stringsStart
            val result = ArrayList<String>(stringCount)
            for (i in 0 until stringCount) {
                val abs = dataStart + offsets[i]
                val str = try {
                    if (abs < dataStart || abs >= chunkEnd) {
                        PLACEHOLDER
                    } else {
                        reader.seek(abs)
                        if (utf8) readUtf8(reader) else readUtf16(reader)
                    }
                } catch (e: com.jadxmp.io.ByteReaderException) {
                    PLACEHOLDER
                }
                result.add(str)
            }
            return StringPool(result)
        }

        /** aapt varint length: 1 byte, or 2 when the high bit of the first byte is set. */
        private fun readLen8(reader: ByteReader): Int {
            val b0 = reader.readU8()
            return if (b0 and 0x80 != 0) ((b0 and 0x7F) shl 8) or reader.readU8() else b0
        }

        private fun readUtf8(reader: ByteReader): String {
            readLen8(reader) // character count (unused; we trust the byte count)
            val byteLen = readLen8(reader)
            val bytes = reader.readBytes(byteLen)
            // aapt writes standard UTF-8 here (not modified UTF-8); stdlib decodes it on all targets.
            return bytes.decodeToString()
        }

        /** aapt varint length for UTF-16, counted in 16-bit units: 1 unit, or 2 when high bit set. */
        private fun readLen16(reader: ByteReader): Int {
            val low = reader.readU16()
            return if (low and 0x8000 != 0) ((low and 0x7FFF) shl 16) or reader.readU16() else low
        }

        private fun readUtf16(reader: ByteReader): String {
            val units = readLen16(reader)
            val sb = StringBuilder(units)
            // Char is itself a UTF-16 code unit, so surrogate pairs round-trip verbatim.
            repeat(units) { sb.append(reader.readU16().toChar()) }
            return sb.toString()
        }
    }
}
