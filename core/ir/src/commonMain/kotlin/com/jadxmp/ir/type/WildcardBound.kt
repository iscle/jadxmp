package com.jadxmp.ir.type

/**
 * Bound direction of a generic wildcard.
 *
 * jadx: ArgType.WildcardBound
 */
enum class WildcardBound(val prefix: String) {
    /** `?` — no bound. */
    UNBOUNDED("?"),

    /** `? extends T` — upper bound. */
    EXTENDS("? extends "),

    /** `? super T` — lower bound. */
    SUPER("? super "),
}
