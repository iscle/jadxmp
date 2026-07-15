package com.jadxmp.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The binary `res/` seam through [ApkResources]: a real (in-memory) zip container is decoded and the NEW
 * on-demand accessors — [ApkResources.binaryResourcePaths] + [ApkResources.rawResource] — are exercised,
 * while the existing manifest/xml handling is re-asserted UNCHANGED (no regression). Runs on every target
 * incl. wasmJs (the browser is the whole point of the rewrite), so the archive is hand-built in pure Kotlin
 * as STORED (uncompressed) entries — the way real APKs align their already-compressed drawables.
 */
class ApkResourcesBinaryEntriesTest {

    private val manifestXml =
        "<?xml version=\"1.0\"?>\n<manifest package=\"com.example\"/>\n".encodeToByteArray()
    private val valuesXml = "<resources><string name=\"a\">b</string></resources>".encodeToByteArray()

    // A PNG magic header + arbitrary tail: proves rawResource hands back the exact stored bytes verbatim.
    private val pngBytes =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(40) { (it * 7 + 1).toByte() }
    private val binBytes = ByteArray(64) { (255 - it).toByte() }

    /** A plain-APK-shaped zip: manifest + a text `res/…xml` + two binary res/ blobs + a non-resource entry. */
    private fun sampleApk(): ByteArray = storedZip(
        listOf(
            "AndroidManifest.xml" to manifestXml,
            "res/values/z.xml" to valuesXml,
            "res/drawable/x.png" to pngBytes,
            "res/raw/y.bin" to binBytes,
            // A non-resource entry that must be ignored by both binaryResourcePaths and rawResource.
            "classes.dex" to "not-a-real-dex".encodeToByteArray(),
        ),
    )

    @Test
    fun binaryResourcePathsListsResBlobsSortedWithoutXml() {
        val res = assertNotNull(ApkResources.decode(sampleApk()))
        assertEquals(
            listOf("res/drawable/x.png", "res/raw/y.bin"),
            res.binaryResourcePaths,
            "binary res/ paths list images + blobs only, sorted, excluding xml/manifest/arsc/dex",
        )
    }

    @Test
    fun rawResourceReturnsExactBytesOnDemand() {
        val res = assertNotNull(ApkResources.decode(sampleApk()))
        assertContentEquals(pngBytes, res.rawResource("res/drawable/x.png"), "image bytes must round-trip exactly")
        assertContentEquals(binBytes, res.rawResource("res/raw/y.bin"), "raw blob bytes must round-trip exactly")
    }

    @Test
    fun rawResourceReturnsNullForAbsentOrNonResourcePath() {
        val res = assertNotNull(ApkResources.decode(sampleApk()))
        assertNull(res.rawResource("res/drawable/missing.png"), "an absent res/ path is null, not a throw")
        assertNull(res.rawResource("does/not/exist"), "a path outside res/ is null")
        assertNull(res.rawResource("classes.dex"), "a non-resource entry is not exposed as raw bytes")
    }

    @Test
    fun rawResourceAlsoServesEagerlyHeldEntries() {
        // A res/…xml is held eagerly in the entries map; rawResource returns its RAW bytes too (from memory,
        // not decoded text) — proving it is a complete raw accessor, not only the on-demand binary path.
        val res = assertNotNull(ApkResources.decode(sampleApk()))
        assertContentEquals(valuesXml, res.rawResource("res/values/z.xml"))
    }

    @Test
    fun binaryEntriesDoNotDisturbXmlOrManifestPaths() {
        val res = assertNotNull(ApkResources.decode(sampleApk()))
        // xmlResourcePaths still lists ONLY res/…xml — the binary broadening does not leak into it.
        assertEquals(listOf("res/values/z.xml"), res.xmlResourcePaths, "no regression to the xml path set")
        // The manifest is still present and still decodes (plain-text passthrough here) — unchanged behavior.
        assertTrue(res.hasManifestEntry, "manifest entry still detected")
        assertEquals(manifestXml.decodeToString(), res.decodeManifest(), "manifest still decodes unchanged")
    }

    @Test
    fun craftedEntriesWithoutContainerHaveNoBinaryPaths() {
        // The internal constructor path (no backing zip) surfaces no binary res/ paths and no raw bytes for
        // them — the on-demand seam simply stays empty, and the eager entries remain reachable as before.
        val res = ApkResources(
            mapOf("res/drawable/x.png" to pngBytes, "res/values/z.xml" to valuesXml),
            diagnostics = emptyList(),
        )
        assertTrue(res.binaryResourcePaths.isEmpty(), "no container => no on-demand binary listing")
        // An eagerly-held entry is still returned; a binary one that would need the container is null.
        assertContentEquals(valuesXml, res.rawResource("res/values/z.xml"))
        assertContentEquals(pngBytes, res.rawResource("res/drawable/x.png"), "an eagerly-provided blob still resolves")
    }

    // ── Minimal pure-Kotlin STORED-zip writer (wasm-safe; mirrors core:binary-io's ZipReaderTest) ─────

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    /**
     * Build a valid ZIP whose entries are all STORED (method 0), so [ApkResources.decode] sees a genuine
     * container its [ZipReader] can list and single-entry extract. DOS date 0x21 (1980-01-01) + time 0 are
     * valid, so no timestamp fix-up interferes.
     */
    private fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val locals = ArrayList<ByteArray>()
        val centrals = ArrayList<ByteArray>()
        var offset = 0
        for ((name, data) in entries) {
            val nameBytes = name.encodeToByteArray()
            val local = le32(0x04034b50) + le16(20) + le16(0) + le16(0) + // sig, version, flags, method(STORED)
                le16(0) + le16(0x21) + le32(0) + // time, date, crc
                le32(data.size) + le32(data.size) + // compressed, uncompressed
                le16(nameBytes.size) + le16(0) + nameBytes + data // nameLen, extraLen, name, data
            val central = le32(0x02014b50) + le16(20) + le16(20) + le16(0) + le16(0) + // sig, made-by, needed, flags, method
                le16(0) + le16(0x21) + le32(0) + // time, date, crc
                le32(data.size) + le32(data.size) + // compressed, uncompressed
                le16(nameBytes.size) + le16(0) + le16(0) + // nameLen, extraLen, commentLen
                le16(0) + le16(0) + le32(0) + // disk#, internal attrs, external attrs
                le32(offset) + nameBytes // local-header offset, name
            locals.add(local)
            centrals.add(central)
            offset += local.size
        }
        val cdStart = offset
        var cd = ByteArray(0)
        for (c in centrals) cd += c
        val eocd = le32(0x06054b50) + le16(0) + le16(0) + le16(entries.size) + le16(entries.size) +
            le32(cd.size) + le32(cdStart) + le16(0)
        var out = ByteArray(0)
        for (l in locals) out += l
        out += cd
        out += eocd
        return out
    }
}
