package com.jadxmp.codegen.kotlin

/**
 * JVM access-flag decoding for the Kotlin backend. **jadx: AccessInfo (Kotlin projection)**
 *
 * Kotlin's defaults differ from Java's, so this is NOT a copy of the Java modifier logic:
 *  - **Visibility**: `public` is the default and is omitted; only `private`/`protected` are spelled
 *    out. There is no JVM access flag for Kotlin's `internal`, so it is never emitted here.
 *  - **Modality**: Kotlin classes and members are `final` by default (the opposite of Java), so a
 *    non-final, non-abstract extendable class/member is emitted `open`; `abstract` stays `abstract`;
 *    `final` is the default and omitted.
 *
 * A fixed keyword order keeps output deterministic and diff-stable.
 */
internal object KotlinModifiers {
    const val PUBLIC = 0x0001
    const val PRIVATE = 0x0002
    const val PROTECTED = 0x0004
    const val STATIC = 0x0008
    const val FINAL = 0x0010
    const val SYNCHRONIZED = 0x0020
    const val VOLATILE = 0x0040
    const val TRANSIENT = 0x0080
    const val NATIVE = 0x0100
    const val INTERFACE = 0x0200
    const val ABSTRACT = 0x0400
    const val SYNTHETIC = 0x1000
    const val ANNOTATION = 0x2000
    const val ENUM = 0x4000

    fun has(flags: Int, mask: Int): Boolean = flags and mask != 0

    /** Visibility keyword with a trailing space (`private `/`protected `), or empty for public/default. */
    fun visibility(flags: Int): String = when {
        has(flags, PRIVATE) -> "private "
        has(flags, PROTECTED) -> "protected "
        else -> "" // public is Kotlin's default
    }

    /**
     * The modality keyword (`abstract `/`open `) for a class declaration, or empty. Interfaces,
     * annotations and enums manage their own modality and pass [suppressModality] = true.
     */
    fun classModality(flags: Int, suppressModality: Boolean): String = when {
        suppressModality -> ""
        has(flags, ABSTRACT) -> "abstract "
        // Kotlin classes are final by default; a non-final Java class must be `open` to stay extendable.
        !has(flags, FINAL) -> "open "
        else -> ""
    }
}
