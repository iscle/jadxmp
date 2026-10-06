package com.jadxmp.input.jvm

import com.jadxmp.input.ArrayAllocationPayload
import com.jadxmp.io.ByteReaderException

/** Checked per-method allocation types. Integer cache keys avoid repeated descriptor hashing. */
internal class JvmArrayAllocations(
    private val constants: JvmConstantOperands,
    private val charge: (Long) -> Unit,
    private val reference: (String) -> JvmFrameValue.Reference,
) {
    data class Allocation(val type: JvmFrameValue.Reference, val payload: ArrayAllocationPayload)
    private val primitives = mutableMapOf<Int, Allocation>()
    private val references = mutableMapOf<Int, Allocation>()

    fun primitive(atype: Int): Allocation = checked(primitives.getOrPut(atype) {
        val element = when (atype) {
            4 -> "Z"; 5 -> "C"; 6 -> "F"; 7 -> "D"
            8 -> "B"; 9 -> "S"; 10 -> "I"; 11 -> "J"
            else -> throw ByteReaderException("invalid newarray type $atype")
        }
        allocation("[$element")
    })

    fun reference(index: Int): Allocation = checked(references.getOrPut(index) {
        allocation("[" + constants.type(index).frameType.descriptor)
    })

    private fun allocation(descriptor: String): Allocation {
        charge(descriptor.length.toLong())
        // The frame validator rejects a 256th dimension before any reader escapes.
        return Allocation(reference(descriptor), ArrayAllocationPayload(descriptor))
    }

    private fun checked(value: Allocation): Allocation {
        // Cached construction does not make every use free: the shared decoder validates and
        // parses the descriptor for each emitted allocation. Bound that work on all targets too.
        charge(value.payload.arrayType.length.toLong())
        return value
    }
}
