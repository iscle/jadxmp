package com.jadxmp.io

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Differential regression against the real corpus APK. Its `resources.arsc` and `assets/classes.dex`
 * are STORED (compression method 0) — exactly the entries the silent-drop bug lost. The reader under
 * test is the engine's [ZipReader]; the JDK's [ZipFile] is used only as the oracle for expected bytes.
 */
class RealApkStoredEntriesTest {

    private val apk = File("../../corpus/binary/app-with-fake-dex.apk")

    @Test
    fun storedEntriesFromRealApkExtractByteExact() {
        assertTrue(apk.exists(), "corpus APK not found at ${apk.absolutePath}")
        val bytes = apk.readBytes()

        val extracted = ZipReader.extract(bytes).associate { it.name to it.bytes }

        // The two STORED payload entries that previously came back MISSING.
        assertTrue("resources.arsc" in extracted, "STORED resources.arsc must be extracted")
        assertTrue("assets/classes.dex" in extracted, "STORED assets/classes.dex must be extracted")

        // Byte-exact vs the JDK oracle for every non-directory entry (covers STORED + DEFLATE).
        ZipFile(apk).use { zf ->
            for (e in zf.entries()) {
                if (e.isDirectory) continue
                val expected = zf.getInputStream(e).readBytes()
                assertContentEquals(expected, extracted[e.name], "bytes differ for ${e.name}")
            }
        }
    }
}
