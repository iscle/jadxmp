package com.jadxmp.resources

import com.jadxmp.io.ByteReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hostile resource inputs that, without guards, crash the process: a string-pool length that OOMs a
 * StringBuilder (F2) and an AXML nested deep enough to StackOverflowError the tree serialiser (F4).
 * Both must degrade to a bounded, catchable outcome — the robustness cardinal rule.
 */
class ResourcesAdversarialTest {

    // ---- F2: attacker-controlled UTF-16 string length ------------------------------------------

    @Test
    fun hugeUtf16StringLengthDoesNotOom() {
        // A UTF-16 ResStringPool whose one string declares a ~2^31-unit length (aapt varint 0xFFFF
        // 0xFFFF). Pre-fix, readUtf16 pre-sized StringBuilder(0x7FFFFFFF) -> OutOfMemoryError, which
        // escaped parse()'s catch(ByteReaderException). Now it is bounded and salvaged to PLACEHOLDER.
        val b = Buf()
        b.u16(ResChunkTypes.STRING_POOL)
        b.u16(0x1c) // headerSize
        val sizePos = b.size; b.u32(0)
        b.u32(1) // stringCount
        b.u32(0) // styleCount
        b.u32(0) // flags = UTF-16 (UTF8 bit clear)
        val strStartPos = b.size; b.u32(0) // stringsStart
        b.u32(0) // stylesStart
        b.u32(0) // offset[0]
        val dataStart = b.size
        b.u16(0xFFFF); b.u16(0xFFFF) // readLen16 -> 0x7FFFFFFF units (the bomb)
        b.patchU32(sizePos, b.size)
        b.patchU32(strStartPos, dataStart)
        val bytes = b.toByteArray()

        val reader = ByteReader(bytes)
        reader.seek(8) // StringPool.parse expects the cursor just past the 8-byte ResChunk_header
        val pool = StringPool.parse(reader, chunkStart = 0, chunkEnd = bytes.size)
        assertEquals(StringPool.PLACEHOLDER, pool[0], "the hostile string is salvaged, not fatal")
    }

    // ---- F4: unbounded AXML nesting (StackOverflowError in the serialiser) ----------------------

    @Test
    fun deeplyNestedXmlDoesNotStackOverflow() {
        // ~8 000 nested elements. Pre-fix the tree built fine (the parse is iterative) but
        // XmlWriter.write recursed ~8 000 frames deep -> StackOverflowError, thrown OUTSIDE
        // decodeWithDiagnostics' try/catch. The depth cap + iterative writer make it graceful.
        val r = BinaryXmlDecoder.decodeWithDiagnostics(deeplyNestedXml(8_000))
        assertTrue(r.xml.startsWith("<?xml"), "a well-formed document is still produced")
        assertTrue(
            r.diagnostics.any { it.contains("nesting exceeds") },
            "the depth cap is reported: ${r.diagnostics}",
        )
    }

    /** A binary XML that nests [depth] `<a>` elements (string #0), then closes them all. */
    private fun deeplyNestedXml(depth: Int): ByteArray {
        val pool = utf8StringPool(listOf("a"))
        val none = 0xffffffff.toInt()
        val b = Buf()
        b.u16(ResChunkTypes.XML); b.u16(8); val sizePos = b.size; b.u32(0)
        b.bytes(pool)
        repeat(depth) {
            // START_ELEMENT: header(16) + ns(-1) + name(0) + attrStart/Size + attrCount(0) + 3 idx
            b.u16(ResChunkTypes.XML_START_ELEMENT); b.u16(0x10); b.u32(36)
            b.u32(1); b.u32(none) // lineNumber, comment
            b.u32(none); b.u32(0) // ns=-1, name=0 ("a")
            b.u16(0x14); b.u16(0x14); b.u16(0); b.u16(0); b.u16(0); b.u16(0)
        }
        repeat(depth) {
            b.u16(ResChunkTypes.XML_END_ELEMENT); b.u16(0x10); b.u32(0x18)
            b.u32(1); b.u32(none) // lineNumber, comment
            b.u32(none); b.u32(0) // ns=-1, name=0
        }
        b.patchU32(sizePos, b.size)
        return b.toByteArray()
    }
}
