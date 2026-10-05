package com.jadxmp.input.jvm

/**
 * Raw JVM instruction. Offsets and sizes are bytes relative to the Code array, not the class file.
 * A wide-prefixed instruction is one instruction: [offset] points at wide, [opcode] at its modified
 * operation, and [size] includes the prefix. Implicit operands (iconst_0, iload_1, etc.) stay encoded
 * in the opcode. Nothing here assigns stack values or normalizes JVM local slots to registers.
 */
internal data class JvmInstruction(
    val offset: Int,
    val opcode: Int,
    val size: Int,
    val operand: JvmOperand = JvmOperand.None,
    val wide: Boolean = false,
)

/** Encoded operands with signedness resolved; branch/switch targets are absolute Code offsets. */
internal sealed interface JvmOperand {
    data object None : JvmOperand
    data class Immediate(val value: Int) : JvmOperand
    data class Local(val index: Int) : JvmOperand
    data class Increment(val index: Int, val amount: Int) : JvmOperand
    data class Constant(val index: Int) : JvmOperand
    data class Branch(val target: Int) : JvmOperand
    data class InterfaceCall(val index: Int, val argumentSlots: Int) : JvmOperand
    data class ArrayType(val type: Int) : JvmOperand
    data class MultiArray(val index: Int, val dimensions: Int) : JvmOperand
    data class Switch(val defaultTarget: Int, val cases: List<JvmSwitchCase>) : JvmOperand
}

internal data class JvmSwitchCase(val key: Int, val target: Int)
