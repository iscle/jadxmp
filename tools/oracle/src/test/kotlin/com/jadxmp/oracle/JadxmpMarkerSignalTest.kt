package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * F1 closure at the signal level: when core:api flags an unrenderable (branchy/φ) method, codegen emits
 * a `// JADXMP ERROR: …` marker. This test locks that such output is scored a no-error **FAIL**, so a
 * later branchy corpus sample can never be mis-scored as a clean PARITY.
 */
class JadxmpMarkerSignalTest {

    private val flagged = DecompiledClass(
        "a.Foo",
        """
        class Foo {
            // JADXMP ERROR: unstructured control flow (phi unresolved)
            int pick() {
                return i4;
            }
        }
        """.trimIndent(),
    )

    private val clean = DecompiledClass("a.Bar", "class Bar {\n    void go() {\n    }\n}\n")

    @Test
    fun flaggedClassFailsNoErrorSignal() {
        assertFalse(
            AccuracySignals.noErrors(listOf(flagged), ErrorMarkers.JADXMP),
            "a JADXMP ERROR marker must fail signal-1",
        )
    }

    @Test
    fun cleanClassPassesNoErrorSignal() {
        assertTrue(AccuracySignals.noErrors(listOf(clean), ErrorMarkers.JADXMP))
    }
}
