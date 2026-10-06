package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReaderException

/** Bound repeated shared-pool string work before either declaration consumer builds lookup maps. */
internal object JvmDeclarationLimits {
    fun check(file: ClassFile) {
        var characters = file.name.length.toLong() + (file.superName?.length ?: 0)
        for (type in file.interfaces) characters += type.length
        for (attribute in file.attributes) characters += attribute.name.length + signatureLength(attribute, file)
        for (member in file.fields + file.methods) {
            characters += member.name.length + member.descriptor.length
            for (attribute in member.attributes) characters += attribute.name.length + signatureLength(attribute, file)
        }
        if (characters > 10_000_000) throw ByteReaderException("invalid JVM declaration: declaration work limit exceeded")
    }

    private fun signatureLength(attribute: JvmAttribute, file: ClassFile): Int {
        if (attribute.name != "Signature" || attribute.bytes.size != 2) return 0
        val index = ((attribute.bytes[0].toInt() and 255) shl 8) or (attribute.bytes[1].toInt() and 255)
        return try { file.constants.utf8(index).length } catch (_: ByteReaderException) { 0 }
    }
}
