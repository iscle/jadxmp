package com.jadxmp.resources

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Turns a raw [ResourceValue] into the text form aapt would have produced (`"true"`, `"16dp"`,
 * `"#ff0000"`, `"@string/app_name"`, `"?android:attr/theme"`, …).
 *
 * Two things are injected because they differ by context:
 *  - [strings]: resolve a string-pool index (the XML pool for binary XML, the global table pool for
 *    ARSC values). Returns `null`/empty for out-of-range indices.
 *  - [resolveRef]: resolve a resource-id reference to `type/name` (app) or `android:type/name`
 *    (framework), or `null` if unknown. See [ResourceTable.symbolicName].
 *
 * Pure `commonMain`: number/hex/complex formatting is hand-rolled so there is no `java.*` or locale
 * dependency (no `String.format`, no `NumberFormat`). jadx equivalent: `ValuesParser.decodeValue`.
 */
internal object ValueFormatter {

    fun format(
        value: ResourceValue,
        strings: (Int) -> String?,
        resolveRef: (Int) -> String?,
    ): String = format(value.type, value.data, strings, resolveRef)

    fun format(
        type: Int,
        data: Int,
        strings: (Int) -> String?,
        resolveRef: (Int) -> String?,
    ): String = when (type) {
        ResValueType.NULL -> if (data == 1) "@empty" else "@null"

        ResValueType.REFERENCE, ResValueType.DYNAMIC_REFERENCE -> {
            val ref = resolveRef(data)
            when {
                ref != null -> "@" + (if (ref.startsWith("id/")) "+" else "") + ref
                data == 0 -> "@null"
                else -> "@0x" + hex(data)
            }
        }

        ResValueType.ATTRIBUTE, ResValueType.DYNAMIC_ATTRIBUTE -> {
            val ref = resolveRef(data)
            if (ref != null) "?$ref" else "?0x" + hex(data)
        }

        ResValueType.STRING -> strings(data) ?: ""
        ResValueType.FLOAT -> numberToString(Float.fromBits(data).toDouble())
        ResValueType.DIMENSION -> complexToString(data, fraction = false)
        ResValueType.FRACTION -> complexToString(data, fraction = true)
        ResValueType.INT_DEC -> data.toString()
        ResValueType.INT_HEX -> "0x" + hex(data)
        ResValueType.INT_BOOLEAN -> if (data != 0) "true" else "false"
        ResValueType.INT_COLOR_ARGB8 -> "#" + hexPadded(data, 8)
        ResValueType.INT_COLOR_RGB8 -> "#" + hexPadded(data and 0xFFFFFF, 6)
        ResValueType.INT_COLOR_ARGB4 -> "#" + hexPadded(data and 0xFFFF, 4)
        ResValueType.INT_COLOR_RGB4 -> "#" + hexPadded(data and 0xFFF, 3)

        else -> "0x" + hex(data)
    }

    /** Lower-case hex of an unsigned 32-bit value, no leading zeros (like `Integer.toHexString`). */
    private fun hex(data: Int): String = data.toUInt().toString(16)

    /** Lower-case hex of [value] left-padded with zeros to [width] digits. */
    private fun hexPadded(value: Int, width: Int): String {
        val s = value.toUInt().toString(16)
        return if (s.length >= width) s else "0".repeat(width - s.length) + s
    }

    // COMPLEX_* layout from ResourceTypes.h: mantissa (top 23 bits) * radix-multiplier + unit.
    private const val UNIT_MASK = 0xf
    private const val RADIX_SHIFT = 4
    private const val RADIX_MASK = 0x3
    private const val MANTISSA_SHIFT = 8
    private const val MANTISSA_MASK = 0xffffff
    private const val MANTISSA_MULT = 1.0 / (1 shl MANTISSA_SHIFT)
    private val RADIX_MULTS = doubleArrayOf(
        MANTISSA_MULT,
        1.0 / (1 shl 7) * MANTISSA_MULT,
        1.0 / (1 shl 15) * MANTISSA_MULT,
        1.0 / (1 shl 23) * MANTISSA_MULT,
    )

    private fun complexToString(data: Int, fraction: Boolean): String {
        val mantissa = (data shr MANTISSA_SHIFT) and MANTISSA_MASK
        var value = mantissa * RADIX_MULTS[(data shr RADIX_SHIFT) and RADIX_MASK]
        val unitType = data and UNIT_MASK
        val unit = if (fraction) {
            value *= 100.0
            when (unitType) {
                0 -> "%"
                1 -> "%p"
                else -> "?f" + unitType.toString(16)
            }
        } else {
            when (unitType) {
                0 -> "px"
                1 -> "dp"
                2 -> "sp"
                3 -> "pt"
                4 -> "in"
                5 -> "mm"
                else -> "?d" + unitType.toString(16)
            }
        }
        return numberToString(value) + unit
    }

    /**
     * Format a floating value like aapt: integers print without a decimal point, otherwise up to
     * four fraction digits with trailing zeros trimmed. Hand-rolled to stay locale- and JVM-free.
     */
    fun numberToString(value: Double): String {
        if (value.isFinite() && value == floor(value)) {
            return value.toLong().toString()
        }
        val negative = value < 0
        val scaled = (abs(value) * 10000.0).roundToLong()
        val intPart = scaled / 10000
        var frac = (scaled % 10000).toInt()
        val sb = StringBuilder()
        if (negative && (intPart != 0L || frac != 0)) sb.append('-')
        sb.append(intPart)
        if (frac != 0) {
            sb.append('.')
            // zero-pad to 4 digits, then trim trailing zeros
            val digits = frac.toString().padStart(4, '0').trimEnd('0')
            sb.append(digits)
        }
        return sb.toString()
    }
}
