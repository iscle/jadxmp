package com.jadxmp.api.plugin

import com.jadxmp.input.CodeLoader
import com.jadxmp.input.dex.BundleInput

/**
 * Input plugin for the zip-of-APKs bundle formats — APKM (APKMirror), XAPK, and APKS (bundletool split
 * zip). A bundle is unpacked into its base + config/split APKs, every APK's `classes*.dex` is collected,
 * and the whole set is merged (base-first, so the base wins any duplicate class) into one [CodeLoader]
 * by [BundleInput], delegating to `core:input-dex`.
 *
 * Registered **before** [DexInputPlugin] (see [PluginRegistry.default]). Detection is content-first:
 * [BundleInput.detect] returns null for a plain APK/DEX/JAR (they carry a top-level `classes.dex` a
 * bundle never has), so a plain APK falls straight through to [DexInputPlugin] unchanged, and only a
 * real bundle is claimed here. `tryLoad` returns null when the bytes are not a bundle.
 */
object BundleInputPlugin : InputPlugin {
    override val id: String = "bundle"

    override fun tryLoad(name: String, bytes: ByteArray): CodeLoader? {
        val result = BundleInput.load(name, bytes) ?: return null
        // A recognized bundle that yields no code (all splits resource-only) is "not mine" so the
        // registry can still try DexInput; a normal bundle returns its merged classes here.
        return if (result.classes.isEmpty()) null else result
    }
}
