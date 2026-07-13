package com.jadxmp.codegen.java

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaIdentifiersTest {

    @Test
    fun legalNamesAreUnchanged() {
        for (n in listOf("foo", "Bar", "count2", "_x", "\$ref", "aLongName")) {
            assertEquals(n, JavaIdentifiers.sanitize(n))
        }
    }

    @Test
    fun reservedWordsGetSuffixed() {
        assertEquals("doWord", JavaIdentifiers.sanitize("do"))
        assertEquals("intWord", JavaIdentifiers.sanitize("int"))
        assertEquals("classWord", JavaIdentifiers.sanitize("class"))
        assertEquals("nullWord", JavaIdentifiers.sanitize("null"))
        assertEquals("trueWord", JavaIdentifiers.sanitize("true"))
    }

    @Test
    fun invalidCharactersBecomeUnderscores() {
        assertEquals("x_y", JavaIdentifiers.sanitize("x-y"))
        assertEquals("a_b_c", JavaIdentifiers.sanitize("a.b.c")) // single segment (already split elsewhere)
        assertEquals("hello_", JavaIdentifiers.sanitize("hello "))
    }

    @Test
    fun digitStartIsPrefixed() {
        assertEquals("_1a", JavaIdentifiers.sanitize("1a"))
        assertEquals("_123", JavaIdentifiers.sanitize("123"))
    }

    @Test
    fun emptyAndLoneUnderscoreAreMadeValid() {
        assertEquals("_Word", JavaIdentifiers.sanitize(""))
        assertEquals("_Word", JavaIdentifiers.sanitize("_"))
    }

    @Test
    fun sanitizeIsDeterministicAndNeverYieldsAReservedWord() {
        for (n in listOf("do", "int", "class", "1a", "", "_", "x-y", "for", "new")) {
            val once = JavaIdentifiers.sanitize(n)
            assertEquals(once, JavaIdentifiers.sanitize(n), "not deterministic for <$n>")
            // The result must itself be a legal, non-reserved identifier (re-sanitizing is a no-op).
            assertEquals(once, JavaIdentifiers.sanitize(once), "result <$once> is not stable/legal for <$n>")
            assertTrue(once.isNotEmpty() && (once[0].isLetter() || once[0] == '_' || once[0] == '$'))
        }
    }

    @Test
    fun qualifiedNamesSanitizePerSegmentKeepingDots() {
        assertEquals("a.doWord.Foo", JavaIdentifiers.sanitizeQualified("a.do.Foo"))
        assertEquals("pkg.Outer.Inner", JavaIdentifiers.sanitizeQualified("pkg.Outer.Inner"))
        assertEquals("_1a.intWord", JavaIdentifiers.sanitizeQualified("1a.int"))
    }
}
