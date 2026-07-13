package com.jadxmp.input.dex

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for the oracle-surfaced REGRESSION: `app-with-fake-dex.apk` has an entry with a zero DOS
 * timestamp that made Kompress abort extraction of the whole archive, losing its `classes.dex`. jadx
 * tolerates it; we must too. Asserts the APK still yields its classes.
 */
class FakeApkTest {

    @Test
    fun loadsClassesDespiteInvalidDosTimestamp() {
        val bytes = javaClass.classLoader.getResourceAsStream("app-with-fake-dex.apk")
            ?.readBytes()
            ?: error("app-with-fake-dex.apk test resource not found")
        val classes = DexInput.load("app-with-fake-dex.apk", bytes).classes
        assertTrue(classes.isNotEmpty(), "expected classes from the APK's classes.dex, got none")
    }
}
