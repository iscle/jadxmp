package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InvalidInputPolicyTest {
    private val sample = ExpectedInvalidInput.SAMPLE
    private val input get() = Corpus.smaliDir().resolve(sample).readBytes()
    private val reference = SignalScore(true, true, null)
    private val candidate = SignalScore(false, false, null)
    private val output = DecompilationResult(sample, listOf(DecompiledClass(
        ExpectedInvalidInput.CLASS_NAME,
        "// ${ExpectedInvalidInput.DIAGNOSTIC}\npublic class TestTryCatchMultiException2 { public static boolean test() { return missing; } }",
    )), 1)

    private fun row(
        result: DecompilationResult = output,
        bytes: ByteArray = input,
        ref: SignalScore = reference,
        cand: SignalScore = candidate,
    ) = SampleResult(sample, ref, cand, invalidInputEvidence = ExpectedInvalidInput.assess(sample, bytes, result))

    @Test
    fun verifiedInvalidInputMustNeverPassWithoutItsRequiredDiagnostic() {
        val row = SampleResult(
            "trycatch/TestTryCatchMultiException2.smali",
            SignalScore(true, true, null),
            SignalScore(true, true, null),
        )
        assertEquals(Verdict.REGRESSION, row.verdict)
    }

    @Test
    fun exactEvidenceIsDistinctFromParityAndKeepsFailedCompilationVisible() {
        val row = row()
        assertEquals(Verdict.EXPECTED_DIAGNOSTIC, row.verdict)
        assertFalse(row.isEvidencedParity)
        val board = Scoreboard().apply { add(row) }
        assertEquals(1, board.expectedDiagnostics().size)
        assertFalse(board.hasRegression())
        requireDifferentialParity(board, 1, emptyList(), emptyList())
        for (report in listOf(board.render(), renderSmaliReport(board, 1, emptyList(), emptyList()))) {
            assertTrue(report.contains("EXPECTED_DIAGNOSTIC"), report)
            assertTrue(report.contains("recompiles=FAIL"), report)
            assertTrue(report.contains(ExpectedInvalidInput.DIAGNOSTIC), report)
            assertTrue(report.contains(ExpectedInvalidInput.SHA256), report)
        }
    }

    @Test
    fun changedInputMissingOrDuplicateDiagnosticCrashAndExtraErrorsFailClosed() {
        val source = output.classes.single()
        val mutations = listOf(
            output.copy(classes = emptyList()),
            output.copy(classes = listOf(source.copy(source = ""))),
            output.copy(classes = listOf(source.copy(source = source.source.replace(ExpectedInvalidInput.DIAGNOSTIC, "other failure")))),
            output.copy(classes = listOf(source.copy(source = source.source + "\n// ${ExpectedInvalidInput.DIAGNOSTIC}"))),
            output.copy(classes = listOf(source.copy(source = source.source + "\n// JADXMP ERROR: new failure"))),
            output.copy(classes = listOf(source.copy(source = source.source + "\n// ${ExpectedInvalidInput.DIAGNOSTIC} EXTRA FAILURE"))),
            output.copy(classes = listOf(source.copy(source = source.source + "\nignored(); // ${ExpectedInvalidInput.DIAGNOSTIC}"))),
            output.copy(classes = listOf(source.copy(source = source.source + "\n// Code decompiled incorrectly"))),
            output.copy(classes = listOf(source.copy(fullName = "Other"))),
            output.copy(classes = listOf(source, source.copy(fullName = "Extra"))),
            output.copy(reportedErrors = 0),
            output.copy(reportedErrors = 2),
            output.copy(inputName = "other.smali"),
        )
        for (mutation in mutations) {
            val row = row(result = mutation)
            assertEquals(Verdict.REGRESSION, row.verdict, mutation.toString())
            assertTrue(row.invalidInputEvidence!!.problems.isNotEmpty())
        }
        val changed = row(bytes = input + '\n'.code.toByte())
        assertEquals(Verdict.REGRESSION, changed.verdict)
        val board = Scoreboard().apply { add(changed) }
        assertThrows(IllegalStateException::class.java) {
            requireDifferentialParity(board, 1, emptyList(), emptyList())
        }
        assertTrue(board.render().contains("input SHA-256 differs"))
    }

    @Test
    fun changedSignalProfileOrUnrelatedSampleCannotBorrowTheException() {
        for (ref in listOf(reference.copy(noErrors = false), reference.copy(recompiles = false), reference.copy(executesCheck = true))) {
            assertEquals(Verdict.REGRESSION, row(ref = ref).verdict)
        }
        for (cand in listOf(candidate.copy(noErrors = true), candidate.copy(recompiles = true), candidate.copy(executesCheck = false))) {
            assertEquals(Verdict.REGRESSION, row(cand = cand).verdict)
        }
        val evidence = row().invalidInputEvidence
        assertEquals(Verdict.REGRESSION, SampleResult("other.smali", reference, candidate, invalidInputEvidence = evidence).verdict)
        assertNull(ExpectedInvalidInput.assess("other.smali", input, output))
        assertEquals(Verdict.REGRESSION, SampleResult.classify(sample, reference, reference))
        assertEquals(Verdict.REGRESSION, SampleResult(sample, reference, candidate).verdict)
    }

    @Test
    fun originalFixtureRequiresActualEngineDiagnosticAndStillFailsJavac() {
        val fixture = Corpus.smaliDir().resolve(sample)
        val assembly = SmaliAssembler.assemble(fixture)
        assertTrue(assembly.ok, assembly.error)
        val ref = ReferenceDecompiler()
        val cand = JadxmpDecompiler()
        val refResult = ref.decompile(sample, assembly.dex!!)
        val result = cand.decompile(sample, assembly.dex!!)
        val refScore = SignalScore.of(refResult, ref.errorMarkers)
        val candScore = SignalScore.of(result, cand.errorMarkers)
        assertEquals(reference, refScore)
        assertEquals(candidate, candScore)
        val row = row(result = result, ref = refScore, cand = candScore)
        assertEquals(Verdict.EXPECTED_DIAGNOSTIC, row.verdict, result.classes.joinToString { it.source })
    }
}
