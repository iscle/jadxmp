package com.jadxmp.input.jvm

import com.jadxmp.input.Opcode
import com.jadxmp.io.ByteReaderException

/** Resolve a component once per method, sharing the constant resolver's bounded string work. */
internal class JvmArrayOperands(
    private val charge: (Long) -> Unit,
    private val reference: (String) -> JvmFrameValue.Reference,
) {
    data class Load(val rawOpcode: Int, val opcode: Opcode, val result: JvmFrameValue)
    private val components = mutableMapOf<JvmFrameValue.Reference, Load>()

    fun load(array: JvmFrameValue, rawOpcode: Int): Load {
        // JVMS 4.10.1.9 defines arrayComponentType(null, null) for aaload. The normal
        // frame is still checked, even though execution always throws before producing it.
        if (array == JvmFrameValue.NullValue) return NULL_LOADS[rawOpcode - 0x2e]
        val type = array as? JvmFrameValue.Reference ?: invalid("expected array reference")
        // Kotlin/JS hashes a String by scanning its characters even on a cache hit. Charge
        // before map access so repeated long descriptors and worklist revisits remain bounded.
        charge(type.descriptor.length.toLong())
        val load = components.getOrPut(type) {
            if (!type.descriptor.startsWith('[')) invalid("requires an array, found ${type.descriptor}")
            val component = type.descriptor.substring(1)
            when (component) {
                "I" -> INT
                "J" -> LONG
                "F" -> FLOAT
                "D" -> DOUBLE
                "B" -> BYTE
                "Z" -> BOOLEAN
                "C" -> CHAR
                "S" -> SHORT
                else -> Load(0x32, Opcode.AGET_OBJECT, reference(component))
            }
        }
        if (load.rawOpcode != rawOpcode) invalid("opcode 0x${rawOpcode.toString(16)} does not match ${type.descriptor}")
        return load
    }

    private fun invalid(reason: String): Nothing = throw ByteReaderException("JVM array load $reason")

    private companion object {
        val INT = Load(0x2e, Opcode.AGET, JvmFrameValue.IntValue)
        val LONG = Load(0x2f, Opcode.AGET_WIDE, JvmFrameValue.LongValue)
        val FLOAT = Load(0x30, Opcode.AGET, JvmFrameValue.FloatValue)
        val DOUBLE = Load(0x31, Opcode.AGET_WIDE, JvmFrameValue.DoubleValue)
        val BYTE = Load(0x33, Opcode.AGET_BYTE, JvmFrameValue.IntValue)
        val BOOLEAN = Load(0x33, Opcode.AGET_BOOLEAN, JvmFrameValue.IntValue)
        val CHAR = Load(0x34, Opcode.AGET_CHAR, JvmFrameValue.IntValue)
        val SHORT = Load(0x35, Opcode.AGET_SHORT, JvmFrameValue.IntValue)
        val NULL_LOADS = listOf(INT, LONG, FLOAT, DOUBLE,
            Load(0x32, Opcode.AGET_OBJECT, JvmFrameValue.NullValue), BYTE, CHAR, SHORT)
    }
}
