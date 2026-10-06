package com.jadxmp.input.jvm

import com.jadxmp.input.InvalidGenericSignatureEncoding
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/** Decodes the optional attribute envelope; the shared model owns signature grammar and erasure. */
internal object JvmGenericSignatures {
    fun read(attributes: List<JvmAttribute>, constants: JvmConstantPool): String? {
        val signatures = attributes.filter { it.name == "Signature" }
        if (signatures.isEmpty()) return null
        if (signatures.size != 1) invalid("duplicate Signature attribute")
        val attribute = signatures.single()
        if (attribute.bytes.size != 2) invalid("Signature attribute must contain exactly one constant-pool index")
        return try {
            constants.utf8(ByteReader(attribute.bytes).readU16BE())
        } catch (failure: ByteReaderException) {
            invalid("Signature attribute requires a UTF8 constant: ${failure.message}")
        }
    }

    private fun invalid(message: String): Nothing = throw InvalidGenericSignatureEncoding("invalid JVM generic metadata: $message")
}
