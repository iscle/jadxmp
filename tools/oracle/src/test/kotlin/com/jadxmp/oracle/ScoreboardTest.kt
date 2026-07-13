package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScoreboardTest {

    private fun sample(ref: SignalScore, cand: SignalScore) =
        SampleResult(sample = "s", reference = ref, candidate = cand)

    /** A PARITY backed by a shared PASS is EVIDENCED. */
    @Test
    fun sharedPassParityIsEvidenced() {
        val ref = SignalScore(noErrors = true, recompiles = false, executesCheck = null)
        val cand = SignalScore(noErrors = true, recompiles = false, executesCheck = null)
        val s = sample(ref, cand)
        assertEquals(Verdict.PARITY, s.verdict)
        assertTrue(s.isEvidencedParity, "both pass no-error -> evidenced parity")
    }

    /** A PARITY where EVERY comparable signal fails on both sides is a TIED (indeterminate) parity. */
    @Test
    fun bothFailEverySignalIsTiedNotEvidencedParity() {
        val ref = SignalScore(noErrors = false, recompiles = false, executesCheck = null)
        val cand = SignalScore(noErrors = false, recompiles = false, executesCheck = null)
        val s = sample(ref, cand)
        assertEquals(Verdict.PARITY, s.verdict)
        assertFalse(s.isEvidencedParity, "all-both-fail parity is indeterminate, not evidence")
    }

    /** Counts split parity into evidenced vs tied so a masked regression can't hide in the total. */
    @Test
    fun scoreboardSplitsEvidencedAndTiedParity() {
        val board = Scoreboard()
        // evidenced: shared recompile pass
        board.add(sample(SignalScore(true, true, null), SignalScore(true, true, null)))
        // tied: both fail all
        board.add(sample(SignalScore(false, false, null), SignalScore(false, false, null)))
        // regression
        board.add(sample(SignalScore(true, true, null), SignalScore(true, false, null)))

        assertEquals(1, board.evidencedParityCount())
        assertEquals(1, board.tiedParityCount())
        assertEquals(1, board.regressions().size)
        assertTrue(board.hasRegression())
    }

    /** A regression DOMINATES a co-occurring improvement, so IMPROVEMENT never masks a worse signal. */
    @Test
    fun regressionDominatesImprovement() {
        // jadx: no-error FAIL (e.g. WARN), recompile PASS. jadxmp: no-error PASS, recompile FAIL.
        val ref = SignalScore(noErrors = false, recompiles = true, executesCheck = null)
        val cand = SignalScore(noErrors = true, recompiles = false, executesCheck = null)
        assertEquals(Verdict.REGRESSION, SampleResult.classify(ref, cand))
    }
}
