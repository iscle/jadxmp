package com.jadxmp.ui.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure tests for [ResourceSurface]'s content-detection router — the magic-byte image sniff, the
 * text/binary classification, and the image/binary leaves [ResourceSurface.buildTree] now surfaces.
 * All exercised without Compose or an engine, since the logic is pure.
 */
class ResourceContentTest {

    // ── Image magic-byte sniffing ────────────────────────────────────────────────

    @Test
    fun imageFormatOf_recognizesPng() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00)
        assertEquals(ImageFormat.PNG, ResourceSurface.imageFormatOf(png))
    }

    @Test
    fun imageFormatOf_recognizesJpeg() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals(ImageFormat.JPEG, ResourceSurface.imageFormatOf(jpeg))
    }

    @Test
    fun imageFormatOf_recognizesGifBothVersions() {
        assertEquals(ImageFormat.GIF, ResourceSurface.imageFormatOf("GIF89a-tail".encodeToByteArray()))
        assertEquals(ImageFormat.GIF, ResourceSurface.imageFormatOf("GIF87a-tail".encodeToByteArray()))
    }

    @Test
    fun imageFormatOf_recognizesBmp() {
        assertEquals(ImageFormat.BMP, ResourceSurface.imageFormatOf("BM and more bytes".encodeToByteArray()))
    }

    @Test
    fun imageFormatOf_recognizesWebp() {
        val webp = "RIFF".encodeToByteArray() + byteArrayOf(0x1A, 0x00, 0x00, 0x00) + "WEBP".encodeToByteArray()
        assertEquals(ImageFormat.WEBP, ResourceSurface.imageFormatOf(webp))
    }

    @Test
    fun imageFormatOf_rejectsRiffThatIsNotWebp() {
        // A RIFF/WAVE audio header must NOT be mistaken for an image.
        val wav = "RIFF".encodeToByteArray() + byteArrayOf(0x00, 0x00, 0x00, 0x00) + "WAVE".encodeToByteArray()
        assertNull(ResourceSurface.imageFormatOf(wav))
    }

    @Test
    fun imageFormatOf_rejectsShortTextAndEmpty() {
        assertNull(ResourceSurface.imageFormatOf(byteArrayOf(0x89.toByte(), 0x50))) // truncated PNG magic
        assertNull(ResourceSurface.imageFormatOf("<?xml version=\"1.0\"?>".encodeToByteArray()))
        assertNull(ResourceSurface.imageFormatOf(ByteArray(0)))
    }

    // ── Content classification (image / text / hex) ──────────────────────────────

    @Test
    fun classify_imageWinsByMagicEvenWithMisleadingPath() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(ResourceContentKind.IMAGE, ResourceSurface.classifyContent("weird.txt", png))
    }

    @Test
    fun classify_plainTextIsText() {
        val text = "hello\r\n\tworld: ordinary text".encodeToByteArray()
        assertEquals(ResourceContentKind.TEXT, ResourceSurface.classifyContent("a.txt", text))
    }

    @Test
    fun classify_utf8WithHighBytesIsText() {
        // High bytes (UTF-8 lead/continuation) must not be mistaken for binary.
        val text = "café — naïve — 日本語 — emoji".encodeToByteArray()
        assertEquals(ResourceContentKind.TEXT, ResourceSurface.classifyContent("a.txt", text))
    }

    @Test
    fun classify_nulByteIsHex() {
        val bin = byteArrayOf(0x4A, 0x41, 0x00, 0x42) // "JA\u0000B"
        assertEquals(ResourceContentKind.HEX, ResourceSurface.classifyContent("a.bin", bin))
    }

    @Test
    fun classify_denseControlBytesIsHex() {
        // No NUL, but a dense run of C0 control bytes → binary.
        val bin = ByteArray(64) { (it % 7 + 1).toByte() }
        assertEquals(ResourceContentKind.HEX, ResourceSurface.classifyContent("a.bin", bin))
    }

    @Test
    fun classify_emptyIsText() {
        assertEquals(ResourceContentKind.TEXT, ResourceSurface.classifyContent("a", ByteArray(0)))
    }

    // ── Tree: image + binary leaves ──────────────────────────────────────────────

    private class BinFake(override val binaryResourcePaths: List<String>) : ResourceProvider {
        override val hasManifestEntry: Boolean = false
        override fun decodeManifest(): String? = null
        override val xmlResourcePaths: List<String> = emptyList()
        override fun decodeXml(path: String): String? {
            throw AssertionError("a binary/image leaf must never be decoded as XML")
        }
        override val tableTypes: List<ResTableType> = emptyList()
        override fun tableEntries(type: String): List<ResTableEntry> = emptyList()
        override val diagnostics: List<String> = emptyList()
    }

    @Test
    fun buildTree_surfacesImageAndBinaryLeavesUnderRes() {
        val provider = BinFake(
            binaryResourcePaths = listOf("res/drawable/ic_launcher.png", "res/raw/data.bin"),
        )
        val tree = ResourceSurface.buildTree(provider)
        assertEquals(listOf("res"), tree.roots.map { it.label })

        val resKids = tree.childrenOf(NodeId("resdir:res")).map { it.label }
        assertTrue("drawable" in resKids)
        assertTrue("raw" in resKids)

        val drawable = tree.childrenOf(NodeId("resdir:res/drawable"))
        assertEquals(1, drawable.size)
        assertEquals(NodeKind.IMAGE, drawable[0].kind) // image extension badges as IMAGE
        assertEquals("res:res/drawable/ic_launcher.png", drawable[0].id.value)

        val raw = tree.childrenOf(NodeId("resdir:res/raw"))
        assertEquals(NodeKind.FILE, raw[0].kind) // non-image binary badges as FILE
        assertEquals("res:res/raw/data.bin", raw[0].id.value)
    }

    @Test
    fun markResourceChildren_neverDecodesImageOrBinaryLeaves() {
        // The marker probe (folder expand) must skip non-xml leaves — BinFake.decodeXml throws if touched.
        val provider = BinFake(binaryResourcePaths = listOf("res/drawable/ic.png", "res/raw/d.bin"))
        val tree = ResourceSurface.buildTree(provider)
        val drawable = ResourceSurface.markResourceChildren(provider, tree.childrenOf(NodeId("resdir:res/drawable")))
        val raw = ResourceSurface.markResourceChildren(provider, tree.childrenOf(NodeId("resdir:res/raw")))
        assertNull(drawable[0].secondary)
        assertNull(raw[0].secondary)
    }

    @Test
    fun buildTree_ignoresBinaryPathsOutsideRes() {
        // Only res/ paths fold into the res tree (the buildResFolders "segs[0] == res" invariant).
        val provider = BinFake(binaryResourcePaths = listOf("assets/blob.bin"))
        val tree = ResourceSurface.buildTree(provider)
        assertTrue(tree.roots.isEmpty())
    }
}
