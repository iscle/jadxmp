package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the now-**wired** contract of the Kotlin self-measurement harness: `core:api` exposes
 * `OutputFormat.KOTLIN`, so [KotlinJadxmpDecompiler.decompileKotlin] drives the real engine (returning a
 * non-null result even for empty input) and the reflection probe reports the backend as wired.
 *
 * These assertions replace the earlier "not wired yet" tripwires (which fired the moment the enum landed,
 * signalling the integration point had to be filled). If [KotlinJadxmpDecompiler.isKotlinOutputWired] ever
 * flips back to `false`, the enum was removed out from under this hook — a real regression.
 */
class KotlinJadxmpDecompilerTest {

    @Test
    fun decompileKotlinIsWiredAndReturnsResult() {
        // Empty bytes → the engine degrades to zero classes, but the hook still returns a real result (the
        // return type is now non-null), so the runner's `!= null` wiring probe correctly reads "wired".
        val result = KotlinJadxmpDecompiler().decompileKotlin("hello.dex", ByteArray(0))
        assertTrue(result.classes.isEmpty(), "empty input degrades to zero classes")
    }

    @Test
    fun kotlinOutputProbeReportsWired() {
        // core:api now has OutputFormat.KOTLIN, so the probe reports wired. This is the flipped tripwire:
        // if it ever reports false again, the enum was removed and the backend is no longer measured.
        assertTrue(
            KotlinJadxmpDecompiler.isKotlinOutputWired(),
            "core:api exposes OutputFormat.KOTLIN; the Kotlin backend must be reported as wired and measured",
        )
    }
}
