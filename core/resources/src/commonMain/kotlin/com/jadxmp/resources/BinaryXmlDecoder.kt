package com.jadxmp.resources

import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import com.jadxmp.resources.android.AndroidResourceMap

/** Result of decoding binary XML: the text XML plus any non-fatal diagnostics. */
public class XmlDecodeResult(
    public val xml: String,
    public val diagnostics: List<String>,
)

/**
 * Decodes Android binary XML (a binary `AndroidManifest.xml` or a compiled layout/resource XML) into
 * readable text XML.
 *
 * The input is a `RES_XML` chunk wrapping a string pool, an optional resource-map (mapping attribute
 * string indices to framework attr ids), and a stream of start-namespace / start-element / attribute
 * / end-element / cdata chunks. Attribute names resolve via the resource-map to `android:` attr
 * names where possible (jadx #1208 behaviour), and references resolve against [AndroidResourceMap]
 * plus an optional app [ResourceTable].
 *
 * Robustness: a malformed chunk is skipped with a diagnostic; the tree built so far is still emitted.
 * Everything runs through [ByteReader], so hostile input fails as a catchable [ByteReaderException].
 */
public object BinaryXmlDecoder {

    private const val ANDROID_NS_URI = "http://schemas.android.com/apk/res/android"
    private const val ANDROID_NS_PREFIX = "android"

    /**
     * Maximum element nesting kept in the tree. Real manifests/layouts nest a few dozen deep at most,
     * so this is huge headroom, while a hostile AXML that nests thousands deep is bounded here (see
     * [Decoder.parseStartElement]) so the serialised output can't blow up to O(depth^2).
     */
    private const val MAX_ELEMENT_DEPTH = 500

    /** Decode to text XML; convenience over [decodeWithDiagnostics]. Never throws for bad input. */
    public fun decode(bytes: ByteArray, table: ResourceTable? = null): String =
        decodeWithDiagnostics(bytes, table).xml

    public fun decodeWithDiagnostics(bytes: ByteArray, table: ResourceTable? = null): XmlDecodeResult {
        val diagnostics = mutableListOf<String>()
        val decoder = Decoder(table, diagnostics)
        val root = try {
            decoder.run(bytes)
        } catch (e: ByteReaderException) {
            diagnostics += "fatal: ${e.message}"
            decoder.root
        }
        val xml = if (root != null) XmlWriter.write(root) else "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        return XmlDecodeResult(xml, diagnostics)
    }

    /** Per-decode mutable state, kept off the object so decoding is thread-safe. */
    private class Decoder(
        private val table: ResourceTable?,
        private val diagnostics: MutableList<String>,
    ) {
        private var strings: StringPool? = null
        private var resourceMap: IntArray? = null

        /** uri → prefix, for resolving an attribute's namespace to its short name. */
        private val nsPrefixByUri = HashMap<String, String>()

        /** Namespace declarations seen but not yet attached to an element. */
        private val pendingNamespaces = mutableListOf<Pair<String, String>>()
        private val stack = ArrayDeque<XmlElement>()

        /** Set once when [MAX_ELEMENT_DEPTH] is first exceeded, so the diagnostic is recorded only once. */
        private var depthCapReported = false
        var root: XmlElement? = null
            private set

        fun run(bytes: ByteArray): XmlElement? {
            val reader = ByteReader(bytes)
            val header = readChunkHeader(reader)
            if (header.type != ResChunkTypes.XML) {
                diagnostics += "not a binary XML file (type=0x${header.type.toString(16)})"
                return null
            }
            iterateChunks(reader, header.bodyStart, header.end, diagnostics) { chunk ->
                when (chunk.type) {
                    ResChunkTypes.STRING_POOL ->
                        strings = StringPool.parse(reader, chunk.start, chunk.end)
                    ResChunkTypes.XML_RESOURCE_MAP -> parseResourceMap(reader, chunk)
                    ResChunkTypes.XML_START_NAMESPACE -> parseStartNamespace(reader, chunk)
                    ResChunkTypes.XML_END_NAMESPACE -> Unit // scope end: nothing to emit
                    ResChunkTypes.XML_START_ELEMENT -> parseStartElement(reader, chunk)
                    ResChunkTypes.XML_END_ELEMENT -> if (stack.isNotEmpty()) stack.removeLast()
                    ResChunkTypes.XML_CDATA -> parseCdata(reader, chunk)
                    else -> Unit
                }
            }
            return root
        }

        private fun parseResourceMap(reader: ByteReader, chunk: ChunkHeader) {
            val count = (chunk.size.toInt() - chunk.headerSize) / 4
            if (count < 0) return
            reader.seek(chunk.bodyStart)
            reader.requireAvailable(count.toLong() * 4L)
            resourceMap = IntArray(count) { reader.readS32() }
        }

        private fun parseStartNamespace(reader: ByteReader, chunk: ChunkHeader) {
            reader.seek(chunk.bodyStart)
            val prefixRef = reader.readS32()
            val uriRef = reader.readS32()
            // Sanitize the prefix at the source: it comes from an untrusted string pool and is later
            // emitted in tag position (`xmlns:PREFIX`, `PREFIX:attr`) where escaping cannot help, so a
            // hostile prefix like `p"><injected` must be neutralised before it can break out.
            val prefix = validPrefix(string(prefixRef))
            val uri = string(uriRef)
            if (uri.isNotEmpty() && nsPrefixByUri[uri] == null) {
                nsPrefixByUri[uri] = prefix
                pendingNamespaces += prefix to uri
            }
        }

        private fun parseStartElement(reader: ByteReader, chunk: ChunkHeader) {
            reader.seek(chunk.bodyStart)
            /* ns = */ reader.readS32()
            val nameRef = reader.readS32()
            val attributeStart = reader.readU16()
            val attributeSize = reader.readU16()
            val attributeCount = reader.readU16()
            reader.readU16() // idIndex
            reader.readU16() // classIndex
            reader.readU16() // styleIndex

            val element = XmlElement(validName(string(nameRef)))
            // Attach namespaces declared since the previous element (root gets the xmlns block).
            if (pendingNamespaces.isNotEmpty()) {
                element.namespaces += pendingNamespaces
                pendingNamespaces.clear()
            }

            val attrBase = chunk.bodyStart + attributeStart
            val seen = HashSet<String>()
            for (i in 0 until attributeCount) {
                reader.seek(attrBase + i * attributeSize)
                parseAttribute(reader, element, seen)
            }

            // Cap tree depth. A hostile AXML can nest START_ELEMENT chunks thousands deep; the parse
            // stays iterative (no overflow), but attaching an unbounded-depth tree would make the
            // serialised output O(depth^2) in size (indentation per line × lines) and risk OOM. Beyond
            // the cap we keep the stack balanced — so END_ELEMENT bookkeeping stays correct — but leave
            // the element detached from the tree, so only the first MAX_ELEMENT_DEPTH levels are emitted.
            if (stack.size < MAX_ELEMENT_DEPTH) {
                val parent = stack.lastOrNull()
                if (parent == null) {
                    if (root == null) root = element
                } else {
                    parent.children += element
                }
            } else if (!depthCapReported) {
                depthCapReported = true
                diagnostics += "element nesting exceeds $MAX_ELEMENT_DEPTH; deeper elements omitted"
            }
            stack.addLast(element)
        }

        private fun parseAttribute(reader: ByteReader, element: XmlElement, seen: HashSet<String>) {
            val nsRef = reader.readS32()
            val nameRef = reader.readS32()
            val rawValueRef = reader.readS32()
            reader.readU16() // typed value size
            reader.readU8() // res0
            val dataType = reader.readU8()
            val data = reader.readS32()

            val prefix = if (nsRef != -1) namespacePrefix(nsRef) else null
            val name = validName(attributeName(nameRef))
            val qualified = if (prefix != null) "$prefix:$name" else name
            if (!seen.add(qualified)) return // drop duplicate attributes (obfuscation artefact)

            val value = if (dataType == ResValueType.STRING && rawValueRef != -1) {
                string(rawValueRef)
            } else {
                ValueFormatter.format(dataType, data, ::string, ::resolveRef)
            }
            element.attributes += qualified to value
        }

        private fun parseCdata(reader: ByteReader, chunk: ChunkHeader) {
            reader.seek(chunk.bodyStart)
            val strRef = reader.readS32()
            val text = string(strRef)
            stack.lastOrNull()?.children?.add(XmlText(text))
        }

        // ---- name / value resolution ------------------------------------------------------------

        private fun string(index: Int): String {
            if (index < 0) return ""
            return strings?.get(index) ?: ""
        }

        /** Resolve an attribute's short namespace name (`android`, `app`, …) from its uri string ref. */
        private fun namespacePrefix(nsRef: Int): String {
            val uri = string(nsRef)
            if (uri.isEmpty()) return ANDROID_NS_PREFIX
            nsPrefixByUri[uri]?.let { if (it.isNotEmpty()) return it } // already sanitized when stored
            return if (uri == ANDROID_NS_URI) ANDROID_NS_PREFIX else validPrefix(uri.substringAfterLast('/'))
        }

        /**
         * Resolve an attribute name. Following jadx #1208, prefer the framework name looked up via the
         * resource-map (correct even when the string pool name is empty/obfuscated); fall back to the
         * string pool.
         */
        private fun attributeName(nameRef: Int): String {
            val map = resourceMap
            if (map != null && nameRef in map.indices) {
                AndroidResourceMap.resName(map[nameRef])?.let { return it.substringAfter('/') }
            }
            val fromPool = string(nameRef)
            if (fromPool.isNotEmpty()) return fromPool
            return "attr_0x${nameRef.toString(16)}"
        }

        /** Resolve a resource id to `type/name` (app table) or `android:type/name` (framework). */
        private fun resolveRef(id: Int): String? =
            table?.symbolicName(id) ?: AndroidResourceMap.resName(id)?.let { "android:$it" }

        /** Replace names that are not valid XML names (obfuscated tags/attrs) with a safe placeholder. */
        private fun validName(name: String): String =
            if (isValidXmlName(name)) name else "_${name.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "x" }}"

        /**
         * Sanitize a namespace prefix. An empty prefix stays empty (a default-namespace `xmlns="…"`);
         * otherwise it must be a valid, colon-free XML name or it is replaced — so a prefix drawn from
         * an untrusted APK can never inject markup into the `xmlns:` declaration or a qualified name.
         */
        private fun validPrefix(prefix: String): String {
            if (prefix.isEmpty()) return ""
            return if (isValidXmlName(prefix) && !prefix.contains(':')) prefix else validName(prefix)
        }
    }

    private fun isValidXmlName(name: String): Boolean {
        if (name.isEmpty()) return false
        val first = name[0]
        if (!(first.isLetter() || first == '_' || first == ':')) return false
        return name.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == ':' }
    }
}
