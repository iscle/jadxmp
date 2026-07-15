package com.jadxmp.input.dex

import com.jadxmp.io.ByteArraySource
import com.jadxmp.io.ExtractedZipEntry
import com.jadxmp.io.ZipReader

/**
 * The zip-of-APKs bundle formats jadxmp recognizes on top of a plain `.apk`/`.dex`. Each is an outer
 * zip holding a base APK plus config/split APKs; the code side is the merged, de-duplicated classes of
 * every constituent APK, and the resource side is the base APK's own `resources.arsc` + manifest.
 *
 * jadx: the `jadx-apkm-input` / `jadx-xapk-input` / `jadx-apks-input` plugins (oracle only — not copied).
 */
public enum class BundleFormat {
    /** APKMirror `.apkm`: outer zip with an `info.json` manifest + base/split APKs. */
    APKM,

    /** `.xapk`: outer zip with a `manifest.json` (`xapk_version`, `split_apks`) + APKs. */
    XAPK,

    /** `bundletool` `.apks`: outer zip of `.apk` files (`base-master.apk` + `splits/…`), no JSON manifest. */
    APKS,
}

/**
 * Detects and loads the [BundleFormat] bundle-of-APKs containers. A bundle is unpacked into its
 * constituent APKs, every APK's `classes*.dex` is collected, and the whole set is merged into one
 * de-duplicated [DexLoadResult] via the ordinary [DexInput] path (so all the zip-slip/zip-bomb guards
 * and multi-dex handling are reused unchanged).
 *
 * **Detection is content-first** (rule: robust to a renamed extension). We never trust the file name:
 * a plain APK — which carries a top-level `classes.dex`/`AndroidManifest.xml`/`resources.arsc` — is
 * explicitly *rejected* here so it stays on the plain [DexInput] path, and a real bundle (which has none
 * of those at the top level, only inner `.apk` entries) is recognized regardless of its extension.
 *
 * **Class-dedup precedence is base-first**: the base APK is loaded before any split, and [DexInput]'s
 * first-wins dedup means a class defined in both base and a split keeps the base copy. Splits contribute
 * only classes the base does not already define.
 *
 * **Fault isolation (rule 4)**: a corrupt inner APK simply parses to zero classes (its own zip read is
 * guarded and skips bad entries) and is dropped from the merge without failing the whole bundle.
 */
public object BundleInput {

    /**
     * Classify [bytes] (named [name], used only as a weak hint) as a bundle, or `null` if it is not one
     * — a plain APK/DEX/JAR, or a zip with no inner APKs. Cheap: it reads the central directory and, for
     * APKM/XAPK, the small JSON manifest entry; it does not inflate the APKs.
     */
    public fun detect(name: String, bytes: ByteArray): BundleFormat? {
        if (!ZipReader.isZip(bytes)) return null
        val names = ZipReader.entryNames(bytes)
        if (names.isEmpty()) return null
        // A plain APK carries these at the top level; a bundle never does (they live inside the inner
        // APKs). Rejecting here keeps a plain APK on the DexInput path and stops a bundle-of-one from
        // being mistaken for its own base.
        if (names.any { it == CLASSES_DEX || it == MANIFEST_XML || it == RESOURCES_ARSC }) return null
        val hasApk = names.any { it.endsWith(APK_SUFFIX, ignoreCase = true) }
        if (!hasApk) return null

        val hasInfoJson = names.any { it == INFO_JSON }
        val hasManifestJson = names.any { it == MANIFEST_JSON }
        return when {
            // APKMirror's signature file. Combined with inner APKs this is an .apkm.
            hasInfoJson && jsonLooksLike(bytes, INFO_JSON, "apkm") -> BundleFormat.APKM
            // XAPK's manifest carries xapk_version / split_apks.
            hasManifestJson && jsonLooksLike(bytes, MANIFEST_JSON, "xapk_version", "split_apks") -> BundleFormat.XAPK
            // A bare zip of APKs with no recognized JSON manifest: bundletool .apks output.
            else -> BundleFormat.APKS
        }
    }

    /**
     * Load [bytes] as a bundle into one merged, de-duplicated [DexLoadResult], or `null` if it is not a
     * bundle. The base APK is first in the merge order, so its classes win any duplicate (base-first
     * precedence). A malformed split contributes nothing rather than aborting the load (rule 4).
     */
    public fun load(name: String, bytes: ByteArray): DexLoadResult? {
        detect(name, bytes) ?: return null
        val apks = extractApks(bytes)
        if (apks.isEmpty()) return null
        // Reuse the ordinary DEX path: each APK is a zip DexInput extracts classes*.dex from, and its
        // first-wins dedup — with the base APK first — realizes base-wins precedence for free.
        return DexInput.load(apks.map { ByteArraySource(it.name, it.bytes) })
    }

    /**
     * The base APK's bytes, for resource decoding — the bundle's `resources.arsc` + `AndroidManifest.xml`
     * come from the base APK. `null` if [bytes] is not a bundle or has no APK. Only the base APK is
     * inflated (splits are left compressed), so this stays cheap for the resource path.
     */
    public fun baseApkBytes(name: String, bytes: ByteArray): ByteArray? {
        detect(name, bytes) ?: return null
        val baseName = pickBaseName(ZipReader.entryNames(bytes).filter { it.endsWith(APK_SUFFIX, ignoreCase = true) })
            ?: return null
        return ZipReader.extract(bytes) { it == baseName }.firstOrNull()?.bytes
    }

    /**
     * Extract every inner `.apk`, ordered base-APK-first (then the remaining APKs by name, for a
     * deterministic merge). The base pick is name-based (see [pickBaseName]); the ordering is what gives
     * base-first dedup precedence in [load].
     */
    internal fun extractApks(bytes: ByteArray): List<ExtractedZipEntry> {
        val apks = ZipReader.extract(bytes) { it.endsWith(APK_SUFFIX, ignoreCase = true) }
        if (apks.isEmpty()) return emptyList()
        val baseName = pickBaseName(apks.map { it.name })
        val base = apks.firstOrNull { it.name == baseName }
        val rest = apks.filter { it !== base }.sortedBy { it.name }
        return if (base != null) listOf(base) + rest else rest
    }

    /**
     * Choose the base APK by name (deterministic, format-agnostic). Prefers the conventional `base.apk` /
     * `base-master.apk` / `base-*.apk` bundletool names, otherwise the first APK that does not look like a
     * config/split, otherwise the lexicographically first APK. Name-based on purpose: identifying the base
     * by decoding each manifest's `split` attribute would mean inflating and parsing every APK.
     */
    private fun pickBaseName(apkNames: List<String>): String? {
        if (apkNames.isEmpty()) return null
        return apkNames.minWithOrNull(compareBy({ basePriority(it) }, { it }))
    }

    private fun basePriority(name: String): Int {
        val leaf = name.substringAfterLast('/').lowercase()
        return when {
            leaf == "base.apk" -> 0
            leaf == "base-master.apk" -> 1
            leaf.startsWith("base-") || leaf.startsWith("base.") || leaf == "base" -> 2
            // A config/split APK is the least preferred base candidate.
            !leaf.contains("split") && !leaf.contains("config.") && !leaf.contains(".config") -> 3
            else -> 4
        }
    }

    /** True if the small JSON entry [entryName] is present and contains any of [needles] (a cheap sniff). */
    private fun jsonLooksLike(bytes: ByteArray, entryName: String, vararg needles: String): Boolean {
        val json = ZipReader.extract(bytes) { it == entryName }.firstOrNull()?.bytes ?: return false
        val text = json.decodeToString()
        return needles.any { text.contains(it) }
    }

    private const val APK_SUFFIX = ".apk"
    private const val CLASSES_DEX = "classes.dex"
    private const val MANIFEST_XML = "AndroidManifest.xml"
    private const val RESOURCES_ARSC = "resources.arsc"
    private const val INFO_JSON = "info.json"
    private const val MANIFEST_JSON = "manifest.json"
}
