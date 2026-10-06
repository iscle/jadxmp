package com.jadxmp.codegen

/**
 * Deterministic compile-time floating constants accepted by both Java and Kotlin, for annotation
 * defaults/values where runtime bit-conversion calls are illegal. Finite values use their exact
 * decimal expansion, without host floating-point arithmetic, formatting or platform libraries.
 *
 * Only the positive canonical quiet NaN is representable here; other NaN signs/payloads return null
 * so callers can diagnose lost bit identity. Infinities and canonical NaNs use constant arithmetic,
 * avoiding a potentially shadowed Float/Double owner. Ordinary expression literal policy is separate.
 *
 * A call retains at most 128 base-10^9 limbs, performs at most 90 bounded limb multiplication passes
 * (each at most 128 limbs), and returns at most [MAX_SOURCE_LENGTH] characters. Emitters processing
 * many values should charge returned source length against their aggregate output/work budget.
 */
object FloatingConstantLiteral {
    const val MAX_SOURCE_LENGTH: Int = 1078

    fun float(bits: Int): String? {
        val fraction = bits and 0x007fffff
        val exponent = (bits ushr 23) and 0xff
        val negative = bits < 0
        if (exponent == 0xff) {
            if (fraction != 0) return if (bits == 0x7fc00000) "(0.0f / 0.0f)" else null
            return if (negative) "(-1.0f / 0.0f)" else "(1.0f / 0.0f)"
        }
        val mantissa = if (exponent == 0) fraction.toLong() else (fraction or 0x00800000).toLong()
        return finite(mantissa, if (exponent == 0) -149 else exponent - 150, negative) + "f"
    }

    fun double(bits: Long): String? {
        val fraction = bits and 0x000fffffffffffffL
        val exponent = ((bits ushr 52) and 0x7ff).toInt()
        val negative = bits < 0
        if (exponent == 0x7ff) {
            if (fraction != 0L) return if (bits == 0x7ff8000000000000L) "(0.0 / 0.0)" else null
            return if (negative) "(-1.0 / 0.0)" else "(1.0 / 0.0)"
        }
        val mantissa = if (exponent == 0) fraction else fraction or 0x0010000000000000L
        return finite(mantissa, if (exponent == 0) -1074 else exponent - 1075, negative)
    }

    private fun finite(significand: Long, binaryExponent: Int, negative: Boolean): String {
        val sign = if (negative) "-" else ""
        if (significand == 0L) return sign + "0.0"
        var mantissa = significand
        var exponent = binaryExponent
        // Cancel denominator factors first. This also avoids unnecessary trailing decimal zeroes.
        while (exponent < 0 && mantissa and 1L == 0L) {
            mantissa = mantissa ushr 1
            exponent++
        }
        val integer = DecimalInteger(mantissa)
        if (exponent >= 0) {
            integer.multiplyPower(2, exponent, 29)
            return sign + integer.digits() + ".0"
        }
        // m / 2^n = (m * 5^n) / 10^n, with n <= 1074 for an IEEE binary64 value.
        val scale = -exponent
        integer.multiplyPower(5, scale, 12)
        val digits = integer.digits()
        val point = digits.length - scale
        return if (point > 0) {
            sign + digits.substring(0, point) + "." + digits.substring(point)
        } else {
            sign + "0." + "0".repeat(-point) + digits
        }
    }

    /** Fixed-capacity magnitude; all intermediate products fit signed Long even on JS/Wasm. */
    private class DecimalInteger(value: Long) {
        private val limbs = IntArray(128)
        private var size = 0

        init {
            var remaining = value
            do {
                limbs[size++] = (remaining % BASE).toInt()
                remaining /= BASE
            } while (remaining != 0L)
        }

        fun multiplyPower(base: Int, exponent: Int, chunk: Int) {
            var remaining = exponent
            while (remaining > 0) {
                val count = minOf(remaining, chunk)
                var factor = 1
                repeat(count) { factor *= base }
                multiply(factor)
                remaining -= count
            }
        }

        private fun multiply(factor: Int) {
            var carry = 0L
            for (index in 0 until size) {
                val product = limbs[index].toLong() * factor + carry
                limbs[index] = (product % BASE).toInt()
                carry = product / BASE
            }
            if (carry != 0L) limbs[size++] = carry.toInt()
        }

        fun digits(): String = buildString(size * 9) {
            append(limbs[size - 1])
            for (index in size - 2 downTo 0) append(limbs[index].toString().padStart(9, '0'))
        }

        private companion object { const val BASE = 1_000_000_000L }
    }
}
