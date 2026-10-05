package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReaderException

/** Logical verification values, not runtime constants or SSA definitions. Long/double take two words. */
internal sealed interface JvmFrameValue {
    val words: Int get() = 1

    data object IntValue : JvmFrameValue
    data object FloatValue : JvmFrameValue
    data object LongValue : JvmFrameValue { override val words: Int get() = 2 }
    data object DoubleValue : JvmFrameValue { override val words: Int get() = 2 }
    data object NullValue : JvmFrameValue

    data class Reference(val descriptor: String) : JvmFrameValue {
        init { validateReference(descriptor, allowArray = true) }
    }

    sealed interface Uninitialized : JvmFrameValue {
        val descriptor: String
    }

    /** Identity is the original NEW offset, never a normalized/synthetic instruction position. */
    data class UninitializedNew(val offset: Int, override val descriptor: String) : Uninitialized {
        init {
            if (offset !in 0..65534) throw ByteReaderException("invalid JVM allocation offset $offset")
            validateReference(descriptor, allowArray = false)
        }
    }

    data class UninitializedThis(override val descriptor: String) : Uninitialized {
        init { validateReference(descriptor, allowArray = false) }
    }
}

/** A wide local's second word is never independently loadable; Top means unusable/unassigned. */
internal sealed interface JvmLocalSlot {
    data object Top : JvmLocalSlot
    data object Tail : JvmLocalSlot
    data class Value(val value: JvmFrameValue) : JvmLocalSlot
}

internal enum class JvmStackOperation { POP, POP2, SWAP, DUP, DUP_X1, DUP_X2, DUP2, DUP2_X1, DUP2_X2 }

private fun validateReference(descriptor: String, allowArray: Boolean) {
    JvmDescriptors.field(descriptor)
    if (!descriptor.startsWith('L') && !(allowArray && descriptor.startsWith('['))) {
        throw ByteReaderException("invalid JVM reference descriptor $descriptor")
    }
}
