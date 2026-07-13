package com.jadxmp.io

/**
 * Thrown when a read would go out of bounds or an encoding is malformed.
 *
 * Binary parsing in jadxmp must never crash the process on hostile/truncated input — callers are
 * expected to catch this and degrade gracefully (see docs/CONVENTIONS.md).
 */
public class ByteReaderException(message: String) : Exception(message)

/**
 * A little-endian cursor over a [ByteArray] with the primitive reads the DEX/class/zip formats need.
 *
 * Pure `commonMain`: no `java.*`, no external dependencies, so it compiles and runs identically on
 * JVM, wasmJs, JS, and Android. This is the single foundation every binary parser in the engine
 * reads through.
 *
 * All multi-byte integers are little-endian (the DEX convention). Signed variants sign-extend;
 * unsigned variants widen into the next larger signed Kotlin type so the value is always exact.
 */
public class ByteReader(
    private val data: ByteArray,
    start: Int = 0,
    private val end: Int = data.size,
) {
    init {
        require(start in 0..data.size) { "start out of range: $start" }
        require(end in start..data.size) { "end out of range: $end" }
    }

    /** Absolute offset of the next byte to be read. */
    public var position: Int = start
        private set

    /** Bytes remaining before [end]. */
    public val remaining: Int get() = end - position

    /** Move the cursor to an absolute [offset]. */
    public fun seek(offset: Int) {
        if (offset < 0 || offset > end) {
            throw ByteReaderException("seek to $offset out of range [0, $end]")
        }
        position = offset
    }

    /** Advance the cursor by [count] bytes. */
    public fun skip(count: Int) {
        require(count >= 0) { "cannot skip a negative count: $count" }
        seek(position + count)
    }

    private fun require(count: Int) {
        if (count > remaining) {
            throw ByteReaderException("need $count byte(s) at $position but only $remaining remain")
        }
    }

    /**
     * Assert that [byteCount] more bytes are available, throwing [ByteReaderException] otherwise.
     *
     * Parsers MUST call this before allocating any array/collection whose size comes from an
     * attacker-controlled length field — it caps allocation to what the input could possibly contain,
     * turning a crafted "1-billion-element" size into a graceful failure instead of an OutOfMemoryError.
     */
    public fun requireAvailable(byteCount: Long) {
        if (byteCount < 0L || byteCount > remaining.toLong()) {
            throw ByteReaderException("need $byteCount byte(s) at $position but only $remaining remain")
        }
    }

    /** Read one byte as an unsigned value in `0..255`. */
    public fun readU8(): Int {
        require(1)
        return data[position++].toInt() and 0xFF
    }

    /** Read one byte as a signed value in `-128..127`. */
    public fun readS8(): Int {
        require(1)
        return data[position++].toInt()
    }

    /** Read two little-endian bytes as an unsigned value in `0..65535`. */
    public fun readU16(): Int {
        require(2)
        val b0 = data[position].toInt() and 0xFF
        val b1 = data[position + 1].toInt() and 0xFF
        position += 2
        return b0 or (b1 shl 8)
    }

    /** Read two little-endian bytes as a signed 16-bit value. */
    public fun readS16(): Int = (readU16() shl 16) shr 16

    /** Read four little-endian bytes as a signed 32-bit [Int]. */
    public fun readS32(): Int {
        require(4)
        val b0 = data[position].toInt() and 0xFF
        val b1 = data[position + 1].toInt() and 0xFF
        val b2 = data[position + 2].toInt() and 0xFF
        val b3 = data[position + 3].toInt() and 0xFF
        position += 4
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    /** Read four little-endian bytes as an unsigned 32-bit value, widened into a [Long]. */
    public fun readU32(): Long = readS32().toLong() and 0xFFFF_FFFFL

    /** Read eight little-endian bytes as a signed 64-bit [Long]. */
    public fun readS64(): Long {
        val low = readU32()
        val high = readU32()
        return low or (high shl 32)
    }

    /** Read [count] raw bytes into a new array. */
    public fun readBytes(count: Int): ByteArray {
        require(count >= 0) { "cannot read a negative count: $count" }
        require(count)
        return data.copyOfRange(position, position + count).also { position += count }
    }

    /**
     * Read an unsigned LEB128 value. DEX uses these for indices and sizes; the encoded value fits in
     * an unsigned 32-bit range, returned widened into a [Long].
     */
    public fun readUleb128(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (shift > 35) throw ByteReaderException("uleb128 too long at $position")
            val byte = readU8()
            result = result or ((byte.toLong() and 0x7F) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    /**
     * Read an unsigned LEB128 value biased by one (`uleb128p1`): the encoded value minus one, so it
     * can represent `-1`. Returned as a signed [Int] as the DEX spec intends.
     */
    public fun readUleb128p1(): Int = (readUleb128() - 1L).toInt()

    /** Read a signed LEB128 value, sign-extended into an [Int]. */
    public fun readSleb128(): Int {
        var result = 0
        var shift = 0
        var byte: Int
        do {
            if (shift > 31) throw ByteReaderException("sleb128 too long at $position")
            byte = readU8()
            result = result or ((byte and 0x7F) shl shift)
            shift += 7
        } while (byte and 0x80 != 0)
        // Sign-extend if the last significant bit was set and we have not filled all 32 bits.
        if (shift < 32 && (byte and 0x40) != 0) {
            result = result or (0.inv() shl shift)
        }
        return result
    }

    /**
     * Decode a DEX/JVM **modified UTF-8** string of [utf16Units] UTF-16 code units.
     *
     * Modified UTF-8 differs from standard UTF-8 in two ways this handles: the null character is
     * encoded as the two bytes `0xC0 0x80` (never a bare `0x00`), and characters outside the BMP are
     * encoded as a surrogate pair of three-byte sequences rather than a single four-byte sequence.
     */
    public fun readMutf8(utf16Units: Int): String {
        require(utf16Units >= 0) { "negative length: $utf16Units" }
        val sb = StringBuilder(utf16Units)
        var produced = 0
        while (produced < utf16Units) {
            val a = readU8()
            when {
                a < 0x80 -> {
                    sb.append(a.toChar())
                }
                a and 0xE0 == 0xC0 -> {
                    val b = readU8()
                    if (b and 0xC0 != 0x80) throw ByteReaderException("bad mutf8 2-byte seq at $position")
                    sb.append((((a and 0x1F) shl 6) or (b and 0x3F)).toChar())
                }
                a and 0xF0 == 0xE0 -> {
                    val b = readU8()
                    val c = readU8()
                    if (b and 0xC0 != 0x80 || c and 0xC0 != 0x80) {
                        throw ByteReaderException("bad mutf8 3-byte seq at $position")
                    }
                    sb.append((((a and 0x0F) shl 12) or ((b and 0x3F) shl 6) or (c and 0x3F)).toChar())
                }
                else -> throw ByteReaderException("invalid mutf8 lead byte 0x${a.toString(16)} at $position")
            }
            produced++
        }
        return sb.toString()
    }
}
