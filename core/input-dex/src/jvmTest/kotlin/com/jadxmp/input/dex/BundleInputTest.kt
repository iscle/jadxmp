package com.jadxmp.input.dex

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bundle-of-APKs input (APKM / XAPK / APKS): detection, base-first class merge, and fault isolation.
 * Fixtures are built in-memory with the JDK zip writer — an inner APK is a zip holding a `classes.dex`
 * (a real [DexBuilder] dex), and a bundle is an outer zip holding those APKs (+ a JSON manifest for
 * APKM/XAPK). The bundle logic itself is `commonMain` (wasm-safe); only these fixtures are JVM.
 */
class BundleInputTest {

    // --- fixtures -------------------------------------------------------------

    /** A one-class DEX defining `L<pkg>/<name>;`. */
    private fun oneClassDex(pkg: String, name: String): ByteArray {
        val b = DexBuilder()
        val t = b.addType("L$pkg/$name;")
        val obj = b.addType("Ljava/lang/Object;")
        b.addClass(t, obj)
        return b.build()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((entryName, data) in entries) {
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A minimal APK: a zip with a single `classes.dex`. */
    private fun apk(dex: ByteArray): ByteArray = zip("classes.dex" to dex)

    private fun classTypes(result: DexLoadResult): Set<String> = result.classes.map { it.type }.toSet()

    // --- APKM -----------------------------------------------------------------

    @Test
    fun apkmDetectsAndMergesBaseAndSplit() {
        val bundle = zip(
            "info.json" to """{"apkm_version":1,"apk_title":"x"}""".encodeToByteArray(),
            "base.apk" to apk(oneClassDex("p", "Base")),
            "split_config.en.apk" to apk(oneClassDex("p", "Split")),
        )
        assertEquals(BundleFormat.APKM, BundleInput.detect("x.apkm", bundle))
        val result = assertNotNull(BundleInput.load("x.apkm", bundle))
        assertEquals(setOf("Lp/Base;", "Lp/Split;"), classTypes(result), "both APKs' classes merge")
    }

    @Test
    fun apkmDetectedByContentDespiteWrongExtension() {
        // Renamed to .zip: detection is content-first, so it is still an APKM.
        val bundle = zip(
            "info.json" to """{"apkm_version":2}""".encodeToByteArray(),
            "base.apk" to apk(oneClassDex("p", "Base")),
        )
        assertEquals(BundleFormat.APKM, BundleInput.detect("renamed.zip", bundle))
    }

    // --- XAPK -----------------------------------------------------------------

    @Test
    fun xapkDetectsAndMergesFromManifest() {
        val bundle = zip(
            "manifest.json" to """{"xapk_version":2,"split_apks":[{"file":"base.apk","id":"base"}]}""".encodeToByteArray(),
            "base.apk" to apk(oneClassDex("p", "Base")),
            "config.arm64_v8a.apk" to apk(oneClassDex("p", "Native")),
        )
        assertEquals(BundleFormat.XAPK, BundleInput.detect("x.xapk", bundle))
        val result = assertNotNull(BundleInput.load("x.xapk", bundle))
        assertEquals(setOf("Lp/Base;", "Lp/Native;"), classTypes(result))
    }

    // --- APKS -----------------------------------------------------------------

    @Test
    fun apksDetectsBareZipOfApks() {
        val bundle = zip(
            "toc.pb" to byteArrayOf(1, 2, 3),
            "base-master.apk" to apk(oneClassDex("p", "Base")),
            "splits/base-en.apk" to apk(oneClassDex("p", "Lang")),
        )
        assertEquals(BundleFormat.APKS, BundleInput.detect("x.apks", bundle))
        val result = assertNotNull(BundleInput.load("x.apks", bundle))
        assertEquals(setOf("Lp/Base;", "Lp/Lang;"), classTypes(result))
    }

    // --- base-first dedup precedence -----------------------------------------

    @Test
    fun duplicateClassKeepsBaseCopy() {
        // Base defines Shared with super Object; the split defines the SAME type with a different super.
        // Base-first dedup must keep the base copy (super == Object) and count one duplicate.
        val baseDex = run {
            val b = DexBuilder()
            val t = b.addType("Lp/Shared;")
            b.addClass(t, b.addType("Ljava/lang/Object;"))
            b.build()
        }
        val splitDex = run {
            val b = DexBuilder()
            val t = b.addType("Lp/Shared;")
            b.addClass(t, b.addType("Lp/Other;"))
            b.build()
        }
        val bundle = zip(
            "info.json" to """{"apkm_version":1}""".encodeToByteArray(),
            "base.apk" to apk(baseDex),
            "split_config.apk" to apk(splitDex),
        )
        val result = assertNotNull(BundleInput.load("x.apkm", bundle))
        val shared = result.classes.single { it.type == "Lp/Shared;" }
        assertEquals("Ljava/lang/Object;", shared.superType, "base copy wins")
        assertEquals(1, result.duplicateClassCount)
    }

    // --- plain APK is NOT a bundle -------------------------------------------

    @Test
    fun plainApkIsNotDetectedAsBundle() {
        val plain = apk(oneClassDex("p", "Only")) // top-level classes.dex -> a plain APK
        assertNull(BundleInput.detect("app.apk", plain), "a plain APK must not be seen as a bundle")
        assertNull(BundleInput.load("app.apk", plain))
        // And it still loads via the ordinary DEX path unchanged.
        assertEquals(setOf("Lp/Only;"), DexInput.load("app.apk", plain).classes.map { it.type }.toSet())
    }

    @Test
    fun plainApkContainingNestedApkAssetStaysAPlainApk() {
        // A plain APK may ship an inner .apk under assets/; the top-level classes.dex guard keeps it a
        // plain APK (loaded by DexInput), not a bundle.
        val plain = zip(
            "classes.dex" to oneClassDex("p", "Host"),
            "assets/embedded.apk" to apk(oneClassDex("p", "Guest")),
        )
        assertNull(BundleInput.detect("app.apk", plain))
    }

    // --- fault isolation ------------------------------------------------------

    @Test
    fun malformedSplitIsSkippedNotFatal() {
        val bundle = zip(
            "info.json" to """{"apkm_version":1}""".encodeToByteArray(),
            "base.apk" to apk(oneClassDex("p", "Base")),
            "split_broken.apk" to ByteArray(64) { (it * 7 + 3).toByte() }, // not a zip
        )
        val result = assertNotNull(BundleInput.load("x.apkm", bundle))
        assertEquals(setOf("Lp/Base;"), classTypes(result), "the bad split is dropped, the base survives")
    }

    @Test
    fun nonZipInputIsNotABundle() {
        assertNull(BundleInput.detect("junk.bin", ByteArray(128) { it.toByte() }))
        assertNull(BundleInput.load("junk.bin", ByteArray(128) { it.toByte() }))
    }

    // --- resource base selection ----------------------------------------------

    @Test
    fun baseApkBytesPicksTheBaseAndDecodesAsAnApk() {
        val bundle = zip(
            "info.json" to """{"apkm_version":1}""".encodeToByteArray(),
            "split_config.en.apk" to apk(oneClassDex("p", "Split")),
            "base.apk" to apk(oneClassDex("p", "Base")),
        )
        val baseBytes = assertNotNull(BundleInput.baseApkBytes("x.apkm", bundle))
        // The returned bytes are the base APK itself: parsing it yields only the base class.
        assertEquals(setOf("Lp/Base;"), DexInput.load("base.apk", baseBytes).classes.map { it.type }.toSet())
    }

    // --- AAB (code-only; resources are protobuf and deferred) -----------------

    @Test
    fun aabIsNotABundleAndItsDexLoadsViaTheDexPath() {
        // An Android App Bundle holds plain dex under base/dex/*.dex (no inner .apk, protobuf resources).
        // It is NOT one of the zip-of-APKs bundle formats, so BundleInput ignores it; its code is picked
        // up by the ordinary DexInput zip path (which extracts every *.dex entry). Resources are protobuf
        // and remain deferred (see the scoping note).
        val aab = zip(
            "base/dex/classes.dex" to oneClassDex("p", "Aab"),
            "base/manifest/AndroidManifest.xml" to byteArrayOf(0x0A, 0x00), // protobuf, not decoded here
            "BundleConfig.pb" to byteArrayOf(1, 2, 3),
        )
        assertNull(BundleInput.detect("app.aab", aab), "an AAB is not a zip-of-APKs bundle")
        assertEquals(setOf("Lp/Aab;"), DexInput.load("app.aab", aab).classes.map { it.type }.toSet())
    }

    @Test
    fun extractApksOrdersBaseFirst() {
        val bundle = zip(
            "split_a.apk" to apk(oneClassDex("p", "A")),
            "base.apk" to apk(oneClassDex("p", "Base")),
            "config.x86.apk" to apk(oneClassDex("p", "X")),
        )
        val ordered = BundleInput.extractApks(bundle)
        assertTrue(ordered.first().name == "base.apk", "base APK must be first: ${ordered.map { it.name }}")
    }
}
