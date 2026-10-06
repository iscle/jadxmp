package com.jadxmp.input.jvm

import com.jadxmp.input.Opcode
import com.jadxmp.io.ByteReaderException

/** Resolve a component once per method, sharing the constant resolver's bounded string work. */
internal class JvmArrayOperands(
    private val charge: (Long) -> Unit,
    private val reference: (String) -> JvmFrameValue.Reference,
) {
    data class Component(val rawOpcode: Int, val read: Opcode, val write: Opcode, val value: JvmFrameValue)
    private val components = mutableMapOf<JvmFrameValue.Reference, Component>()

    fun load(array: JvmFrameValue, rawOpcode: Int): Component = component(array, rawOpcode, store = false)
    fun store(array: JvmFrameValue, rawOpcode: Int): Component = component(array, rawOpcode, store = true)

    private fun component(array: JvmFrameValue, rawOpcode: Int, store: Boolean): Component {
        val loadOpcode = if (store) rawOpcode - 0x21 else rawOpcode
        fun invalid(reason: String): Nothing = throw ByteReaderException("JVM array ${if (store) "store" else "load"} $reason")
        // JVMS 4.10.1.9 defines arrayComponentType(null, null) for aaload. The normal
        // frame is still checked, even though execution always throws before producing it.
        if (array == JvmFrameValue.NullValue) return NULL_COMPONENTS[loadOpcode - 0x2e]
        val type = array as? JvmFrameValue.Reference ?: invalid("expected array reference")
        // Kotlin/JS hashes a String by scanning its characters even on a cache hit. Charge
        // before map access so repeated long descriptors and worklist revisits remain bounded.
        charge(type.descriptor.length.toLong())
        val component = components.getOrPut(type) {
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
                else -> Component(0x32, Opcode.AGET_OBJECT, Opcode.APUT_OBJECT, reference(component))
            }
        }
        if (component.rawOpcode != loadOpcode) invalid("opcode 0x${rawOpcode.toString(16)} does not match ${type.descriptor}")
        return component
    }

    private companion object {
        val INT = Component(0x2e, Opcode.AGET, Opcode.APUT, JvmFrameValue.IntValue)
        val LONG = Component(0x2f, Opcode.AGET_WIDE, Opcode.APUT_WIDE, JvmFrameValue.LongValue)
        val FLOAT = Component(0x30, Opcode.AGET, Opcode.APUT, JvmFrameValue.FloatValue)
        val DOUBLE = Component(0x31, Opcode.AGET_WIDE, Opcode.APUT_WIDE, JvmFrameValue.DoubleValue)
        val BYTE = Component(0x33, Opcode.AGET_BYTE, Opcode.APUT_BYTE, JvmFrameValue.IntValue)
        val BOOLEAN = Component(0x33, Opcode.AGET_BOOLEAN, Opcode.APUT_BOOLEAN, JvmFrameValue.IntValue)
        val CHAR = Component(0x34, Opcode.AGET_CHAR, Opcode.APUT_CHAR, JvmFrameValue.IntValue)
        val SHORT = Component(0x35, Opcode.AGET_SHORT, Opcode.APUT_SHORT, JvmFrameValue.IntValue)
        val NULL_COMPONENTS = listOf(INT, LONG, FLOAT, DOUBLE,
            Component(0x32, Opcode.AGET_OBJECT, Opcode.APUT_OBJECT, JvmFrameValue.NullValue), BYTE, CHAR, SHORT)
    }
}
