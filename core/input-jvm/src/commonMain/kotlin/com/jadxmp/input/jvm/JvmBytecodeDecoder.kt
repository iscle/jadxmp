package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/**
 * Structural instruction decoder following JVMS 6.5 and the instruction-boundary rules of 4.9.1.
 * Bounds every payload before allocation, then validates all direct control-flow targets. This is
 * not a verifier: constant-pool tags, stack/local types, max_locals, version-specific jsr/ret rules,
 * and fall-through/return semantics belong to later validation and stack normalization.
 */
internal object JvmBytecodeDecoder {
    fun decode(code: ByteArray): List<JvmInstruction> {
        if (code.size !in 1..65535) throw ByteReaderException("JVM code length must be in 1..65535")
        return Decoder(code).decode()
    }

    private class Decoder(code: ByteArray) {
        private val reader = ByteReader(code)
        private val starts = BooleanArray(code.size)

        fun decode(): List<JvmInstruction> {
            val result = mutableListOf<JvmInstruction>()
            while (reader.remaining > 0) {
                val offset = reader.position
                starts[offset] = true
                try {
                    result += instruction(offset)
                } catch (error: ByteReaderException) {
                    throw ByteReaderException("invalid JVM instruction at $offset: ${error.message}")
                }
            }
            for (instruction in result) {
                when (val operand = instruction.operand) {
                    is JvmOperand.Branch -> checkBoundary(instruction.offset, operand.target)
                    is JvmOperand.Switch -> {
                        checkBoundary(instruction.offset, operand.defaultTarget)
                        operand.cases.forEach { checkBoundary(instruction.offset, it.target) }
                    }
                    else -> Unit
                }
            }
            return result
        }

        private fun instruction(offset: Int): JvmInstruction {
            val opcode = reader.readU8()
            if (opcode == 0xc4) return wide(offset)
            val operand = when (opcode) {
                in 0x00..0x0f, in 0x1a..0x35, in 0x3b..0x83, in 0x85..0x98,
                in 0xac..0xb1, 0xbe, 0xbf, 0xc2, 0xc3 -> JvmOperand.None
                0x10 -> JvmOperand.Immediate(reader.readS8())
                0x11 -> JvmOperand.Immediate(signedShort())
                0x12 -> JvmOperand.Constant(constantIndex(reader.readU8()))
                0x13, 0x14, in 0xb2..0xb8, 0xbb, 0xbd, 0xc0, 0xc1 ->
                    JvmOperand.Constant(constantIndex(reader.readU16BE()))
                in 0x15..0x19, in 0x36..0x3a, 0xa9 -> JvmOperand.Local(reader.readU8())
                0x84 -> JvmOperand.Increment(reader.readU8(), reader.readS8())
                in 0x99..0xa8, 0xc6, 0xc7 -> JvmOperand.Branch(target(offset, signedShort()))
                0xc8, 0xc9 -> JvmOperand.Branch(target(offset, reader.readS32BE()))
                0xaa, 0xab -> switch(offset, opcode == 0xaa)
                0xb9 -> {
                    val index = constantIndex(reader.readU16BE())
                    val count = reader.readU8()
                    if (count == 0) malformed("invokeinterface argument count is zero")
                    if (reader.readU8() != 0) malformed("invokeinterface reserved byte is not zero")
                    JvmOperand.InterfaceCall(index, count)
                }
                0xba -> {
                    val index = constantIndex(reader.readU16BE())
                    if (reader.readU16BE() != 0) malformed("invokedynamic reserved bytes are not zero")
                    JvmOperand.Constant(index)
                }
                0xbc -> {
                    val type = reader.readU8()
                    if (type !in 4..11) malformed("invalid newarray type $type")
                    JvmOperand.ArrayType(type)
                }
                0xc5 -> {
                    val index = constantIndex(reader.readU16BE())
                    val dimensions = reader.readU8()
                    if (dimensions == 0) malformed("multianewarray dimensions is zero")
                    JvmOperand.MultiArray(index, dimensions)
                }
                else -> malformed("reserved or unknown opcode 0x${opcode.toString(16)}")
            }
            return JvmInstruction(offset, opcode, reader.position - offset, operand)
        }

        private fun wide(offset: Int): JvmInstruction {
            val opcode = reader.readU8()
            val operand = when (opcode) {
                in 0x15..0x19, in 0x36..0x3a, 0xa9 -> JvmOperand.Local(reader.readU16BE())
                0x84 -> JvmOperand.Increment(reader.readU16BE(), signedShort())
                else -> malformed("invalid wide opcode 0x${opcode.toString(16)}")
            }
            return JvmInstruction(offset, opcode, reader.position - offset, operand, wide = true)
        }

        private fun switch(offset: Int, table: Boolean): JvmOperand.Switch {
            // Padding is aligned to the Code array, and its values are unspecified by JVMS 6.5.
            val padding = (4 - reader.position % 4) % 4
            reader.requireAvailable(padding.toLong())
            reader.skip(padding)
            val defaultTarget = target(offset, reader.readS32BE())
            val cases = if (table) {
                val low = reader.readS32BE()
                val high = reader.readS32BE()
                if (low > high) malformed("tableswitch low exceeds high")
                val count = high.toLong() - low.toLong() + 1
                reader.requireAvailable(count * 4)
                List(count.toInt()) { index -> JvmSwitchCase(low + index, target(offset, reader.readS32BE())) }
            } else {
                val count = reader.readS32BE()
                if (count < 0) malformed("negative lookupswitch pair count")
                reader.requireAvailable(count.toLong() * 8)
                var previous = 0
                List(count) { index ->
                    val key = reader.readS32BE()
                    if (index > 0 && key <= previous) malformed("lookupswitch keys are not strictly increasing")
                    previous = key
                    JvmSwitchCase(key, target(offset, reader.readS32BE()))
                }
            }
            return JvmOperand.Switch(defaultTarget, cases)
        }

        private fun signedShort(): Int = reader.readU16BE().toShort().toInt()

        private fun constantIndex(index: Int): Int {
            if (index == 0) malformed("constant-pool index is zero")
            return index
        }

        private fun target(offset: Int, displacement: Int): Int {
            val target = offset.toLong() + displacement.toLong()
            if (target < 0 || target >= starts.size) malformed("branch target $target is outside Code array")
            return target.toInt()
        }

        private fun checkBoundary(source: Int, target: Int) {
            if (!starts[target]) malformed("branch from $source targets non-instruction offset $target")
        }

        private fun malformed(message: String): Nothing = throw ByteReaderException(message)
    }
}
