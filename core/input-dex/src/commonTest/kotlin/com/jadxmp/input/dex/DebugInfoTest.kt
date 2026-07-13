package com.jadxmp.input.dex

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives the debug-info state machine over a crafted `debug_info_item`: a special opcode that emits a
 * line entry, plus a `START_LOCAL_EXTENDED` local whose scope is closed at end-of-sequence.
 */
class DebugInfoTest {

    @Test
    fun decodesLineNumbersAndLocalScope() {
        val b = DexBuilder()
        val tV = b.addType("V")
        val proto = b.addProto(tV) // no params -> methodParamTypes(0) is empty
        val tFoo = b.addType("LFoo;")
        val sM = b.addString("m")
        b.addMethod(tFoo, proto, sM) // method idx 0
        val sX = b.addString("x")
        val base = b.build()

        val codeOffset = base.size
        val debugOff = codeOffset + 24 // code_item is 16 header + 4 units (8 bytes), no tries
        val codeItem = buildBytes {
            u16(1); u16(0); u16(0); u16(0) // registers=1, ins, outs, tries=0
            i32(debugOff)
            i32(4) // insns_size units
            u16(0); u16(0); u16(0); u16(0) // 4 nop units
        }
        val debug = buildBytes {
            uleb(7) // line_start
            uleb(0) // parameters_size
            // START_LOCAL_EXTENDED reg0, name="x", type=LFoo;, no signature
            u8(0x04); uleb(0); uleb(sX + 1); uleb(tFoo + 1); uleb(0)
            u8(0x1D) // special: addr += 1, line += 0  -> line entry {1: 7}
            u8(0x00) // END_SEQUENCE
        }
        val dex = Dex(base + codeItem + debug, "t", 0)
        val info = DexCodeReader(dex, codeOffset, methodIdx = 0).debugInfo
            ?: error("expected debug info")

        assertEquals(mapOf(1 to 7), info.lineNumbers)

        val local = info.localVars.single()
        assertEquals("x", local.name)
        assertEquals("LFoo;", local.type)
        assertEquals(0, local.startOffset)
        assertEquals(3, local.endOffset) // closed at codeSize - 1
        assertEquals(false, local.isParameter)
    }
}
