package com.jadxmp.ui.workbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure tests for [HexDump] row math (and the [binarySizeCaption] / [imageCaption] helpers) — the
 * Compose-free formatting behind the hex + image viewers, so alignment and windowing are verified
 * without composing anything.
 */
class HexDumpTest {

    @Test
    fun rowCount_roundsUp() {
        assertEquals(0, HexDump.rowCount(0))
        assertEquals(1, HexDump.rowCount(1))
        assertEquals(1, HexDump.rowCount(16))
        assertEquals(2, HexDump.rowCount(17))
        assertEquals(2, HexDump.rowCount(32))
        assertEquals(3, HexDump.rowCount(33))
    }

    @Test
    fun offsetLabel_is8UpperHexDigits() {
        assertEquals("00000000", HexDump.offsetLabel(0))
        assertEquals("00000010", HexDump.offsetLabel(16))
        assertEquals("000000FF", HexDump.offsetLabel(255))
        assertEquals("00001000", HexDump.offsetLabel(4096))
    }

    @Test
    fun hexColumn_fullRowHasMidpointGapAndUpperHexPairs() {
        val bytes = ByteArray(16) { it.toByte() } // 00..0F
        assertEquals(
            "00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F",
            HexDump.hexColumn(bytes, 0),
        )
    }

    @Test
    fun hexColumn_partialRowPadsToConstantWidthForAlignment() {
        val partial = HexDump.hexColumn(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()), 0)
        val full = HexDump.hexColumn(ByteArray(16) { 0 }, 0)
        assertEquals(full.length, partial.length) // pad keeps the ASCII gutter aligned across rows
        assertTrue(partial.startsWith("AB CD EF"), "was: $partial")
    }

    @Test
    fun hexColumn_readsTheCorrectRowSlice() {
        val bytes = ByteArray(20) { it.toByte() } // 00..13
        assertTrue(HexDump.hexColumn(bytes, 1).startsWith("10 11 12 13"))
    }

    @Test
    fun asciiColumn_printableVerbatimElseDot() {
        val bytes = byteArrayOf(0x41, 0x42, 0x00, 0x7F, 0x20, 0x7E) // A B NUL DEL space ~
        assertEquals("AB.. ~", HexDump.asciiColumn(bytes, 0))
    }

    @Test
    fun asciiColumn_offsetsToTheRequestedRow() {
        val bytes = ByteArray(20) { if (it < 16) 0x2E else (0x41 + it - 16).toByte() } // row1 = "ABCD"
        assertEquals("ABCD", HexDump.asciiColumn(bytes, 1))
    }

    @Test
    fun binarySizeCaption_scalesUnits() {
        assertEquals("512 bytes", binarySizeCaption(512))
        assertTrue(binarySizeCaption(2048).contains("KiB"))
        assertTrue(binarySizeCaption(3 * 1024 * 1024).contains("MiB"))
    }

    @Test
    fun imageCaption_includesFormatDimensionsAndSize() {
        val cap = imageCaption("PNG", 128, 64, 4096)
        assertTrue(cap.contains("PNG"))
        assertTrue(cap.contains("128"))
        assertTrue(cap.contains("64"))
        assertTrue(cap.contains("4096"))
    }

    @Test
    fun imageCaption_omitsBlankFormat() {
        assertTrue(imageCaption(null, 2, 2, 10).startsWith("2 "))
        assertTrue(imageCaption("", 2, 2, 10).startsWith("2 "))
    }
}
