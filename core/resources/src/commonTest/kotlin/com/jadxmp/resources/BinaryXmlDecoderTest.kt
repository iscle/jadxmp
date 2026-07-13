package com.jadxmp.resources

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BinaryXmlDecoderTest {

    private val table = ArscDecoder.decode(Fixtures.ARSC)

    @Test
    fun decodesRealManifest() {
        val xml = BinaryXmlDecoder.decode(Fixtures.MANIFEST, table)
        assertContains(xml, "<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        assertContains(xml, "<manifest")
        // namespace declaration recovered from the start-namespace chunk
        assertContains(xml, "xmlns:android=\"http://schemas.android.com/apk/res/android\"")
        // app package (a plain, namespaceless attribute)
        assertContains(xml, "package=\"com.github.skylot.simple\"")
        // framework attribute names resolved via the resource-map (android:versionCode etc.)
        assertContains(xml, "android:versionName=\"1.0\"")
        assertContains(xml, "android:versionCode=\"1\"")
        // nested elements
        assertContains(xml, "<application")
        assertContains(xml, "<activity")
        assertContains(xml, "android:name=\"com.github.skylot.simple.MainActivity\"")
        assertContains(xml, "<action")
        assertContains(xml, "android:name=\"android.intent.action.MAIN\"")
        assertContains(xml, "android.intent.category.LAUNCHER")
    }

    @Test
    fun resolvesAppReferenceWithTable() {
        // android:label / theme reference the app resources; with the table they become @string/@style
        val xml = BinaryXmlDecoder.decode(Fixtures.MANIFEST, table)
        assertTrue(
            xml.contains("@string/app_name") || xml.contains("@style/AppTheme"),
            "expected an app reference to resolve symbolically:\n$xml",
        )
    }

    @Test
    fun decodesRealLayout() {
        val xml = BinaryXmlDecoder.decode(Fixtures.LAYOUT, table)
        assertContains(xml, "<?xml")
        // a layout root element and some android: attributes
        assertContains(xml, "android:")
        assertTrue(xml.trim().startsWith("<?xml"))
    }

    @Test
    fun malformedInputDoesNotCrash() {
        val garbage = ByteArray(120) { (it * 7 + 3).toByte() }
        val r = BinaryXmlDecoder.decodeWithDiagnostics(garbage)
        assertTrue(r.xml.startsWith("<?xml"), "even garbage yields a well-formed declaration")
        assertTrue(r.diagnostics.isNotEmpty())

        // empty input decodes to just the XML declaration, never a crash
        assertEquals("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n", BinaryXmlDecoder.decode(ByteArray(0)))
    }

    @Test
    fun salvagesPartialTreeWhenTruncated() {
        // Truncate mid-stream and patch the root chunk size to the new length so the inner chunk
        // loop parses what it can; the manifest header + first element must still be recovered.
        val salvage = Fixtures.MANIFEST.copyOfRange(0, 1400)
        val n = salvage.size
        salvage[4] = (n and 0xff).toByte()
        salvage[5] = ((n shr 8) and 0xff).toByte()
        salvage[6] = 0
        salvage[7] = 0
        val r = BinaryXmlDecoder.decodeWithDiagnostics(salvage)
        assertContains(r.xml, "<manifest")
        assertContains(r.xml, "package=\"com.github.skylot.simple\"")
    }

    @Test
    fun hostileNamespacePrefixCannotInjectMarkup() {
        // A crafted binary XML whose START_NAMESPACE prefix is `p"><injected foo="bar` must not be
        // able to break out of the xmlns declaration or a qualified name.
        val bytes = HostileXml.withNamespacePrefix("p\"><injected foo=\"bar")
        val xml = BinaryXmlDecoder.decode(bytes)
        // No injected element, and no raw breakout characters survive in tag/name position.
        assertFalse(xml.contains("<injected"), "namespace prefix injected an element:\n$xml")
        assertFalse(xml.contains("p\"><"), "raw breakout sequence survived:\n$xml")
        // Output is exactly the well-formed document with the prefix sanitized.
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<a xmlns:_pinjectedfoobar=\"http://e\"/>\n",
            xml,
        )
    }
}
