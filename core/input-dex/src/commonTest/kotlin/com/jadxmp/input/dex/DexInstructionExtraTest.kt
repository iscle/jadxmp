package com.jadxmp.input.dex

import com.jadxmp.input.Opcode
import com.jadxmp.input.SwitchPayload
import com.jadxmp.input.dex.DexTestSupport.bytes
import com.jadxmp.input.dex.DexTestSupport.minimalHeader
import com.jadxmp.io.ByteReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

/** Additional instruction-decode coverage: /2addr and /lit folding, high16, and sparse-switch. */
class DexInstructionExtraTest {

    private fun decode(opcodeUnit: Int, vararg operandBytes: Int): DexInstruction {
        val dex = Dex(minimalHeader(), "t", 0)
        val insn = DexInstruction(dex, ByteReader(bytes(*operandBytes)))
        insn.reset(0, 0, opcodeUnit, DexOpcodeTable.lookup(opcodeUnit))
        insn.decode()
        return insn
    }

    @Test
    fun foldsTwoAddrForm() {
        // add-int/2addr v1, v2  -> normalized ADD_INT
        val insn = decode(0x21b0)
        assertEquals(Opcode.ADD_INT, insn.opcode)
        assertEquals(1, insn.register(0))
        assertEquals(2, insn.register(1))
    }

    @Test
    fun foldsLit16Form() {
        // add-int/lit16 v1, v2, #1000 -> ADD_INT_LIT
        val insn = decode(0x21d0, 0xE8, 0x03)
        assertEquals(Opcode.ADD_INT_LIT, insn.opcode)
        assertEquals(1, insn.register(0))
        assertEquals(2, insn.register(1))
        assertEquals(1000L, insn.literal)
    }

    @Test
    fun foldsLit8FormWithSignedLiteral() {
        // add-int/lit8 v3, v4, #-1 -> ADD_INT_LIT, literal -1
        val insn = decode(0x03d8, 0x04, 0xFF)
        assertEquals(Opcode.ADD_INT_LIT, insn.opcode)
        assertEquals(3, insn.register(0))
        assertEquals(4, insn.register(1))
        assertEquals(-1L, insn.literal)
    }

    @Test
    fun decodesHigh16() {
        // const/high16 v2, #0x1_0000 (raw 0x0001 shifted left 16)
        val insn = decode(0x0215, 0x01, 0x00)
        assertEquals(Opcode.CONST, insn.opcode)
        assertEquals(2, insn.register(0))
        assertEquals(0x10000L, insn.literal)
    }

    @Test
    fun decodesConstWideHigh16ShiftsBy48() {
        // const-wide/high16 v0, #0x0001_0000_0000_0000 (raw 0x0001 shifted left 48)
        val insn = decode(0x0019, 0x01, 0x00)
        assertEquals(Opcode.CONST_WIDE, insn.opcode)
        assertEquals(1L shl 48, insn.literal)
    }

    @Test
    fun decodesSparseSwitchPayload() {
        val insn = decode(
            0x0200,
            0x02, 0x00, // size
            0x05, 0x00, 0x00, 0x00, // key 0
            0x09, 0x00, 0x00, 0x00, // key 1
            0x10, 0x00, 0x00, 0x00, // target 0
            0x20, 0x00, 0x00, 0x00, // target 1
        )
        val payload = assertIs<SwitchPayload>(insn.payload)
        assertContentEquals(intArrayOf(5, 9), payload.keys)
        assertContentEquals(intArrayOf(16, 32), payload.targets)
        assertEquals(2 * 4 + 2, insn.length)
    }

    @Test
    fun registerBeforeDecodeThrows() {
        val dex = Dex(minimalHeader(), "t", 0)
        val insn = DexInstruction(dex, ByteReader(bytes()))
        insn.reset(0, 0, 0x21b0, DexOpcodeTable.lookup(0x21b0))
        // decode() intentionally not called
        assertFailsWith<IllegalStateException> { insn.register(0) }
    }
}
