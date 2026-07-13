@file:OptIn(ExperimentalCompressionApi::class)

package com.jadxmp.io

import dev.karmakrafts.kompress.ExperimentalCompressionApi
import dev.karmakrafts.kompress.zip.appendEntry
import dev.karmakrafts.kompress.zip.zip
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipReaderTest {

    /** Build a DEFLATE zip in memory via Kompress, so the reader is exercised against real archives. */
    private fun makeZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = Buffer()
        out.zip().use { archiver ->
            for ((name, data) in entries) {
                archiver.appendEntry(name) { sink ->
                    sink.write(data)
                    false
                }
            }
        }
        return out.readByteArray()
    }

    private fun bytesOf(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // --- Hand-built ZIP writer (all targets: pure Kotlin, no java.util.zip) ---------------------
    // We construct raw ZIP bytes so we control the compression method per entry. STORED (method 0)
    // stores data verbatim; DEFLATE (method 8) here wraps the data in a single *uncompressed* DEFLATE
    // block (BFINAL=1, BTYPE=00, LEN/NLEN, raw data) — a valid deflate stream a real inflater accepts —
    // so a genuine method-8 entry is produced without needing a compressor in the test.

    // localExtra is written into the LOCAL header only (with the local extra-len set to its size); the
    // central directory keeps extra-len 0. This deliberately makes local-extra ≠ CD-extra so a fixture
    // can prove the reader locates STORED data from the LOCAL header (real APKs zipalign-pad it there).
    private class ZipSpec(
        val name: String,
        val data: ByteArray,
        val stored: Boolean,
        val localExtra: ByteArray = ByteArray(0),
    )

    /** Collects skip diagnostics so tests can assert what was reported (name + reason). */
    private class SkipCollector : ZipDiagnostics {
        val skips = mutableListOf<Pair<String, String>>()
        override fun onSkippedEntry(name: String, reason: String) {
            skips += name to reason
        }
    }

    private fun le16(v: Int) = bytesOf(v and 0xFF, (v ushr 8) and 0xFF)
    private fun le32(v: Int) = bytesOf(v and 0xFF, (v ushr 8) and 0xFF, (v ushr 16) and 0xFF, (v ushr 24) and 0xFF)

    /** A well-formed ZIP extra field (2-byte header id, 2-byte data size, data) — as real APKs write. */
    private fun extraField(id: Int, payload: ByteArray) = le16(id) + le16(payload.size) + payload

    /** Wrap [data] in one stored (uncompressed) DEFLATE block — valid method-8 compressed payload. */
    private fun deflateStored(data: ByteArray): ByteArray {
        val len = data.size
        val header = bytesOf(0x01) + le16(len) + le16(len.inv() and 0xFFFF)
        return header + data
    }

    /**
     * Build a raw ZIP with the given entries, honoring each entry's compression method. Optionally
     * overrides the central-directory sizes/method for adversarial fixtures.
     */
    private fun buildZip(
        specs: List<ZipSpec>,
        storedUncompressedOverride: ((ZipSpec) -> Int)? = null,
    ): ByteArray {
        val locals = ArrayList<ByteArray>()
        val centrals = ArrayList<ByteArray>()
        val offsets = ArrayList<Int>()
        var offset = 0
        for (spec in specs) {
            val nameBytes = spec.name.encodeToByteArray()
            val method = if (spec.stored) 0 else 8
            val payload = if (spec.stored) spec.data else deflateStored(spec.data)
            val compressedSize = payload.size
            val uncompressedSize = storedUncompressedOverride?.invoke(spec) ?: spec.data.size
            val local = le32(0x04034b50) + // local header signature
                le16(20) + // version needed
                le16(0) + // gp flag
                le16(method) + // compression method
                le16(0) + // mod time
                le16(0x21) + // mod date (valid 1980-01-01)
                le32(0) + // crc32 (reader does not verify)
                le32(compressedSize) +
                le32(uncompressedSize) +
                le16(nameBytes.size) +
                le16(spec.localExtra.size) + // LOCAL extra len (may differ from the CD's)
                nameBytes +
                spec.localExtra +
                payload
            offsets.add(offset)
            locals.add(local)
            offset += local.size

            val central = le32(0x02014b50) + // central dir signature
                le16(20) + // version made by
                le16(20) + // version needed
                le16(0) + // gp flag
                le16(method) +
                le16(0) + // mod time
                le16(0x21) + // mod date
                le32(0) + // crc32
                le32(compressedSize) +
                le32(uncompressedSize) +
                le16(nameBytes.size) +
                le16(0) + // extra len
                le16(0) + // comment len
                le16(0) + // disk number start
                le16(0) + // internal attrs
                le32(0) + // external attrs
                le32(offsets.last()) + // local header offset
                nameBytes
            centrals.add(central)
        }
        val cdStart = offset
        var cd = ByteArray(0)
        for (c in centrals) cd += c
        val eocd = le32(0x06054b50) +
            le16(0) + // disk number
            le16(0) + // disk with cd
            le16(specs.size) + // entries on this disk
            le16(specs.size) + // total entries
            le32(cd.size) +
            le32(cdStart) +
            le16(0) // comment len
        var out = ByteArray(0)
        for (l in locals) out += l
        out += cd
        out += eocd
        return out
    }

    @Test
    fun roundTripsDeflatedEntries() {
        val a = "Hello, World!".encodeToByteArray()
        val b = ByteArray(5000) { (it % 251).toByte() } // compressible payload > one chunk
        val zip = makeZip(listOf("a.txt" to a, "dir/b.bin" to b))

        assertTrue(ZipReader.isZip(zip))
        val entries = ZipReader.extract(zip).associate { it.name to it.bytes }
        assertEquals(setOf("a.txt", "dir/b.bin"), entries.keys)
        assertContentEquals(a, entries["a.txt"])
        assertContentEquals(b, entries["dir/b.bin"])
    }

    @Test
    fun filtersEntriesByName() {
        val zip = makeZip(listOf("classes.dex" to bytesOf(1, 2, 3), "res/x" to bytesOf(9)))
        val dex = ZipReader.extract(zip) { it.endsWith(".dex") }
        assertEquals(1, dex.size)
        assertEquals("classes.dex", dex[0].name)
        assertContentEquals(bytesOf(1, 2, 3), dex[0].bytes)
    }

    @Test
    fun rejectsPathTraversalNames() {
        assertNull(ZipReader.sanitizeName("../evil"))
        assertNull(ZipReader.sanitizeName("a/../../evil"))
        assertNull(ZipReader.sanitizeName("/etc/passwd"))
        assertNull(ZipReader.sanitizeName("C:/windows"))
        assertNull(ZipReader.sanitizeName("a\\..\\b"))
        assertEquals("a/b/c.txt", ZipReader.sanitizeName("a/b/c.txt"))
        assertEquals("a/b", ZipReader.sanitizeName("a\\b"))
    }

    @Test
    fun enforcesEntrySizeCap() {
        val big = ByteArray(20_000) // inflates past a tiny cap
        val zip = makeZip(listOf("big" to big))
        assertFailsWith<ByteReaderException> {
            ZipReader.extract(zip, guard = ZipGuard(maxEntryBytes = 1024))
        }
    }

    @Test
    fun enforcesEntryCountCap() {
        val zip = makeZip((0 until 5).map { "f$it" to bytesOf(it) })
        assertFailsWith<ByteReaderException> {
            ZipReader.extract(zip, guard = ZipGuard(maxEntries = 2))
        }
    }

    @Test
    fun truncatedArchiveDegradesGracefully() {
        // A truncated local header must not crash — Kompress stops and we return no entries.
        val entries = ZipReader.extract(bytesOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0))
        assertTrue(entries.isEmpty())
    }

    @Test
    fun corruptArchiveNormalizesToByteReaderException() {
        // Valid-looking local header magic followed by garbage that Kompress rejects mid-parse.
        val corrupt = ByteArray(64).also {
            it[0] = 0x50; it[1] = 0x4B; it[2] = 0x03; it[3] = 0x04
            // an unsupported compression method + nonzero sizes drive an internal failure
            it[8] = 0x63.toByte()
            it[18] = 0x20; it[22] = 0x20
        }
        // Either a graceful empty result or our normalized exception — never a foreign crash type.
        val result = runCatching { ZipReader.extract(corrupt) }
        assertTrue(result.isSuccess || result.exceptionOrNull() is ByteReaderException)
    }

    @Test
    fun isZipRejectsNonZip() {
        assertTrue(!ZipReader.isZip(bytesOf(0x64, 0x65, 0x78, 0x0a)))
    }

    @Test
    fun globalCapAppliesToFilteredOutEntries() {
        // A bomb of highly-compressible, NON-matching entries: the total-inflated cap must still fire
        // even though the filter keeps nothing (regression for the bypassed-cap bug).
        val payload = ByteArray(200_000) // zeros -> compresses tiny, inflates large
        val zip = makeZip((0 until 6).map { "junk$it.bin" to payload })
        assertFailsWith<ByteReaderException> {
            ZipReader.extract(zip, guard = ZipGuard(maxTotalBytes = 300_000)) { it.endsWith(".dex") }
        }
    }

    @Test
    fun toleratesZeroDosTimestamp() {
        // A zero DOS date is "month 0" — Kompress would throw and abort the whole archive. The entry
        // must still be recovered (real APKs ship such timestamps; jadx tolerates them).
        val zip = makeZip(listOf("classes.dex" to bytesOf(1, 2, 3)))
        assertTrue(ZipReader.isZip(zip)) // first local header is at offset 0
        zip[10] = 0; zip[11] = 0 // DOS time
        zip[12] = 0; zip[13] = 0 // DOS date (invalid)

        val entries = ZipReader.extract(zip).associate { it.name to it.bytes }
        assertEquals(setOf("classes.dex"), entries.keys)
        assertContentEquals(bytesOf(1, 2, 3), entries["classes.dex"])
    }

    @Test
    fun toleratesGarbageDosTimestamp() {
        val zip = makeZip(listOf("a.bin" to bytesOf(9, 8, 7)))
        zip[10] = 0xFF.toByte(); zip[11] = 0xFF.toByte() // impossible time (hour 31)
        zip[12] = 0xFF.toByte(); zip[13] = 0xFF.toByte() // impossible date

        val entries = ZipReader.extract(zip)
        assertEquals(1, entries.size)
        assertContentEquals(bytesOf(9, 8, 7), entries[0].bytes)
    }

    @Test
    fun truncatedMidEntryStreamTerminates() {
        // Truncating a valid archive mid-entry must not hang the drain pump; it returns or throws.
        val zip = makeZip(listOf("a.bin" to ByteArray(8000) { it.toByte() }))
        val truncated = zip.copyOf(zip.size - 40)
        val result = runCatching { ZipReader.extract(truncated) }
        assertTrue(result.isSuccess || result.exceptionOrNull() is ByteReaderException)
    }

    // --- STORED (compression method 0) entries -------------------------------------------------

    @Test
    fun extractsStoredEntryByteExact() {
        // A STORED entry has no deflate stream; the reader must copy its bytes verbatim. Regression
        // for the silent-drop bug (resources.arsc / classes.dex are STORED in real APKs).
        val payload = bytesOf(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01, 0x7F, 0x80)
        val zip = buildZip(listOf(ZipSpec("resources.arsc", payload, stored = true)))
        val entries = ZipReader.extract(zip)
        assertEquals(1, entries.size)
        assertEquals("resources.arsc", entries[0].name)
        assertContentEquals(payload, entries[0].bytes)
    }

    @Test
    fun extractsMixedStoredAndDeflateEntries() {
        // Both methods in one archive must both come back with exact bytes.
        val stored = ByteArray(300) { (it * 7 + 3).toByte() }
        val deflated = "the quick brown fox".encodeToByteArray()
        val zip = buildZip(
            listOf(
                ZipSpec("assets/classes.dex", stored, stored = true),
                ZipSpec("readme.txt", deflated, stored = false),
            ),
        )
        val entries = ZipReader.extract(zip).associate { it.name to it.bytes }
        assertEquals(setOf("assets/classes.dex", "readme.txt"), entries.keys)
        assertContentEquals(stored, entries["assets/classes.dex"])
        assertContentEquals(deflated, entries["readme.txt"])
    }

    @Test
    fun realApkLikeStoredArscAndDexBothExtract() {
        // The concrete real-world case the bug broke: an APK-like zip whose resources.arsc AND
        // classes.dex are both STORED must yield BOTH entries.
        val arsc = ByteArray(512) { (it and 0xFF).toByte() }
        val dex = "dex\n035 ".encodeToByteArray() + ByteArray(200) { (255 - it).toByte() }
        val zip = buildZip(
            listOf(
                ZipSpec("classes.dex", dex, stored = true),
                ZipSpec("resources.arsc", arsc, stored = true),
                ZipSpec("AndroidManifest.xml", "manifest".encodeToByteArray(), stored = true),
            ),
        )
        val entries = ZipReader.extract(zip).associate { it.name to it.bytes }
        assertEquals(setOf("classes.dex", "resources.arsc", "AndroidManifest.xml"), entries.keys)
        assertContentEquals(dex, entries["classes.dex"])
        assertContentEquals(arsc, entries["resources.arsc"])
    }

    @Test
    fun storedEntrySubjectToEntrySizeCap() {
        // A STORED entry claiming a large size must be rejected by the per-entry cap, not extracted
        // (and must never OOM by allocating the claimed size first).
        val payload = ByteArray(20_000)
        val zip = buildZip(listOf(ZipSpec("big.bin", payload, stored = true)))
        assertFailsWith<ByteReaderException> {
            ZipReader.extract(zip, guard = ZipGuard(maxEntryBytes = 1024))
        }
    }

    @Test
    fun storedEntrySubjectToTotalSizeCap() {
        // STORED entries must count toward the global bomb cap exactly like inflated ones.
        val payload = ByteArray(100_000)
        val zip = buildZip((0 until 6).map { ZipSpec("s$it.bin", payload, stored = true) })
        assertFailsWith<ByteReaderException> {
            ZipReader.extract(zip, guard = ZipGuard(maxTotalBytes = 300_000))
        }
    }

    @Test
    fun storedSizeMismatchIsolatedAndRecorded() {
        // For STORED, compressedSize must equal uncompressedSize. A crafted mismatch (tiny stored bytes,
        // huge claimed size) is hostile — but one malformed entry must NOT abort the archive (rule 4
        // fault isolation). It is skipped, recorded, and the valid neighbour still extracts.
        val diag = SkipCollector()
        val zip = buildZip(
            listOf(
                ZipSpec("evil.bin", bytesOf(1, 2, 3, 4), stored = true),
                ZipSpec("classes.dex", bytesOf(9, 9, 9), stored = true),
            ),
            storedUncompressedOverride = { if (it.name == "evil.bin") 1_000_000 else it.data.size },
        )
        val entries = ZipReader.extract(zip, diagnostics = diag).associate { it.name to it.bytes }
        assertEquals(setOf("classes.dex"), entries.keys) // malformed entry dropped, valid one kept
        assertContentEquals(bytesOf(9, 9, 9), entries["classes.dex"])
        assertEquals(1, diag.skips.size)
        assertEquals("evil.bin", diag.skips[0].first)
        assertTrue(diag.skips[0].second.contains("mismatch"), "reason=${diag.skips[0].second}")
    }

    @Test
    fun storedEntryStillBlocksPathTraversal() {
        // The zip-slip guard applies regardless of compression method (policy → abort, not a per-entry skip).
        val zip = buildZip(listOf(ZipSpec("../../etc/passwd", bytesOf(1, 2, 3), stored = true)))
        assertFailsWith<ByteReaderException> { ZipReader.extract(zip) }
    }

    @Test
    fun unsupportedMethodOnWantedEntryIsolatedNotAborting() {
        // The attacker-reachable case: a decoy WANTED entry (method 12 = BZIP2) must skip only itself —
        // never abort the archive and lose the real classes.dex. And the skip must be recorded, not silent.
        val diag = SkipCollector()
        val zip = buildZip(
            listOf(
                ZipSpec("resources.arsc", bytesOf(7, 7, 7), stored = false), // becomes the method-12 decoy
                ZipSpec("classes.dex", "REALDEX".encodeToByteArray(), stored = false),
            ),
        )
        val patched = patchMethodForName(zip, "resources.arsc", 12)
        val entries = ZipReader.extract(patched, diagnostics = diag).associate { it.name to it.bytes }
        // The real DEFLATE classes.dex still comes through despite the poisoned neighbour.
        assertContentEquals("REALDEX".encodeToByteArray(), entries["classes.dex"])
        assertTrue("resources.arsc" !in entries, "decoy must be skipped, not extracted")
        assertEquals(listOf("resources.arsc"), diag.skips.map { it.first })
        assertTrue(diag.skips[0].second.contains("unsupported compression method 12"), diag.skips[0].second)
    }

    @Test
    fun deflateInflateFailureIsRecordedNotSilent() {
        // A wanted DEFLATE entry whose stream is corrupt used to be silently dropped by the broad catch.
        // It must now be recorded via diagnostics (isolated, but honest), while a good entry still extracts.
        val diag = SkipCollector()
        val zip = buildZip(
            listOf(
                ZipSpec("bad.dex", "not-really-deflate".encodeToByteArray(), stored = false),
                ZipSpec("good.dex", "GOOD".encodeToByteArray(), stored = false),
            ),
        )
        // Corrupt only bad.dex's deflate payload: flip its first deflate byte (the block header) to an
        // invalid value so the inflater rejects it. Its local data starts right after name "bad.dex".
        val badDataOffset = 30 + "bad.dex".length
        val corrupted = zip.copyOf().also { it[badDataOffset] = 0xFF.toByte() }
        val entries = ZipReader.extract(corrupted, diagnostics = diag).associate { it.name to it.bytes }
        assertContentEquals("GOOD".encodeToByteArray(), entries["good.dex"])
        assertTrue("bad.dex" !in entries)
        assertEquals(listOf("bad.dex"), diag.skips.map { it.first })
    }

    @Test
    fun storedEntryWithLocalExtraFieldExtractsByteExact() {
        // Coverage for localDataStart reading the LOCAL extra-len (not the CD's). The local header here
        // carries a 6-byte extra field while the CD extra-len is 0; a regression to using the CD value
        // would read from the wrong offset and return wrong bytes. Runs on ALL targets (jvm/js/wasmJs).
        val payload = bytesOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val extra = extraField(0xCAFE, bytesOf(0xAA, 0xBB)) // 6 bytes, LOCAL-only (CD extra-len stays 0)
        val zip = buildZip(listOf(ZipSpec("resources.arsc", payload, stored = true, localExtra = extra)))
        val entries = ZipReader.extract(zip)
        assertEquals(1, entries.size)
        assertContentEquals(payload, entries[0].bytes)
    }

    @Test
    fun deflateEntryWithLocalExtraFieldExtractsByteExact() {
        // THE bug this change fixes: a DEFLATE entry carrying a LOCAL extra field (Info-ZIP timestamp
        // 0x5455 / UID-GID 0x7875, present in JARs built with Unix `zip`; alignment padding) used to be
        // DROPPED — the old path routed the whole local header through Kompress's container parser, whose
        // extra-field decoder throws on any header id but Zip64. Reading the raw deflate stream from
        // localDataStart (like STORED) and inflating only those bytes makes it robust. The LOCAL extra-len
        // here is 9 while the CD extra-len is 0, so a regression to the CD value would slice wrong bytes.
        val payload = "compress me, compress me, compress me, compress me".encodeToByteArray()
        val extra = extraField(0x5455, bytesOf(0x03, 0x00, 0x00, 0x00, 0x00)) // Info-ZIP UT, 9 bytes, LOCAL-only
        val zip = buildZip(listOf(ZipSpec("classes.dex", payload, stored = false, localExtra = extra)))
        val entries = ZipReader.extract(zip)
        assertEquals(1, entries.size)
        assertEquals("classes.dex", entries[0].name)
        assertContentEquals(payload, entries[0].bytes)
    }

    @Test
    fun mixedStoredAndDeflateWithAndWithoutExtraAllExtract() {
        // One archive mixing STORED + DEFLATE-with-local-extra + DEFLATE-without-extra: all three must
        // come back byte-exact (the DEFLATE-with-extra one is the entry the old path lost).
        val stored = ByteArray(64) { (it * 3 + 1).toByte() }
        val deflatedPlain = "no extra field on this deflate entry".encodeToByteArray()
        val deflatedExtra = "this deflate entry carries a local extra field".encodeToByteArray()
        val extra = extraField(0x7875, bytesOf(0x01, 0x04, 0xE8, 0x03, 0x00, 0x00)) // Info-ZIP UID/GID
        val zip = buildZip(
            listOf(
                ZipSpec("assets/classes.dex", stored, stored = true),
                ZipSpec("plain.txt", deflatedPlain, stored = false),
                ZipSpec("res/values.bin", deflatedExtra, stored = false, localExtra = extra),
            ),
        )
        val entries = ZipReader.extract(zip).associate { it.name to it.bytes }
        assertEquals(setOf("assets/classes.dex", "plain.txt", "res/values.bin"), entries.keys)
        assertContentEquals(stored, entries["assets/classes.dex"])
        assertContentEquals(deflatedPlain, entries["plain.txt"])
        assertContentEquals(deflatedExtra, entries["res/values.bin"])
    }

    @Test
    fun deflateEntryClaimingHugeSizeIsCappedNotOom() {
        // A DEFLATE bomb whose central directory CLAIMS a huge uncompressed size (small compressed, huge
        // claimed) must be capped by the per-entry policy BEFORE inflating — never allocating the claimed
        // size — exactly like the STORED cap. Aborts as a guard breach, no OOM.
        val payload = bytesOf(1, 2, 3, 4, 5, 6, 7, 8)
        val zip = buildZip(
            listOf(ZipSpec("bomb.bin", payload, stored = false)),
            storedUncompressedOverride = { 1_500_000_000 }, // ~1.5 GB claimed; must not be allocated
        )
        assertFailsWith<ByteReaderException> {
            ZipReader.extract(zip, guard = ZipGuard(maxEntryBytes = 4096))
        }
    }

    @Test
    fun deflateActualInflationOverCapIsCappedNotOom() {
        // The other bomb shape: the CD claims a SMALL size (bypassing the pre-check) but the stream
        // actually inflates past the per-entry cap. The streaming inflate must trip the cap on the ACTUAL
        // output as it grows, never accumulating unboundedly. Uses a genuinely-compressed payload (makeZip
        // runs Kompress's real deflater) so compressed << uncompressed.
        val big = ByteArray(50_000) { (it % 40).toByte() } // compresses small, inflates to 50 KB
        val zip = makeZip(listOf("big.bin" to big))
        assertFailsWith<ByteReaderException> {
            ZipReader.extract(zip, guard = ZipGuard(maxEntryBytes = 4096))
        }
    }

    /** Rewrite the 2-byte compression-method field of the local header + CD entry named [targetName]. */
    private fun patchMethodForName(zip: ByteArray, targetName: String, method: Int): ByteArray {
        val out = zip.copyOf()
        val nameBytes = targetName.encodeToByteArray()
        var i = 0
        while (i + 4 <= out.size) {
            val sig = (out[i].toInt() and 0xFF) or ((out[i + 1].toInt() and 0xFF) shl 8) or
                ((out[i + 2].toInt() and 0xFF) shl 16) or ((out[i + 3].toInt() and 0xFF) shl 24)
            when (sig) {
                0x04034b50 -> { // local file header: name begins at i+30
                    if (nameAt(out, i + 30, nameBytes)) { out[i + 8] = method.toByte(); out[i + 9] = 0 }
                }
                0x02014b50 -> { // central directory header: name begins at i+46
                    if (nameAt(out, i + 46, nameBytes)) { out[i + 10] = method.toByte(); out[i + 11] = 0 }
                }
            }
            i++
        }
        return out
    }

    private fun nameAt(data: ByteArray, off: Int, name: ByteArray): Boolean {
        if (off + name.size > data.size) return false
        for (k in name.indices) if (data[off + k] != name[k]) return false
        return true
    }

    @Test
    fun entryNamesListsWithoutExtracting() {
        val zip = buildZip(
            listOf(
                ZipSpec("classes.dex", bytesOf(1), stored = true),
                ZipSpec("res/drawable/icon.png", bytesOf(2, 3), stored = false),
                ZipSpec("resources.arsc", bytesOf(4), stored = true),
            ),
        )
        assertEquals(
            listOf("classes.dex", "res/drawable/icon.png", "resources.arsc"),
            ZipReader.entryNames(zip),
        )
    }
}
