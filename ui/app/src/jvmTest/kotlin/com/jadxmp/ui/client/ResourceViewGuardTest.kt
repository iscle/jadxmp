package com.jadxmp.ui.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the large-file view guard (P1#19): a decoded resource over [ResourceSurface.MAX_VIEW_CHARS]
 * must NOT be rendered/colorized into the viewer — a short "too large to view" placeholder is shown
 * instead, so opening a huge file never freezes the single-threaded UI on a giant text render (rule 1).
 * Lives in `jvmTest` because it allocates a ~10 MB string to cross the cap; the pure size formatter is
 * asserted here too. Also covers [ResourceSurface.megabytesOf].
 */
class ResourceViewGuardTest {

    private class SingleFileProvider(private val path: String, private val text: String?) : ResourceProvider {
        override val hasManifestEntry: Boolean = false
        override fun decodeManifest(): String? = null
        override val xmlResourcePaths: List<String> = listOf(path)
        override fun decodeXml(path: String): String? = if (path == this.path) text else null
        override val tableTypes: List<ResTableType> = emptyList()
        override fun tableEntries(type: String): List<ResTableEntry> = emptyList()
        override val diagnostics: List<String> = emptyList()
    }

    @Test
    fun oversizeResourceShowsPlaceholderNotContent() {
        val path = "res/raw/huge.xml"
        val huge = "a".repeat(ResourceSurface.MAX_VIEW_CHARS) // exactly at the cap → guarded
        val doc = ResourceSurface.document(SingleFileProvider(path, huge), NodeId("res:$path"), CodeView.JAVA)
        val rendered = doc.plainText()
        assertTrue(rendered.contains("File too large to view"), "an oversize file must show the guard placeholder")
        assertTrue(rendered.contains("MB"), "the placeholder reports the size in MB")
        assertEquals(1, doc.lines.size, "only the one-line placeholder is rendered — the huge body is never colorized")
    }

    @Test
    fun normalSizedResourceStillRenders() {
        val path = "res/layout/ok.xml"
        val doc = ResourceSurface.document(SingleFileProvider(path, "<LinearLayout/>"), NodeId("res:$path"), CodeView.JAVA)
        val rendered = doc.plainText()
        assertFalse(rendered.contains("File too large"), "a normal file is not guarded")
        assertTrue(rendered.contains("LinearLayout"), "a normal file colorizes to its content")
    }

    @Test
    fun megabytesOfFormatsWholeAndTenths() {
        assertEquals("1.0", ResourceSurface.megabytesOf(1024 * 1024))
        assertEquals("1.5", ResourceSurface.megabytesOf(1024 * 1024 + 512 * 1024))
        assertEquals("10.0", ResourceSurface.megabytesOf(10 * 1024 * 1024))
    }
}
