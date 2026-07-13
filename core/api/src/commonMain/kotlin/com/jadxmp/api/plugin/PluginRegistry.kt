package com.jadxmp.api.plugin

import com.jadxmp.input.CodeLoader
import com.jadxmp.pipeline.pass.ClassPass
import com.jadxmp.pipeline.pass.MethodPass
import com.jadxmp.pipeline.pass.RootPass

/**
 * An input plugin: turns raw container bytes into the normalized [CodeLoader] the engine consumes.
 *
 * jadx: `JadxInputPlugin`. Registered **statically** (see [PluginRegistry]) — we deliberately do not
 * use `ServiceLoader`, which does not exist off-JVM (ARCHITECTURE §7).
 */
interface InputPlugin {
    /** Stable identifier, for diagnostics and ordering. */
    val id: String

    /**
     * Attempt to load [bytes] (named [name]) into a [CodeLoader]. Return null if this plugin does not
     * recognize the container, so the registry can try the next one. Implementations must not throw on
     * a merely-unrecognized input (that is a null return); a real parse failure may still throw.
     */
    fun tryLoad(name: String, bytes: ByteArray): CodeLoader?
}

/**
 * A pass plugin: contributes analysis passes on top of the built-in Phase-2 pipeline. Ordered against
 * the built-ins by each pass's own `runAfter`/`runBefore` hints (never by registration order).
 *
 * jadx: `JadxPassPlugin`. The default registry contributes none — the standard passes come from
 * `AnalysisPipeline`.
 */
interface PassPlugin {
    val id: String
    fun rootPasses(): List<RootPass> = emptyList()
    fun classPasses(): List<ClassPass> = emptyList()
    fun methodPasses(): List<MethodPass> = emptyList()
}

/**
 * The static plugin registry handed to a [com.jadxmp.api.Decompiler]. Holds the ordered input plugins
 * (first that recognizes an input wins) and any extra pass plugins. Construct one explicitly to add
 * more; [default] gives the built-in configuration (the DEX input plugin, no extra passes).
 */
class PluginRegistry(
    val inputPlugins: List<InputPlugin>,
    val passPlugins: List<PassPlugin> = emptyList(),
) {
    /** First plugin that recognizes ([name], [bytes]) wins; null if none does. */
    fun load(name: String, bytes: ByteArray): CodeLoader? {
        for (plugin in inputPlugins) {
            val loaded = plugin.tryLoad(name, bytes)
            if (loaded != null && !loaded.isEmpty) return loaded
        }
        return null
    }

    companion object {
        /** The built-in registry: DEX input, standard passes only. More can be registered by callers. */
        fun default(): PluginRegistry = PluginRegistry(inputPlugins = listOf(DexInputPlugin))
    }
}
