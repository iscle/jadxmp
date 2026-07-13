package com.jadxmp.input.dex

import com.jadxmp.input.dex.DexTestSupport.bytes
import com.jadxmp.input.dex.DexTestSupport.getIntLe
import com.jadxmp.input.dex.DexTestSupport.minimalHeader
import com.jadxmp.input.dex.DexTestSupport.setIntLe
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Hostile inputs where an attacker-controlled length field would, without bounds checks, force a
 * gigantic allocation (OutOfMemoryError) or a negative-size crash. Every case must degrade to a
 * catchable [ByteReaderException] — the robustness cardinal rule.
 */
class AdversarialTest {

    private fun decodeFillArray(vararg operandBytes: Int): Unit {
        val dex = Dex(minimalHeader(), "t", 0)
        val insn = DexInstruction(dex, ByteReader(bytes(*operandBytes)))
        insn.reset(0, 0, 0x0300, DexOpcodeTable.lookup(0x0300))
        insn.decode()
    }

    @Test
    fun hugeStringIdsSizeDoesNotOom() {
        // The demonstrated 112-byte OOM: string_ids_size = 0x40000000 in a tiny file.
        val header = minimalHeader().setIntLe(56, 0x40000000)
        assertFailsWith<ByteReaderException> { DexInput.load("evil.dex", header) }
    }

    @Test
    fun hugeClassDefsSizeDoesNotOom() {
        val header = minimalHeader().setIntLe(96, 0x40000000) // class_defs_size
        assertFailsWith<ByteReaderException> { DexInput.load("evil.dex", header) }
    }

    @Test
    fun hugeMapOffsetFailsGracefully() {
        // Hostile OFFSET (not size): map_off points far past the file. Must be ByteReaderException,
        // not the IllegalArgumentException that ByteReader's constructor would otherwise raise.
        val header = minimalHeader().setIntLe(52, 0x40000000) // map_off
        assertFailsWith<ByteReaderException> { DexInput.load("evil.dex", header) }
    }

    @Test
    fun hugeClassDataOffsetFailsGracefully() {
        // A real class whose class_data_off is corrupted to a wild offset.
        val dex = DexBuilder().apply {
            val tFoo = addType("LFoo;")
            val tObj = addType("Ljava/lang/Object;")
            addClass(tFoo, tObj)
        }.build()
        val classDefsOff = dex.getIntLe(100) // header field class_defs_off
        dex.setIntLe(classDefsOff + 24, 0x40000000) // class_data_off within the class_def
        assertFailsWith<ByteReaderException> { DexInput.load("evil.dex", dex) }
    }

    @Test
    fun negativeFillArraySizeFailsGracefully() {
        // elemSize=4, size=-1  -> would be NegativeArraySizeException without a guard
        assertFailsWith<ByteReaderException> {
            decodeFillArray(0x04, 0x00, 0xFF, 0xFF, 0xFF, 0xFF)
        }
    }

    @Test
    fun hugeFillArraySizeFailsGracefully() {
        // elemSize=4, size=0x40000000, no data present
        assertFailsWith<ByteReaderException> {
            decodeFillArray(0x04, 0x00, 0x00, 0x00, 0x00, 0x40)
        }
    }

    @Test
    fun hugeEncodedArrayCountFailsGracefully() {
        val parser = EncodedValueParser(Dex(minimalHeader(), "t", 0))
        // VALUE_ARRAY then a uleb count of 0x0FFFFFFF with no elements following.
        val payload = buildBytes { u8(0x1c); uleb(0x0FFFFFFF) }
        assertFailsWith<ByteReaderException> { parser.parseValue(ByteReader(payload)) }
    }

    @Test
    fun hugeAnnotationElementCountFailsGracefully() {
        val parser = AnnotationsParser(Dex(minimalHeader(), "t", 0))
        // encoded_annotation: type_idx=0, size=0x0FFFFFFF, no elements follow
        val payload = buildBytes { uleb(0); uleb(0x0FFFFFFF) }
        assertFailsWith<ByteReaderException> {
            parser.readAnnotation(ByteReader(payload), readVisibility = false)
        }
    }

    @Test
    fun hugeCatchHandlerCountFailsGracefully() {
        // A try table whose handler list declares a colossal number of handlers.
        val codeItem = buildBytes {
            u16(1); u16(0); u16(0); u16(1)
            i32(0)
            i32(2)
            u16(0); u16(0)
            i32(0); u16(2); u16(1) // try_item
            uleb(0x0FFFFFFF) // handler list size (bomb), nothing follows
        }
        val reader = DexCodeReader(Dex(minimalHeader() + codeItem, "t", 0), codeItemOffset = 112, methodIdx = 0)
        assertFailsWith<ByteReaderException> { reader.tries }
    }
}
