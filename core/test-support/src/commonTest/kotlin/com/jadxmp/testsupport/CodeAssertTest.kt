package com.jadxmp.testsupport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * Tests for the CodeAssert DSL itself. Since this is test *infrastructure* that gates
 * the whole engine, its own correctness matters: a false-green here silently weakens
 * every downstream accuracy test. So both the pass and the fail paths are exercised.
 */
class CodeAssertTest {

    private val loopCode =
        """
        public void test(int[] a, int b) {
            for (int i = 0; i < a.length; i++) {
                a[i]++;
                if (i < b) {
                    break;
                }
            }
            this.f++;
        }
        """.trimIndent()

    /** Asserts that [block] fails via the DSL (raises an AssertionError). */
    private fun assertDslFails(block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            return
        }
        fail("Expected the DSL assertion to fail, but it passed")
    }

    // ---- countOccurrences (the TestUtils.count analogue) ----

    @Test
    fun countCountsAllOccurrences() {
        assertEquals(3, CodeAssert.countOccurrences("ababab", "ab"))
        assertEquals(0, CodeAssert.countOccurrences("abc", "z"))
        assertEquals(1, CodeAssert.countOccurrences("abc", "abc"))
    }

    @Test
    fun countCountsOverlappingOccurrences() {
        // "aaaa" contains "aa" at indices 0,1,2 -> jadx counts overlaps (cursor +1).
        assertEquals(3, CodeAssert.countOccurrences("aaaa", "aa"))
    }

    @Test
    fun countRejectsEmptySubstring() {
        assertFailsWith<IllegalArgumentException> { CodeAssert.countOccurrences("abc", "") }
    }

    // ---- containsOne / countString ----

    @Test
    fun containsOnePassesForSingleOccurrence() {
        assertThatCode(loopCode)
            .containsOne("for (int i = 0; i < a.length; i++) {")
            .containsOne("break;")
            .containsOne("this.f++;")
    }

    @Test
    fun containsOneFailsWhenAbsent() {
        assertDslFails { assertThatCode(loopCode).containsOne("while (true) {") }
    }

    @Test
    fun containsOneFailsWhenDuplicated() {
        assertDslFails { assertThatCode("x;\nx;").containsOne("x;") }
    }

    @Test
    fun countStringMatchesExactCount() {
        assertThatCode(loopCode).countString(0, "else")
        assertThatCode("x;\nx;\nx;").countString(3, "x;")
    }

    @Test
    fun countStringFailsOnWrongCount() {
        assertDslFails { assertThatCode("x;\nx;").countString(1, "x;") }
    }

    // ---- doesNotContain ----

    @Test
    fun doesNotContainPasses() {
        assertThatCode(loopCode).doesNotContain("switch")
    }

    @Test
    fun doesNotContainFails() {
        assertDslFails { assertThatCode(loopCode).doesNotContain("break;") }
    }

    // ---- contains ----

    @Test
    fun containsAllowsMultiple() {
        assertThatCode("x;\nx;").contains("x;")
    }

    @Test
    fun containsFailsWhenAbsent() {
        assertDslFails { assertThatCode("abc").contains("z") }
    }

    // ---- containsLine / notContainsLine (indentation) ----

    @Test
    fun containsLineRespectsIndentLevel() {
        // After trimIndent, "break;" sits at 3 indent units (12 spaces) in loopCode.
        assertThatCode(loopCode).containsLine(3, "break;")
    }

    @Test
    fun containsLineFailsAtWrongIndent() {
        // Deeper than any real indent -> no match. (A shallower indent would still match as a
        // substring of the real line, mirroring jadx's own indent-prefix matching semantics.)
        assertDslFails { assertThatCode(loopCode).containsLine(5, "break;") }
    }

    @Test
    fun notContainsLinePasses() {
        // A line that appears at no indent level at all.
        assertThatCode(loopCode).notContainsLine(1, "while (true) {")
    }

    @Test
    fun indentStrBuildsFourSpacePerLevel() {
        assertEquals("", CodeAssert.indentStr(0))
        assertEquals("    ", CodeAssert.indentStr(1))
        assertEquals("        ", CodeAssert.indentStr(2))
    }

    // ---- containsLines (contiguous block) ----

    @Test
    fun containsLinesMatchesContiguousBlock() {
        // The if-block sits at indent 2 (8 spaces); "break;" is one level deeper inside it.
        assertThatCode(loopCode).containsLines(
            2,
            "if (i < b) {",
            "    break;",
            "}",
        )
    }

    @Test
    fun containsLinesSingleLineDelegatesToContainsLine() {
        assertThatCode(loopCode).containsLines(3, "break;")
    }

    @Test
    fun containsLinesFailsWhenNotContiguous() {
        // These lines exist individually but are not adjacent as a block.
        assertDslFails {
            assertThatCode(loopCode).containsLines(
                0,
                "a[i]++;",
                "this.f++;",
            )
        }
    }

    @Test
    fun containsLinesHandlesBlankLines() {
        val code = "first;\n\nsecond;"
        assertThatCode(code).containsLines(0, "first;", "", "second;")
    }

    // ---- containsOneOf ----

    @Test
    fun containsOneOfPassesForExactlyOneMatch() {
        assertThatCode(loopCode).containsOneOf("break;", "continue;", "goto")
    }

    @Test
    fun containsOneOfFailsWhenNoneMatch() {
        assertDslFails { assertThatCode(loopCode).containsOneOf("continue;", "goto") }
    }

    @Test
    fun containsOneOfFailsWhenMultipleMatch() {
        assertDslFails { assertThatCode("break;\ncontinue;").containsOneOf("break;", "continue;") }
    }

    // ---- oneOf ----

    @Test
    fun oneOfPassesWhenExactlyOneCheckPasses() {
        assertThatCode(loopCode).oneOf(
            { it.containsOne("break;") }, // passes
            { it.containsOne("while (true) {") }, // fails
        )
    }

    @Test
    fun oneOfFailsWhenNoCheckPasses() {
        assertDslFails {
            assertThatCode(loopCode).oneOf(
                { it.containsOne("while (true) {") },
                { it.containsOne("switch (i) {") },
            )
        }
    }

    @Test
    fun oneOfFailsWhenMoreThanOneCheckPasses() {
        assertDslFails {
            assertThatCode(loopCode).oneOf(
                { it.containsOne("break;") },
                { it.containsOne("this.f++;") },
            )
        }
    }

    // ---- chaining returns same instance ----

    @Test
    fun assertionsChainOnSameCode() {
        val a = assertThatCode(loopCode)
        assertEquals(a, a.containsOne("break;").doesNotContain("switch"))
    }
}
