package com.jadxmp.api

import com.jadxmp.io.ZipReader
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end proof of the "export decompiled sources" facade (P0#7): a loaded input → [ExportedFile]s
 * (`path → bytes`) → a single ZIP via [SourceArchive] that reads back through the production [ZipReader].
 *
 * A jvmTest only to read the `.dex`/`.apk` fixtures from disk; the facade it drives is all `commonMain`.
 */
class DecompilerExportTest {

    private fun helloDexBytes(): ByteArray =
        javaClass.classLoader.getResourceAsStream("hello.dex")?.readBytes()
            ?: error("hello.dex test resource not found")

    private fun apkBytes(): ByteArray {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "corpus/binary/app-with-fake-dex.apk") }
            .firstOrNull { it.isFile }
            ?: error("app-with-fake-dex.apk not found under corpus/binary")
        return file.readBytes()
    }

    @Test
    fun exportsHelloWorldToItsSourcePathWithRealCode() = runBlocking {
        val decompiler = Decompiler()
        decompiler.load("hello.dex", helloDexBytes())

        val files = decompiler.exportSources()
        // HelloWorld is in the default package → a top-level HelloWorld.java entry, no package dirs.
        val hello = assertNotNull(
            files.firstOrNull { it.path == "HelloWorld.java" },
            "expected a HelloWorld.java export; got ${files.map { it.path }}",
        )
        val source = hello.bytes.decodeToString()
        assertTrue("class HelloWorld" in source, "exported file must carry the real decompiled body:\n$source")
        assertTrue("\"Hello, World!\"" in source, "the greeting literal must survive to the export:\n$source")
    }

    @Test
    fun exportPathsMatchTheEmittedSourceNameAndExtension() = runBlocking {
        val decompiler = Decompiler()
        decompiler.load("hello.dex", helloDexBytes())

        val java = decompiler.exportSources(format = OutputFormat.JAVA)
        assertTrue(java.all { it.path.endsWith(".java") || it.path.startsWith("resources/") }, java.map { it.path }.toString())

        // Kotlin export uses the .kt extension and renders `fun` bodies — same walk, different backend.
        val kotlin = decompiler.exportSources(format = OutputFormat.KOTLIN)
        val helloKt = assertNotNull(kotlin.firstOrNull { it.path == "HelloWorld.kt" })
        assertTrue("fun " in helloKt.bytes.decodeToString(), "Kotlin export should render Kotlin source")
    }

    @Test
    fun exportZipRoundTripsThroughZipReader() = runBlocking {
        val decompiler = Decompiler()
        decompiler.load("hello.dex", helloDexBytes())
        val files = decompiler.exportSources()

        val zip = SourceArchive.zip(files)
        val readBack = ZipReader.extract(zip).associate { it.name to it.bytes }

        assertEquals(files.map { it.path }.toSet(), readBack.keys, "every exported path must be present in the zip")
        for (f in files) {
            assertTrue(
                readBack[f.path].contentEquals(f.bytes),
                "zip entry ${f.path} must round-trip byte-for-byte",
            )
        }
    }

    @Test
    fun includesDecodedResourcesFromAnApk() = runBlocking {
        val decompiler = Decompiler()
        decompiler.load("app.apk", apkBytes())

        val files = decompiler.exportSources()
        val manifest = assertNotNull(
            files.firstOrNull { it.path == "resources/AndroidManifest.xml" },
            "an APK export must include the decoded manifest under resources/; got ${files.map { it.path }}",
        )
        val text = manifest.bytes.decodeToString()
        assertTrue(text.startsWith("<?xml"), "the exported manifest is decoded text XML:\n$text")
        assertTrue("com.github.skylot.simple" in text, "manifest package should be present:\n$text")

        // The whole APK export still packages into one re-readable zip (classes + resources together).
        val zip = SourceArchive.zip(files)
        assertTrue(ZipReader.entryNames(zip).contains("resources/AndroidManifest.xml"))
    }

    @Test
    fun exportOfNothingLoadedIsEmptyNotACrash() = runBlocking {
        assertTrue(Decompiler().exportSources().isEmpty(), "no input → empty export, never a crash")
    }
}
