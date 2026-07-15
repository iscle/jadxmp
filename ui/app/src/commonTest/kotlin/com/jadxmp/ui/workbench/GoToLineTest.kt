package com.jadxmp.ui.workbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure line-number parse/clamp behind the Go-to-line bar ([parseGoToLine]): in-range numbers pass
 * through, out-of-range clamps into `[1, lastLine]`, and blank/non-numeric text is rejected (null) so the
 * bar flags it instead of jumping. Kept free of Compose so it is unit-tested directly on every target.
 */
class GoToLineTest {

    @Test
    fun inRangeNumberParsesAsItself() {
        assertEquals(5, parseGoToLine("5", lastLine = 10))
        assertEquals(1, parseGoToLine("1", lastLine = 10))
        assertEquals(10, parseGoToLine("10", lastLine = 10))
    }

    @Test
    fun belowRangeClampsToOne() {
        assertEquals(1, parseGoToLine("0", lastLine = 10))
        assertEquals(1, parseGoToLine("-3", lastLine = 10))
    }

    @Test
    fun aboveRangeClampsToLastLine() {
        assertEquals(10, parseGoToLine("9999", lastLine = 10))
    }

    @Test
    fun nonNumericOrBlankIsInvalid() {
        assertNull(parseGoToLine("abc", lastLine = 10))
        assertNull(parseGoToLine("", lastLine = 10))
        assertNull(parseGoToLine("   ", lastLine = 10))
        assertNull(parseGoToLine("5x", lastLine = 10))
        assertNull(parseGoToLine("1.5", lastLine = 10))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(5, parseGoToLine("  5  ", lastLine = 10))
    }

    @Test
    fun hugeValueBeyondIntStillClampsNotCrashes() {
        // Parsed as Long, so a value past Int.MAX clamps to the last line instead of overflowing (rule 4).
        assertEquals(10, parseGoToLine("99999999999", lastLine = 10))
    }

    @Test
    fun emptyDocumentClampsToLineOne() {
        // lastLine 0 (or negative) must never invert the clamp range — the floor is always 1.
        assertEquals(1, parseGoToLine("5", lastLine = 0))
        assertEquals(1, parseGoToLine("5", lastLine = -4))
    }
}
