package com.jadxmp.api.plugin

import com.jadxmp.input.CodeLoader
import com.jadxmp.input.dex.DexInput

/**
 * The default input plugin: DEX (`.dex`, DEX v41 containers, and `.apk`/`.jar`/`.zip` holding
 * `classes*.dex`), delegating to `core:input-dex`'s [DexInput]. `tryLoad` returns null when the bytes
 * are neither a DEX nor a zip containing one, letting the registry fall through to another plugin.
 */
object DexInputPlugin : InputPlugin {
    override val id: String = "dex"

    override fun tryLoad(name: String, bytes: ByteArray): CodeLoader? {
        val result = DexInput.load(name, bytes)
        // DexInput returns an empty result for an unrecognized container; treat that as "not mine".
        return if (result.classes.isEmpty()) null else result
    }
}
