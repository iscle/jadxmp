package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScoreboardTest {

    private fun sample(ref: SignalScore, cand: SignalScore) =
        SampleResult(sample = "s", reference = ref, candidate = cand)

    private fun named(name: String, ref: SignalScore, cand: SignalScore) =
        SampleResult(sample = name, reference = ref, candidate = cand)

    // The two real allowlist entries under test (see DocumentedDivergences).
    private val loopRestore = "loops/TestLoopRestore3.smali" // expected fail: no-error
    private val insnsBeforeThis = "others/TestInsnsBeforeThis.smali" // expected fail: recompiles

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

    // ---- documented-divergence allowlist ----

    /**
     * (a) An allowlisted sample whose jadx-passes/jadxmp-fails signals equal the entry's set EXACTLY is
     * scored EXPECTED_DIVERGENCE and is excluded from the gate.
     */
    @Test
    fun allowlistedExactMatchIsExpectedDivergenceAndNotGated() {
        // TestLoopRestore3: jadx passes no-error, jadxmp fails it; both fail recompile (unrelated). Only
        // regressed signal is no-error == the entry's documented set.
        val ref = SignalScore(noErrors = true, recompiles = false, executesCheck = null)
        val cand = SignalScore(noErrors = false, recompiles = false, executesCheck = null)
        val s = named(loopRestore, ref, cand)
        assertEquals(Verdict.EXPECTED_DIVERGENCE, s.verdict)

        val board = Scoreboard()
        board.add(s)
        assertFalse(board.hasRegression(), "expected divergence must not fail the gate")
        assertEquals(1, board.expectedDivergences().size)
        assertEquals(0, board.regressions().size)
        assertTrue(board.staleAllowlistEntries().none { it.sample == loopRestore }, "still failing => not stale")
    }

    /**
     * (b) ANTI-MASKING (load-bearing): an allowlisted sample that fails an ADDITIONAL signal not in the
     * entry is STILL a REGRESSION. The allowlist must never absorb a new/different loss.
     */
    @Test
    fun allowlistedWithExtraFailingSignalStaysRegression() {
        // TestLoopRestore3 entry = {no-error}. Here jadxmp ALSO regresses recompiles (jadx passes both).
        val ref = SignalScore(noErrors = true, recompiles = true, executesCheck = null)
        val cand = SignalScore(noErrors = false, recompiles = false, executesCheck = null)
        val s = named(loopRestore, ref, cand)
        assertEquals(Verdict.REGRESSION, s.verdict, "extra failing signal => not absorbed by allowlist")

        val board = Scoreboard()
        board.add(s)
        assertTrue(board.hasRegression(), "an additional regression must still fail the gate")
        assertEquals(setOf("no-error", "recompiles"), regressedSignalNames(ref, cand))
    }

    /** (c) A non-allowlisted sample with the same signal profile is an unchanged REGRESSION. */
    @Test
    fun nonAllowlistedSampleUnchangedRegression() {
        val ref = SignalScore(noErrors = true, recompiles = false, executesCheck = null)
        val cand = SignalScore(noErrors = false, recompiles = false, executesCheck = null)
        assertEquals(Verdict.REGRESSION, named("loops/NotAllowlisted.smali", ref, cand).verdict)
    }

    /**
     * (d) SANITY GUARD: when jadx ALSO fails the allowlisted signal, the entry is inapplicable — no real
     * divergence exists, so it is NOT an expected divergence.
     */
    @Test
    fun allowlistedButJadxAlsoFailsIsNotExpectedDivergence() {
        // TestInsnsBeforeThis entry = {recompiles}. Here jadx ALSO fails recompiles (both fail it), and
        // both pass no-error => a plain (evidenced) PARITY, never an expected divergence.
        val ref = SignalScore(noErrors = true, recompiles = false, executesCheck = null)
        val cand = SignalScore(noErrors = true, recompiles = false, executesCheck = null)
        val s = named(insnsBeforeThis, ref, cand)
        assertEquals(Verdict.PARITY, s.verdict)
        assertTrue(s.isEvidencedParity)
    }

    /**
     * (d-sharp) Combined sanity + anti-masking: jadx fails the allowlisted signal (recompiles) while
     * jadxmp regresses a DIFFERENT signal (no-error). The regressed set {no-error} != entry {recompiles},
     * so it is a REGRESSION — the recompiles allowlist can neither be honored nor mask a no-error loss.
     */
    @Test
    fun allowlistedRecompilesEntryDoesNotAbsorbNoErrorRegression() {
        val ref = SignalScore(noErrors = true, recompiles = false, executesCheck = null)
        val cand = SignalScore(noErrors = false, recompiles = false, executesCheck = null)
        val s = named(insnsBeforeThis, ref, cand)
        assertEquals(Verdict.REGRESSION, s.verdict)
        assertEquals(setOf("no-error"), regressedSignalNames(ref, cand))
    }

    /**
     * STALE detection: once jadxmp improves to PASS an allowlisted signal, the sample is no longer an
     * expected divergence and the entry is flagged stale for removal.
     */
    @Test
    fun improvingPastAllowlistedSignalFlagsStaleEntry() {
        // TestInsnsBeforeThis now recompiles (jadxmp fixed) => shared pass => evidenced PARITY, entry stale.
        val ref = SignalScore(noErrors = true, recompiles = true, executesCheck = null)
        val cand = SignalScore(noErrors = true, recompiles = true, executesCheck = null)
        val s = named(insnsBeforeThis, ref, cand)
        assertEquals(Verdict.PARITY, s.verdict)

        val board = Scoreboard()
        board.add(s)
        assertEquals(0, board.expectedDivergences().size)
        assertTrue(
            board.staleAllowlistEntries().any { it.sample == insnsBeforeThis },
            "an allowlisted signal jadxmp now passes must be flagged stale",
        )
    }

    /** The two shipped allowlist entries are exactly the documented jadx-bug samples & signals. */
    @Test
    fun shippedAllowlistIsTheTwoDocumentedEntries() {
        assertEquals(setOf(loopRestore, insnsBeforeThis), DocumentedDivergences.entries.map { it.sample }.toSet())
        assertEquals(setOf("no-error"), DocumentedDivergences.forSample(loopRestore)?.signals)
        assertEquals(setOf("recompiles"), DocumentedDivergences.forSample(insnsBeforeThis)?.signals)
    }
}
