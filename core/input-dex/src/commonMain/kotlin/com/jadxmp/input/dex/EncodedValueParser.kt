package com.jadxmp.input.dex

import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/**
 * Decodes DEX `encoded_value` / `encoded_array` blobs (annotation elements, static-field initial
 * values, and `call_site` arguments) into the normalized [EncodedValue] model.
 *
 * The one-byte header packs `(value_arg << 5) | value_type`; `value_arg` is a size-minus-one for the
 * variable-width numeric encodings, and integer-ish values are little-endian and (where signed)
 * sign-extended from `size` bytes.
 *
 * jadx: EncodedValueParser
 */
internal class EncodedValueParser(private val dex: Dex) {

    fun parseValue(input: ByteReader): EncodedValue {
        val argAndType = input.readU8()
        val type = argAndType and 0x1F
        val arg = (argAndType and 0xE0) ushr 5
        val size = arg + 1
        return when (type) {
            VALUE_NULL -> EncodedValue.NULL
            VALUE_BOOLEAN -> EncodedValue(EncodedValueType.BOOLEAN, arg == 1)
            VALUE_BYTE -> EncodedValue(EncodedValueType.BYTE, input.readS8().toByte())
            VALUE_SHORT -> EncodedValue(EncodedValueType.SHORT, parseNumber(input, size, signExtend = true).toShort())
            VALUE_CHAR -> EncodedValue(EncodedValueType.CHAR, parseUnsigned(input, size).toChar())
            VALUE_INT -> EncodedValue(EncodedValueType.INT, parseNumber(input, size, signExtend = true).toInt())
            VALUE_LONG -> EncodedValue(EncodedValueType.LONG, parseNumber(input, size, signExtend = true))
            VALUE_FLOAT -> EncodedValue(
                EncodedValueType.FLOAT,
                Float.fromBits(parseNumber(input, size, signExtend = false, fillRightTo = 4).toInt()),
            )
            VALUE_DOUBLE -> EncodedValue(
                EncodedValueType.DOUBLE,
                Double.fromBits(parseNumber(input, size, signExtend = false, fillRightTo = 8)),
            )
            VALUE_STRING -> EncodedValue(EncodedValueType.STRING, dex.string(parseUnsigned(input, size)))
            VALUE_TYPE -> EncodedValue(EncodedValueType.TYPE, dex.type(parseUnsigned(input, size)))
            VALUE_FIELD -> EncodedValue(EncodedValueType.FIELD, dex.fieldRef(parseUnsigned(input, size)))
            VALUE_ENUM -> EncodedValue(EncodedValueType.ENUM, dex.fieldRef(parseUnsigned(input, size)))
            VALUE_METHOD -> EncodedValue(EncodedValueType.METHOD, dex.methodRef(parseUnsigned(input, size)))
            VALUE_METHOD_TYPE -> EncodedValue(EncodedValueType.METHOD_TYPE, dex.proto(parseUnsigned(input, size)))
            VALUE_METHOD_HANDLE -> EncodedValue(EncodedValueType.METHOD_HANDLE, dex.methodHandle(parseUnsigned(input, size)))
            VALUE_ARRAY -> EncodedValue(EncodedValueType.ARRAY, parseArray(input))
            VALUE_ANNOTATION -> EncodedValue(
                EncodedValueType.ANNOTATION,
                AnnotationsParser(dex).readAnnotation(input, readVisibility = false),
            )
            else -> throw ByteReaderException("unknown encoded value type: 0x${type.toString(16)}")
        }
    }

    fun parseArray(input: ByteReader): List<EncodedValue> {
        val count = input.readUleb128().toInt()
        // Each element is at least one byte, so cap the preallocation to the bytes remaining; a hostile
        // huge/negative count fails gracefully when the reads run out rather than OOM-ing here.
        return ArrayList<EncodedValue>(Bounds.capacity(count, stride = 1, reader = input)).apply {
            repeat(count) { add(parseValue(input)) }
        }
    }

    private fun parseUnsigned(input: ByteReader, byteCount: Int): Int =
        parseNumber(input, byteCount, signExtend = false).toInt()

    private fun parseNumber(
        input: ByteReader,
        byteCount: Int,
        signExtend: Boolean,
        fillRightTo: Int = 0,
    ): Long {
        var result = 0L
        var last = 0L
        for (i in 0 until byteCount) {
            last = input.readU8().toLong()
            result = result or (last shl (i * 8))
        }
        if (fillRightTo != 0) {
            // float/double: value occupies the high bytes, zero-fill the low ones.
            for (i in byteCount until fillRightTo) {
                result = result shl 8
            }
        } else if (signExtend && (last and 0x80L) != 0L) {
            for (i in byteCount until 8) {
                result = result or (0xFFL shl (i * 8))
            }
        }
        return result
    }

    private companion object {
        const val VALUE_BYTE = 0x00
        const val VALUE_SHORT = 0x02
        const val VALUE_CHAR = 0x03
        const val VALUE_INT = 0x04
        const val VALUE_LONG = 0x06
        const val VALUE_FLOAT = 0x10
        const val VALUE_DOUBLE = 0x11
        const val VALUE_METHOD_TYPE = 0x15
        const val VALUE_METHOD_HANDLE = 0x16
        const val VALUE_STRING = 0x17
        const val VALUE_TYPE = 0x18
        const val VALUE_FIELD = 0x19
        const val VALUE_METHOD = 0x1a
        const val VALUE_ENUM = 0x1b
        const val VALUE_ARRAY = 0x1c
        const val VALUE_ANNOTATION = 0x1d
        const val VALUE_NULL = 0x1e
        const val VALUE_BOOLEAN = 0x1f
    }
}
