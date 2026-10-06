package com.jadxmp.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FloatingConstantLiteralTest {
    @Test fun fixedDecimalsAndSignedZeroHaveOneCrossTargetSpelling() {
        assertEquals("0.0f", FloatingConstantLiteral.float(0))
        assertEquals("-0.0f", FloatingConstantLiteral.float(Int.MIN_VALUE))
        assertEquals("0.0", FloatingConstantLiteral.double(0))
        assertEquals("-0.0", FloatingConstantLiteral.double(Long.MIN_VALUE))
        assertEquals("0.100000001490116119384765625f", FloatingConstantLiteral.float(0x3dcccccd))
        assertEquals("0.1000000000000000055511151231257827021181583404541015625", FloatingConstantLiteral.double(0x3fb999999999999aL))
        assertEquals("340282346638528859811704183484516925440.0f", FloatingConstantLiteral.float(0x7f7fffff))
        assertEquals("1.0f", FloatingConstantLiteral.float(0x3f800000))
        assertEquals("-1.5", FloatingConstantLiteral.double(-4613937818241073152L))
    }

    @Test fun infinitiesUseArithmeticAndCanonicalNanUsesAnExplicitOwner() {
        assertEquals("java.lang.Float.NaN", FloatingConstantLiteral.float(0x7fc00000))
        assertEquals("java.lang.Double.NaN", FloatingConstantLiteral.double(0x7ff8000000000000L))
        assertEquals("(1.0f / 0.0f)", FloatingConstantLiteral.float(0x7f800000))
        assertEquals("(-1.0f / 0.0f)", FloatingConstantLiteral.float(-0x800000))
        assertEquals("(1.0 / 0.0)", FloatingConstantLiteral.double(0x7ff0000000000000L))
        assertEquals("(-1.0 / 0.0)", FloatingConstantLiteral.double(-0x10000000000000L))
        for (bits in listOf(0x7f800001, 0x7fc00001, -0x400000, -1)) assertNull(FloatingConstantLiteral.float(bits))
        for (bits in listOf(0x7ff0000000000001L, 0x7ff8000000000001L, -0x8000000000000L, -1L))
            assertNull(FloatingConstantLiteral.double(bits))
    }

    @Test fun deterministicFiniteSampleRoundTripsAndStaysWithinItsOutputBound() {
        var seed = 123456789L
        var sourceDigest = -3750763034362895579L
        fun record(source: String) {
            for (character in source + "\n") sourceDigest = (sourceDigest xor character.code.toLong()) * 1099511628211L
        }
        repeat(256) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val doubleBits = seed and -0x0010000000000001L // Clear an exponent bit: always finite.
            val double = FloatingConstantLiteral.double(doubleBits)!!
            record(double)
            assertEquals(doubleBits, double.toDouble().toRawBits())
            assertTrue(double.length <= FloatingConstantLiteral.MAX_SOURCE_LENGTH)
            val floatBits = seed.toInt() and -0x00800001
            val float = FloatingConstantLiteral.float(floatBits)!!
            record(float)
            assertEquals(floatBits, float.removeSuffix("f").toFloat().toRawBits())
            assertTrue(float.length <= FloatingConstantLiteral.MAX_SOURCE_LENGTH)
        }
        // Golden produced independently from Python Decimal.from_float, exact fixed expansions.
        // The same digest on every target verifies source identity, not merely numeric equivalence.
        assertEquals(-7516791003024740909L, sourceDigest)
        for (bits in listOf(1L, Long.MIN_VALUE or 1, 0x000fffffffffffffL, 0x0010000000000000L, 0x7fefffffffffffffL)) {
            val source = FloatingConstantLiteral.double(bits)!!
            assertEquals(bits, source.toDouble().toRawBits())
            assertTrue(source.length <= FloatingConstantLiteral.MAX_SOURCE_LENGTH)
        }
        for (bits in listOf(1, Int.MIN_VALUE or 1, 0x007fffff, 0x00800000, 0x7f7fffff)) {
            val source = FloatingConstantLiteral.float(bits)!!
            assertEquals(bits, source.removeSuffix("f").toFloat().toRawBits())
            assertTrue(source.length <= FloatingConstantLiteral.MAX_SOURCE_LENGTH)
        }
    }
    @Test fun canonicalNanOwnerIsResolvedLazilyAndCannotExceedTheSourceBound() {
        val owners = mutableListOf<String>()
        val resolve: (String) -> String = { owners.add(it); "Safe" + it.substringAfterLast('.') }
        assertEquals("SafeFloat.NaN", FloatingConstantLiteral.float(0x7fc00000, resolve))
        assertEquals("SafeDouble.NaN", FloatingConstantLiteral.double(0x7ff8000000000000L, resolve))
        assertEquals(listOf("java.lang.Float", "java.lang.Double"), owners)
        val forbidden: (String) -> String = { error("non-NaN must not resolve a symbol") }
        assertEquals("1.0f", FloatingConstantLiteral.float(0x3f800000, forbidden))
        assertEquals("(1.0 / 0.0)", FloatingConstantLiteral.double(0x7ff0000000000000L, forbidden))
        assertNull(FloatingConstantLiteral.float(-0x400000, forbidden))
        assertNull(FloatingConstantLiteral.double(-0x8000000000000L, forbidden))
        assertFailsWith<IllegalArgumentException> {
            FloatingConstantLiteral.float(0x7fc00000) { "x".repeat(FloatingConstantLiteral.MAX_SOURCE_LENGTH) }
        }
        assertFailsWith<IllegalArgumentException> { FloatingConstantLiteral.double(0x7ff8000000000000L) { "" } }
    }

}
