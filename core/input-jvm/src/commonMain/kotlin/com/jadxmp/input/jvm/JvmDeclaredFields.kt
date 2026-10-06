package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReaderException

/** One bounded declaration index per class; inherited/external field resolution is deliberately absent. */
internal class JvmDeclaredFields(val owner: String, fields: List<JvmMember>, maxWork: Long = 10_000_000) {
    data class Field(val descriptor: String, val flags: Int, val hasConstantValue: Boolean)
    private val fieldsByName: Map<String, Field>

    init {
        var work = owner.length.toLong()
        if (work > maxWork) throw ByteReaderException("JVM field declaration work limit exceeded")
        val table = mutableMapOf<String, Field>()
        for (field in fields) {
            work += field.name.length.toLong() + field.descriptor.length + field.attributes.size + 1
            if (work > maxWork) throw ByteReaderException("JVM field declaration work limit exceeded")
            // JVM permits two fields with one name and different descriptors. This bounded native slice
            // does not reconstruct source aliases for this case; do not quietly choose one or rewrite its owner.
            if (table.put(field.name, Field(field.descriptor, field.accessFlags, field.attributes.any { it.name == "ConstantValue" })) != null) {
                throw ByteReaderException("JVM field name collision requires source renaming: ${field.name}")
            }
        }
        fieldsByName = table
    }

    fun find(name: String, descriptor: String): Field? = fieldsByName[name]?.takeIf { it.descriptor == descriptor }
}
