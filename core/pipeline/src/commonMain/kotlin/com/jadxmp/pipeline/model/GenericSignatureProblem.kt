package com.jadxmp.pipeline.model

/** Only proven invalid optional metadata may recover to the authoritative erased declaration. */
internal class InvalidGenericSignature(message: String) : IllegalArgumentException(message)
internal open class UnsupportedGenericSignature(message: String) : IllegalArgumentException(message)

/** Scope availability, rather than syntax, determines whether this is malformed or unsupported. */
internal class UnboundGenericVariable(
    val variableName: String,
    val classParameters: List<com.jadxmp.ir.generics.TypeParameter>? = null,
) : UnsupportedGenericSignature("unbound type variable $variableName")

internal fun signatureRequire(condition: Boolean, message: () -> String) {
    if (!condition) throw InvalidGenericSignature(message())
}
