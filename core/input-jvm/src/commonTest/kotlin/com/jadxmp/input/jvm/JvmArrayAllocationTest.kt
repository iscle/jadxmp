package com.jadxmp.input.jvm

import com.jadxmp.input.ArrayAllocationPayload
import com.jadxmp.input.CodeReader
import com.jadxmp.input.Instruction
import com.jadxmp.input.Opcode
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.*

class JvmArrayAllocationTest {
    @Test fun allPrimitiveAllocationsPreserveSizeSnapshotAndOriginalPosition() {
        for ((atype, element) in listOf(4 to "Z", 5 to "C", 6 to "F", 7 to "D", 8 to "B", 9 to "S", 10 to "I", 11 to "J")) {
            val instructions = read(normalize("(I)[$element", bytes(0x1a, 0x84, 0, 1, 0xbc, atype, 0xb0)))
            val allocation = instructions.single { it.opcode == Opcode.NEW_ARRAY }
            assertEquals(listOf(1, 1), (0 until allocation.registerCount).map(allocation::register))
            assertEquals(112, allocation.fileOffset)
            assertEquals(-1, allocation.index)
            assertEquals("[$element", (allocation.payload as ArrayAllocationPayload).arrayType)
        }
    }

    @Test fun referenceAndNestedArrayComponentsAllocateOnlyOuterDimension() {
        for (name in listOf("java/lang/String", "[I", "[[Ljava/lang/String;", "I")) {
            val descriptor = if (name.startsWith('[')) name else "L$name;"
            val result = read(normalize("(I)[$descriptor", bytes(0x1a, 0xbd, 0, 2, 0xb0), pool(utf8(name), bytes(7, 0, 1))))
            assertEquals(1, result.count { it.opcode == Opcode.NEW_ARRAY })
            val allocation = result.single { it.opcode == Opcode.NEW_ARRAY }
            assertEquals(-1, allocation.index)
            assertEquals("[$descriptor", (allocation.payload as ArrayAllocationPayload).arrayType)
        }
    }

    @Test fun invalidSizesTagsAndDimensionOverflowAreRejected() {
        for (atype in listOf(0, 3, 12, 255)) assertFailsWith<ByteReaderException> {
            normalize("(I)Ljava/lang/Object;", bytes(0x1a, 0xbc, atype, 0xb0))
        }
        for (code in listOf(bytes(0x0b, 0xbc, 10, 0xb0), bytes(0x09, 0xbd, 0, 2, 0xb0))) assertFailsWith<ByteReaderException> {
            normalize("(I)Ljava/lang/Object;", code, pool(utf8("java/lang/Object"), bytes(7, 0, 1)), stack = 2)
        }
        assertFailsWith<ByteReaderException> { normalize("(I)Ljava/lang/Object;", bytes(0x1a, 0xbd, 0, 1, 0xb0), pool(utf8("java/lang/Object"))) }
        assertFailsWith<ByteReaderException> { normalize("(I)Ljava/lang/Object;", bytes(0x1a, 0xbd, 0, 2, 0xb0), pool(utf8("[".repeat(255) + "I"), bytes(7, 0, 1))) }
    }

    @Test fun allocationDescriptorsAreCachedWithinMethodWorkBudget() {
        val name = "x".repeat(2048)
        val pool = pool(utf8(name), bytes(7, 0, 1))
        val code = (0 until 100).fold(byteArrayOf()) { result, _ -> result + bytes(0x03, 0xbd, 0, 2, 0x57) } + bytes(0xb1)
        val allocations = read(normalize("()V", code, pool, locals = 0, limits = JvmAnalysisLimits(maxConstantWork = 500000)))
            .filter { it.opcode == Opcode.NEW_ARRAY }
        assertEquals(100, allocations.size)
        for (allocation in allocations) assertSame(allocations.first().payload, allocation.payload)
        normalize("()V", bytes(0x03, 0xbd, 0, 2, 0x57, 0xb1), pool, locals = 0,
            limits = JvmAnalysisLimits(maxConstantWork = 10000))
        assertFailsWith<ByteReaderException> { normalize("()V", code, pool, locals = 0, limits = JvmAnalysisLimits(maxConstantWork = 10000)) }
    }

    @Test fun multipleSizedDimensionsRemainExplicitlyUnsupported() {
        val error = assertFailsWith<ByteReaderException> { normalize("(I)[[I", bytes(0x1a, 0x1a, 0xc5, 0, 2, 2, 0xb0), pool(utf8("[[I"), bytes(7, 0, 1)), stack = 2) }
        assertTrue(error.message.orEmpty().contains("unsupported"))
    }

    private fun read(reader: CodeReader): List<Instruction> = buildList { reader.visitInstructions { it.decode(); add(it) } }
    private fun normalize(descriptor: String, code: ByteArray, pool: JvmConstantPool = pool(), locals: Int = 1, stack: Int = 1, limits: JvmAnalysisLimits = JvmAnalysisLimits()): CodeReader {
        val attribute = JvmAttribute("Code", 100, u2(stack) + u2(locals) + i4(code.size) + code + u2(0) + u2(0))
        val member = JvmMember(8, "test", descriptor, listOf(attribute))
        return JvmRegisterNormalizer.normalize("Example", member, JvmMethodDescriptor.parse(descriptor), pool, JvmCodeAttribute.parse(attribute, pool), limits)
    }
    private fun pool(vararg entries: ByteArray) = JvmConstantPool.read(ByteReader(u2(entries.size + 1) + entries.fold(byteArrayOf()) { a, b -> a + b }), 65)
    private fun utf8(value: String) = bytes(1) + u2(value.length) + value.encodeToByteArray()
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private fun u2(value: Int) = bytes(value ushr 8, value)
    private fun i4(value: Int) = bytes(value ushr 24, value ushr 16, value ushr 8, value)
}
