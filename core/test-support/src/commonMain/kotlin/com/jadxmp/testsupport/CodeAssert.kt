package com.jadxmp.testsupport

import kotlin.test.fail

/**
 * The shared **CodeAssert DSL** for asserting on decompiled source text.
 *
 * Pure-Kotlin, multiplatform string logic that mirrors jadx's test-side
 * `JadxCodeAssertions` + `TestUtils.count` (clean-room reimplementation — no jadx
 * source is copied). It operates on a decompiled-code [String] and lets engine
 * `commonTest` suites read like the jadx originals:
 *
 * ```kotlin
 * assertThatCode(decompiled)
 *     .containsOne("for (int i = 0; i < a.length; i++) {")
 *     .containsOne("break;")
 *     .countString(0, "else")
 * ```
 *
 * Assertion failures are raised via [kotlin.test.fail] so they surface as ordinary
 * test failures on every target (jvm / js / wasmJs / android).
 *
 * jadx cross-reference: `jadx.tests.api.utils.assertj.JadxCodeAssertions`.
 */
class CodeAssert(val code: String) {

    /** Asserts [substring] occurs exactly once. jadx: `containsOne`. */
    fun containsOne(substring: String): CodeAssert = countString(1, substring)

    /**
     * Asserts [substring] occurs exactly [count] times. Matching mirrors jadx's
     * `TestUtils.count`: occurrences may overlap (search advances by one char).
     * jadx: `countString`.
     */
    fun countString(count: Int, substring: String): CodeAssert {
        val actual = countOccurrences(code, substring)
        if (actual != count) {
            fail("Expected substring <$substring> count <$count> but was <$actual>")
        }
        return this
    }

    /** Asserts [substring] appears at least once. */
    fun contains(substring: String): CodeAssert {
        if (!code.contains(substring)) {
            fail("Expected code to contain <$substring> but it did not")
        }
        return this
    }

    /** Asserts [substring] never appears. jadx: `doesNotContain`. */
    fun doesNotContain(substring: String): CodeAssert {
        val actual = countOccurrences(code, substring)
        if (actual != 0) {
            fail("Expected code to NOT contain <$substring> but found it <$actual> time(s)")
        }
        return this
    }

    /** Asserts [line], prefixed with [indent] indentation levels, occurs exactly once. jadx: `containsLine`. */
    fun containsLine(indent: Int, line: String): CodeAssert = countLine(1, indent, line)

    /** Asserts [line], prefixed with [indent] indentation levels, never occurs. jadx: `notContainsLine`. */
    fun notContainsLine(indent: Int, line: String): CodeAssert = countLine(0, indent, line)

    private fun countLine(count: Int, indent: Int, line: String): CodeAssert =
        countString(count, indentStr(indent) + line)

    /** [containsLines] with no common indent. */
    fun containsLines(vararg lines: String): CodeAssert = containsLines(0, *lines)

    /**
     * Asserts that [lines], each prefixed with [commonIndent] indentation levels and
     * joined by single newlines, appear as one contiguous block exactly once. Empty
     * strings match blank lines (no indent applied). Each individual line is also
     * checked for presence first, for clearer failure messages. jadx: `containsLines`.
     */
    fun containsLines(commonIndent: Int, vararg lines: String): CodeAssert {
        if (lines.size == 1) {
            return containsLine(commonIndent, lines[0])
        }
        val indent = indentStr(commonIndent)
        val block = StringBuilder()
        for (line in lines) {
            block.append('\n')
            if (line.isEmpty()) {
                // Don't add common indent to empty lines.
                continue
            }
            val searchLine = indent + line
            block.append(searchLine)
            // Check every line individually for easier debugging.
            contains(searchLine)
        }
        // Drop the leading '\n' and assert the whole block occurs exactly once.
        return countString(1, block.substring(1))
    }

    /**
     * Asserts that exactly one of [substrings] (summed over occurrences) appears
     * once in total. jadx: `containsOneOf`.
     */
    fun containsOneOf(vararg substrings: String): CodeAssert {
        var matches = 0
        for (s in substrings) {
            matches += countOccurrences(code, s)
        }
        if (matches != 1) {
            fail("Expected exactly one match from <${substrings.joinToString()}> but was <$matches>")
        }
        return this
    }

    /**
     * Asserts that **exactly one** of the supplied [checks] passes (the others must
     * throw). Useful where jadx accepts more than one valid rendering of a construct
     * and exactly one should match a given output. jadx: `oneOf`.
     */
    fun oneOf(vararg checks: (CodeAssert) -> CodeAssert): CodeAssert {
        var passed = 0
        val failures = mutableListOf<String>()
        for (check in checks) {
            try {
                check(this)
                passed++
            } catch (e: Throwable) {
                failures.add(e.message ?: e.toString())
            }
        }
        if (passed != 1) {
            fail(
                "Expected exactly one matching check but passed <$passed>, failed <${failures.size}>:\n" +
                    failures.joinToString("\nFailed check:\n ")
            )
        }
        return this
    }

    /** Prints the code fenced by rulers; returns `this` for chaining. Debug aid. jadx: `print`. */
    fun print(): CodeAssert {
        println("-----------------------------------------------------------")
        println(code)
        println("-----------------------------------------------------------")
        return this
    }

    companion object {
        /** jadx's `JadxArgs.DEFAULT_INDENT_STR` — four spaces per indent level. */
        const val INDENT_UNIT: String = "    "

        /** [count] indent units. jadx: `TestUtils.indent`. */
        fun indentStr(count: Int): String = INDENT_UNIT.repeat(count)

        /**
         * Number of (possibly overlapping) occurrences of [substring] in [text].
         * Byte-for-byte behavioural match of jadx's `TestUtils.count`: the search
         * cursor advances by one after each hit, so overlapping matches are counted.
         *
         * @throws IllegalArgumentException if [substring] is empty (as in jadx).
         */
        fun countOccurrences(text: String, substring: String): Int {
            require(substring.isNotEmpty()) { "Substring can't be empty" }
            var count = 0
            var idx = 0
            while (true) {
                val found = text.indexOf(substring, idx)
                if (found == -1) break
                idx = found + 1
                count++
            }
            return count
        }
    }
}

/** DSL entry point: begin asserting on a block of decompiled [code]. */
fun assertThatCode(code: String): CodeAssert = CodeAssert(code)
