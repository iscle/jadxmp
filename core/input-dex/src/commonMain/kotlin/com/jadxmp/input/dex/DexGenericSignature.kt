package com.jadxmp.input.dex

import com.jadxmp.input.AnnotationData
import com.jadxmp.input.InvalidGenericSignatureEncoding
import com.jadxmp.input.AnnotationVisibility
import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType

/** Joins DEX's split signature payload without making the engine depend on DEX annotations. */
internal object DexGenericSignature {
    private fun signatureRequire(condition: Boolean, message: () -> String) {
        if (!condition) throw InvalidGenericSignatureEncoding(message())
    }

    fun read(annotations: List<AnnotationData>): String? {
        val matching = annotations.filter { it.annotationType == "Ldalvik/annotation/Signature;" }
        if (matching.isEmpty()) return null
        signatureRequire(matching.size == 1) { "duplicate DEX Signature annotation" }
        val annotation = matching.single()
        signatureRequire(annotation.visibility == AnnotationVisibility.SYSTEM) { "non-system DEX Signature annotation" }
        val value = annotation.defaultValue
        signatureRequire(value?.type == EncodedValueType.ARRAY) { "DEX Signature requires a string array" }
        val parts = value?.value as? List<*> ?: throw InvalidGenericSignatureEncoding("invalid DEX Signature payload")
        require(parts.size <= 65535) { "unsupported DEX Signature fragment count" }
        return buildString {
            for (part in parts) {
                val encoded = part as? EncodedValue
                signatureRequire(encoded?.type == EncodedValueType.STRING) { "non-string DEX Signature element" }
                val fragment = encoded?.value as? String ?: throw InvalidGenericSignatureEncoding("invalid DEX Signature string")
                require(fragment.length <= 65535 - length) { "unsupported DEX Signature length" }
                append(fragment)
            }
        }
    }
}
