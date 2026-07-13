package com.jadxmp.input.dex

import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/**
 * Guards against the "attacker-controlled length field → giant allocation → OutOfMemoryError" class
 * of bug. Every size read from the wire is validated against the bytes actually remaining before it
 * is used to size an array or collection, so a crafted count can never force an allocation larger
 * than the input could possibly contain.
 */
internal object Bounds {

    /**
     * Validate a fixed-size element count: rejects negatives and any count whose elements
     * (`count * stride` bytes) cannot fit in [reader]. Use before allocating a fixed primitive array
     * that will be fully populated.
     */
    fun checkCount(count: Int, stride: Int, reader: ByteReader): Int {
        if (count < 0) throw ByteReaderException("negative element count: $count")
        reader.requireAvailable(count.toLong() * stride.coerceAtLeast(1))
        return count
    }

    /**
     * A safe *preallocation capacity* for a growable collection: never larger than what [reader] could
     * hold ([reader.remaining] / [stride]). The read loop still consumes real bytes and fails
     * gracefully if the data is short, so an honest-but-large count keeps its capacity while a hostile
     * one is clamped. Rejects negatives.
     */
    fun capacity(count: Int, stride: Int, reader: ByteReader): Int {
        if (count < 0) throw ByteReaderException("negative element count: $count")
        val maxPossible = reader.remaining / stride.coerceAtLeast(1)
        return minOf(count, maxOf(maxPossible, 0))
    }
}
