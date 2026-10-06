package com.jadxmp.input.jvm

import com.jadxmp.input.CodeReader
import com.jadxmp.input.Instruction
import com.jadxmp.input.Opcode
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmArrayLengthTest {
    @Test fun arrayLengthConsumesOneReferenceAndProducesIntegerAtOriginalPosition() {
        for (type in listOf("[Z", "[B", "[C", "[S", "[I", "[J", "[F", "[D", "[Ljava/lang/String;", "[[I")) {
            val instructions = read(normalize("($type)I", bytes(0x2a, 0xbe, 0xac)))
            val length = instructions.single { it.opcode == Opcode.ARRAY_LENGTH }
            assertEquals(listOf(1, 1), (0 until length.registerCount).map(length::register))
            assertEquals(109, length.fileOffset)
            assertEquals("arraylength", length.mnemonic)
            assertEquals(Opcode.RETURN, instructions.last().opcode)
        }
    }

    @Test fun nullIsAValidArrayOperandAndUnusedLengthIsRetained() {
        val instructions = read(normalize("()V", bytes(0x01, 0xbe, 0x57, 0xb1), locals = 0))
        assertEquals(1, instructions.count { it.opcode == Opcode.ARRAY_LENGTH })
        assertEquals(Opcode.RETURN_VOID, instructions.last().opcode)
    }

    @Test fun nullArrayJoinKeepsItsArrayType() {
        // flag ? array : null; arraylength
        val instructions = read(normalize("(Z[I)I", bytes(0x1a, 0x99, 0, 7, 0x2b, 0xa7, 0, 4, 0x01, 0xbe, 0xac), locals = 2))
        assertEquals(1, instructions.count { it.opcode == Opcode.ARRAY_LENGTH })
    }

    @Test fun rejectsNonArrayReferencesPrimitivesAndStackUnderflow() {
        for (type in listOf("Ljava/lang/Object;", "Ljava/lang/String;")) {
            val failure = assertFailsWith<ByteReaderException> { normalize("($type)I", bytes(0x2a, 0xbe, 0xac)) }
            assertTrue(failure.message.orEmpty().contains("arraylength requires an array"), failure.message)
        }
        for (code in listOf(bytes(0x03, 0xbe, 0xac), bytes(0xbe, 0xac), bytes(0x09, 0xbe, 0xac))) {
            assertFailsWith<ByteReaderException> { normalize("()I", code, locals = 0, stack = 2) }
        }
    }

    private fun read(reader: CodeReader): List<Instruction> = buildList { reader.visitInstructions { it.decode(); add(it) } }
    private fun normalize(descriptor: String, code: ByteArray, locals: Int = 1, stack: Int = 1): CodeReader {
        val pool = JvmConstantPool.read(ByteReader(u2(1)), 65)
        val attribute = JvmAttribute("Code", 100, u2(stack) + u2(locals) + i4(code.size) + code + u2(0) + u2(0))
        val member = JvmMember(8, "test", descriptor, listOf(attribute))
        return JvmRegisterNormalizer.normalize("Example", member, JvmMethodDescriptor.parse(descriptor), pool,
            JvmCodeAttribute.parse(attribute, pool))
    }
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private fun u2(value: Int) = bytes(value ushr 8, value)
    private fun i4(value: Int) = bytes(value ushr 24, value ushr 16, value ushr 8, value)
}
