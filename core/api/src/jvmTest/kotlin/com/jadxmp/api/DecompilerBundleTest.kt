package com.jadxmp.api

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end bundle input through the public facade. A synthetic APKM is built with the real sample APK
 * (`corpus/binary/app-with-fake-dex.apk`, which carries `classes.dex` + resources) as its base plus a
 * split APK holding `hello.dex`. Asserts the facade merges the code of both APKs, decompiles a class,
 * and decodes resources from the *base* APK — the flow the desktop/web UI drives for an .apkm/.xapk/.apks.
 */
class DecompilerBundleTest {

    private fun corpusFile(rel: String): ByteArray {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, rel) }
            .firstOrNull { it.isFile }
            ?: error("$rel not found under the repo root")
        return file.readBytes()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun apkm(): ByteArray {
        val baseApk = corpusFile("corpus/binary/app-with-fake-dex.apk")
        val splitApk = zip("classes.dex" to corpusFile("corpus/binary/hello.dex"))
        return zip(
            "info.json" to """{"apkm_version":1,"apk_title":"sample"}""".encodeToByteArray(),
            "base.apk" to baseApk,
            "split_config.en.apk" to splitApk,
        )
    }

    @Test
    fun apkmLoadsMergedCodeAndDecompiles() {
        val decompiler = Decompiler()
        val count = decompiler.load("sample.apkm", apkm())
        assertTrue(count > 0, "the bundle should load classes from the base + split APKs")

        // Classes from the base APK (com.github.skylot.simple.*) are present and decompile.
        val mainActivity = decompiler.classNames.firstOrNull { it.endsWith("MainActivity") }
        assertNotNull(mainActivity, "base APK's classes should be in the merge: ${decompiler.classNames}")
        val decompiled = assertNotNull(decompiler.decompileClass(mainActivity))
        assertTrue(decompiled.code.isNotBlank(), "the class should render to non-empty source")
    }

    @Test
    fun apkmResourcesComeFromBaseApk() {
        val decompiler = Decompiler()
        decompiler.load("sample.apkm", apkm())

        val resources = assertNotNull(decompiler.resources, "a bundle exposes the base APK's resources")
        val manifest = assertNotNull(resources.decodeManifest(), "base APK's manifest should decode")
        assertTrue(
            manifest.contains("package=\"com.github.skylot.simple\""),
            "the manifest must be the base APK's:\n$manifest",
        )
        val appName = resources.table?.entries?.firstOrNull { it.typeName == "string" && it.name == "app_name" }
        assertNotNull(appName, "the base APK's resource table should decode")
    }
}
