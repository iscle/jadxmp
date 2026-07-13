package com.jadxmp.input

/**
 * JVM/DEX access-flag bit constants, shared by classes, fields, and methods.
 *
 * Several bits are context-overloaded in the class-file/DEX spec (e.g. `0x20` is `SYNCHRONIZED` on a
 * method but `SUPER` on a class; `0x40` is `VOLATILE`/`BRIDGE`; `0x80` is `TRANSIENT`/`VARARGS`) —
 * interpret them against the member kind.
 *
 * jadx: AccessFlags
 */
public object AccessFlags {
    public const val PUBLIC: Int = 0x1
    public const val PRIVATE: Int = 0x2
    public const val PROTECTED: Int = 0x4
    public const val STATIC: Int = 0x8
    public const val FINAL: Int = 0x10
    public const val SYNCHRONIZED: Int = 0x20
    public const val SUPER: Int = 0x20
    public const val VOLATILE: Int = 0x40
    public const val BRIDGE: Int = 0x40
    public const val TRANSIENT: Int = 0x80
    public const val VARARGS: Int = 0x80
    public const val NATIVE: Int = 0x100
    public const val INTERFACE: Int = 0x200
    public const val ABSTRACT: Int = 0x400
    public const val STRICT: Int = 0x800
    public const val SYNTHETIC: Int = 0x1000
    public const val ANNOTATION: Int = 0x2000
    public const val ENUM: Int = 0x4000
    public const val MODULE: Int = 0x8000
    public const val CONSTRUCTOR: Int = 0x10000
    public const val DECLARED_SYNCHRONIZED: Int = 0x20000

    public fun has(flags: Int, flag: Int): Boolean = (flags and flag) != 0
}
