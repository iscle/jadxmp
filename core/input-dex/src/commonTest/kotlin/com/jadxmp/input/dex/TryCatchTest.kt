package com.jadxmp.input.dex

import com.jadxmp.input.dex.DexTestSupport.minimalHeader
import kotlin.test.Test
import kotlin.test.assertEquals

/** Crafted `code_item` try tables covering both the typed and the `size <= 0` catch-all handler forms. */
class TryCatchTest {

    @Test
    fun catchAllHandler() {
        // one try over the whole method, guarded by a catch-all handler (encoded size 0) -> addr 5
        val codeItem = buildBytes {
            u16(1); u16(0); u16(0); u16(1) // registers, ins, outs, tries
            i32(0) // debug_info_off
            i32(2) // insns_size (units)
            u16(0); u16(0) // two nop code units
            i32(0); u16(2); u16(1) // try_item: start_addr=0, insn_count=2, handler_off=1
            uleb(1) // handler list size
            u8(0x00) // encoded_catch_handler size = 0 (catch-all only)
            uleb(5) // catch_all_addr
        }
        val reader = DexCodeReader(Dex(minimalHeader() + codeItem, "t", 0), codeItemOffset = 112, methodIdx = 0)
        val tries = reader.tries
        assertEquals(1, tries.size)
        assertEquals(0, tries[0].startOffset)
        assertEquals(1, tries[0].endOffset)
        val handler = tries[0].catchHandler
        assertEquals(emptyList(), handler.types)
        assertEquals(5, handler.catchAllHandler)
    }

    @Test
    fun typedHandler() {
        val b = DexBuilder()
        val tExc = b.addType("LMyException;")
        val base = b.build()
        val codeItem = buildBytes {
            u16(1); u16(0); u16(0); u16(1)
            i32(0)
            i32(2)
            u16(0); u16(0)
            i32(0); u16(2); u16(1) // try_item -> handler at byte offset 1
            uleb(1) // handler list size
            uleb(1) // encoded_catch_handler size = 1 (one typed handler, no catch-all)
            uleb(tExc); uleb(7) // (type_idx, addr)
        }
        val reader = DexCodeReader(Dex(base + codeItem, "t", 0), codeItemOffset = base.size, methodIdx = 0)
        val handler = reader.tries.single().catchHandler
        assertEquals(listOf("LMyException;"), handler.types)
        assertEquals(listOf(7), handler.handlers)
        assertEquals(-1, handler.catchAllHandler)
    }
}
