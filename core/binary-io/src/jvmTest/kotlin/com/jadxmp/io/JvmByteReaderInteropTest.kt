package com.jadxmp.io

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmByteReaderInteropTest {
    @Test
    fun everyUtf16CodeUnitRoundTripsAgainstJdkModifiedUtf8Encoder() {
        // Separate chunks keep each encoded payload within writeUTF's unsigned-short byte length.
        for (start in 0..0xFFFF step 8192) {
            val expected = buildString { for (code in start until start + 8192) append(code.toChar()) }
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { it.writeUTF(expected) }
            val input = ByteReader(output.toByteArray())
            assertEquals(expected, input.readMutf8Bytes(input.readU16BE()), "chunk $start")
            assertEquals(0, input.remaining)
        }
    }

    @Test
    fun bigEndianLongsMatchJdkEncoderIncludingExtrema() {
        for (value in listOf(Long.MIN_VALUE, -0x123456789ABCDEFL, -1L, 0L, 1L, 0x123456789ABCDEFL, Long.MAX_VALUE)) {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { it.writeLong(value) }
            assertEquals(value, ByteReader(output.toByteArray()).readS64BE())
        }
    }
}
