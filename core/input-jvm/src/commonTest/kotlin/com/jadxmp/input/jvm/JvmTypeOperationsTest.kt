package com.jadxmp.input.jvm

import com.jadxmp.input.CodeReader
import com.jadxmp.input.IndexType
import com.jadxmp.input.Instruction
import com.jadxmp.input.Opcode
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertSame

class JvmTypeOperationsTest {
    @Test fun castAndInstanceofUseTypedIndexedOperandsAndOriginalPositions() {
        val pool = pool(utf8("java/lang/String"), bytes(7, 0, 1))
        val cast = read(normalize("(Ljava/lang/Object;)Ljava/lang/String;", bytes(0x2a, 0xc0, 0, 2, 0xb0), pool))
        val instruction = cast.single { it.opcode == Opcode.CHECK_CAST }
        assertEquals(IndexType.TYPE_REF, instruction.indexType)
        assertEquals(2, instruction.index)
        assertEquals("Ljava/lang/String;", instruction.indexAsType())
        assertEquals(1, instruction.registerCount)
        assertEquals(109, instruction.fileOffset)
        assertEquals("checkcast", instruction.mnemonic)
        assertFailsWith<ByteReaderException> { instruction.indexAsString() }
        val test = read(normalize("(Ljava/lang/Object;)I", bytes(0x2a, 0xc1, 0, 2, 0x04, 0x60, 0xac), pool, stack = 2))
        assertEquals(listOf(1, 1), test.single { it.opcode == Opcode.INSTANCE_OF }.registers)
        assertEquals(Opcode.ADD_INT, test[test.lastIndex - 1].opcode)
    }

    @Test fun arrayCastsAndClassLiteralsKeepDescriptorsAndNeverInventPrimitiveClassNames() {
        for ((name, type) in listOf("[I" to "[I", "[[Ljava/lang/String;" to "[[Ljava/lang/String;", "I" to "LI;")) {
            val pool = pool(utf8(name), bytes(7, 0, 1))
            assertEquals(type, read(normalize("(Ljava/lang/Object;)$type", bytes(0x2a, 0xc0, 0, 2, 0xb0), pool))
                .single { it.opcode == Opcode.CHECK_CAST }.indexAsType())
            val load = read(normalize("()Ljava/lang/Class;", bytes(0x12, 2, 0xb0), pool, locals = 0)).first()
            assertEquals(Opcode.CONST_CLASS, load.opcode)
            assertEquals(IndexType.TYPE_REF, load.indexType)
            assertEquals(type, load.indexAsType())
        }
    }

    @Test fun ldcAndWideLdcRetainStringsAndHighConstantPoolIndexes() {
        val entries = mutableListOf(utf8("literal"), bytes(8, 0, 1))
        repeat(254) { entries += bytes(3, 0, 0, 0, 0) }
        entries += bytes(8, 0, 1) // #257 aliases the same UTF8 literal.
        val pool = pool(*entries.toTypedArray())
        for ((code, index) in listOf(bytes(0x12, 2, 0xb0) to 2, bytes(0x13, 1, 1, 0xb0) to 257)) {
            val load = read(normalize("()Ljava/lang/String;", code, pool, locals = 0)).first()
            assertEquals(Opcode.CONST_STRING, load.opcode)
            assertEquals(IndexType.STRING_REF, load.indexType)
            assertEquals(index, load.index)
            assertEquals("literal", load.indexAsString())
            assertFailsWith<ByteReaderException> { load.indexAsType() }
        }
    }

    @Test fun rejectsWrongTagsCategoriesPrimitiveOperandsAndUnsupportedConstantKinds() {
        val pool = pool(utf8("java/lang/String"), bytes(7, 0, 1), bytes(8, 0, 1), bytes(3, 0, 0, 0, 7))
        for (code in listOf(bytes(0x2a, 0xc0, 0, 3, 0xb0), bytes(0x2a, 0xc1, 0, 4, 0xac),
            bytes(0x03, 0xc0, 0, 2, 0xb0), bytes(0x03, 0xc1, 0, 2, 0xac),
            bytes(0x14, 0, 2, 0xb0), bytes(0x14, 0, 3, 0xb0), bytes(0x12, 1, 0xb0),
            bytes(0x12, 0, 0xb0), bytes(0x2a, 0xc0, 0, 99, 0xb0))) {
            assertFailsWith<ByteReaderException> { normalize("(Ljava/lang/Object;)Ljava/lang/Object;", code, pool) }
        }
        val methodType = pool(utf8("()V"), bytes(16, 0, 1))
        val error = assertFailsWith<ByteReaderException> {
            normalize("()Ljava/lang/Object;", bytes(0x12, 2, 0xb0), methodType, locals = 0)
        }
        assertTrue(error.message.orEmpty().contains("unsupported") || error.message.orEmpty().contains("lowering"))
    }

    @Test fun classLiteralRequiresClassFileVersion49ButTypeChecksDoNot() {
        val oldPool = JvmConstantPool.read(ByteReader(u2(3) + utf8("java/lang/String") + bytes(7, 0, 1)), 48)
        normalize("(Ljava/lang/Object;)Ljava/lang/String;", bytes(0x2a, 0xc0, 0, 2, 0xb0), oldPool)
        assertFailsWith<ByteReaderException> {
            normalize("()Ljava/lang/Class;", bytes(0x12, 2, 0xb0), oldPool, locals = 0)
        }
    }

    @Test fun sharedLongClassNamesAreCanonicalizedWithinABoundedMethodBudget() {
        val name = "x".repeat(2048)
        val entries = mutableListOf(utf8(name))
        repeat(100) { entries += bytes(7, 0, 1) }
        val pool = pool(*entries.toTypedArray())
        val code = (2..101).fold(byteArrayOf()) { result, index ->
            result + bytes(0x13) + u2(index) + bytes(0x57)
        } + bytes(0xb1)
        val loads = read(normalize("()V", code, pool, locals = 0,
            limits = JvmAnalysisLimits(maxConstantWork = 2300))).filter { it.opcode == Opcode.CONST_CLASS }
        assertEquals(100, loads.size)
        for (load in loads) assertSame(loads.first().indexAsType(), load.indexAsType())
        val failure = assertFailsWith<ByteReaderException> {
            normalize("()V", code, pool, locals = 0, limits = JvmAnalysisLimits(maxConstantWork = 2048))
        }
        assertTrue(failure.message.orEmpty().contains("constant operand work limit"))
    }

    @Test fun workBudgetRejectsDistinctLongOperandsAndChargesAliasesOnce() {
        val text = "x".repeat(1000)
        val pool = pool(utf8(text), bytes(8, 0, 1), bytes(8, 0, 1), utf8(text + "y"), bytes(8, 0, 4))
        val aliases = bytes(0x12, 2, 0x57, 0x12, 3, 0x57, 0xb1)
        normalize("()V", aliases, pool, locals = 0, limits = JvmAnalysisLimits(maxConstantWork = 1002))
        val distinct = bytes(0x12, 2, 0x57, 0x12, 5, 0x57, 0xb1)
        assertFailsWith<ByteReaderException> {
            normalize("()V", distinct, pool, locals = 0, limits = JvmAnalysisLimits(maxConstantWork = 1002))
        }
        // Worklist revisits and repeated instructions use the cached operand rather than rescan UTF8.
        normalize("()V", ByteArray(300) { when (it % 3) { 0 -> 0x12; 1 -> 2; else -> 0x57 } } + bytes(0xb1),
            pool, locals = 0, limits = JvmAnalysisLimits(maxConstantWork = 1001))
    }

    private val Instruction.registers get() = (0 until registerCount).map(::register)
    private fun read(reader: CodeReader): List<Instruction> = buildList { reader.visitInstructions { it.decode(); add(it) } }
    private fun normalize(descriptor: String, code: ByteArray, pool: JvmConstantPool, locals: Int = 1, stack: Int = 1, limits: JvmAnalysisLimits = JvmAnalysisLimits()): CodeReader {
        val attr = JvmAttribute("Code", 100, u2(stack) + u2(locals) + i4(code.size) + code + u2(0) + u2(0))
        val member = JvmMember(8, "test", descriptor, listOf(attr))
        return JvmRegisterNormalizer.normalize("Example", member, JvmMethodDescriptor.parse(descriptor), pool,
            JvmCodeAttribute.parse(attr, pool), limits)
    }
    private fun pool(vararg entries: ByteArray) = JvmConstantPool.read(ByteReader(u2(entries.size + 1) + entries.fold(byteArrayOf()) { a, b -> a + b }), 65)
    private fun utf8(value: String) = bytes(1) + u2(value.length) + value.encodeToByteArray()
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private fun u2(value: Int) = bytes(value ushr 8, value)
    private fun i4(value: Int) = bytes(value ushr 24, value ushr 16, value ushr 8, value)
}
