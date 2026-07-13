package com.jadxmp.input.dex

import com.jadxmp.input.ClassData
import com.jadxmp.input.CodeLoader
import com.jadxmp.io.ByteSource
import com.jadxmp.io.ZipReader

/**
 * Entry point for the DEX input format: turns raw bytes (a `.dex`, a DEX v41 container, or an
 * APK/JAR/ZIP holding several `classes*.dex`) into the normalized [CodeLoader] the engine consumes.
 *
 * This is where the multi-container concerns are settled:
 * - **DEX v41 containers** are split into their constituent sub-dex units.
 * - **Multi-dex** (`classes.dex`, `classes2.dex`, …) is flattened into one class list.
 * - **Duplicate classes** — the same type defined in more than one dex — are resolved *first-wins*,
 *   which mirrors the Android runtime's earliest-dex-wins behavior. Duplicates are counted in
 *   [DexLoadResult.duplicateClassCount] for diagnostics.
 *
 * jadx: DexInputPlugin / DexFileLoader
 */
public object DexInput {

    /** Load a single in-memory input (a dex, a dex container, or a zip/apk). */
    public fun load(name: String, bytes: ByteArray): DexLoadResult =
        load(listOf(com.jadxmp.io.ByteArraySource(name, bytes)))

    /** Load and merge several inputs into one de-duplicated [CodeLoader]. */
    public fun load(sources: List<ByteSource>): DexLoadResult {
        val all = ArrayList<ClassData>()
        for (source in sources) {
            all.addAll(loadOne(source.name, source.readBytes()))
        }
        return dedup(all)
    }

    /** Parse one container into its classes (no cross-container de-duplication yet). */
    private fun loadOne(name: String, bytes: ByteArray): List<ClassData> = when {
        DexConsts.startsWith(bytes, DexConsts.DEX_MAGIC) -> loadDexUnits(name, bytes)
        ZipReader.isZip(bytes) || isZipName(name) -> loadZip(bytes)
        else -> emptyList()
    }

    /** Parse a dex file, expanding a v41 container into its sub-dex units. */
    private fun loadDexUnits(name: String, bytes: ByteArray): List<ClassData> {
        val offsets = DexContainer.subDexOffsets(bytes)
        val out = ArrayList<ClassData>()
        for ((i, off) in offsets.withIndex()) {
            val unitName = if (offsets.size == 1) name else "$name!$i"
            out.addAll(DexClassParser(Dex(bytes, unitName, off)).parseAll())
        }
        return out
    }

    /** Extract and parse every `.dex` entry from a zip/apk (guarded against zip-slip/zip-bomb). */
    private fun loadZip(bytes: ByteArray): List<ClassData> {
        val dexEntries = ZipReader.extract(bytes) { it.endsWith(".dex") }
        val out = ArrayList<ClassData>()
        for (entry in dexEntries) {
            if (DexConsts.startsWith(entry.bytes, DexConsts.DEX_MAGIC)) {
                out.addAll(loadDexUnits(entry.name, entry.bytes))
            }
        }
        return out
    }

    private fun dedup(classes: List<ClassData>): DexLoadResult {
        val byType = LinkedHashMap<String, ClassData>(classes.size)
        var duplicates = 0
        for (cls in classes) {
            if (byType.putIfAbsentCompat(cls.type, cls) != null) {
                duplicates++
            }
        }
        return DexLoadResult(byType.values.toList(), duplicates)
    }

    private fun isZipName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".apk") || lower.endsWith(".jar") || lower.endsWith(".zip")
    }

    // kotlin.collections has no putIfAbsent on common MutableMap; emulate first-wins insertion.
    private fun <K, V> MutableMap<K, V>.putIfAbsentCompat(key: K, value: V): V? {
        val existing = this[key]
        if (existing == null) this[key] = value
        return existing
    }
}

/**
 * The outcome of a [DexInput] load: the merged, de-duplicated classes plus how many duplicate class
 * definitions were dropped.
 *
 * jadx: DexLoadResult
 */
public class DexLoadResult(
    override val classes: List<ClassData>,
    public val duplicateClassCount: Int,
) : CodeLoader
