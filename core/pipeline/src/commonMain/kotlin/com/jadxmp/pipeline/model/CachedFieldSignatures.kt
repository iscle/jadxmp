package com.jadxmp.pipeline.model

import com.jadxmp.ir.generics.TypeParameter
import com.jadxmp.ir.type.IrType

/**
 * One lexical field scope, discarded after its class is attached. Repeated malformed attributes
 * otherwise capture the same exception stack for every field (particularly costly on JS).
 * Callers must charge their work budget before lookup: hashing even a cached string costs work.
 */
internal class CachedFieldSignatures(private val scope: List<TypeParameter>) {
    private val parsed = mutableMapOf<String, Result<IrType>>()
    private var storedCharacters = 0

    fun parse(text: String): IrType {
        parsed[text]?.let { return it.getOrThrow() }
        val result = try {
            Result.success(GenericSignatures.parseField(text, scope))
        } catch (invalid: InvalidGenericSignature) {
            // Only proven malformed syntax is memoized. Unsupported scope/limits, provider failures
            // and cancellation keep their ordinary propagation and classification.
            Result.failure(invalid)
        }
        if (parsed.size < 128 && text.length <= 65_535 - storedCharacters) {
            parsed[text] = result
            storedCharacters += text.length
        }
        return result.getOrThrow()
    }
}
