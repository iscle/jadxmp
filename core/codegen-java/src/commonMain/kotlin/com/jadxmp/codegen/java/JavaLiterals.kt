package com.jadxmp.codegen.java

import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.type.TypeKind

/**
 * Formats constant values as Java source literals. **jadx: literal handling in InsnGen / StringUtils**
 *
 * A [LiteralOperand] carries the raw bit pattern in a [Long]; its meaning comes from the operand type
 * (float/double are IEEE-754 bits). Booleans, chars, longs (`L` suffix), floats (`f` suffix), doubles,
 * and `null` are each rendered in their canonical Java form, with correct string/char escaping.
 */
internal object JavaLiterals {

    // Integers with magnitude up to 2^53 (double) / 2^24 (float) are represented exactly, so an
    // integer-valued value in range round-trips through toLong/toDouble and can render as decimal `N.0`.
    private val DOUBLE_EXACT_LONG_RANGE = -(1L shl 53)..(1L shl 53)
    private val FLOAT_EXACT_LONG_RANGE = -(1L shl 24)..(1L shl 24)

    fun format(op: LiteralOperand): String {
        val v = op.value
        return when (val kind = op.type.primitiveKind()) {
            TypeKind.BOOLEAN -> if (v != 0L) "true" else "false"
            TypeKind.CHAR -> charLiteral(v.toInt())
            TypeKind.BYTE, TypeKind.SHORT, TypeKind.INT -> v.toInt().toString()
            TypeKind.LONG -> v.toString() + "L"
            TypeKind.FLOAT -> floatLiteral(Float.fromBits(v.toInt()))
            TypeKind.DOUBLE -> doubleLiteral(Double.fromBits(v))
            else -> {
                // Reference (or still-partial) type: zero is null, otherwise fall back to an int.
                if (op.type.isReferenceLike()) {
                    if (v == 0L) "null" else v.toString()
                } else {
                    v.toInt().toString()
                }
            }
        }
    }

    fun stringLiteral(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) sb.append(escapeChar(c, inString = true))
        sb.append('"')
        return sb.toString()
    }

    private fun charLiteral(code: Int): String {
        val c = code.toChar()
        return "'" + escapeChar(c, inString = false) + "'"
    }

    private fun escapeChar(c: Char, inString: Boolean): String = when (c) {
        '\\' -> "\\\\"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        '\b' -> "\\b"
        '' -> "\\f"
        '"' -> if (inString) "\\\"" else "\""
        '\'' -> if (inString) "'" else "\\'"
        else -> if (c.code in 0x20..0x7E) c.toString() else unicodeEscape(c)
    }

    private fun unicodeEscape(c: Char): String {
        val hex = c.code.toString(16).padStart(4, '0')
        return "\\u$hex"
    }

    /**
     * Float/double literals must be **byte-identical across platforms** (the accuracy oracle diffs text,
     * and Kotlin's `Double.toString`/`Float.toString` are explicitly platform-unspecified). We therefore
     * never call `toString`: integer-valued values render as decimal `N.0` (computed via `toLong`, which
     * is exact and deterministic), and every other finite value renders as a Java **hexadecimal
     * floating-point literal** built purely from its IEEE-754 bits — exact, round-tripping, and the same
     * on JVM, JS and wasm.
     */
    private fun floatLiteral(f: Float): String {
        when {
            f.isNaN() -> return "Float.NaN"
            f == Float.POSITIVE_INFINITY -> return "Float.POSITIVE_INFINITY"
            f == Float.NEGATIVE_INFINITY -> return "Float.NEGATIVE_INFINITY"
        }
        if (f == 0.0f) return if (f.toRawBits() == 0) "0.0f" else hexFloat(f)
        val asLong = f.toLong()
        if (asLong in FLOAT_EXACT_LONG_RANGE && asLong.toFloat() == f) return "$asLong.0f"
        return hexFloat(f)
    }

    private fun doubleLiteral(d: Double): String {
        when {
            d.isNaN() -> return "Double.NaN"
            d == Double.POSITIVE_INFINITY -> return "Double.POSITIVE_INFINITY"
            d == Double.NEGATIVE_INFINITY -> return "Double.NEGATIVE_INFINITY"
        }
        if (d == 0.0) return if (d.toRawBits() == 0L) "0.0" else hexDouble(d)
        val asLong = d.toLong()
        if (asLong in DOUBLE_EXACT_LONG_RANGE && asLong.toDouble() == d) return "$asLong.0"
        return hexDouble(d)
    }

    private fun hexDouble(d: Double): String {
        val bits = d.toRawBits()
        val sign = if (bits < 0) "-" else ""
        val exp = ((bits ushr 52) and 0x7FF).toInt()
        val mant = bits and 0xFFFFFFFFFFFFFL
        return when {
            exp == 0 && mant == 0L -> "${sign}0x0.0p0"
            exp == 0 -> "${sign}0x0.${trimHex(mant, 13)}p-1022"
            else -> "${sign}0x1.${trimHex(mant, 13)}p${exp - 1023}"
        }
    }

    private fun hexFloat(f: Float): String {
        val bits = f.toRawBits()
        val sign = if (bits < 0) "-" else ""
        val exp = (bits ushr 23) and 0xFF
        val mant = bits and 0x7FFFFF
        // The 23-bit significand is shifted left to fill 24 bits (6 hex digits), matching Java's form.
        val hex = trimHex(((mant.toLong() shl 1) and 0xFFFFFF), 6)
        val body = when {
            exp == 0 && mant == 0 -> "${sign}0x0.0p0"
            exp == 0 -> "${sign}0x0.${hex}p-126"
            else -> "${sign}0x1.${hex}p${exp - 127}"
        }
        return body + "f"
    }

    /** Fixed-width lowercase hex of [value]'s low bits, trailing zeros trimmed (never empty). */
    private fun trimHex(value: Long, digits: Int): String {
        val padded = value.toString(16).padStart(digits, '0')
        val trimmed = padded.trimEnd('0')
        return trimmed.ifEmpty { "0" }
    }

    private fun IrType.primitiveKind(): TypeKind? = (this as? IrType.Primitive)?.kind

    private fun IrType.isReferenceLike(): Boolean = when (this) {
        is IrType.Object, is IrType.ArrayType, is IrType.TypeVariable, is IrType.Wildcard -> true
        is IrType.Unknown -> possible.all { it == TypeKind.OBJECT || it == TypeKind.ARRAY }
        else -> false
    }
}
