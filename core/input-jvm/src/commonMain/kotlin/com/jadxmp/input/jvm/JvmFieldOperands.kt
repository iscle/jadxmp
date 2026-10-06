package com.jadxmp.input.jvm

import com.jadxmp.input.FieldRef
import com.jadxmp.io.ByteReaderException

/** Checked immutable FIELD_REF operands. Source reconstruction currently proves only own mutable fields. */
internal class JvmFieldOperands(
    private val pool: JvmConstantPool,
    private val owner: String,
    private val declarations: JvmDeclaredFields?,
    private val charge: (Long) -> Unit,
) {
    data class Field(val operand: JvmIndexedOperand.FieldValue, val flags: Int)
    private val fields = mutableMapOf<Int, Field>()

    fun field(index: Int, static: Boolean): Field {
        val result = fields.getOrPut(index) {
            charge(1)
            val entry = pool.entry(index) as? JvmConstant.MemberRef
            if (entry == null || entry.tag != 9) fail("expected JVM field reference at index $index")
            val named = pool.entry(entry.nameAndTypeIndex) as? JvmConstant.NameAndType
                ?: fail("expected JVM field name and type at index $index")
            val fieldOwner = pool.className(entry.classIndex, allowArray = false)
            val name = pool.memberName(named.nameIndex, method = false)
            val type = pool.descriptor(named.descriptorIndex, method = false)
            charge(fieldOwner.length.toLong() + name.length + type.length)
            if (fieldOwner != owner || declarations == null || declarations.owner != owner) {
                fail("external JVM field access requires declaration metadata: $fieldOwner.$name:$type")
            }
            val declared = declarations.find(name, type)
                ?: fail("inherited or unresolved JVM field: $fieldOwner.$name:$type")
            if (declared.flags and 0x0010 != 0 || declared.hasConstantValue) {
                fail("final or ConstantValue JVM field access requires initialization-preserving source: $fieldOwner.$name:$type")
            }
            val reference = object : FieldRef {
                override val declaringClassType = "L$fieldOwner;"
                override val name = name
                override val type = type
            }
            Field(JvmIndexedOperand.FieldValue(index, reference), declared.flags)
        }
        val field = result.operand.value
        // Shared decoding reparses the owner/type, and comparisons/hash operations scan strings
        // on web targets. Integer-key caching must not hide that per-use work from the budget.
        charge(field.declaringClassType.length.toLong() + field.name.length + field.type.length)
        if ((result.flags and 0x0008 != 0) != static) fail("JVM field static kind mismatch: ${field.name}")
        return result
    }

    private fun fail(message: String): Nothing = throw ByteReaderException(message)
}
