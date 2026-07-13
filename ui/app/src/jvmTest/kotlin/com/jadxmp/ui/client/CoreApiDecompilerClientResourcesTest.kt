package com.jadxmp.ui.client

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
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
}
