package com.jadxmp.api

import com.jadxmp.api.plugin.PluginRegistry

/**
 * How far the pipeline takes a method before codegen.
 *
 * jadx: `DecompilationMode`.
 */
enum class DecompilationMode {
    /**
     * The full Phase-2 analysis pipeline — decode → CFG → dominators → SSA → type inference — then the
     * out-of-SSA [prepare-for-codegen][com.jadxmp.api.internal.CodegenBridge] bridge. Straight-line
     * (φ-free) methods render to compilable Java; branchy control flow is best-effort until Phase-3
     * structuring lands.
     */
    FULL,

    /**
     * Decode + CFG only — no SSA/type inference, no structuring. Codegen falls back to its linear
     * per-block form. A fast, robust degrade path for hostile or unstructurable input; not intended to
     * be compilable for arbitrary control flow.
     */
    FALLBACK,
}

/** Source language of the generated code. */
enum class OutputFormat {
    /** Java source via `core:codegen-java` — the accuracy-diff target against reference jadx. */
    JAVA,

    /**
     * Kotlin source via `core:codegen-kotlin`. Same pipeline/out-of-SSA feed and the same
     * [RenderabilityGuard][com.jadxmp.api.internal.RenderabilityGuard] honesty markers as [JAVA]; only the
     * leaf source emission differs. Its accuracy is self-measured by the oracle's kotlinc-recompile signal.
     */
    KOTLIN,
}

/**
 * Immutable configuration for a [Decompiler] run: what to produce and how much parallelism to use.
 * The input bytes themselves are supplied to [Decompiler.load], not here, so one args value can drive
 * several loads.
 *
 * jadx: `JadxArgs` (a tiny, multiplatform-safe subset).
 */
data class DecompilerArgs(
    val mode: DecompilationMode = DecompilationMode.FULL,
    val outputFormat: OutputFormat = OutputFormat.JAVA,
    /** Max classes decompiled concurrently by [DecompilerScheduler]; defaults to the platform value. */
    val parallelism: Int = defaultParallelism(),
    /** Input/pass plugins available to this run. Defaults to the built-in DEX input plugin. */
    val registry: PluginRegistry = PluginRegistry.default(),
)
