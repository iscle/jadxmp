package com.jadxmp.resources

import com.jadxmp.resources.android.AndroidResourceMap

/**
 * One decoded resource entry: a single (id, config) row of `resources.arsc`.
 *
 * A resource id `0xPPTTEEEE` (package/type/entry) can have many entries — one per configuration
 * (default, `-en`, `-xhdpi`, …). [value] is set for a simple entry; [bag] for a complex/bag entry
 * (styles, arrays, plurals, attr definitions), with [parentRef] the parent style id (0 if none).
 */
public class ResourceEntry(
    public val id: Int,
    public val packageName: String,
    public val typeName: String,
    public val entryName: String,
    /** Config qualifier suffix, e.g. `""` (default), `"en-rUS"`, `"xhdpi"`. */
    public val config: String,
    public val value: ResourceValue?,
    public val bag: List<ResourceBagItem> = emptyList(),
    public val parentRef: Int = 0,
) {
    val isComplex: Boolean get() = value == null
}

/** A package chunk: its id (`0x7f` for the app, `0x01` for framework) and its entries. */
public class ResourcePackage(
    public val id: Int,
    public val name: String,
    public val entries: List<ResourceEntry>,
)

/**
 * The in-memory resource table produced by [ArscDecoder]: packages → entries, plus the global value
 * string pool used to resolve string-typed values.
 *
 * Query surface:
 *  - [entriesById] / [entriesByName]: raw lookups.
 *  - [symbolicName]: id → `type/name` (app) or `android:type/name` (framework) — the resolver that
 *    powers reference formatting and readable output.
 *  - [formatEntry]: an entry's value(s) as text.
 *  - [diagnostics]: non-fatal problems encountered while decoding (graceful degradation).
 */
public class ResourceTable internal constructor(
    public val packages: List<ResourcePackage>,
    internal val globalStrings: StringPool?,
    public val diagnostics: List<String>,
) {
    private val byId: Map<Int, List<ResourceEntry>> by lazy {
        val m = LinkedHashMap<Int, MutableList<ResourceEntry>>()
        for (pkg in packages) {
            for (e in pkg.entries) {
                m.getOrPut(e.id) { mutableListOf() }.add(e)
            }
        }
        m
    }

    /** All config-specific entries for a resource id, in decode order. */
    public fun entriesById(id: Int): List<ResourceEntry> = byId[id] ?: emptyList()

    /** All entries matching a `type` and `name` (e.g. `"string"`, `"app_name"`). */
    public fun entriesByName(type: String, name: String): List<ResourceEntry> =
        packages.asSequence()
            .flatMap { it.entries.asSequence() }
            .filter { it.typeName == type && it.entryName == name }
            .toList()

    /** Every distinct resource id in the table. */
    public val ids: Set<Int> get() = byId.keys

    /**
     * Resolve a resource id to its symbolic reference target: `type/name` for a resource in this
     * table, `android:type/name` for a bundled framework id, or `null` if unknown.
     */
    public fun symbolicName(id: Int): String? {
        byId[id]?.firstOrNull()?.let { return "${it.typeName}/${it.entryName}" }
        AndroidResourceMap.resName(id)?.let { return "android:$it" }
        return null
    }

    /** The full `@type/name` form (or `@android:type/name`), or `null` if the id is unknown. */
    public fun resourceRef(id: Int): String? = symbolicName(id)?.let { "@$it" }

    /**
     * Format a single value using this table's string pool and reference resolver. Framework and app
     * references resolve to `@…`/`?…` forms; unknown references degrade to `@0x…`.
     */
    public fun formatValue(value: ResourceValue): String =
        ValueFormatter.format(value, { globalStrings?.get(it) }, ::symbolicName)

    /**
     * Format an entry's value(s) as text: the simple value, or a bracketed list of the bag's
     * `name=value` pairs.
     */
    public fun formatEntry(entry: ResourceEntry): String {
        entry.value?.let { return formatValue(it) }
        return entry.bag.joinToString(prefix = "[", postfix = "]") { item ->
            val name = symbolicName(item.nameRef)?.replace('/', '.')
            val v = formatValue(item.value)
            if (name != null) "$name=$v" else v
        }
    }
}
