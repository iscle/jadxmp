package com.jadxmp.input.dex

import com.jadxmp.input.FillArrayDataPayload
import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.input.SwitchPayload
import com.jadxmp.input.dex.DexTestSupport.bytes
import com.jadxmp.input.dex.DexTestSupport.minimalHeader
import com.jadxmp.io.ByteReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DexInstructionTest {

    private fun decode(opcodeUnit: Int, vararg operandBytes: Int): DexInstruction {
        val dex = Dex(minimalHeader(), "t", 0)
        val insn = DexInstruction(dex, ByteReader(bytes(*operandBytes)))
        insn.reset(fileOffset = 0, offset = 0, opcodeUnit = opcodeUnit, insnInfo = DexOpcodeTable.lookup(opcodeUnit))
        insn.decode()
        return insn
    }

    @Test
    fun decodesConst4_11n() {
        val insn = decode(0x1012) // const/4 v0, #1
        assertEquals(Opcode.CONST, insn.opcode)
        assertEquals(0, insn.register(0))
        assertEquals(1L, insn.literal)
    }

    @Test
    fun decodesConst16_21s() {
        val insn = decode(0x0213, 0x00, 0x01) // const/16 v2, #256
        assertEquals(Opcode.CONST, insn.opcode)
        assertEquals(2, insn.register(0))
        assertEquals(256L, insn.literal)
    }

    @Test
    fun decodesGotoForwardAndBackward_10t() {
        assertEquals(5, decode(0x0528).target) // goto +5
        assertEquals(-1, decode(0xFF28).target) // goto -1
    }

    @Test
    fun decodesConstString_21c() {
        val insn = decode(0x031a, 0x07, 0x00) // const-string v3, string@7
        assertEquals(Opcode.CONST_STRING, insn.opcode)
        assertEquals(IndexType.STRING_REF, insn.indexType)
        assertEquals(3, insn.register(0))
        assertEquals(7, insn.index)
    }

    @Test
    fun decodesInvokeVirtual_35c() {
        // invoke-virtual {v1, v2}, method@5  (arg count 2)
        val insn = decode(0x206e, 0x05, 0x00, 0x21, 0x00)
        assertEquals(Opcode.INVOKE_VIRTUAL, insn.opcode)
        assertEquals(IndexType.METHOD_REF, insn.indexType)
        assertEquals(5, insn.index)
        assertEquals(2, insn.registerCount)
        assertEquals(1, insn.register(0))
        assertEquals(2, insn.register(1))
    }

    @Test
    fun decodesInvokeRange_3rc() {
        // invoke-static/range {v4..v6}, method@9  (count 3, start reg 4)
        val insn = decode(0x0377, 0x09, 0x00, 0x04, 0x00)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, insn.opcode)
        assertEquals(9, insn.index)
        assertEquals(3, insn.registerCount)
        assertEquals(4, insn.register(0))
        assertEquals(5, insn.register(1))
        assertEquals(6, insn.register(2))
    }

    @Test
    fun decodesPackedSwitchPayload() {
        // size=2, firstKey=10, targets=[20, 28]
        val insn = decode(
            0x0100,
            0x02, 0x00, // size
            0x0A, 0x00, 0x00, 0x00, // first key
            0x14, 0x00, 0x00, 0x00, // target 0
            0x1C, 0x00, 0x00, 0x00, // target 1
        )
        val payload = assertIs<SwitchPayload>(insn.payload)
        assertContentEquals(intArrayOf(10, 11), payload.keys)
        assertContentEquals(intArrayOf(20, 28), payload.targets)
        assertEquals(2 * 2 + 4, insn.length)
    }

    @Test
    fun decodesFillArrayDataPayload() {
        // elemSize=1, size=3, data=[1,2,3] (+1 pad byte)
        val insn = decode(
            0x0300,
            0x01, 0x00, // element size
            0x03, 0x00, 0x00, 0x00, // count
            0x01, 0x02, 0x03, 0x00, // data + pad
        )
        val payload = assertIs<FillArrayDataPayload>(insn.payload)
        assertEquals(3, payload.size)
        assertEquals(1, payload.elementSize)
        assertContentEquals(bytes(1, 2, 3), payload.data as ByteArray)
        assertEquals((3 * 1 + 1) / 2 + 4, insn.length)
    }
}
