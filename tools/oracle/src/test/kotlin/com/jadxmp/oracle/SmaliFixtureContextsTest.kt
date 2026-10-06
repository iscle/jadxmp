package com.jadxmp.oracle

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SmaliFixtureContextsTest {
    @Test fun completeOriginalGroupConsumesEachPhysicalInputExactlyOnce() {
        val inputs = Corpus.smaliInputs()
        val plan = SmaliFixtureContexts.plan(inputs)
        assertEquals(inputs.size - 2, plan.size)
        assertEquals(inputs.toSet(), plan.flatMap { it.inputs }.toSet())
        assertEquals(inputs.size, plan.sumOf { it.inputs.size })
        val group = plan.single { it.id == "inline/TestMethodInline" }
        assertEquals(listOf("A.smali", "B.smali", "C.smali"), group.inputs.map { it.name })
        assertTrue(plan.single { it.id == ExpectedInvalidInput.SAMPLE }.inputs.size == 1)
    }

    @Test fun incompleteDuplicateChangedAndAdditionalGroupMembersFailClosed() {
        val original = Corpus.smaliDir().resolve("inline/TestMethodInline")
        val files = original.listFiles { it.extension == "smali" }!!.sortedBy { it.name }
        assertThrows(IllegalArgumentException::class.java) { SmaliFixtureContexts.plan(files.dropLast(1)) }
        assertThrows(IllegalArgumentException::class.java) { SmaliFixtureContexts.plan(files + files.first()) }
        val temp = Files.createTempDirectory("jadxmp-fixture-context").toFile()
        try {
            val copied = files.map { file -> temp.resolve("inline/TestMethodInline/${file.name}").apply {
                parentFile.mkdirs(); writeBytes(file.readBytes())
            } }
            copied.first().appendText("\n# changed\n")
            assertThrows(IllegalArgumentException::class.java) { SmaliFixtureContexts.plan(copied, temp) }
            copied.first().writeBytes(files.first().readBytes())
            val extra = copied.first().parentFile.resolve("D.smali").apply { writeText(".class LD;") }
            assertThrows(IllegalArgumentException::class.java) { SmaliFixtureContexts.plan(copied + extra, temp) }
        } finally { temp.deleteRecursively() }
    }

    @Test fun unrelatedCategoryKeepsItsOriginalSingletonFixtures() {
        val inputs = Corpus.smaliInputs(setOf("loops"))
        assertTrue(inputs.isNotEmpty())
        val plan = SmaliFixtureContexts.plan(inputs, selectedCategories = setOf("loops"))
        assertEquals(inputs.size, plan.size)
        assertTrue(plan.all { it.inputs.size == 1 })
    }
    @Test fun losingARequiredClassFailsContextValidationEvenWhenRemainingSourceCompiles() {
        val group = SmaliFixtureContexts.plan(Corpus.smaliInputs()).single { it.id == "inline/TestMethodInline" }
        val incomplete = DecompilationResult(group.id,
            listOf(DecompiledClass("inline.A", "package inline; public class A {}")), 0)
        assertTrue(AccuracySignals.recompiles(incomplete.classes).success)
        assertEquals(listOf("missing original top-level classes: [inline.other.B, inline.other.C]"), group.outputProblems(incomplete))
        val duplicate = incomplete.copy(classes = incomplete.classes + incomplete.classes)
        assertTrue(group.outputProblems(duplicate).any { it.contains("duplicate") })
    }

    @Test fun incompleteCandidateCannotPassByTyingAFlaggedReference() {
        val group = SmaliFixtureContexts.plan(Corpus.smaliInputs()).single { it.id == "inline/TestMethodInline" }
        val incomplete = DecompilationResult(group.id,
            listOf(DecompiledClass("inline.A", "package inline; public class A {}")), 0)
        val flagged = SignalScore(noErrors = false, recompiles = true, executesCheck = null)
        val board = Scoreboard().apply { add(SampleResult(group.id, flagged, flagged)) }
        assertFalse(board.hasRegression())
        for (output in listOf(incomplete, incomplete.copy(classes = incomplete.classes + incomplete.classes))) {
            val problems = group.outputProblems(output).map { "${group.id}: $it" }
            val failure = assertThrows(IllegalStateException::class.java) {
                requireDifferentialParity(board, 1, emptyList(), emptyList(), problems)
            }
            assertTrue(failure.message.orEmpty().contains("candidate context failure"))
            val report = renderSmaliReport(board, 1, emptyList(), emptyList(), 3, problems)
            assertTrue(report.contains("GATE: FAIL"))
            assertFalse(report.contains("GATE: PASS"))
        }
    }

    @Test fun removingTheEntireRegisteredGroupCannotShrinkTheDefaultCorpus() {
        val inputs = Corpus.smaliInputs().filterNot { Corpus.smaliSampleName(it).startsWith("inline/TestMethodInline/") }
        assertTrue(inputs.isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { SmaliFixtureContexts.plan(inputs) }
        assertThrows(IllegalArgumentException::class.java) {
            SmaliFixtureContexts.plan(inputs.filter { Corpus.categoryOf(it) == "inline" }, selectedCategories = setOf("inline"))
        }
        val selected = SmaliFixtureContexts.plan(Corpus.smaliInputs(setOf("inline")), selectedCategories = setOf("inline"))
        assertTrue(selected.any { it.id == "inline/TestMethodInline" })
    }

}
