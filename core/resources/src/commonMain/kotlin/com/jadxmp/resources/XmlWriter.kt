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
        writeTree(sb, root)
        return sb.toString()
    }

    /**
     * Serialise the element tree with an explicit work stack instead of recursion. The AXML *parse*
     * is iterative, so a hostile file can nest elements thousands deep — a recursive writer would then
     * StackOverflowError here, and this runs *outside* BinaryXmlDecoder's try/catch. Indentation is
     * carried in a buffer grown/shrunk one level per element (never rebuilt with `INDENT.repeat(depth)`
     * per node, which made a deep-but-narrow tree O(depth^2)). Output is byte-for-byte identical to the
     * former recursive writer; the decoder additionally caps tree depth so this is never handed an
     * unbounded tree.
     */
    private fun writeTree(sb: StringBuilder, root: XmlElement) {
        val indent = StringBuilder()
        val work = ArrayDeque<Step>()
        work.addLast(Open(root))
        while (work.isNotEmpty()) {
            when (val step = work.removeLast()) {
                is Open -> openElement(sb, indent, step.element, work)
                is CloseTag -> {
                    indent.setLength(indent.length - INDENT.length)
                    sb.append(indent).append("</").append(step.name).append(">\n")
                }
                is TextLine -> sb.append(indent).append(escapeText(step.text)).append('\n')
            }
        }
    }

    private fun openElement(
        sb: StringBuilder,
        indent: StringBuilder,
        element: XmlElement,
        work: ArrayDeque<Step>,
    ) {
        sb.append(indent).append('<').append(element.name)
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
        if (children.size == 1 && children[0] is XmlText) {
            sb.append('>').append(escapeText((children[0] as XmlText).text))
            sb.append("</").append(element.name).append(">\n")
            return
        }
        sb.append(">\n")
        indent.append(INDENT)
        // Push the close first so it runs after all children; push children in reverse so LIFO pops
        // them back into document order. Text children are pre-trimmed here exactly as before.
        work.addLast(CloseTag(element.name))
        for (i in children.indices.reversed()) {
            when (val child = children[i]) {
                is XmlElement -> work.addLast(Open(child))
                is XmlText -> {
                    val t = child.text.trim()
                    if (t.isNotEmpty()) work.addLast(TextLine(t))
                }
            }
        }
    }

    /** A unit of serialisation work on [writeTree]'s explicit stack (replaces call-stack recursion). */
    private sealed interface Step
    private class Open(val element: XmlElement) : Step
    private class CloseTag(val name: String) : Step
    private class TextLine(val text: String) : Step

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
