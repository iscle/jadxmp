package com.jadxmp.input.dex

import com.jadxmp.input.Opcode
import com.jadxmp.input.dex.DexTestSupport.minimalHeader
import com.jadxmp.input.dex.DexTestSupport.setIntLe
import com.jadxmp.input.dex.DexTestSupport.setShortLe
import kotlin.test.Test
import kotlin.test.assertEquals

class DexCodeReaderTest {

    /** Header + a hand-built code_item with two instructions: `const/4 v0, #1` then `return-void`. */
    private fun dexWithCode(): Dex {
        val header = minimalHeader()
        val codeItem = ByteArray(20)
        codeItem.setShortLe(0, 2) // registers_size
        codeItem.setShortLe(2, 0) // ins_size
        codeItem.setShortLe(4, 0) // outs_size
        codeItem.setShortLe(6, 0) // tries_size
        codeItem.setIntLe(8, 0) // debug_info_off
        codeItem.setIntLe(12, 2) // insns_size (code units)
        codeItem.setShortLe(16, 0x1012) // const/4 v0, #1
        codeItem.setShortLe(18, 0x000e) // return-void
        val data = header + codeItem
        return Dex(data, "t", 0)
    }

    @Test
    fun visitsInstructionsInOrder() {
        val reader = DexCodeReader(dexWithCode(), codeItemOffset = 112, methodIdx = 0)
        assertEquals(2, reader.registerCount)
        assertEquals(2, reader.unitsCount)

        val opcodes = mutableListOf<Opcode>()
        var firstLiteral = 0L
        var firstOffset = -1
        reader.visitInstructions { insn ->
            insn.decode()
            opcodes.add(insn.opcode)
            if (opcodes.size == 1) {
                firstLiteral = insn.literal
                firstOffset = insn.offset
            }
        }
        assertEquals(listOf(Opcode.CONST, Opcode.RETURN_VOID), opcodes)
        assertEquals(1L, firstLiteral)
        assertEquals(0, firstOffset)
    }

    @Test
    fun noTriesWhenTableEmpty() {
        val reader = DexCodeReader(dexWithCode(), codeItemOffset = 112, methodIdx = 0)
        assertEquals(0, reader.tries.size)
        assertEquals(null, reader.debugInfo)
    }
}
