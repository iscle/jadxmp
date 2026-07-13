package com.jadxmp.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end resource decoding through the public facade: load the real sample APK and read its
 * `AndroidManifest.xml`, `resources.arsc` table, and a compiled `res/layout` XML back as text — the
 * flow the web/desktop UI drives. Uses `corpus/binary/app-with-fake-dex.apk` (skylot 'simple' sample),
 * the same container `core:resources`' unit fixtures were extracted from.
 */
class DecompilerResourcesTest {

    private fun apkBytes(): ByteArray {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "corpus/binary/app-with-fake-dex.apk") }
            .firstOrNull { it.isFile }
            ?: error("app-with-fake-dex.apk not found under corpus/binary")
        return file.readBytes()
    }

    @Test
    fun decodesManifestToTextXmlThroughFacade() {
        val decompiler = Decompiler()
        decompiler.load("app.apk", apkBytes())

        val resources = assertNotNull(decompiler.resources, "an APK with resources should expose resources")
        val manifest = assertNotNull(resources.decodeManifest(), "manifest should decode to text XML")

        assertTrue(manifest.startsWith("<?xml"), "manifest is well-formed text XML:\n$manifest")
        assertContainsAll(
            manifest,
            "<manifest",
            "package=\"com.github.skylot.simple\"",
            "xmlns:android=\"http://schemas.android.com/apk/res/android\"",
            "<application",
            "<activity",
            "android:name=\"com.github.skylot.simple.MainActivity\"",
            "android:name=\"android.intent.action.MAIN\"",
            "android.intent.category.LAUNCHER",
        )
    }

    /**
     * The resource-table view decodes `resources.arsc` to flat rows end-to-end through the real engine
     * path: the APK is loaded via [Decompiler], whose `ZipReader` extracts the (STORED) `resources.arsc`,
     * and [ApkResources.table] maps it to rows. No JDK-zip scaffold — this is the flow the UI drives.
     */
    @Test
    fun exposesResourceTableRows() {
        val decompiler = Decompiler()
        decompiler.load("app.apk", apkBytes())
        val table = assertNotNull(decompiler.resources?.table, "resources.arsc should decode to a table view")

        val appName = table.entries.single { it.typeName == "string" && it.name == "app_name" }
        assertEquals("Simple", appName.value)
        assertEquals("com.github.skylot.simple", appName.packageName)
        assertEquals("@string/app_name", appName.reference)
        assertEquals("0x7f040000", appName.hexId)

        val layout = table.entries.single { it.typeName == "layout" && it.name == "activity_main" }
        assertEquals("res/layout/activity_main.xml", layout.value)

        // exact color values decoded from the ARSC (TYPE_INT_COLOR_RGB8)
        val colors = table.entriesOfType("color").associate { it.name to it.value }
        assertEquals("#d81b60", colors["colorAccent"])
        assertEquals("#008577", colors["colorPrimary"])
        assertEquals("#00574b", colors["colorPrimaryDark"])

        assertTrue("string" in table.types && "color" in table.types && "layout" in table.types)
    }

    /**
     * With the table now extracted end-to-end, the binary-XML decoder resolves app resource ids in the
     * manifest to symbolic `@type/name` references (it is handed the decoded [ResourceTable]) instead of
     * emitting raw `@0x7f……` — jadx #1208 behaviour. `android:label` → `@string/app_name`,
     * `android:theme` → `@style/AppTheme`.
     */
    @Test
    fun manifestReferencesResolveAgainstTable() {
        val decompiler = Decompiler()
        decompiler.load("app.apk", apkBytes())
        val manifest = assertNotNull(decompiler.resources?.decodeManifest())

        assertContainsAll(
            manifest,
            "android:label=\"@string/app_name\"",
            "android:theme=\"@style/AppTheme\"",
        )
        assertFalse(
            manifest.contains("@0x7f"),
            "no app reference should remain a raw numeric id once the table resolves them:\n$manifest",
        )
    }

    @Test
    fun decodesBinaryLayoutXml() {
        val decompiler = Decompiler()
        decompiler.load("app.apk", apkBytes())
        val resources = assertNotNull(decompiler.resources)

        assertTrue(
            "res/layout/activity_main.xml" in resources.xmlResourcePaths,
            "expected the compiled layout among xml resources: ${resources.xmlResourcePaths}",
        )
        val layout = assertNotNull(resources.decodeXml("res/layout/activity_main.xml"))
        assertTrue(layout.startsWith("<?xml"))
        assertTrue(layout.contains("android:"), "decoded layout should carry android: attributes:\n$layout")
    }

    @Test
    fun realApkManifestIsPresentAndDecodesWithoutSpuriousDiagnostics() {
        // S1/S2 honesty on a good container: the manifest is present AND decodes, and a clean arsc yields
        // no salvage notes — so nothing spurious lands on diagnostics.
        val decompiler = Decompiler()
        decompiler.load("app.apk", apkBytes())
        val resources = assertNotNull(decompiler.resources)

        assertTrue(resources.hasManifestEntry, "the sample APK carries an AndroidManifest.xml entry")
        assertNotNull(resources.decodeManifest(), "a well-formed manifest decodes")
        assertNotNull(resources.table, "a well-formed arsc decodes")
        // Touch the table repeatedly to prove no double-count path taints a clean container.
        resources.table
        resources.decodeXml("res/layout/activity_main.xml")

        assertFalse(
            resources.diagnostics.any { it.contains("could not be decoded") || it.contains("resources.arsc") },
            "a clean APK must produce no undecodable/arsc diagnostics: ${resources.diagnostics}",
        )
    }

    @Test
    fun unknownResourcePathReturnsNull() {
        val decompiler = Decompiler()
        decompiler.load("app.apk", apkBytes())
        assertNull(decompiler.resources?.decodeXml("res/layout/does_not_exist.xml"))
    }

    @Test
    fun plainDexInputHasNoResources() {
        val decompiler = Decompiler()
        // A bare .dex is not a zip container -> no resources, but classes still load.
        val dex = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "corpus/binary/hello.dex") }
            .firstOrNull { it.isFile } ?: error("hello.dex not found")
        decompiler.load("hello.dex", dex.readBytes())
        assertNull(decompiler.resources, "a bare dex carries no resources")
    }

    private fun assertContainsAll(haystack: String, vararg needles: String) {
        for (n in needles) assertTrue(haystack.contains(n), "expected to find '$n' in:\n$haystack")
    }
}
