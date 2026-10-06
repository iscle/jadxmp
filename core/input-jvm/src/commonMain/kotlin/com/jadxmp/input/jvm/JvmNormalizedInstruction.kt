package com.jadxmp.input.jvm

import com.jadxmp.input.CallSite
import com.jadxmp.input.FieldRef
import com.jadxmp.input.InlineSwitchPayload
import com.jadxmp.input.IndexType
import com.jadxmp.input.Instruction
import com.jadxmp.input.InstructionPayload
import com.jadxmp.input.MethodHandle
import com.jadxmp.input.MethodProto
import com.jadxmp.input.MethodRef
import com.jadxmp.input.Opcode
import com.jadxmp.io.ByteReaderException

/** Immutable instruction snapshot. Normalized offsets are ordinals; file offsets preserve raw PCs. */
internal class JvmNormalizedInstruction(
    override val offset: Int,
    override val fileOffset: Int,
    override val opcode: Opcode,
    private val registers: IntArray,
    override val literal: Long,
    override val rawOpcodeUnit: Int,
    override val mnemonic: String,
    override val target: Int = -1,
    override val payload: InstructionPayload? = null,
    private val indexedOperand: JvmIndexedOperand? = null,
) : Instruction {
    /** Resolve raw bytecode targets only after all original positions have been emitted. */
    fun resolveTargets(position: (Int) -> Int): JvmNormalizedInstruction {
        val table = payload as? InlineSwitchPayload
        if (target < 0 && table == null) return this
        return JvmNormalizedInstruction(offset, fileOffset, opcode, registers, literal, rawOpcodeUnit, mnemonic,
            if (target < 0) target else position(target),
            table?.let { InlineSwitchPayload(it.keys.copyOf(), it.targets.map(position).toIntArray(), position(it.defaultTarget)) }, indexedOperand)
    }

    override fun decode() = Unit
    override val registerCount: Int get() = registers.size
    override fun register(argNum: Int): Int = registers.getOrNull(argNum) ?: missing()
    override val resultRegister: Int get() = -1
    override val indexType: IndexType get() = when (indexedOperand) {
        is JvmIndexedOperand.StringRef -> IndexType.STRING_REF
        is JvmIndexedOperand.TypeRef -> IndexType.TYPE_REF
        is JvmIndexedOperand.FieldValue -> IndexType.FIELD_REF
        null -> IndexType.NONE
    }
    override val index: Int get() = indexedOperand?.index ?: -1
    override fun indexAsString(): String = (indexedOperand as? JvmIndexedOperand.StringRef)?.value ?: missing()
    override fun indexAsType(): String = (indexedOperand as? JvmIndexedOperand.TypeRef)?.value ?: missing()
    override fun indexAsField(): FieldRef = (indexedOperand as? JvmIndexedOperand.FieldValue)?.value ?: missing()
    override fun indexAsMethod(): MethodRef = missing()
    override fun indexAsProto(protoIndex: Int): MethodProto = missing()
    override fun indexAsCallSite(): CallSite = missing()
    override fun indexAsMethodHandle(): MethodHandle = missing()
    private fun missing(): Nothing = throw ByteReaderException("operand is not present on normalized JVM $mnemonic at $fileOffset")
}

/** Only the resolver constructs these checked, immutable operand values. */
internal sealed interface JvmIndexedOperand {
    val index: Int
    data class StringRef(override val index: Int, val value: String) : JvmIndexedOperand
    data class FieldValue(override val index: Int, val value: FieldRef) : JvmIndexedOperand
    data class TypeRef(override val index: Int, val value: String) : JvmIndexedOperand
}
