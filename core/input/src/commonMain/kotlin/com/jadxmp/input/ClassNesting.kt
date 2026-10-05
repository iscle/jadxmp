package com.jadxmp.input

/**
 * Lexical enclosure supplied by an input format. This is distinct from JVM access-control nests.
 * A null [ClassData.nesting] means unavailable metadata; [TopLevel] is authoritative evidence that
 * a dollar sign in the binary name does not denote enclosure.
 */
public sealed interface ClassNesting {
    public data object TopLevel : ClassNesting

    public data class Nested(
        /** Descriptor of the lexical enclosing class, even if it is absent from this input. */
        public val enclosingClassType: String,
        /** Null for member classes or an enclosing initializer context. */
        public val enclosingMethod: MethodRef? = null,
        /** Source inner name when recorded; null for anonymous classes or unavailable metadata. */
        public val innerName: String? = null,
    ) : ClassNesting
}
