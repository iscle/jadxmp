package com.jadxmp.api

import com.jadxmp.ir.node.IrClass

/**
 * What kind of type a class node declares — enough for the UI to pick a distinct tree badge
 * (interface vs enum vs annotation vs plain class). Derived from the raw JVM/DEX access flags of the
 * loaded model, without decompiling. jadx: the `AccessInfo` type predicates (`isInterface`/`isEnum`/
 * `isAnnotation`).
 */
enum class ClassKind {
    /** An ordinary class. */
    CLASS,

    /** An interface (`ACC_INTERFACE`, but NOT `ACC_ANNOTATION`). */
    INTERFACE,

    /** An enum (`ACC_ENUM`). */
    ENUM,

    /** An annotation type (`ACC_ANNOTATION`; the JVM also sets `ACC_INTERFACE`, so it is checked first). */
    ANNOTATION,
}

/**
 * The kind + source modifiers of a loaded class, enumerated cheaply **from the loaded model, without
 * decompiling** (ARCHITECTURE §9: the navigation tree reads this to badge each class row as an
 * interface/enum/annotation/abstract-class rather than a generic class).
 *
 * The [Modifier] set reuses the same enum as [MemberInfo]; only the modifiers a reader would see on a
 * source class declaration are surfaced (`public`/`private`/`protected` for nested classes, `static`
 * for a nested class, `final`, `abstract`). Class-only bits that are NOT source modifiers — `ACC_SUPER`
 * (0x0020, same bit as a method's `ACC_SYNCHRONIZED`), `ACC_SYNTHETIC`, `ACC_INTERFACE`, `ACC_ENUM`,
 * `ACC_ANNOTATION` — are deliberately excluded (the kind carries the interface/enum/annotation fact).
 *
 * @property kind which sort of type this class declares.
 * @property modifiers the source modifiers on the class declaration (an interface reports `abstract`
 *   because the JVM sets `ACC_ABSTRACT` on it — honest to the flags).
 * @property isInner true when the class is nested inside another (non-top-level); a top-level class
 *   whose binary name merely contains `$` but has no resolved outer stays `false`.
 */
data class ClassInfo(
    val kind: ClassKind,
    val modifiers: Set<Modifier>,
    val isInner: Boolean,
)

// ---- construction from the model (internal) ---------------------------------

// Class access-flag bits (a subset overlaps field/method bits at the same value but with class meaning).
private const val ACC_PUBLIC = 0x0001
private const val ACC_PRIVATE = 0x0002 // nested classes only
private const val ACC_PROTECTED = 0x0004 // nested classes only
private const val ACC_STATIC = 0x0008 // nested classes only
private const val ACC_FINAL = 0x0010
private const val ACC_INTERFACE = 0x0200
private const val ACC_ABSTRACT = 0x0400
private const val ACC_ANNOTATION = 0x2000
private const val ACC_ENUM = 0x4000

/**
 * Derive [ClassInfo] for [cls] from its raw access flags and its structural nesting link.
 *
 * Kind precedence is load-bearing: `ACC_ANNOTATION` is tested **before** `ACC_INTERFACE` because an
 * annotation type also sets `ACC_INTERFACE` — checking interface first would misclassify every
 * annotation as a plain interface. `ACC_ENUM` cannot co-occur with `ACC_INTERFACE`, so its order
 * relative to interface is immaterial; it is placed after annotation for symmetry.
 */
internal fun classInfoOf(cls: IrClass): ClassInfo {
    val flags = cls.accessFlags
    return ClassInfo(
        kind = classKindOf(flags),
        modifiers = classModifiersOf(flags),
        // The structural reverse link is the honest "is this nested?" signal: a top-level class whose
        // name happens to contain `$` (its outer absent from the model) keeps outerClass == null.
        isInner = cls.outerClass != null,
    )
}

/** Pure kind-from-flags mapping (see [classInfoOf] for why annotation is tested before interface). */
internal fun classKindOf(flags: Int): ClassKind = when {
    flags and ACC_ANNOTATION != 0 -> ClassKind.ANNOTATION
    flags and ACC_ENUM != 0 -> ClassKind.ENUM
    flags and ACC_INTERFACE != 0 -> ClassKind.INTERFACE
    else -> ClassKind.CLASS
}

/**
 * Source modifiers on a class declaration. Unlike [MemberInfo]'s member modifiers, this must NOT
 * consult bit 0x0020 (`ACC_SUPER` on a class, which nearly every class sets) as `synchronized`, nor
 * treat the kind bits (`interface`/`enum`/`annotation`) as modifiers.
 */
private fun classModifiersOf(flags: Int): Set<Modifier> {
    val mods = LinkedHashSet<Modifier>()
    if (flags and ACC_PUBLIC != 0) mods.add(Modifier.PUBLIC)
    if (flags and ACC_PRIVATE != 0) mods.add(Modifier.PRIVATE)
    if (flags and ACC_PROTECTED != 0) mods.add(Modifier.PROTECTED)
    if (flags and ACC_STATIC != 0) mods.add(Modifier.STATIC)
    if (flags and ACC_FINAL != 0) mods.add(Modifier.FINAL)
    if (flags and ACC_ABSTRACT != 0) mods.add(Modifier.ABSTRACT)
    return mods
}
