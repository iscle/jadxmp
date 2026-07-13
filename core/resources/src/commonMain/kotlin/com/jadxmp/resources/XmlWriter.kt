package com.jadxmp.resources

/** A node in the lightweight XML tree the binary-XML decoder builds before serialisation. */
internal sealed interface XmlNode

/** Character data between elements. */
internal class XmlText(val text: String) : XmlNode

/** An XML element: its name, namespace declarations, attributes (in order), and children. */
internal class XmlElement(val name: String) : XmlNode {
    /** `xmlns` declarations as (prefix, uri); prefix is `""` for a default namespace. */
    val namespaces = mutableListOf<Pair<String, String>>()

    /** Attributes as (qualifiedName, value), preserving document order. */
    val attributes = mutableListOf<Pair<String, String>>()
    val children = mutableListOf<XmlNode>()
}

/**
 * A minimal, dependency-free XML serialiser: indentation, self-closing empty elements, inline
 * single-text elements, and correct escaping. This is the module's own writer — deliberately NOT
 * `javax.xml`/`org.w3c.dom`, so it compiles for wasmJs. jadx equivalent: the `ICodeWriter` calls in
 * `BinaryXMLParser` + `StringUtils.escapeXML`.
 */
internal object XmlWriter {
    private const val INDENT = "    "

    fun write(root: XmlElement, declaration: Boolean = true): String {
        val sb = StringBuilder()
        if (declaration) {
            sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        }
        writeElement(sb, root, 0)
        return sb.toString()
    }

    private fun writeElement(sb: StringBuilder, element: XmlElement, depth: Int) {
        val pad = INDENT.repeat(depth)
        sb.append(pad).append('<').append(element.name)
        for ((prefix, uri) in element.namespaces) {
            sb.append(" xmlns")
            if (prefix.isNotEmpty()) sb.append(':').append(prefix)
            sb.append("=\"").append(escapeAttr(uri)).append('"')
        }
        for ((name, value) in element.attributes) {
            sb.append(' ').append(name).append("=\"").append(escapeAttr(value)).append('"')
        }

        val children = element.children
        if (children.isEmpty()) {
            sb.append("/>\n")
            return
        }
        // Inline when the only child is text: <tag>text</tag>.
        val onlyText = children.size == 1 && children[0] is XmlText
        if (onlyText) {
            sb.append('>').append(escapeText((children[0] as XmlText).text))
            sb.append("</").append(element.name).append(">\n")
            return
        }
        sb.append(">\n")
        for (child in children) {
            when (child) {
                is XmlElement -> writeElement(sb, child, depth + 1)
                is XmlText -> {
                    val t = child.text.trim()
                    if (t.isNotEmpty()) sb.append(INDENT.repeat(depth + 1)).append(escapeText(t)).append('\n')
                }
            }
        }
        sb.append(pad).append("</").append(element.name).append(">\n")
    }

    /** Escape text content: `&`, `<`, `>`. */
    fun escapeText(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> appendEscaped(sb, c)
            }
        }
        return sb.toString()
    }

    /** Escape an attribute value (delimited by double quotes): text escapes plus `"`. */
    fun escapeAttr(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\n' -> sb.append("&#10;")
                '\r' -> sb.append("&#13;")
                '\t' -> sb.append("&#9;")
                else -> appendEscaped(sb, c)
            }
        }
        return sb.toString()
    }

    /** Emit disallowed C0 control chars as numeric refs; everything else verbatim (well-formed). */
    private fun appendEscaped(sb: StringBuilder, c: Char) {
        if (c.code < 0x20 && c != '\n' && c != '\r' && c != '\t') {
            sb.append("&#").append(c.code).append(';')
        } else {
            sb.append(c)
        }
    }
}
