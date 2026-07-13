package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.type.TypeKind

/**
 * Formats constant values as Kotlin source literals. **jadx: literal handling in InsnGen / StringUtils**
 *
 * A [LiteralOperand] carries the raw bit pattern in a [Long]; its meaning comes from the operand type
 * (float/double are IEEE-754 bits). Kotlin's literal grammar differs from Java's in ways that matter
 * for correctness:
 *  - **no hexadecimal floating-point literal** exists in Kotlin, so a non-integer float/double that
 *    cannot be written as `N.0` is emitted as an exact, deterministic `Float.fromBits(..)` /
 *    `Double.fromBits(..)` reconstruction rather than a `toString` (which is platform-unspecified);
 *  - the long suffix is `L`, the float suffix `f`;
 *  - `$` must be escaped inside a string literal (it introduces a template), but not inside a char.
 */
internal object KotlinLiterals {

    // Integers with magnitude up to 2^53 (double) / 2^24 (float) are represented exactly.
    private val DOUBLE_EXACT_LONG_RANGE = -(1L shl 53)..(1L shl 53)
    private val FLOAT_EXACT_LONG_RANGE = -(1L shl 24)..(1L shl 24)

    // The canonical quiet-NaN bit patterns; any other NaN payload is preserved via fromBits.
    private const val CANONICAL_FLOAT_NAN: Int = 0x7fc00000
    private const val CANONICAL_DOUBLE_NAN: Long = 0x7ff8000000000000L

    fun format(op: LiteralOperand): String {
        val v = op.value
        return when (op.type.primitiveKind()) {
            TypeKind.BOOLEAN -> if (v != 0L) "true" else "false"
            TypeKind.CHAR -> charLiteral(v.toInt())
            TypeKind.BYTE, TypeKind.SHORT, TypeKind.INT -> v.toInt().toString()
            TypeKind.LONG -> v.toString() + "L"
            TypeKind.FLOAT -> floatLiteral(Float.fromBits(v.toInt()))
            TypeKind.DOUBLE -> doubleLiteral(Double.fromBits(v))
            else -> {
                if (op.type.isReferenceLike()) {
                    // TODO(N2): a *nonzero* reference-typed literal has no valid source form (it is a raw
                    // handle); we fall back to its integer bits, matching the Java backend. Real inputs
                    // only ever carry the zero (`null`) reference constant, so this is a defensive path.
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
        // Kotlin has no `\f`/octal escape; a form feed (and any other control char) uses \uXXXX.
        '$' -> if (inString) "\\$" else "$"
        '"' -> if (inString) "\\\"" else "\""
        '\'' -> if (inString) "'" else "\\'"
        else -> if (c.code in 0x20..0x7E) c.toString() else unicodeEscape(c)
    }

    private fun unicodeEscape(c: Char): String {
        val hex = c.code.toString(16).padStart(4, '0')
        return "\\u$hex"
    }

    /**
     * Kotlin float literal. Integer-valued floats render as decimal `N.0f` (computed via `toLong`,
     * exact and deterministic); NaN/Infinity use the stdlib constants; every other finite value is
     * reconstructed exactly with `Float.fromBits`, since Kotlin has no hex-float literal and
     * `Float.toString` is platform-unspecified.
     */
    private fun floatLiteral(f: Float): String {
        when {
            // A non-canonical NaN bit pattern must round-trip exactly (consistency with fromBits below),
            // so only the canonical NaN collapses to the named constant.
            f.isNaN() -> return if (f.toRawBits() == CANONICAL_FLOAT_NAN) "Float.NaN" else "Float.fromBits(${f.toRawBits()})"
            f == Float.POSITIVE_INFINITY -> return "Float.POSITIVE_INFINITY"
            f == Float.NEGATIVE_INFINITY -> return "Float.NEGATIVE_INFINITY"
        }
        if (f == 0.0f) return if (f.toRawBits() == 0) "0.0f" else "Float.fromBits(${f.toRawBits()})"
        val asLong = f.toLong()
        if (asLong in FLOAT_EXACT_LONG_RANGE && asLong.toFloat() == f) return "$asLong.0f"
        return "Float.fromBits(${f.toRawBits()})"
    }

    private fun doubleLiteral(d: Double): String {
        when {
            d.isNaN() -> return if (d.toRawBits() == CANONICAL_DOUBLE_NAN) "Double.NaN" else "Double.fromBits(${d.toRawBits()}L)"
            d == Double.POSITIVE_INFINITY -> return "Double.POSITIVE_INFINITY"
            d == Double.NEGATIVE_INFINITY -> return "Double.NEGATIVE_INFINITY"
        }
        if (d == 0.0) return if (d.toRawBits() == 0L) "0.0" else "Double.fromBits(${d.toRawBits()}L)"
        val asLong = d.toLong()
        if (asLong in DOUBLE_EXACT_LONG_RANGE && asLong.toDouble() == d) return "$asLong.0"
        return "Double.fromBits(${d.toRawBits()}L)"
    }

    private fun IrType.primitiveKind(): TypeKind? = (this as? IrType.Primitive)?.kind

    private fun IrType.isReferenceLike(): Boolean = when (this) {
        is IrType.Object, is IrType.ArrayType, is IrType.TypeVariable, is IrType.Wildcard -> true
        is IrType.Unknown -> possible.all { it == TypeKind.OBJECT || it == TypeKind.ARRAY }
        else -> false
    }
}
