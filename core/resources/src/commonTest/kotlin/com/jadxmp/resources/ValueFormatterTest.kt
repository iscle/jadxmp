package com.jadxmp.resources

import kotlin.test.Test
import kotlin.test.assertEquals

class ValueFormatterTest {

    private fun fmt(type: Int, data: Int, ref: (Int) -> String? = { null }): String =
        ValueFormatter.format(type, data, { "str$it" }, ref)

    @Test
    fun formatsPrimitives() {
        assertEquals("42", fmt(ResValueType.INT_DEC, 42))
        assertEquals("0xff", fmt(ResValueType.INT_HEX, 255))
        assertEquals("true", fmt(ResValueType.INT_BOOLEAN, 1))
        assertEquals("false", fmt(ResValueType.INT_BOOLEAN, 0))
        assertEquals("str3", fmt(ResValueType.STRING, 3))
    }

    @Test
    fun formatsColors() {
        assertEquals("#ff112233", fmt(ResValueType.INT_COLOR_ARGB8, 0xff112233.toInt()))
        assertEquals("#112233", fmt(ResValueType.INT_COLOR_RGB8, 0x112233))
        assertEquals("#1234", fmt(ResValueType.INT_COLOR_ARGB4, 0x1234))
        assertEquals("#123", fmt(ResValueType.INT_COLOR_RGB4, 0x123))
    }

    @Test
    fun formatsFloat() {
        assertEquals("1.5", fmt(ResValueType.FLOAT, 1.5f.toRawBits()))
        assertEquals("2", fmt(ResValueType.FLOAT, 2.0f.toRawBits()))
    }

    @Test
    fun formatsDimensionAndFraction() {
        // 16dp: mantissa 16*256=0x1000 (radix 23p0), unit DIP(1) -> data 0x00100001
        assertEquals("16dp", fmt(ResValueType.DIMENSION, 0x00100001))
        // 100% fraction: mantissa 1.0 -> 0x100 << 8 ... value 1.0 * 100 => "100%"
        assertEquals("100%", fmt(ResValueType.FRACTION, 0x00010000))
    }

    @Test
    fun formatsReferences() {
        assertEquals("@style/AppTheme", fmt(ResValueType.REFERENCE, 0x7f050000) { "style/AppTheme" })
        assertEquals("@+id/textView", fmt(ResValueType.REFERENCE, 0x7f020000) { "id/textView" })
        assertEquals("@null", fmt(ResValueType.REFERENCE, 0))
        assertEquals("@0x7f990000", fmt(ResValueType.REFERENCE, 0x7f990000))
        assertEquals("?android:attr/theme", fmt(ResValueType.ATTRIBUTE, 0x01010000) { "android:attr/theme" })
    }

    @Test
    fun formatsNull() {
        assertEquals("@null", fmt(ResValueType.NULL, 0))
        assertEquals("@empty", fmt(ResValueType.NULL, 1))
    }

    @Test
    fun numberFormatterTrimsTrailingZeros() {
        assertEquals("3.14", ValueFormatter.numberToString(3.1400))
        assertEquals("-2.5", ValueFormatter.numberToString(-2.5))
        assertEquals("0", ValueFormatter.numberToString(0.0))
        assertEquals("10", ValueFormatter.numberToString(10.0))
    }
}
