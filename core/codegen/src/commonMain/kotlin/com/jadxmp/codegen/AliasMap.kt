package com.jadxmp.codegen

/**
 * An immutable symbol → source-identifier override map, consumed at codegen to spell a class/member with
 * a chosen name instead of its raw (obfuscated) one. **jadx: the alias a Deobfuscator/rename stores on a
 * node (design reference only — jadxmp keeps codegen a pure read and carries the override here instead).**
 *
 * ## What it is
 * A read-only lookup keyed by the SAME [CodeNodeRef] identities the backend already records as
 * definition/reference metadata ([ClassNodeRef] by binary full name, [FieldNodeRef]/[MethodNodeRef] by
 * binary owner + name (+ erased arg descriptors)). Because the key is the metadata identity, an override
 * applies at BOTH the definition site and every reference site with no extra bookkeeping — the codegen
 * naming seams look the symbol up by the identical ref they would attach as an annotation, so a renamed
 * class/member stays coherent for find-usages, go-to-def and imports.
 *
 * ## Why immutable + built once
 * jadxmp codegen renders classes lazily and in parallel over distinct nodes, and its name spellings are
 * pure functions of the immutable model (see `JavaMemberAliases`/`JavaSourceName`). An override map must
 * therefore be COMPUTED ONCE up front (from a stable ordering of the model) and only READ during
 * rendering — never written cross-node — so the sequential and parallel paths derive identical names. This
 * type is that once-built, read-only carrier.
 *
 * ## Reuse
 * The map is intentionally populator-agnostic: the deobfuscation heuristic builds one today, and a future
 * user-driven "rename this symbol" feature will populate the very same structure (adding one entry per
 * user rename). Consumers never learn which populator produced an entry.
 *
 * ## The safety invariant
 * [EMPTY] (the default everywhere) must make every naming seam fall through to its exact pre-existing
 * behavior, so output with no overrides is byte-for-byte identical to output built without this feature.
 * Callers guarantee this by short-circuiting on [isEmpty] before doing any override-specific work.
 */
class AliasMap private constructor(private val overrides: Map<CodeNodeRef, String>) {

    /** True when no override is present — the signal every seam uses to take its identical fast path. */
    val isEmpty: Boolean get() = overrides.isEmpty()

    /** The overriding source identifier for [ref], or `null` to keep the symbol's raw (sanitized) name. */
    fun aliasOf(ref: CodeNodeRef): String? = overrides[ref]

    companion object {
        /** The no-override map: every consumer takes its byte-identical fast path. */
        val EMPTY: AliasMap = AliasMap(emptyMap())

        /**
         * Build a map from [overrides]. Returns [EMPTY] for an empty input so consumers hit the fast path
         * by identity. The map is copied defensively so a later mutation of the source cannot leak in.
         */
        fun of(overrides: Map<CodeNodeRef, String>): AliasMap =
            if (overrides.isEmpty()) EMPTY else AliasMap(overrides.toMap())
    }
}
