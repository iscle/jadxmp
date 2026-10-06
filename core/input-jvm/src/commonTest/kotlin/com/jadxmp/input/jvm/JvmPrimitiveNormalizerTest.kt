package com.jadxmp.input.jvm

import com.jadxmp.input.Instruction
import com.jadxmp.input.Opcode
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmPrimitiveNormalizerTest {
    @Test fun parsesMethodShapeOnceWithWideParameterWords() {
        val shape = JvmMethodDescriptor.parse("(IJI[[DLjava/lang/String;)J")
        assertEquals(listOf("I", "J", "I", "[[D", "Ljava/lang/String;"), shape.parameterTypes)
        assertEquals("J", shape.returnType)
        assertEquals(6, shape.argumentSlots)
        assertFailsWith<ByteReaderException> { JvmMethodDescriptor.parse("(V)V") }
    }

    @Test fun preservesRawMnemonicNamesInsteadOfNormalizedOperationNames() {
        val names = (0..0xc9).map { JvmOpcodeNames.name(JvmInstruction(0, it, 1)) }
        assertEquals(202, names.toSet().size)
        assertEquals("iload_0", names[0x1a])
        assertEquals("iinc", names[0x84])
        assertEquals("i2l", names[0x85])
        assertEquals("tableswitch", names[0xaa])
        assertEquals("return", names[0xb1])
        assertEquals("jsr_w", names[0xc9])
        assertEquals("wide iinc", JvmOpcodeNames.name(JvmInstruction(0, 0x84, 6, wide = true)))
    }

    @Test fun mapsMixedWideParametersToHighestRegistersAndPreservesOriginalPositions() {
        val reader = fixture("(IJI)J", 4, 4, bytes(0x1f, 0x1a, 0x85, 0x61, 0x1d, 0x85, 0x61, 0xad))
        assertEquals(16, reader.registerCount)
        val instructions = read(reader)
        assertEquals(listOf(listOf(0, 12), listOf(1, 13), listOf(3, 15)), instructions.take(3).map { it.registers })
        val body = instructions.drop(3)
        assertEquals(listOf(Opcode.MOVE_WIDE, Opcode.MOVE, Opcode.INT_TO_LONG, Opcode.ADD_LONG,
            Opcode.MOVE, Opcode.INT_TO_LONG, Opcode.ADD_LONG, Opcode.RETURN), body.map { it.opcode })
        assertEquals(listOf(4, 1), body[0].registers)
        assertEquals(listOf(6, 0), body[1].registers)
        assertEquals(listOf(6, 3), body[4].registers)
        assertEquals(instructions.indices.toList(), instructions.map { it.offset })
        assertEquals((108..115).toList(), body.map { it.fileOffset })
        assertEquals(instructions.size, reader.unitsCount)
    }

    @Test fun snapshotsLocalLoadBeforeIincAndMapsInstanceReceiver() {
        val all = read(fixture("(I)I", 1, 1, bytes(0x1a, 0x84, 0, 1, 0xac)))
        assertEquals(listOf(0, 6), all.first().registers)
        val code = all.drop(1)
        assertEquals(listOf(Opcode.MOVE, Opcode.ADD_INT_LIT, Opcode.RETURN), code.map { it.opcode })
        assertEquals(listOf(1, 0), code[0].registers)
        assertEquals(listOf(0, 0), code[1].registers)
        assertEquals(listOf(1), code[2].registers)
        assertEquals(1L, code[1].literal)
        val instance = fixture("(IJ)J", 4, 2, bytes(0x20, 0xad), isStatic = false)
        assertEquals(14, instance.registerCount)
        assertEquals(listOf(0, 10), read(instance)[0].registers)
        assertEquals(Opcode.MOVE_OBJECT, read(instance)[0].opcode)
        assertEquals(listOf(2, 12), read(instance)[2].registers)
        assertEquals(listOf(4, 2), read(instance)[3].registers)
    }

    @Test fun preservesDistinctSameTypedValuesAcrossStackPermutations() {
        val code = read(fixture("(II)I", 2, 3, bytes(0x1a, 0x1b, 0x5f, 0x64, 0xac))).drop(2)
        val moves = code.filter { it.opcode == Opcode.MOVE }
        assertEquals(listOf(listOf(2, 0), listOf(3, 1), listOf(5, 2), listOf(6, 3), listOf(2, 6), listOf(3, 5)),
            moves.map { it.registers })
        assertEquals(listOf(2, 2, 3), code.single { it.opcode == Opcode.SUB_INT }.registers)
        assertEquals(listOf(110, 110, 110, 110), moves.drop(2).map { it.fileOffset })
    }

    @Test fun supportsWideStackPermutationAndWideIincEncoding() {
        val code = read(fixture("(J)J", 2, 4, bytes(0x1e, 0x5c, 0x61, 0xad)))
        assertEquals(listOf(listOf(0, 10), listOf(2, 0), listOf(6, 2), listOf(2, 6), listOf(4, 6)),
            code.filter { it.opcode == Opcode.MOVE_WIDE }.map { it.registers })
        val wide = read(fixture("(I)I", 1, 1, bytes(0xc4, 0x84, 0, 0, 0x80, 0, 0x1a, 0xac)))
        assertEquals(-32768L, wide.single { it.opcode == Opcode.ADD_INT_LIT }.literal)
    }

    @Test fun retainsFloatingBitsIncludingPayloadNaNsAndSignedZero() {
        val floatBits = 0xffc01234.toInt()
        val floatPool = pool(bytes(4) + i4(floatBits))
        val float = read(fixture("()F", 0, 1, bytes(0x12, 1, 0xae), constants = floatPool))
        assertEquals(floatBits.toLong(), float.first().literal)
        val doubleBits = Long.MIN_VALUE
        val doublePool = pool(bytes(6) + i8(doubleBits), slots = 2)
        val double = read(fixture("()D", 0, 2, bytes(0x14, 0, 1, 0xaf), constants = doublePool))
        assertEquals(doubleBits, double.first().literal)
        assertEquals(Opcode.CONST_WIDE, double.first().opcode)
        assertEquals(1.0f.toRawBits().toLong(), read(fixture("()F", 0, 1, bytes(0x0c, 0xae))).first().literal)
    }

    @Test fun retainsCompareNaNBiasAndTypedShiftCounts() {
        for ((raw, expected) in listOf(0x95 to Opcode.CMPL_FLOAT, 0x96 to Opcode.CMPG_FLOAT)) {
            assertEquals(expected, read(fixture("(FF)I", 2, 2, bytes(0x22, 0x23, raw, 0xac)))[4].opcode)
        }
        for ((raw, expected) in listOf(0x97 to Opcode.CMPL_DOUBLE, 0x98 to Opcode.CMPG_DOUBLE)) {
            assertEquals(expected, read(fixture("(DD)I", 4, 4, bytes(0x26, 0x28, raw, 0xac)))[4].opcode)
        }
        assertEquals(Opcode.CMP_LONG, read(fixture("(JJ)I", 4, 4, bytes(0x1e, 0x20, 0x94, 0xac)))[4].opcode)
        assertEquals(Opcode.SHL_LONG, read(fixture("()J", 0, 3, bytes(0x0a, 0x05, 0x79, 0xad)))[2].opcode)
        assertFailsWith<ByteReaderException> { fixture("()J", 0, 4, bytes(0x0a, 0x0a, 0x79, 0xad)) }
    }

    @Test fun coversPrimitiveConversionsAndEveryArithmeticFamily() {
        val conversions = listOf(
            Triple("I", "J", Opcode.INT_TO_LONG), Triple("I", "F", Opcode.INT_TO_FLOAT),
            Triple("I", "D", Opcode.INT_TO_DOUBLE), Triple("J", "I", Opcode.LONG_TO_INT),
            Triple("J", "F", Opcode.LONG_TO_FLOAT), Triple("J", "D", Opcode.LONG_TO_DOUBLE),
            Triple("F", "I", Opcode.FLOAT_TO_INT), Triple("F", "J", Opcode.FLOAT_TO_LONG),
            Triple("F", "D", Opcode.FLOAT_TO_DOUBLE), Triple("D", "I", Opcode.DOUBLE_TO_INT),
            Triple("D", "J", Opcode.DOUBLE_TO_LONG), Triple("D", "F", Opcode.DOUBLE_TO_FLOAT),
            Triple("I", "I", Opcode.INT_TO_BYTE), Triple("I", "I", Opcode.INT_TO_CHAR),
            Triple("I", "I", Opcode.INT_TO_SHORT),
        )
        val constants = mapOf("I" to 0x04, "J" to 0x0a, "F" to 0x0c, "D" to 0x0f)
        val returns = mapOf("I" to 0xac, "J" to 0xad, "F" to 0xae, "D" to 0xaf)
        for ((index, conversion) in conversions.withIndex()) {
            val (from, to, expected) = conversion
            val result = read(fixture("()$to", 0, 4,
                bytes(constants.getValue(from), 0x85 + index, returns.getValue(to))))
            assertEquals(expected, result[1].opcode)
        }
        for ((group, operation) in listOf("ADD", "SUB", "MUL", "DIV", "REM").withIndex()) {
            for ((category, type) in listOf("I", "J", "F", "D").withIndex()) {
                val name = listOf("INT", "LONG", "FLOAT", "DOUBLE")[category]
                val result = read(fixture("()$type", 0, 4,
                    bytes(constants.getValue(type), constants.getValue(type), 0x60 + group * 4 + category,
                        returns.getValue(type))))
                assertEquals(Opcode.valueOf("${operation}_$name"), result[2].opcode)
            }
        }
    }

    @Test fun rejectsUnsupportedShapesWithoutReturningPartialReader() {
        for (code in listOf(bytes(0xa8, 0, 0), bytes(0x01, 0xb0), bytes(0xb8, 0, 1, 0xb1),
            bytes(0x2a, 0xb0), bytes(0xbb, 0, 1, 0xb0))) {
            assertFailsWith<ByteReaderException> { fixture("()V", 1, 4, code) }
        }
        assertFailsWith<ByteReaderException> { fixture("()V", 0, 0, bytes(0xb1), handlers = true) }
        assertFailsWith<ByteReaderException> { fixture("()V", 1, 1, bytes(0xb1), name = "<init>", isStatic = false) }
    }

    @Test fun rejectsWrongTypesUndefinedLocalsBadReturnsAndMissingTerminators() {
        for (code in listOf(bytes(0x1a, 0xac), bytes(0x0b, 0xac), bytes(0x03, 0x60, 0xac),
            bytes(0x03), bytes(0xac), bytes(0xb1), bytes(0x03, 0xac, 0x03))) {
            assertFailsWith<ByteReaderException> { fixture("()I", 1, 3, code) }
        }
        assertFailsWith<ByteReaderException> { fixture("(J)J", 1, 2, bytes(0x1e, 0xad)) }
        assertFailsWith<ByteReaderException> { fixture("()J", 2, 2, bytes(0x09, 0x3f, 0x1f, 0xad)) }
        assertFailsWith<ByteReaderException> { fixture("()F", 0, 1, bytes(0x12, 1, 0xae), constants = pool(bytes(3) + i4(1))) }
        assertFailsWith<ByteReaderException> { fixture("()J", 0, 2, bytes(0x14, 0, 1, 0xad), constants = pool(bytes(3) + i4(1))) }
    }

    @Test fun emptyVoidBodyAndIntegerExtremaAreRepresentedExactly() {
        assertEquals(Opcode.RETURN_VOID, read(fixture("()V", 0, 0, bytes(0xb1))).single().opcode)
        assertEquals(-128L, read(fixture("()I", 0, 1, bytes(0x10, 0x80, 0xac))).first().literal)
        assertEquals(-32768L, read(fixture("()I", 0, 1, bytes(0x11, 0x80, 0, 0xac))).first().literal)
        val reader = fixture("()I", 0, 1, bytes(0x12, 1, 0xac), constants = pool(bytes(3) + i4(Int.MIN_VALUE)))
        assertEquals(Int.MIN_VALUE.toLong(), read(reader).first().literal)
        assertTrue(reader.tries.isEmpty())
        assertEquals(null, reader.debugInfo)
    }

    @Test fun materializesImplicitIreturnNarrowingIncludingBooleanLowBit() {
        for ((descriptor, expected) in listOf("B" to Opcode.INT_TO_BYTE, "C" to Opcode.INT_TO_CHAR,
            "S" to Opcode.INT_TO_SHORT, "Z" to Opcode.AND_INT_LIT)) {
            val instructions = read(fixture("(I)$descriptor", 1, 1, bytes(0x1a, 0xac))).drop(1)
            assertEquals(listOf(Opcode.MOVE, expected, Opcode.RETURN), instructions.map { it.opcode })
            assertEquals(listOf(1, 1), instructions[1].registers)
            if (descriptor == "Z") assertEquals(1L, instructions[1].literal)
        }
    }

    @Test fun keepsWideLocalsContiguousWhenStoresOverwriteParameterBoundary() {
        for ((descriptor, locals, code) in listOf(
            Triple("(I)J", 2, bytes(0x0a, 0x3f, 0x1e, 0xad)),
            Triple("(II)J", 3, bytes(0x0a, 0x40, 0x1f, 0xad)),
        )) {
            val reader = fixture(descriptor, locals, 2, code)
            val instructions = read(reader)
            for (instruction in instructions.filter { it.opcode == Opcode.MOVE_WIDE }) {
                for (register in instruction.registers) assertTrue(register >= 0 && register + 1 < reader.registerCount)
            }
            val originalLocal = if (locals == 2) 0 else 1
            assertEquals(listOf(originalLocal, locals), instructions[instructions.size - 3].registers)
            assertEquals(listOf(locals, originalLocal), instructions[instructions.size - 2].registers)
        }
        // Local one is the tail of the replacement long, not a surviving incoming int.
        assertFailsWith<ByteReaderException> { fixture("(II)I", 2, 2, bytes(0x0a, 0x3f, 0x1b, 0xac)) }
    }

    private data class Seen(val offset: Int, val fileOffset: Int, val opcode: Opcode, val registers: List<Int>, val literal: Long)
    private fun read(reader: com.jadxmp.input.CodeReader): List<Seen> = buildList {
        reader.visitInstructions { instruction: Instruction ->
            instruction.decode()
            add(Seen(instruction.offset, instruction.fileOffset, instruction.opcode,
                (0 until instruction.registerCount).map(instruction::register), instruction.literal))
        }
    }

    private fun fixture(descriptor: String, locals: Int, stack: Int, code: ByteArray,
        isStatic: Boolean = true, name: String = "test", constants: JvmConstantPool = pool(), handlers: Boolean = false,
    ): com.jadxmp.input.CodeReader {
        val handler = if (handlers) u2(1) + u2(0) + u2(code.size) + u2(0) + u2(0) else u2(0)
        val attribute = JvmAttribute("Code", 100, u2(stack) + u2(locals) + i4(code.size) + code + handler + u2(0))
        val method = JvmMember(if (isStatic) 8 else 0, name, descriptor, listOf(attribute))
        return JvmRegisterNormalizer.normalize("Example", method, JvmMethodDescriptor.parse(descriptor), constants,
            JvmCodeAttribute.parse(attribute, constants))
    }
    private fun pool(entry: ByteArray = byteArrayOf(), slots: Int = if (entry.isEmpty()) 0 else 1) =
        JvmConstantPool.read(ByteReader(u2(slots + 1) + entry), 65)
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun u2(value: Int) = bytes(value ushr 8, value)
    private fun i4(value: Int) = bytes(value ushr 24, value ushr 16, value ushr 8, value)
    private fun i8(value: Long) = i4((value ushr 32).toInt()) + i4(value.toInt())
}
