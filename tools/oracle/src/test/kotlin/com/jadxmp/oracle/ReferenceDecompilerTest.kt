package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReferenceDecompilerTest {

    /**
     * End-to-end smoke test of the reference oracle plus two of the three accuracy signals:
     * jadx must decompile `corpus/binary/hello.dex` into a single `HelloWorld` class whose source
     * has no error markers and recompiles with the in-process JDK compiler.
     */
    @Test
    fun helloDexDecompilesToRecompilableHelloWorld() {
        val result = ReferenceDecompiler().decompileFile(Corpus.binaryFile("hello.dex"))

        assertEquals(1, result.classes.size, "hello.dex should yield exactly one class")
        val cls = result.classes.single()
        assertEquals("HelloWorld", cls.simpleName, "decompiled class should be HelloWorld")
        assertTrue(cls.source.contains("class HelloWorld"), "source should declare class HelloWorld")

        // Signal 1: no-error scan (jadx's own markers).
        assertTrue(AccuracySignals.noErrors(result.classes, ErrorMarkers.JADX), "HelloWorld should have no error markers")

        // Signal 2: recompiles with javax.tools.
        val recompile = AccuracySignals.recompiles(result.classes)
        assertTrue(recompile.success, "HelloWorld should recompile; diagnostics: ${recompile.diagnostics}")
    }

    @Test
    fun signalScoreClassifiesRegressionAndImprovement() {
        val ref = SignalScore(noErrors = true, recompiles = true, executesCheck = null)
        val worse = SignalScore(noErrors = true, recompiles = false, executesCheck = null)
        val better = SignalScore(noErrors = true, recompiles = true, executesCheck = null)
        val worseThanFail = SignalScore(noErrors = false, recompiles = false, executesCheck = null)

        assertEquals(Verdict.REGRESSION, SampleResult.classify(ref, worse))
        assertEquals(Verdict.PARITY, SampleResult.classify(ref, better))
        // reference itself failing no-error, candidate passing it -> improvement
        assertEquals(Verdict.IMPROVEMENT, SampleResult.classify(worseThanFail, ref))
    }

    // ---- Adversarial regression guards (findings F1, F2, F3, F5) ----

    /**
     * F1: empty / comment-only source must NOT score recompile PASS. Such a unit compiles with exit 0
     * yet produces zero `.class` files; without the `.class`-existence check a decompiler emitting
     * nothing would fully pass the gate.
     */
    @Test
    fun recompileFailsForEmptyOrCommentOnlySource() {
        assertFalse(
            AccuracySignals.recompiles(listOf(DecompiledClass("Foo", ""))).success,
            "empty source must fail recompile",
        )
        assertFalse(
            AccuracySignals.recompiles(listOf(DecompiledClass("Foo", "// only a comment\n"))).success,
            "comment-only source must fail recompile",
        )
    }

    /**
     * F2: a candidate that produced ZERO classes (e.g. jadxmp crashed and fault-isolation returned
     * nothing) must be classified REGRESSION against a reference that passed no-error — not PARITY via
     * a vacuous `all {}`. The empty-candidate score goes through [SignalScore.of] to prove the
     * empty-list handling, not a hand-built score.
     */
    @Test
    fun emptyCandidateClassifiesAsRegressionNotParity() {
        // Reference passed no-error but failed recompile (realistic: correct output references android.*).
        val reference = SignalScore(noErrors = true, recompiles = false, executesCheck = null)

        val emptyCandidate = SignalScore.of(
            DecompilationResult(inputName = "does-not-matter", classes = emptyList(), reportedErrors = 1),
            ErrorMarkers.JADXMP,
        )
        assertFalse(emptyCandidate.noErrors, "empty candidate output must fail the no-error signal (F2)")
        assertFalse(emptyCandidate.recompiles, "empty candidate output must fail recompile")

        assertEquals(
            Verdict.REGRESSION,
            SampleResult.classify(reference, emptyCandidate),
            "total decompilation failure must be a REGRESSION, never PARITY",
        )
    }

    /**
     * The no-error scan fails on ERROR-level sentinels and the inconsistent-code sentence (F5), but a
     * benign WARN is ADVISORY, not a hard failure (corrected from the earlier "WARN fails" rule): in a
     * differential gate, failing the reference on a benign `JADX WARN` masks real jadxmp regressions
     * and fabricates false improvements (see ErrorMarkers doc; proven by switches/TestSwitchOverStrings4).
     */
    @Test
    fun noErrorScanFailsOnErrorButToleratesBenignWarn() {
        val warn = listOf(DecompiledClass("W", "class W { /* JADX WARN: Code duplicated */ }"))
        assertTrue(
            AccuracySignals.noErrors(warn, ErrorMarkers.JADX),
            "a benign JADX WARN must NOT fail no-error (advisory only)",
        )

        val inconsistent = listOf(
            DecompiledClass("I", "class I { void m() { /* Code decompiled incorrectly, please refer... */ } }"),
        )
        assertFalse(AccuracySignals.noErrors(inconsistent, ErrorMarkers.JADX), "inconsistent-code sentence must fail (F5)")

        val error = listOf(DecompiledClass("E", "class E { /*  JADX ERROR: boom */ }"))
        assertFalse(AccuracySignals.noErrors(error, ErrorMarkers.JADX), "JADX ERROR must fail no-error")

        val clean = listOf(DecompiledClass("C", "class C { void m() {} }"))
        assertTrue(AccuracySignals.noErrors(clean, ErrorMarkers.JADX), "clean source should pass no-error")
    }

    /** F2 corollary: empty class list is never a vacuous no-error pass. */
    @Test
    fun noErrorFailsOnEmptyClassList() {
        assertFalse(AccuracySignals.noErrors(emptyList(), ErrorMarkers.JADX))
        assertFalse(AccuracySignals.noErrors(emptyList(), ErrorMarkers.JADXMP))
    }

    /**
     * F4: the candidate's STRUCTURED error count gates no-error even when the text is clean. A jadxmp
     * result whose source has no marker strings but whose `reportedErrors > 0` (e.g. a
     * RenderabilityGuard-flagged unstructured method) must still fail signal 1.
     */
    @Test
    fun noErrorFailsOnStructuredErrorCountEvenWhenTextClean() {
        val cleanText = listOf(DecompiledClass("C", "class C { void m() {} }"))
        val withStructuralError = DecompilationResult(inputName = "s", classes = cleanText, reportedErrors = 1)
        assertFalse(
            AccuracySignals.noErrors(withStructuralError, ErrorMarkers.JADXMP),
            "structured errorCount>0 must fail no-error even with clean text",
        )
        val clean = DecompilationResult(inputName = "s", classes = cleanText, reportedErrors = 0)
        assertTrue(AccuracySignals.noErrors(clean, ErrorMarkers.JADXMP))
    }
}
