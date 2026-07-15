package com.jadxmp.ui.client

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [CoreApiDecompilerClient] end-to-end with the real sample APK, proving the Resources seam the
 * web/desktop shell runs: engine load → Resources tree (manifest / res folders / resource table) →
 * selecting a node yields decoded XML or a table listing through [DecompilerClient.code]. The pure tree
 * and document logic is covered without the engine in `ResourceSurfaceTest`; this pins the adapter and
 * the engine wiring.
 *
 * A jvmTest (not commonTest) purely to read the `.apk`/`.dex` fixtures from disk and use `runBlocking`;
 * the client it drives is all `commonMain` and wasm-safe.
 */
class CoreApiDecompilerClientResourcesTest {

    private fun corpusBytes(relative: String): ByteArray {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.isFile }
            ?: error("$relative not found under any parent of ${System.getProperty("user.dir")}")
        return file.readBytes()
    }

    private fun apkClient(): CoreApiDecompilerClient = runBlocking {
        CoreApiDecompilerClient().also {
            it.open(OpenRequest("app.apk", corpusBytes("corpus/binary/app-with-fake-dex.apk")))
        }
    }

    @Test
    fun resourcesTreeHasManifestResFolderAndTable() = runBlocking {
        val roots = apkClient().rootNodes(TreeKind.RESOURCES)
        val labels = roots.map { it.label }
        assertTrue("AndroidManifest.xml" in labels, "expected manifest root; got $labels")
        assertTrue("res" in labels, "expected res/ folder root; got $labels")
        assertTrue("Resource Table" in labels, "expected resource table root; got $labels")
    }

    @Test
    fun resourceTableExpandsToTypesWithEntries() = runBlocking {
        val client = apkClient()
        val types = client.childNodes(NodeId("restable:")).map { it.label }
        assertTrue("string" in types && "color" in types && "layout" in types, "got types=$types")

        // Selecting a type renders a readable listing with @type/name references and hex ids.
        val stringDoc = client.code(NodeId("restype:string"), CodeView.JAVA).plainText()
        assertTrue(stringDoc.contains("@string/app_name"), "table listing should reference app_name:\n$stringDoc")
        assertTrue(stringDoc.contains("Simple"), "app_name value 'Simple' should appear:\n$stringDoc")
        assertTrue(stringDoc.contains("0x"), "entries should carry a hex id:\n$stringDoc")
    }

    @Test
    fun selectingManifestYieldsDecodedResolvedXml() = runBlocking {
        val doc = apkClient().code(NodeId("res:AndroidManifest.xml"), CodeView.JAVA)
        val text = doc.plainText()
        assertTrue(text.contains("<manifest"), "manifest should decode to XML:\n$text")
        // References resolved against the table (jadx #1208), surfaced through the UI seam.
        assertTrue(text.contains("@string/app_name"), "manifest refs should resolve:\n$text")
    }

    @Test
    fun selectingBinaryLayoutYieldsDecodedXml() = runBlocking {
        val client = apkClient()
        // The res/ subtree groups the compiled layout under res/layout.
        val layoutFiles = client.childNodes(NodeId("resdir:res/layout")).map { it.id.value }
        assertTrue(
            "res:res/layout/activity_main.xml" in layoutFiles,
            "expected the compiled layout among res/layout children; got $layoutFiles",
        )
        val text = client.code(NodeId("res:res/layout/activity_main.xml"), CodeView.JAVA).plainText()
        assertTrue(text.startsWith("<?xml"), "layout should decode to XML:\n$text")
        assertTrue(text.contains("android:"), "decoded layout should carry android: attributes")
    }

    @Test
    fun nonApkInputHasEmptyResourcesTreeAndHonestPlaceholder() = runBlocking {
        val client = CoreApiDecompilerClient()
        // hello.dex is a bare dex on the test classpath — classes but no resources.
        val dex = javaClass.classLoader.getResourceAsStream("hello.dex")?.readBytes()
            ?: corpusBytes("corpus/binary/hello.dex")
        client.open(OpenRequest("hello.dex", dex))

        assertTrue(client.rootNodes(TreeKind.RESOURCES).isEmpty(), "a bare dex has no resources tree")

        // A resource node that somehow reaches code() (e.g. a stale nav target) degrades honestly.
        val doc = client.code(NodeId("res:AndroidManifest.xml"), CodeView.JAVA)
        assertTrue(
            doc.plainText().contains("not available"),
            "resource on a non-APK input should be an honest placeholder, not a crash",
        )
    }

    /**
     * Regression (real compiled AXML): `AndroidManifest.xml` and a compiled `res/…xml` are eagerly held as
     * raw binary XML (AXML magic `03 00 08 00`, NUL at index 1). [DecompilerClient.resourceBytes] must NOT
     * hand those to the image/hex byte path — the workbench's content sniffer would see the NUL, classify
     * HEX, and render a hex dump instead of the decoded XML. They are not in `binaryResourcePaths`, so the
     * gate returns `null` and they keep decoding to text via [DecompilerClient.code]. Unmasked only on a real
     * APK: every plain-text XML fixture classifies as TEXT and would slip past. (At base 28e4933 `resourceBytes`
     * had no `rawResource` and returned `null` here — this pins that behavior back in place.)
     */
    @Test
    fun compiledManifestAndXmlDoNotRouteToByteViewer() = runBlocking {
        val client = apkClient()
        assertNull(
            client.resourceBytes(NodeId("res:AndroidManifest.xml")),
            "compiled manifest must stay on the decoded-text path, not the byte/hex viewer",
        )
        assertNull(
            client.resourceBytes(NodeId("res:res/layout/activity_main.xml")),
            "compiled layout must stay on the decoded-text path, not the byte/hex viewer",
        )
        // And they still decode to XML — proving the null above lands them on the decoded path, not nowhere.
        assertTrue(
            client.code(NodeId("res:AndroidManifest.xml"), CodeView.JAVA).plainText().contains("<manifest"),
            "manifest still decodes to XML",
        )
    }

    /**
     * The full routing matrix through the real client: text-decodable entries NOT in `binaryResourcePaths`
     * (the manifest + a text `res/…xml`) resolve to `null` (stay decoded), while genuine binary `res/` blobs
     * IN `binaryResourcePaths` (a png + a raw file) return their exact bytes for the image/hex viewer. Proves
     * the gate blocks manifest/xml WITHOUT over-blocking real blobs — a gate returning `null` for everything
     * would fail the two positive assertions. The corpus APK carries no binary `res/` blob, so this uses a
     * purpose-built zip.
     */
    @Test
    fun onlyGenuineBinaryResBlobsRouteToByteViewer() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24) { it.toByte() }
        val bin = ByteArray(48) { (255 - it).toByte() }
        val apk = zipOf(
            listOf(
                "AndroidManifest.xml" to "<?xml version=\"1.0\"?>\n<manifest package=\"com.x\"/>\n".encodeToByteArray(),
                "res/values/z.xml" to "<resources/>".encodeToByteArray(),
                "res/drawable/x.png" to png,
                "res/raw/y.bin" to bin,
            ),
        )
        val client = CoreApiDecompilerClient()
        client.open(OpenRequest("app.apk", apk))

        assertNull(client.resourceBytes(NodeId("res:AndroidManifest.xml")), "manifest stays on the decoded path")
        assertNull(client.resourceBytes(NodeId("res:res/values/z.xml")), "text res xml stays on the decoded path")
        assertContentEquals(png, client.resourceBytes(NodeId("res:res/drawable/x.png")), "png routes to bytes exactly")
        assertContentEquals(bin, client.resourceBytes(NodeId("res:res/raw/y.bin")), "raw blob routes to bytes exactly")
    }

    /** Build a small in-memory zip (DEFLATE entries) so the real client's ApkResources sees a genuine APK. */
    private fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
