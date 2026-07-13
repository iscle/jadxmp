package com.jadxmp.input.dex

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DexOpcodeTableTest {

    @Test
    fun normalizesMoveVariantsToOneOpcode() {
        // move, move/from16, move/16 all collapse to MOVE with different wire formats.
        assertEquals(Opcode.MOVE, DexOpcodeTable.lookup(0x01)!!.apiOpcode)
        assertEquals(DexFormat.F12X, DexOpcodeTable.lookup(0x01)!!.format)
        assertEquals(Opcode.MOVE, DexOpcodeTable.lookup(0x02)!!.apiOpcode)
        assertEquals(DexFormat.F22X, DexOpcodeTable.lookup(0x02)!!.format)
        assertEquals(Opcode.MOVE, DexOpcodeTable.lookup(0x03)!!.apiOpcode)
    }

    @Test
    fun mapsIndexTypesForRefInstructions() {
        assertEquals(IndexType.STRING_REF, DexOpcodeTable.lookup(0x1a)!!.indexType) // const-string
        assertEquals(IndexType.TYPE_REF, DexOpcodeTable.lookup(0x1c)!!.indexType) // const-class
        assertEquals(IndexType.FIELD_REF, DexOpcodeTable.lookup(0x52)!!.indexType) // iget
        assertEquals(IndexType.METHOD_REF, DexOpcodeTable.lookup(0x6e)!!.indexType) // invoke-virtual
        assertEquals(IndexType.CALL_SITE, DexOpcodeTable.lookup(0xfc)!!.indexType) // invoke-custom
    }

    @Test
    fun collapsesArithmeticAndTwoAddrForms() {
        assertEquals(Opcode.ADD_INT, DexOpcodeTable.lookup(0x90)!!.apiOpcode) // add-int
        assertEquals(Opcode.ADD_INT, DexOpcodeTable.lookup(0xb0)!!.apiOpcode) // add-int/2addr
        assertEquals(Opcode.ADD_INT_LIT, DexOpcodeTable.lookup(0xd0)!!.apiOpcode) // add-int/lit16
        assertEquals(Opcode.ADD_INT_LIT, DexOpcodeTable.lookup(0xd8)!!.apiOpcode) // add-int/lit8
    }

    @Test
    fun mnemonicsMatchSmali() {
        assertEquals("move-result-object", DexOpcodeTable.lookup(0x0c)!!.mnemonic)
        assertEquals("invoke-direct", DexOpcodeTable.lookup(0x70)!!.mnemonic)
    }

    @Test
    fun classifiesConstMethodHandleAndType() {
        assertEquals(IndexType.METHOD_HANDLE_REF, DexOpcodeTable.lookup(0xfe)!!.indexType) // const-method-handle
        assertEquals(IndexType.PROTO_REF, DexOpcodeTable.lookup(0xff)!!.indexType) // const-method-type
    }

    @Test
    fun resolvesPayloadPseudoOpcodes() {
        assertEquals(Opcode.PACKED_SWITCH_PAYLOAD, DexOpcodeTable.lookup(0x0100)!!.apiOpcode)
        assertEquals(Opcode.SPARSE_SWITCH_PAYLOAD, DexOpcodeTable.lookup(0x0200)!!.apiOpcode)
        assertEquals(Opcode.FILL_ARRAY_DATA_PAYLOAD, DexOpcodeTable.lookup(0x0300)!!.apiOpcode)
    }

    @Test
    fun unusedOpcodeSlotsAreNull() {
        assertNull(DexOpcodeTable.lookup(0x3e)) // unused gap
        assertNull(DexOpcodeTable.lookup(0x73)) // reserved
    }
}
