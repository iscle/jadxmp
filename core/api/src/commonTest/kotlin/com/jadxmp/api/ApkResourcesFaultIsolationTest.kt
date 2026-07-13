package com.jadxmp.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fault isolation (rule 4) and honest content handling (rule 2) for the resources facade, exercised
 * with in-memory bytes so it runs on every target (incl. wasmJs — the browser is the whole point).
 * Uses the internal [ApkResources] constructor to feed crafted entries without a real zip.
 */
class ApkResourcesFaultIsolationTest {

    private fun resources(vararg entries: Pair<String, ByteArray>): ApkResources =
        ApkResources(entries.toMap(), diagnostics = emptyList())

    @Test
    fun alreadyTextXmlIsPassedThroughUnchanged() {
        val text = "<?xml version=\"1.0\"?>\n<resources>\n    <string name=\"a\">b</string>\n</resources>\n"
        val res = resources("res/values/strings.xml" to text.encodeToByteArray())
        assertEquals(text, res.decodeXml("res/values/strings.xml"), "plain-text XML must pass through verbatim")
    }

    @Test
    fun nonXmlBinaryBlobIsSkippedHonestlyAsNull() {
        // Not the RES_XML magic and not '<' text: neither decodable nor passthrough -> honest null, no corruption.
        val blob = ByteArray(64) { (it * 31 + 7).toByte() }
        val res = resources("res/raw/data.xml" to blob)
        assertNull(res.decodeXml("res/raw/data.xml"))
    }

    @Test
    fun malformedBinaryXmlDoesNotThrowAndReturnsNull() {
        // RES_XML magic (03 00 08 00) but a bogus/oversized chunk body: the decoder degrades, we return null.
        val magicButGarbage = byteArrayOf(0x03, 0x00, 0x08, 0x00) +
            ByteArray(120) { (it * 13 + 5).toByte() }
        val res = resources("AndroidManifest.xml" to magicButGarbage)
        // Must not throw; a decode that yields nothing decodable is null, not a mangled shell.
        assertNull(res.decodeManifest())
    }

    @Test
    fun malformedArscDegradesToErrorMarkedTableWithoutThrowing() {
        val garbageArsc = ByteArray(80) { (it * 17 + 1).toByte() }
        val res = resources("resources.arsc" to garbageArsc)
        val table = assertNotNull(res.table, "a present-but-broken arsc still yields an (error-marked) table")
        assertTrue(table.entries.isEmpty(), "no rows salvageable from garbage")
        assertTrue(table.diagnostics.isNotEmpty(), "the failure is reported, not silent")
    }

    @Test
    fun emptyEntryReturnsNull() {
        val res = resources("res/layout/x.xml" to ByteArray(0))
        assertNull(res.decodeXml("res/layout/x.xml"))
    }

    @Test
    fun xmlResourcePathsListsOnlyResXml() {
        val res = resources(
            "AndroidManifest.xml" to byteArrayOf(0x03, 0x00, 0x08, 0x00),
            "res/layout/a.xml" to byteArrayOf(0x03, 0x00, 0x08, 0x00),
            "res/values/b.xml" to "<resources/>".encodeToByteArray(),
            "resources.arsc" to ByteArray(4),
        )
        assertEquals(listOf("res/layout/a.xml", "res/values/b.xml"), res.xmlResourcePaths)
    }

    @Test
    fun partialBinaryXmlDecodeSurfacesDiagnostics() {
        // A well-formed RES_XML with a real <a/> root element (so it decodes to non-blank content) followed
        // by a corrupt trailing chunk: the decoder emits the element AND records a skip diagnostic. The
        // facade must return the best-effort XML *and* surface that diagnostic — a partial decode must never
        // be presented as complete (S1).
        val res = resources("res/layout/a.xml" to partialBinaryXml())
        val xml = res.decodeXml("res/layout/a.xml")
        assertNotNull(xml, "the root element decodes to best-effort content, not null")
        assertTrue(xml.contains("<a"), "the recovered <a/> element is present:\n$xml")
        assertTrue(
            res.diagnostics.any { it.startsWith("res/layout/a.xml:") },
            "the partial-decode diagnostic must be surfaced on ApkResources.diagnostics, got ${res.diagnostics}",
        )
    }

    /**
     * A minimal binary `RES_XML`: header → UTF-8 string pool `["a"]` → one `<a>` start element (root) →
     * a bogus 8-byte chunk header claiming a huge size. The decoder recovers `<a/>` but records a
     * "bad chunk header" diagnostic for the corrupt trailing chunk — exactly the chunk-skipped partial
     * decode S1 must not hide.
     */
    private fun partialBinaryXml(): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(v: Int) { out.add((v and 0xff).toByte()) }
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Int) { u16(v); u16(v shr 16) }
        fun patchU32(pos: Int, v: Int) {
            out[pos] = (v and 0xff).toByte(); out[pos + 1] = ((v shr 8) and 0xff).toByte()
            out[pos + 2] = ((v shr 16) and 0xff).toByte(); out[pos + 3] = ((v shr 24) and 0xff).toByte()
        }
        val none = -1 // 0xffffffff

        // RES_XML header (type=0x0003, headerSize=8, size patched last).
        u16(0x0003); u16(0x0008); val xmlSizePos = out.size; u32(0)
        // UTF-8 string pool holding just "a" (mirrors the resources module's own test builder layout).
        // Its size / stringsStart offsets are relative to the pool chunk start, not the whole buffer.
        val poolStart = out.size
        u16(0x0001); u16(0x1c); val poolSizePos = out.size; u32(0)
        u32(1); u32(0); u32(0x100) // stringCount=1, styleCount=0, flags=UTF8
        val strStartPos = out.size; u32(0); u32(0) // stringsStart (patch), stylesStart
        val offsetsPos = out.size; u32(0) // offset[0] (patch)
        val dataStart = out.size
        patchU32(strStartPos, dataStart - poolStart) // pool-relative strings start
        patchU32(offsetsPos, out.size - dataStart) // offset[0] relative to strings start
        u8(1); u8(1); u8('a'.code); u8(0) // charCount, byteCount, 'a', NUL
        patchU32(poolSizePos, out.size - poolStart) // pool chunk length (already 4-aligned)
        // START_ELEMENT "a" (name index 0), no attributes.
        val elStart = out.size
        u16(0x0102); u16(0x0010); val elSizePos = out.size; u32(0)
        u32(1); u32(none) // lineNumber, comment
        u32(none); u32(0) // ns=-1, name=0
        u16(0x14); u16(0x14); u16(0); u16(0); u16(0); u16(0) // attrStart, attrSize, count, id/class/style
        patchU32(elSizePos, out.size - elStart)
        // Corrupt trailing chunk: valid 8-byte header claiming a huge size -> skipped with a diagnostic.
        u16(0x0001); u16(0x0008); u32(0x7fffffff)
        patchU32(xmlSizePos, out.size)
        return out.toByteArray()
    }

    // ---- S1: a present-but-undecodable manifest/xml must not silently vanish (rule 4) ----

    @Test
    fun undecodableManifestIsRecordedNotSilentlyDropped() {
        // RES_XML magic but a corrupt body: decodeManifest() honestly returns null, but because the entry
        // EXISTS it must leave a diagnostic and expose hasManifestEntry so a UI can render a placeholder.
        val magicButGarbage = byteArrayOf(0x03, 0x00, 0x08, 0x00) + ByteArray(120) { (it * 13 + 5).toByte() }
        val res = resources("AndroidManifest.xml" to magicButGarbage)
        assertNull(res.decodeManifest(), "a corrupt manifest is honestly undecodable")
        assertTrue(res.hasManifestEntry, "the manifest entry is present even though it does not decode")
        assertTrue(
            res.diagnostics.contains("AndroidManifest.xml present but could not be decoded"),
            "a present-but-undecodable manifest must leave a trace, got ${res.diagnostics}",
        )
    }

    @Test
    fun undecodableManifestDiagnosticIsNotDoubleCounted() {
        val magicButGarbage = byteArrayOf(0x03, 0x00, 0x08, 0x00) + ByteArray(120) { (it * 13 + 5).toByte() }
        val res = resources("AndroidManifest.xml" to magicButGarbage)
        repeat(3) { res.decodeManifest() }
        assertEquals(
            1,
            res.diagnostics.count { it == "AndroidManifest.xml present but could not be decoded" },
            "repeated decode attempts must not double-count the diagnostic: ${res.diagnostics}",
        )
    }

    @Test
    fun missingManifestReportsNoEntryAndNoDiagnostic() {
        val res = resources("res/values/strings.xml" to "<resources/>".encodeToByteArray())
        assertFalse(res.hasManifestEntry, "no manifest entry present")
        assertNull(res.decodeManifest())
        assertTrue(res.diagnostics.isEmpty(), "an absent manifest is not a loss and must not be reported")
    }

    @Test
    fun presentManifestEntryFlagIsIndependentOfDecodeOutcome() {
        // hasManifestEntry reflects only the zip entry's presence, so it is true before any decode is tried.
        val res = resources("AndroidManifest.xml" to byteArrayOf(0x03, 0x00, 0x08, 0x00))
        assertTrue(res.hasManifestEntry)
    }

    @Test
    fun undecodableResXmlLeavesOnePerPathDiagnostic() {
        // A res/*.xml that is neither binary nor plain text: present but undecodable -> one diagnostic.
        val blob = ByteArray(64) { (it * 31 + 7).toByte() }
        val res = resources("res/raw/data.xml" to blob)
        repeat(2) { assertNull(res.decodeXml("res/raw/data.xml")) }
        assertEquals(
            1,
            res.diagnostics.count { it == "res/raw/data.xml present but could not be decoded" },
            "one diagnostic per failed path, no double-count: ${res.diagnostics}",
        )
    }

    @Test
    fun absentXmlPathIsNotReportedAsLost() {
        val res = resources("res/values/strings.xml" to "<resources/>".encodeToByteArray())
        assertNull(res.decodeXml("res/layout/missing.xml"))
        assertTrue(res.diagnostics.isEmpty(), "a path that was never present is not a loss: ${res.diagnostics}")
    }

    // ---- S2: arsc-salvage notes must surface on ApkResources.diagnostics, exactly once ----

    @Test
    fun arscSalvageNotesSurfaceOnApkDiagnostics() {
        val garbageArsc = ByteArray(80) { (it * 17 + 1).toByte() }
        val res = resources("resources.arsc" to garbageArsc)
        // The table itself carries the salvage note...
        val tableDiags = assertNotNull(res.table).diagnostics
        assertTrue(tableDiags.isNotEmpty(), "the garbage arsc yields a salvage note on the table")
        // ...and that same note must now be visible on the facade's own diagnostics (the UI reads these).
        for (d in tableDiags) {
            assertTrue(
                res.diagnostics.contains(d),
                "arsc salvage note '$d' must surface on ApkResources.diagnostics, got ${res.diagnostics}",
            )
        }
    }

    @Test
    fun arscSalvageNotesAreNotDoubleCountedAcrossRepeatedTableAccess() {
        val garbageArsc = ByteArray(80) { (it * 17 + 1).toByte() }
        val res = resources(
            "resources.arsc" to garbageArsc,
            // A real binary XML so decodeXml resolves references against the (memoized) resTable.
            "res/layout/a.xml" to partialBinaryXml(),
        )
        val note = assertNotNull(res.table).diagnostics.first()
        // Hit the memoized table via several access paths (table view + a binary xml that resolves against it).
        res.table
        res.decodeXml("res/layout/a.xml")
        res.table
        assertEquals(
            1,
            res.diagnostics.count { it == note },
            "the arsc note must appear once regardless of how often the table is accessed: ${res.diagnostics}",
        )
    }

    @Test
    fun cleanApkWithoutArscHasNoSpuriousArscDiagnostic() {
        val res = resources("AndroidManifest.xml" to byteArrayOf(0x03, 0x00, 0x08, 0x00))
        // No resources.arsc entry -> no table -> no arsc diagnostic at all.
        assertNull(res.table)
        assertTrue(
            res.diagnostics.none { it.contains("resources.arsc") },
            "no arsc means no arsc diagnostic: ${res.diagnostics}",
        )
    }

    @Test
    fun nonZipContainerHasNoResources() {
        // ApkResources.decode short-circuits to null for anything that is not a zip -> a bare dex, garbage, etc.
        assertNull(ApkResources.decode(ByteArray(256) { it.toByte() }))
    }

    @Test
    fun loadingNonZipBytesLeavesResourcesNullWithoutCrashing() {
        val decompiler = Decompiler()
        decompiler.load("junk.bin", ByteArray(512) { (it * 7).toByte() })
        assertNull(decompiler.resources)
    }
}
