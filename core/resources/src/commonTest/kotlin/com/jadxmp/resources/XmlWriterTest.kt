package com.jadxmp.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmlWriterTest {

    @Test
    fun escapesTextSpecialChars() {
        assertEquals("a&lt;b&gt;&amp;c", XmlWriter.escapeText("a<b>&c"))
        // quotes and apostrophes are legal in text content and left as-is
        assertEquals("say \"hi\" it's", XmlWriter.escapeText("say \"hi\" it's"))
    }

    @Test
    fun escapesAttributeSpecialChars() {
        assertEquals("&quot;x&quot;&amp;&lt;", XmlWriter.escapeAttr("\"x\"&<"))
        // whitespace controls become numeric refs so the value stays on one line
        assertEquals("a&#10;b&#9;c", XmlWriter.escapeAttr("a\nb\tc"))
    }

    @Test
    fun escapesControlCharsAsNumericRefs() {
        assertEquals("&#1;", XmlWriter.escapeText("\u0001"))
    }

    @Test
    fun serializesTreeWithIndentAndSelfClosing() {
        val root = XmlElement("manifest").apply {
            namespaces += "android" to "http://schemas.android.com/apk/res/android"
            attributes += "package" to "com.example"
            children += XmlElement("application").apply {
                attributes += "android:label" to "Hi & Bye"
                children += XmlElement("activity").apply {
                    attributes += "android:name" to ".Main"
                }
            }
        }
        val xml = XmlWriter.write(root)
        val expected = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example">
                <application android:label="Hi &amp; Bye">
                    <activity android:name=".Main"/>
                </application>
            </manifest>
        """.trimIndent() + "\n"
        assertEquals(expected, xml)
    }

    @Test
    fun inlinesSingleTextChild() {
        val root = XmlElement("item").apply { children += XmlText("hello") }
        assertTrue(XmlWriter.write(root, declaration = false).trim() == "<item>hello</item>")
    }

    @Test
    fun serializesMixedTextAndElementChildren() {
        // Guards the iterative writer's byte-for-byte fidelity on the mixed-children branch: a trimmed
        // text line, an element sibling, and a whitespace-only text child that must be dropped.
        val root = XmlElement("root").apply {
            children += XmlText("  hello  ")
            children += XmlElement("child").apply { attributes += "k" to "v" }
            children += XmlText("   ")
        }
        val expected = """
            <root>
                hello
                <child k="v"/>
            </root>
        """.trimIndent() + "\n"
        assertEquals(expected, XmlWriter.write(root, declaration = false))
    }
}
