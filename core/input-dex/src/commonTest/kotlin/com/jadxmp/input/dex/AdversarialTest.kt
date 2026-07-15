package com.jadxmp.input.dex

import com.jadxmp.input.dex.DexTestSupport.bytes
import com.jadxmp.input.dex.DexTestSupport.getIntLe
import com.jadxmp.input.dex.DexTestSupport.minimalHeader
import com.jadxmp.input.dex.DexTestSupport.setIntLe
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    /** Like [decodeFillArray] but exercises skip() — the operand-stepping twin that must guard `size` too. */
    private fun skipFillArray(vararg operandBytes: Int): Unit {
        val dex = Dex(minimalHeader(), "t", 0)
        val insn = DexInstruction(dex, ByteReader(bytes(*operandBytes)))
        insn.reset(0, 0, 0x0300, DexOpcodeTable.lookup(0x0300))
        insn.skip()
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
        // A real class whose class_data_off is corrupted to a wild offset. Per-class fault isolation
        // (CLAUDE.md rule 4) now SALVAGES the DEX: the poison class is caught, skipped and recorded as
        // a diagnostic, and the load succeeds — the wild offset no longer unwinds through the whole
        // file. (Formerly this asserted DexInput.load THREW ByteReaderException; that assertion pinned
        // the rule-4 violation F5 fixes, so it is updated to the corrected graceful-salvage behaviour.)
        val dex = DexBuilder().apply {
            val tFoo = addType("LFoo;")
            val tObj = addType("Ljava/lang/Object;")
            addClass(tFoo, tObj)
        }.build()
        val classDefsOff = dex.getIntLe(100) // header field class_defs_off
        dex.setIntLe(classDefsOff + 24, 0x40000000) // class_data_off within the (only) class_def

        val parser = DexClassParser(Dex(dex, "evil.dex", 0))
        val classes = parser.parseAll()
        assertTrue(classes.isEmpty(), "the sole corrupt class is skipped, not fatal")
        assertTrue(parser.diagnostics.isNotEmpty(), "the dropped class is recorded, not silent")
        // The top-level loader no longer throws for this input either.
        assertTrue(DexInput.load("evil.dex", dex).isEmpty)
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

    // ---- F1: attacker-controlled utf16_size reached through Dex.string() -----------------------

    @Test
    fun hugeStringUtf16SizeDoesNotOom() {
        // A string_data_item whose uleb128 utf16_size is 0x40000000, reached via Dex.string() ->
        // ByteReader.readMutf8(). Without the bound this pre-sizes a ~2 GiB StringBuilder (OOM).
        val header = minimalHeader().setIntLe(56, 1).setIntLe(60, 112) // string_ids_size=1, off=112
        val dexBytes = header + buildBytes {
            i32(116) // string_ids[0] -> string_data_item at offset 116
            uleb(0x40000000) // utf16_size bomb; no MUTF-8 bytes follow
        }
        assertFailsWith<ByteReaderException> { Dex(dexBytes, "evil.dex", 0).string(0) }
    }

    // ---- F3: unbounded encoded-value recursion (StackOverflowError) ----------------------------

    @Test
    fun deeplyNestedEncodedArrayFailsGracefully() {
        val parser = EncodedValueParser(Dex(minimalHeader(), "t", 0))
        // 20 000 nested VALUE_ARRAY headers, each with count 1. Without a depth cap parseValue ->
        // parseArray -> parseValue recurses ~20 000 frames deep and StackOverflowError-s (an Error
        // that escapes catch(Exception)); the cap turns it into a catchable ByteReaderException.
        val payload = buildBytes { repeat(20_000) { u8(0x1c); uleb(1) } }
        assertFailsWith<ByteReaderException> { parser.parseValue(ByteReader(payload)) }
    }

    @Test
    fun deeplyNestedEncodedAnnotationFailsGracefully() {
        // A resolvable string #0 is needed for each element's name lookup; the type lookup happens on
        // unwind and is never reached because the depth cap throws first.
        val dexBytes = DexBuilder().apply { addString("v") }.build()
        val parser = EncodedValueParser(Dex(dexBytes, "t", 0))
        // 20 000 nested VALUE_ANNOTATION values: each encoded_annotation { type_idx=0, size=1,
        // name_idx=0 } whose single element is the next annotation. parseValue -> readAnnotation ->
        // parseValue must be bounded just like the array path.
        val payload = buildBytes {
            u8(0x1d) // outermost VALUE_ANNOTATION marker
            repeat(20_000) { uleb(0); uleb(1); uleb(0); u8(0x1d) }
        }
        assertFailsWith<ByteReaderException> { parser.parseValue(ByteReader(payload)) }
    }

    // ---- F6: fill-array skip() must guard `size` like its decode() twin ------------------------

    @Test
    fun fillArraySkipWithHugeSizeFailsGracefully() {
        // elemSize=4, size=0x40000000, no data present. Without the guard, `size * elemSize` overflows
        // Int to 0 and skip() silently mis-steps the stream; the guard fails it as ByteReaderException.
        assertFailsWith<ByteReaderException> { skipFillArray(0x04, 0x00, 0x00, 0x00, 0x00, 0x40) }
    }

    @Test
    fun fillArraySkipWithNegativeSizeFailsGracefully() {
        // elemSize=4, size=-1. Without the guard, skip(size * elemSize) hits ByteReader.skip's
        // negative-count IllegalArgumentException; the guard converges it on ByteReaderException.
        assertFailsWith<ByteReaderException> { skipFillArray(0x04, 0x00, 0xFF, 0xFF, 0xFF, 0xFF) }
    }

    // ---- F5: one corrupt class must not drop the whole DEX (rule 4) ----------------------------

    @Test
    fun oneCorruptClassDoesNotDropTheWholeDex() {
        // Three classes; the middle one's class_data_off is corrupted to a wild offset so parseClass
        // throws. The two good classes must still load — pre-fix, the throw unwound through the whole
        // loop and every class in the DEX was lost.
        val dex = DexBuilder().apply {
            val tObj = addType("Ljava/lang/Object;")
            val tA = addType("LA;")
            val tBad = addType("LBad;")
            val tC = addType("LC;")
            addClass(tA, tObj) // class_def #0
            addClass(tBad, tObj) // class_def #1  <- corrupted below
            addClass(tC, tObj) // class_def #2
        }.build()
        val classDefsOff = dex.getIntLe(100)
        dex.setIntLe(classDefsOff + 1 * 32 + 24, 0x40000000) // class #1 class_data_off -> wild

        val names = DexInput.load("evil.dex", dex).classes.map { it.type }.toSet()
        assertTrue("LA;" in names && "LC;" in names, "good classes survived: $names")
        assertFalse("LBad;" in names, "corrupt class was skipped: $names")
    }
}
