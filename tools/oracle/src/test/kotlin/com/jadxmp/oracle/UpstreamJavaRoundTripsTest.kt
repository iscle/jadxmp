package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UpstreamJavaRoundTripsTest {
    @Test fun passingOriginalRunsAllThreeOutputsThroughCompilationAndExecution() {
        val row = UpstreamJavaRoundTrips().measure("Fixture.java", fixture("public boolean check() { return true; }"))
        assertEquals("CHECK_PASSED", row.originalStatus)
        assertNull(row.preparationFailure)
        for (output in listOf(row.reference, row.java, row.kotlin)) {
            assertNotNull(output)
            assertEquals(true, output!!.signals.executesCheck, output.diagnostics.toString())
            assertTrue(output.signals.recompiles, output.diagnostics.toString())
        }
    }

    @Test fun failedOrUnextractableOriginalsNeverBecomeDecompilerPasses() {
        val fixtures = mapOf(
            "class Fixture {}" to "NO_SAMPLE",
            fixture("public boolean check() { return false; }") to "CHECK_FAILED",
            fixture("public void check() { missing(); }") to "COMPILE_FAILED",
        )
        for ((source, status) in fixtures) {
            val row = UpstreamJavaRoundTrips().measure("Fixture.java", source)
            assertEquals(status, row.originalStatus)
            assertNull(row.reference)
            assertNull(row.java)
            assertNull(row.kotlin)
        }
    }

    @Test fun absenceOfAnOriginalCheckIsNeverReportedAsExecutionSuccess() {
        val row = UpstreamJavaRoundTrips().measure("Fixture.java", fixture("public int value() { return 7; }"))
        assertEquals("COMPILED_NO_CHECK", row.originalStatus)
        for (output in listOf(row.reference, row.java, row.kotlin)) {
            assertNotNull(output)
            assertNull(output!!.signals.executesCheck)
        }
    }

    @Test fun missingRebuiltChecksAndEmptyOutputsRemainFailures() {
        val sample = UpstreamJavaExtractor.extract(fixture("public boolean check() { return true; }")).sample!!
        val original = JavaCheckFixture(listOf(sample.source), sample.checkClass, UpstreamJavaInventory.fixtureClasspath)
        for (kotlin in listOf(false, true)) {
            val absent = if (kotlin) "package fixtures; class Fixture { class TestCls }" else "package fixtures; public class Fixture { public static class TestCls {} }"
            for (classes in listOf(emptyList(), listOf(DecompiledClass("fixtures.Fixture", absent)))) {
                val evidence = UpstreamJavaRoundTrips.output(DecompilationResult("Fixture.java", classes, 0),
                    ErrorMarkers.JADXMP, original, hasCheck = true, kotlin = kotlin)
                assertEquals(false, evidence.signals.executesCheck)
                if (classes.isEmpty()) {
                    assertFalse(evidence.signals.noErrors)
                    assertFalse(evidence.signals.recompiles)
                }
            }
        }
    }

    @Test fun aCrashedDecompilerRemainsMeasuredWhileOtherOutputsContinue() {
        for (failure in listOf(IllegalStateException("deliberate crash"), AssertionError("deliberate crash"), StackOverflowError("deliberate crash"))) {
            val broken = object : Decompiler {
                override val name = "broken"
                override val errorMarkers = ErrorMarkers.JADXMP
                override fun decompile(name: String, bytes: ByteArray): DecompilationResult = throw failure
            }
            val row = UpstreamJavaRoundTrips(candidate = broken).measure("Fixture.java", fixture("public boolean check() { return true; }"))
            assertEquals(SignalScore(false, false, false), row.java!!.signals)
            assertTrue(row.java!!.diagnostics.any { it.contains("deliberate crash") })
            assertEquals(true, row.reference!!.signals.executesCheck)
            assertEquals(true, row.kotlin!!.signals.executesCheck)
        }
    }

    @Test fun cancellationAndFatalTerminationPropagateUnchanged() {
        for (failure in listOf(java.util.concurrent.CancellationException(), com.jadxmp.pipeline.pass.CancellationSignal(),
            InterruptedException(), OutOfMemoryError("simulated"), ThreadDeath())) {
            val cancelled = object : Decompiler {
                override val name = "cancelled"
                override val errorMarkers = ErrorMarkers.JADXMP
                override fun decompile(name: String, bytes: ByteArray): DecompilationResult = throw failure
            }
            val actual = assertThrows(Throwable::class.java) {
                UpstreamJavaRoundTrips(reference = cancelled).measure("Fixture.java", fixture("public int value() { return 7; }"))
            }
            assertSame(failure, actual)
        }
    }

    @Test fun reportPreservesEveryInputAndSeparatesMissingChecksFromFailures() {
        val passing = OutputEvidence(SignalScore(true, true, true), "COMPILED", emptyList())
        val failed = OutputEvidence(SignalScore(true, false, false), "COMPILE_FAILED", listOf("line1\nline2\tbad\\path"))
        val noCheck = OutputEvidence(SignalScore(true, true, null), "COMPILED", emptyList())
        val rows = listOf(
            UpstreamRoundTripRow("passing.java", "a", "b", listOf("wrapper retained"), "CHECK_PASSED", emptyList(),
                reference = passing, java = failed, kotlin = passing),
            UpstreamRoundTripRow("no-check.java", "c", "d", emptyList(), "COMPILED_NO_CHECK", emptyList(),
                reference = noCheck, java = noCheck, kotlin = noCheck),
            UpstreamRoundTripRow("invalid.java", "e", null, emptyList(), "NO_SAMPLE", listOf("no sample")),
            UpstreamRoundTripRow("dex-failed.java", "f", "g", emptyList(), "CHECK_PASSED", emptyList(), "D8 failure"),
        )
        val lines = UpstreamRoundTripReport.table(rows).trimEnd().lines()
        assertEquals(5, lines.size)
        assertTrue(lines.all { it.split('\t').size == 10 })
        assertEquals(rows.map { it.input }, lines.drop(1).map { it.substringBefore('\t') })
        assertTrue(lines[1].contains("line1\\nline2\\tbad\\\\path"))
        assertTrue(lines[2].contains("check=NOT_EVALUATED"))
        assertTrue(lines[3].contains("NOT_MEASURED"))
        assertTrue(lines[4].contains("D8 failure"))
        val summary = UpstreamRoundTripReport.summary(rows, 612, "selected")
        assertTrue(summary.contains("discovered=612 selected=4 scope=selected"))
        assertTrue(summary.contains("DEX preparation failures=1"))
        assertTrue(summary.contains("candidate Java: measured=2, no_error=2, compiles=1, checks_pass=0, checks_fail=1, checks_not_evaluated=1"))
        assertTrue(summary.contains("Java cases where reference passes a candidate-failing signal=1"))
        assertTrue(summary.contains("passing.java:"))
    }

    @Test fun byteInputHashRetainsExactBytesInsteadOfDecodedText() {
        val bytes = byteArrayOf(0xc3.toByte(), 0x28)
        val row = UpstreamJavaRoundTrips().measure("invalid.java", bytes)
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, row.inputHash)
        assertNull(row.reference)
    }

    @Test fun interruptedJournalKeepsRowsAndCannotClaimCompletion() {
        val directory = java.nio.file.Files.createTempDirectory("upstream-journal-test").toFile()
        try {
            val report = directory.resolve("report.tsv")
            report.writeText("stale success")
            UpstreamRoundTripJournal.markStarted(report)
            assertEquals("status=INCOMPLETE phase=VERIFY_BASELINE\n", report.readText())
            val journal = UpstreamRoundTripJournal(report, 612, listOf("a.java", "b.java"), "subset")
            val first = UpstreamRoundTripRow("a.java", "hash", null, emptyList(), "NO_SAMPLE", emptyList())
            journal.record(first)
            assertTrue(report.readText().startsWith("status=INCOMPLETE"))
            assertTrue(report.readText().contains("a.java\thash"))
            assertFalse(report.readText().contains("stale success"))
            assertThrows(IllegalStateException::class.java) { journal.finish() }
            assertThrows(IllegalStateException::class.java) { journal.record(first) }
            assertThrows(IllegalStateException::class.java) { journal.record(first.copy(input = "other.java")) }
            journal.record(first.copy(input = "b.java"))
            journal.finish()
            assertTrue(report.readText().contains("status=COMPLETE"))
            assertFalse(report.readText().contains("status=INCOMPLETE"))
            assertEquals(2, report.readLines().count { it.startsWith("a.java\t") || it.startsWith("b.java\t") })
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun fixture(body: String) = "package fixtures; class Fixture extends MissingHarness { public static class TestCls { $body } }"
}
