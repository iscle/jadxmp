package com.jadxmp.ir.attr

/**
 * A decompilation error attached to a node, kept as an attribute so a single bad method or class
 * degrades to a diagnostic instead of aborting the job (ARCHITECTURE §3, CONVENTIONS "Errors").
 *
 * jadx: JadxError
 */
data class DecompileError(
    val message: String,
    val cause: Throwable? = null,
)

/**
 * Registry of the attribute keys the IR itself defines. Passes declare their own keys next to the
 * pass that produces them; only cross-cutting, pass-agnostic keys live here.
 */
object IrAttrs {
    /**
     * Set on any node whose decompilation failed. Should be accompanied by [AttrFlag.HAS_ERROR].
     * Multiple layers may fail, so consumers treat presence as "at least one error".
     */
    val ERROR: AttrKey<DecompileError> = AttrKey("ERROR")
}
