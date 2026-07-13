package com.jadxmp.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemTest {

    private val fs = InMemoryFileSystem(
        mapOf(
            "a.txt" to "A".encodeToByteArray(),
            "dir/b.txt" to "B".encodeToByteArray(),
            "dir/sub/c.txt" to "C".encodeToByteArray(),
        ),
    )

    @Test
    fun existsAndRead() {
        assertTrue(fs.exists("a.txt"))
        assertFalse(fs.exists("missing"))
        assertContentEquals("B".encodeToByteArray(), fs.readBytes("dir/b.txt"))
    }

    @Test
    fun readMissingThrows() {
        assertFailsWith<ByteReaderException> { fs.readBytes("nope") }
    }

    @Test
    fun listsDirectChildren() {
        assertEquals(listOf("dir/b.txt", "dir/sub"), fs.list("dir").sorted())
    }

    @Test
    fun sourceReadsBytes() {
        val src = fs.source("a.txt")
        assertEquals("a.txt", src.name)
        assertContentEquals("A".encodeToByteArray(), src.readBytes())
    }

    @Test
    fun kotlinxIoBridgeRoundTrips() {
        val original = ByteArray(300) { (it % 128).toByte() }
        val bytes = original.asSource().readAllBytes()
        assertContentEquals(original, bytes)
    }
}
