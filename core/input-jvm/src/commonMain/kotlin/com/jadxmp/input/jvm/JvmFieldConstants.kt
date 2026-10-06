package com.jadxmp.input.jvm

import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/** JVM ConstantValue initializers, separate from executable constructor/class-initializer code. */
internal object JvmFieldConstants {
    fun read(field: JvmMember, constants: JvmConstantPool): EncodedValue? {
        val attributes = field.attributes.filter { it.name == "ConstantValue" }
        if (attributes.size > 1) invalid("duplicate ConstantValue attribute")
        val attribute = attributes.singleOrNull() ?: return null
        if (attribute.bytes.size != 2) invalid("ConstantValue length must be two")
        val constant = constants.entry(ByteReader(attribute.bytes).readU16BE())
        val value = when (field.descriptor) {
            "Z", "B", "S", "C", "I" -> {
                val integer = (constant as? JvmConstant.IntegerValue)?.value ?: invalid("expected integer constant")
                when (field.descriptor) {
                    "Z" -> EncodedValue(EncodedValueType.BOOLEAN, integer and 1 != 0)
                    "B" -> EncodedValue(EncodedValueType.BYTE, integer.toByte())
                    "S" -> EncodedValue(EncodedValueType.SHORT, integer.toShort())
                    "C" -> EncodedValue(EncodedValueType.CHAR, integer.toChar())
                    else -> EncodedValue(EncodedValueType.INT, integer)
                }
            }
            "J" -> EncodedValue(EncodedValueType.LONG,
                (constant as? JvmConstant.LongValue)?.value ?: invalid("expected long constant"))
            "F" -> EncodedValue(EncodedValueType.FLOAT, Float.fromBits(
                (constant as? JvmConstant.FloatBits)?.bits ?: invalid("expected float constant")))
            "D" -> EncodedValue(EncodedValueType.DOUBLE, Double.fromBits(
                (constant as? JvmConstant.DoubleBits)?.bits ?: invalid("expected double constant")))
            "Ljava/lang/String;" -> EncodedValue(EncodedValueType.STRING, constants.utf8(
                (constant as? JvmConstant.StringRef)?.stringIndex ?: invalid("expected string constant")))
            else -> invalid("ConstantValue is not supported for field type ${field.descriptor}")
        }
        // JVMS 4.7.2: only static fields receive this initializer, including non-final fields.
        // A javac instance-final constant also has this attribute; its constructor does the write.
        return value.takeIf { field.accessFlags and 0x0008 != 0 }
    }

    private fun invalid(message: String): Nothing = throw ByteReaderException("invalid JVM field constant: $message")
}
