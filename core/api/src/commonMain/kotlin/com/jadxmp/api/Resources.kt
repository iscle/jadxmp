package com.jadxmp.api

import com.jadxmp.input.dex.BundleInput
import com.jadxmp.io.ZipReader
import com.jadxmp.resources.ArscDecoder
import com.jadxmp.resources.BinaryXmlDecoder
import com.jadxmp.resources.ResourceTable

/**
 * One row of the decoded resource table (`resources.arsc`): a single (id, config) pairing, flattened
 * for a UI resources tree and the oracle. A resource id can appear on several rows — one per
 * configuration qualifier (default, `-en`, `-xhdpi`, …).
 *
 * jadx: the per-entry view of `ResTable` rows. Mirrors `com.jadxmp.resources.ResourceEntry` but with
 * the value already formatted to text, so `core:api` consumers never touch the raw `Res_value` model.
 */
public data class ResourceEntryView(
    /** Full resource id `0xPPTTEEEE` (package / type / entry). */
    public val id: Int,
    public val packageName: String,
    /** Resource type, e.g. `"string"`, `"layout"`, `"color"`, `"style"`. */
    public val typeName: String,
    /** Entry name, e.g. `"app_name"`. */
    public val name: String,
    /** Config qualifier suffix: `""` (default), `"en-rUS"`, `"xhdpi"`, … */
    public val config: String,
    /** The value(s) rendered to text (a simple value, or a `[k=v, …]` list for a bag/style entry). */
    public val value: String,
) {
    /** The `@type/name` symbolic reference other resources use to point at this entry. */
    public val reference: String get() = "@$typeName/$name"

    /** The id as the conventional zero-padded `0xPPTTEEEE` hex string. */
    public val hexId: String get() = "0x" + id.toUInt().toString(16).padStart(8, '0')
}

/**
 * The decoded `resources.arsc` as flat [entries] plus any non-fatal [diagnostics] from decoding.
 *
 * This is the `core:api` projection of `core:resources`' richer `ResourceTable`: the UI's resources
 * tree groups these rows by [typeName]; the value column is pre-formatted. Malformed chunks are
 * salvaged (jadx #1911) — whatever decoded is here, and what did not is described in [diagnostics].
 */
public class ResourceTableView internal constructor(
    public val entries: List<ResourceEntryView>,
    public val diagnostics: List<String>,
) {
    /** Distinct resource types present, sorted — the top level of a resources tree. */
    public val types: List<String> by lazy { entries.map { it.typeName }.distinct().sorted() }

    /** Rows of one type, in decode order. */
    public fun entriesOfType(type: String): List<ResourceEntryView> = entries.filter { it.typeName == type }
}

/**
 * The resource half of a decoded APK, exposed through [Decompiler.resources]. Present only when the
 * loaded input is a zip/APK that actually carries resources; a bare `.dex`/`.jar` yields `null`.
 *
 * Decoding is **fault-isolated** (rule 4): a single malformed manifest/xml/arsc returns `null` (or an
 * error-marked table) with a diagnostic, and never crashes the surrounding load. The small, repeatedly
 * decoded entries (`AndroidManifest.xml`, `resources.arsc`, binary XML under `res/`) are held eagerly;
 * binary `res/` blobs (images, raw files) are listed by name and inflated one at a time on demand from the
 * retained container (see [binaryResourcePaths]/[rawResource]), never eagerly expanded into the heap.
 * `classes.dex`, signatures, and non-`res/` assets are not exposed here.
 */
public class ApkResources internal constructor(
    // Sanitized `/`-separated entry name → raw (already-inflated) bytes. Eagerly held, but ONLY for the
    // small, repeatedly-decoded entries (AndroidManifest.xml, resources.arsc, res/…xml). Binary res/ blobs
    // are deliberately NOT here — they are served on demand from [container] (see [rawResource]).
    private val entries: Map<String, ByteArray>,
    // The retained COMPRESSED container bytes (the base APK's own bytes) that [entries] was extracted from,
    // kept so binary res/ blobs can be listed ([binaryResourcePaths]) and inflated one entry at a time on
    // demand ([rawResource]) WITHOUT holding the whole res/ tree inflated — a real APK's res/ is tens of MB
    // expanded, far too much for a wasm heap. `null` when built from crafted entries (tests) with no backing
    // zip: then only [entries] is reachable and there are no binary res/ paths. Compressed form is the
    // minimal representation — we never keep a second, inflated copy of the archive.
    private val container: ByteArray? = null,
    diagnostics: List<String>,
) {
    // Backing store so per-file decode diagnostics (S1: a chunk-skipped XML) and an unexpected arsc-decode
    // failure (S2) can be appended after construction, rather than a partial/failed decode being silently
    // presented as complete. Single-instance, single-threaded use (see Decompiler's threading contract).
    private val diag: MutableList<String> = diagnostics.toMutableList()

    // S1: paths whose decode outcome has already been noted in [diag] (a partial-decode salvage note, or a
    // "could not be decoded" failure). Decoding is deterministic and the container is memoized, so the same
    // path yields the same outcome every call — this guard keeps repeated decodeXml/decodeManifest calls from
    // double-counting or spamming the same diagnostic. One diagnostic per failed/partial path, at most.
    private val notedXmlPaths: MutableSet<String> = mutableSetOf()

    /** Non-fatal problems noted while reading the container (e.g. a skipped unreadable entry, a partial decode). */
    public val diagnostics: List<String> get() = diag.toList()

    /**
     * True if the container carries an `AndroidManifest.xml` **entry at all**, independent of whether it
     * decodes. When this is `true` but [decodeManifest] returns `null`, the manifest is present but
     * undecodable (rule 4: it must not silently vanish) — a UI can render a "could not be decoded"
     * placeholder node instead of hiding it, and [diagnostics] carries the matching note. Cheap: reads the
     * already-extracted entry list, no re-unzip.
     */
    public val hasManifestEntry: Boolean = MANIFEST_NAME in entries

    /** The decoded `resources.arsc` table, or `null` if the APK has none / it could not be read. */
    public val table: ResourceTableView? by lazy { decodeTable() }

    /** The lower-level table, kept for reference resolution when decoding binary XML. */
    private val resTable: ResourceTable? by lazy {
        entries[ARSC_NAME]?.let { bytes ->
            // S2: ArscDecoder normally degrades a malformed table to an error-marked table (never throws),
            // but an UNEXPECTED throw here must not null the table silently — that would make a real decode
            // bug indistinguishable from "APK has no arsc". Record it; the table still degrades to null.
            runCatching { ArscDecoder.decode(bytes) }
                // S2: surface the arsc-salvage notes (skipped/recovered chunks) so a partially-decoded table is
                // not presented as complete. `lazy` runs this initializer exactly once, so the notes are added
                // exactly once no matter how often the table (or a binary XML that resolves against it) is
                // accessed — no double-count. A clean table has no diagnostics, so nothing spurious is added.
                .onSuccess { t -> diag.addAll(t.diagnostics) }
                .onFailure { e -> diag.add("resources.arsc could not be decoded: ${e.message ?: e.toString()}") }
                .getOrNull()
        }
    }

    /** Every `res/…xml` path present, sorted — the files [decodeXml] can render. */
    public val xmlResourcePaths: List<String> by lazy {
        entries.keys.filter { it.startsWith(RES_PREFIX) && it.endsWith(XML_SUFFIX) }.sorted()
    }

    /**
     * Every binary `res/` resource path present — the images and raw blobs: everything under `res/` that
     * is NOT `…xml`, sorted. Listed straight from the container's central directory ([ZipReader.entryNames])
     * with **no inflation**, so surfacing even tens of MB of drawables to the tree costs only their names
     * (rule 1 — the wasm heap). Empty when there is no backing container (a crafted-entries instance) or the
     * container carries no binary `res/` entries. These are the leaves the image/hex viewers open via
     * [rawResource]; the text `res/…xml` set stays on [xmlResourcePaths], untouched.
     */
    public val binaryResourcePaths: List<String> by lazy {
        val bytes = container ?: return@lazy emptyList()
        ZipReader.entryNames(bytes)
            .filter { it.startsWith(RES_PREFIX) && !it.endsWith(XML_SUFFIX) && !it.endsWith("/") }
            .distinct()
            .sorted()
    }

    // Membership index for [rawResource]'s on-demand gate — the same paths as [binaryResourcePaths] but as
    // a Set, so a bogus/absent path returns null in O(1) WITHOUT draining the whole archive to discover it
    // is not there. Memoized alongside the list it derives from.
    private val binaryResourcePathSet: Set<String> by lazy { binaryResourcePaths.toSet() }

    /**
     * The raw (already-inflated) bytes of a resource entry by [path], or `null` when it is absent or
     * unreadable. Fault-isolated: never throws (rule 4). Resolved in two steps:
     *  1. an eagerly-held entry (`AndroidManifest.xml`, `resources.arsc`, a `res/…xml`) is returned straight
     *     from memory — its raw compiled bytes, not decoded text;
     *  2. a binary `res/` blob (an image / raw file listed in [binaryResourcePaths]) is inflated from the
     *     retained [container] ONE entry at a time here, so the whole `res/` tree never sits inflated in the
     *     heap (rule 1 — the wasm target). The default [ZipReader] guard/zip-slip policy still applies.
     * Any other path (a non-resource entry, or nothing at all) is a cheap `null`. The image/hex viewers
     * sniff these bytes to choose a renderer.
     */
    public fun rawResource(path: String): ByteArray? {
        entries[path]?.let { return it }
        val bytes = container ?: return null
        // Gate on the known binary set so an absent/non-resource path is a cheap null rather than a wasted
        // full-archive drain; then inflate just this one wanted entry.
        if (path !in binaryResourcePathSet) return null
        return runCatching { ZipReader.extract(bytes) { it == path }.firstOrNull()?.bytes }.getOrNull()
    }

    /**
     * Decode `AndroidManifest.xml` to readable text XML, resolving `@…`/`android:` references against
     * the app table and the bundled framework map. `null` if the container has no manifest or it is not
     * decodable.
     */
    public fun decodeManifest(): String? = decodeXml(MANIFEST_NAME)

    /**
     * Decode one resource file to text. A compiled **binary** XML (`AndroidManifest.xml`, a compiled
     * layout) is decoded to text XML; a resource that is *already* plain-text XML is passed through
     * unchanged (never corrupted); anything that is neither is honestly skipped as `null`. Returns
     * `null` for an unknown [path] or a decode that yields nothing.
     */
    public fun decodeXml(path: String): String? {
        // Absent entry: nothing is present, so nothing is lost and nothing is noted (rule 4 is about
        // present-but-undecodable resources, not resources that were never there).
        val bytes = entries[path] ?: return null
        if (bytes.isEmpty()) return noteUndecodable(path)
        // Already-text XML (uncompiled): hand it back as-is rather than run it through the binary decoder.
        if (!isBinaryXml(bytes)) {
            return if (looksLikeText(bytes)) bytes.decodeToString() else noteUndecodable(path)
        }
        val result = runCatching { BinaryXmlDecoder.decodeWithDiagnostics(bytes, resTable) }.getOrNull()
            ?: return noteUndecodable(path)
        // The decoder emits a lone `<?xml …?>` shell (with a "not a binary XML file" diagnostic) when the
        // magic lied; treat that as an honest miss, not decoded content.
        if (result.diagnostics.any { it.startsWith(NOT_XML_DIAG) }) return noteUndecodable(path)
        val trimmed = result.xml.trimEnd()
        if (trimmed.substringAfter("?>").isBlank()) return noteUndecodable(path)
        // S1: a chunk-skipped / partially-decoded XML is still returned best-effort, but its per-file decode
        // diagnostics must be surfaced (not discarded by getOrNull) so a partial decode is not presented as
        // complete. Prefix with the path so the reader knows which file the diagnostic belongs to. Guard with
        // [notedXmlPaths] so repeated calls (UI re-open, oracle re-read) do not append the same notes twice.
        if (notedXmlPaths.add(path)) {
            for (d in result.diagnostics) diag.add("$path: $d")
        }
        return result.xml
    }

    // S1(a): a present-but-undecodable resource entry must leave a trace (rule 4: no silent code loss),
    // not vanish from the tree and diagnostics alike. Records one diagnostic per failed path (deduped via
    // [notedXmlPaths]) and returns `null` so callers keep their existing "null == not decodable" contract.
    private fun noteUndecodable(path: String): String? {
        if (notedXmlPaths.add(path)) diag.add("$path present but could not be decoded")
        return null
    }

    private fun decodeTable(): ResourceTableView? {
        val table = resTable ?: return null
        val rows = table.packages.asSequence()
            .flatMap { pkg -> pkg.entries.asSequence() }
            .map { e ->
                ResourceEntryView(
                    id = e.id,
                    packageName = e.packageName,
                    typeName = e.typeName,
                    name = e.entryName,
                    config = e.config,
                    value = table.formatEntry(e),
                )
            }
            .toList()
        return ResourceTableView(rows, table.diagnostics)
    }

    internal companion object {
        private const val MANIFEST_NAME = "AndroidManifest.xml"
        private const val ARSC_NAME = "resources.arsc"
        private const val RES_PREFIX = "res/"
        private const val XML_SUFFIX = ".xml"
        private const val NOT_XML_DIAG = "not a binary XML file"

        /**
         * Build the resources view for a just-loaded container. Reuses the engine's one zip reader
         * ([ZipReader], the same path `core:input-dex` uses for `classes.dex`) — no second unzip. Returns
         * `null` for a non-zip input or a zip with no resources. Guard breaches (zip bomb / too many
         * entries) surface as a `null` result plus a diagnostic collected by the caller, never a throw.
         */
        fun decode(bytes: ByteArray): ApkResources? {
            if (!ZipReader.isZip(bytes)) return null
            // A bundle (APKM/XAPK/APKS) carries no top-level resources — they live in the base APK. Point
            // resource decoding at the base APK's bytes so `AndroidManifest.xml` + `resources.arsc` load
            // exactly as they do for a plain APK; a plain APK is not a bundle so this is a no-op for it.
            val resourceBytes = BundleInput.baseApkBytes(name = "", bytes = bytes) ?: bytes
            val extracted = ZipReader.extract(resourceBytes) { name -> isResourceEntry(name) }
            if (extracted.isEmpty()) return null
            val map = LinkedHashMap<String, ByteArray>(extracted.size)
            for (entry in extracted) map[entry.name] = entry.bytes
            // Retain the (base-APK) container so binary res/ blobs can be listed + inflated on demand later,
            // without eagerly holding the whole res/ tree. `resourceBytes` already honors the bundle
            // (APKM/XAPK/APKS) base-APK redirection above, so on-demand extraction targets the same archive
            // the eager entries came from.
            return ApkResources(map, container = resourceBytes, diagnostics = emptyList())
        }

        private fun isResourceEntry(name: String): Boolean =
            name == MANIFEST_NAME ||
                name == ARSC_NAME ||
                (name.startsWith(RES_PREFIX) && name.endsWith(XML_SUFFIX))
    }
}

/** True if [bytes] begins with the `RES_XML` chunk header magic (`type=0x0003`, `headerSize=0x0008`). */
private fun isBinaryXml(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0].toInt() and 0xFF == 0x03 && bytes[1].toInt() and 0xFF == 0x00 &&
        bytes[2].toInt() and 0xFF == 0x08 && bytes[3].toInt() and 0xFF == 0x00

/** Heuristic: the first non-whitespace byte is `<`, i.e. this is plain-text XML we can pass through. */
private fun looksLikeText(bytes: ByteArray): Boolean {
    val limit = minOf(bytes.size, 64)
    for (i in 0 until limit) {
        val b = bytes[i].toInt() and 0xFF
        if (b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D || b == 0xEF || b == 0xBB || b == 0xBF) continue // ws / UTF-8 BOM
        return b == '<'.code
    }
    return false
}
