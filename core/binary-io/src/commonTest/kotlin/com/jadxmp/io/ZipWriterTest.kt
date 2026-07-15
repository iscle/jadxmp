package com.jadxmp.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ZipWriter] proves out by round-tripping through the production [ZipReader]: whatever it writes must
 * read back byte-for-byte, on every target (jvm/js/wasmJs). This is the export-packaging contract — the
 * bytes handed to a browser download / android Downloads write must be a valid, re-readable archive.
 */
class ZipWriterTest {

    private fun bytesOf(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun writesAValidZipHeader() {
        val zip = ZipWriter.write(listOf("a.txt" to "hi".encodeToByteArray()))
        assertTrue(ZipReader.isZip(zip), "output must start with the PK local-header magic")
    }

    @Test
    fun roundTripsTextEntriesThroughZipReader() {
        val entries = listOf(
            "HelloWorld.java" to "public class HelloWorld {}\n".encodeToByteArray(),
            "com/example/Foo.java" to "package com.example;\nclass Foo {}\n".encodeToByteArray(),
            "resources/AndroidManifest.xml" to "<manifest/>\n".encodeToByteArray(),
        )
        val zip = ZipWriter.write(entries)
        val read = ZipReader.extract(zip).associate { it.name to it.bytes }
        assertEquals(entries.map { it.first }.toSet(), read.keys)
        for ((name, data) in entries) assertContentEquals(data, read[name], "entry $name must round-trip")
    }

    @Test
    fun deflatesCompressiblePayloadYetRoundTripsExactly() {
        // A large, highly compressible payload forces the DEFLATE (method-8) branch; the reader must
        // inflate it back to the exact bytes, and the stored archive must be smaller than the raw input.
        val big = ("abcdefgh".repeat(4000)).encodeToByteArray()
        val zip = ZipWriter.write(listOf("big.txt" to big))
        assertTrue(zip.size < big.size, "a compressible entry should shrink (proves DEFLATE was used)")
        val read = ZipReader.extract(zip)
        assertEquals(1, read.size)
        assertContentEquals(big, read[0].bytes)
    }

    @Test
    fun storesIncompressibleAndEmptyEntriesAndRoundTrips() {
        // Tiny + already-random data should stay STORED (deflate wouldn't shrink it); an empty entry has
        // no deflate stream at all. Both must read back exactly.
        val tiny = bytesOf(0x00, 0xFF, 0x7F, 0x80)
        val empty = ByteArray(0)
        val zip = ZipWriter.write(listOf("tiny.bin" to tiny, "empty.bin" to empty))
        val read = ZipReader.extract(zip).associate { it.name to it.bytes }
        assertContentEquals(tiny, read["tiny.bin"])
        assertContentEquals(empty, read["empty.bin"])
    }

    @Test
    fun compressFalseForcesStoredButStillRoundTrips() {
        val data = ("xyz".repeat(2000)).encodeToByteArray()
        val zip = ZipWriter.write(listOf("d.txt" to data), compress = false)
        val read = ZipReader.extract(zip)
        assertContentEquals(data, read[0].bytes)
    }

    @Test
    fun preservesEntryOrderAndNormalizesBackslashes() {
        val zip = ZipWriter.write(
            listOf(
                "b\\second.txt" to "2".encodeToByteArray(),
                "a/first.txt" to "1".encodeToByteArray(),
            ),
        )
        // entryNames reads the central directory in write order; backslashes fold to forward slashes.
        assertEquals(listOf("b/second.txt", "a/first.txt"), ZipReader.entryNames(zip))
    }
}
