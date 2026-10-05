package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/** Ordered JVM handlers; the first matching entry wins, so parsing must never group or sort them. */
internal data class JvmExceptionHandler(
    val startOffset: Int,
    val endOffset: Int,
    val handlerOffset: Int,
    val catchType: String?,
)

/**
 * A bounded Code payload, decoded independently so a bad method need not discard its class.
 * Offsets in handlers are relative to [bytes]; [codeOffset] and attribute offsets are absolute.
 * [decodeInstructions] validates instruction boundaries; stack/local types require frame analysis.
 */
internal class JvmCodeAttribute private constructor(
    val maxStack: Int,
    val maxLocals: Int,
    val codeOffset: Int,
    val bytes: ByteArray,
    val handlers: List<JvmExceptionHandler>,
    val attributes: List<JvmAttribute>,
) {
    fun decodeInstructions(): List<JvmInstruction> {
        val instructions = JvmBytecodeDecoder.decode(bytes)
        val boundaries = BooleanArray(bytes.size + 1)
        for (instruction in instructions) boundaries[instruction.offset] = true
        // Only the exclusive protected-range end may be exactly code_length.
        boundaries[bytes.size] = true
        for (handler in handlers) {
            if (!boundaries[handler.startOffset] || !boundaries[handler.endOffset] ||
                !boundaries[handler.handlerOffset]) {
                throw ByteReaderException("JVM exception handler points inside an instruction: $handler")
            }
        }
        return instructions
    }

    companion object {
        fun parse(attribute: JvmAttribute, constants: JvmConstantPool): JvmCodeAttribute {
            if (attribute.name != "Code") throw ByteReaderException("expected JVM Code attribute")
            if (attribute.offset < 0 || attribute.offset.toLong() + attribute.bytes.size > Int.MAX_VALUE) {
                throw ByteReaderException("invalid JVM Code attribute offset")
            }
            val reader = ByteReader(attribute.bytes)
            val maxStack = reader.readU16BE()
            val maxLocals = reader.readU16BE()
            val length = reader.readU32BE()
            if (length !in 1L..65535L) throw ByteReaderException("invalid JVM code length $length")
            reader.requireAvailable(length)
            val codeOffset = attribute.offset + reader.position
            val code = reader.readBytes(length.toInt())
            val handlerCount = reader.readU16BE()
            reader.requireAvailable(handlerCount.toLong() * 8)
            val handlers = List(handlerCount) {
                val start = reader.readU16BE()
                val end = reader.readU16BE()
                val handler = reader.readU16BE()
                val catchIndex = reader.readU16BE()
                if (start >= end || end > code.size || handler >= code.size) {
                    throw ByteReaderException("invalid JVM exception range [$start, $end) -> $handler")
                }
                val catchType = if (catchIndex == 0) null else constants.className(catchIndex, allowArray = false)
                JvmExceptionHandler(start, end, handler, catchType)
            }
            val nestedAttributes = ClassFileParser.attributes(reader, constants).map {
                JvmAttribute(it.name, attribute.offset + it.offset, it.bytes)
            }
            if (reader.remaining != 0) throw ByteReaderException("trailing bytes in JVM Code attribute")
            return JvmCodeAttribute(maxStack, maxLocals, codeOffset, code, handlers, nestedAttributes)
        }
    }
}
