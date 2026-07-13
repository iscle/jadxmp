package com.jadxmp.api

import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.CodeNodeRef
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType

/**
 * What kind of declared member a [MemberInfo] describes — enough for the UI to pick a tree icon and to
 * decide how to render the entry. jadx: the JNode subtypes shown under a class in the tree.
 */
enum class MemberKind {
    /** An ordinary method. */
    METHOD,

    /** A constructor (`<init>`); rendered with the class's own name rather than `<init>`. */
    CONSTRUCTOR,

    /** The class's static initializer (`<clinit>`); rendered as a `static { … }` block. */
    STATIC_INITIALIZER,

    /** A field. */
    FIELD,

    /** A *reference* to a directly-nested class (not the nested class's own members). */
    NESTED_CLASS,
}

/**
 * A source-level access/declaration modifier, extracted from a member's raw JVM/DEX access-flag
 * bitmask. Only modifiers a reader would see in Java source are surfaced (compiler-internal bits such
 * as `bridge`/`varargs`/`synthetic` are not modifiers and are excluded).
 */
enum class Modifier {
    PUBLIC,
    PRIVATE,
    PROTECTED,
    STATIC,
    FINAL,
    ABSTRACT,
    SYNCHRONIZED,
    NATIVE,
    VOLATILE,
    TRANSIENT,
}

/**
 * One declared member of a class — a method, constructor, static initializer, field, or a reference to
 * a directly-nested class — enumerated cheaply **from the loaded model, without decompiling** (ARCHITECTURE
 * §9: the UI navigation tree and the Methods/Fields search scopes read this).
 *
 * ## Navigation alignment (the load-bearing contract)
 * [key] is the **same** [CodeNodeRef] the Java backend attaches as the member's `DefinitionAnnotation` in
 * the decompiled [ClassMetadata.code] (see `JavaCodeGenerator`): a method is
 * `MethodNodeRef(binaryOwner, rawName, argTypes.map { it.toString() })`, a field is
 * `FieldNodeRef(binaryOwner, name)`, a nested class is `ClassNodeRef(binaryFullName)`. So a UI that has a
 * [MemberInfo] from the tree can, once the class is decompiled, find that member's definition offset by
 * scanning [com.jadxmp.codegen.CodeMetadata] for the `DefinitionAnnotation` whose `ref == key` — and scroll
 * to it. The owner is the **binary** [IrClass.fullName] (not the emitted source name), exactly as the
 * backend records it.
 *
 * Exception: the static initializer carries a well-formed [key] but the current Java backend emits **no**
 * definition annotation for `<clinit>` (it has no signature line), so its key will not resolve to an
 * offset yet. The entry is still shown honestly; wiring a `<clinit>` definition annotation is a codegen
 * follow-up.
 *
 * ## Display consistency caveat
 * [displayName]/[signature] use the member's **raw** (sanitized-for-reading) name and source-rendered
 * types. They do NOT apply the backend's per-class collision/reserved-word aliasing
 * (`JavaMemberAliases`/`JavaSourceName`, both `internal` to `core:codegen-java`), so for the rare member
 * whose emitted identifier was disambiguated (`do` → `doWord`, a return-type-only overload suffixed
 * `foo2`) the tree label can differ from the source spelling. [key] is unaffected — it uses the binary
 * name and always aligns. Reconciling labels with the emitted source is a follow-up that needs the
 * class decompiled.
 *
 * @property kind which sort of member this is.
 * @property displayName the short label for a tree row (a method/field name; the class name for a
 *   constructor; `static` for the static initializer; the simple name for a nested class).
 * @property signature a one-line human-readable signature (`foo(int, String): boolean`, `count: int`,
 *   `Outer(String)`), best-effort and never blank.
 * @property modifiers the source modifiers on the declaration.
 * @property isSynthetic true when the member is compiler-generated — `ACC_SYNTHETIC` for any member, plus
 *   `ACC_BRIDGE` for a **method** (on a field that same bit 0x0040 is `ACC_VOLATILE`, so it is deliberately
 *   ignored there). Such members are filtered out of [Decompiler.classMembers]; the property exists so a
 *   caller that opts to show them can label them.
 * @property key the stable identity that aligns with the decompile metadata (see the class doc).
 */
data class MemberInfo(
    val kind: MemberKind,
    val displayName: String,
    val signature: String,
    val modifiers: Set<Modifier>,
    val isSynthetic: Boolean,
    val key: CodeNodeRef,
)

// ---- construction from the model (internal) ---------------------------------

// Standard JVM/DEX access-flag bits (shared by classes/fields/methods; a few bits are context-specific).
private const val ACC_PUBLIC = 0x0001
private const val ACC_PRIVATE = 0x0002
private const val ACC_PROTECTED = 0x0004
private const val ACC_STATIC = 0x0008
private const val ACC_FINAL = 0x0010
private const val ACC_SYNCHRONIZED = 0x0020 // methods only
private const val ACC_VOLATILE = 0x0040 // fields only
private const val ACC_BRIDGE = 0x0040 // methods only (same bit as VOLATILE)
private const val ACC_TRANSIENT = 0x0080 // fields only
private const val ACC_NATIVE = 0x0100 // methods only
private const val ACC_ABSTRACT = 0x0400
private const val ACC_SYNTHETIC = 0x1000

private const val CONSTRUCTOR_NAME = "<init>"
private const val STATIC_INIT_NAME = "<clinit>"

/**
 * The declared members of [cls] in a deterministic, jadx-like order — **fields, then methods, then
 * nested-class references**, each in binary declaration order (the same order the Java backend emits
 * them). Synthetic/bridge members are filtered out (they are compiler-generated, never source members),
 * with a **field/method-aware** test so a `volatile` field — whose `ACC_VOLATILE` shares bit 0x0040 with
 * a method's `ACC_BRIDGE` — is never mistaken for a bridge and dropped. Every filter decision is driven
 * by the raw access flags, so no real source member is dropped. Individual members that fail to render
 * fall back to an honest label rather than throwing, so one odd member never empties the list.
 *
 * Filtering is **not yet fully jadx-equivalent for enums**: javac does not flag an enum's synthetic
 * `values()`/`valueOf(String)`/`$VALUES` as synthetic, so they are still listed here even though jadx's
 * tree (and the enum backend) omit them — extra, harmless rows that simply won't resolve to a definition
 * offset. Enum-aware filtering is a follow-up gated on enum reconstruction landing in the backend (kept
 * decoupled from `core:codegen-java`).
 */
internal fun membersOf(cls: IrClass): List<MemberInfo> {
    val result = ArrayList<MemberInfo>(cls.fields.size + cls.methods.size + cls.innerClasses.size)
    for (field in cls.fields) {
        if (isSyntheticField(field.accessFlags)) continue
        result.add(fieldMember(cls, field))
    }
    for (method in cls.methods) {
        if (isSyntheticMethod(method.accessFlags)) continue
        result.add(methodMember(cls, method))
    }
    for (inner in cls.innerClasses) {
        // Nested classes don't collide on 0x0040, but ACC_SYNTHETIC is the only synthetic signal for them.
        if (isSyntheticField(inner.accessFlags)) continue
        result.add(nestedClassMember(inner))
    }
    return result
}

/**
 * Synthetic test for a **field** (or a class): ONLY `ACC_SYNTHETIC`. The bridge bit (0x0040) must NOT be
 * consulted here — on a field that bit is `ACC_VOLATILE`, so a `volatile` field would otherwise be
 * wrongly treated as a compiler-generated bridge and dropped from the tree (rule-4 code loss).
 */
private fun isSyntheticField(flags: Int): Boolean = flags and ACC_SYNTHETIC != 0

/** Synthetic test for a **method**: `ACC_SYNTHETIC` or `ACC_BRIDGE` (0x0040 means bridge on a method). */
private fun isSyntheticMethod(flags: Int): Boolean =
    flags and ACC_SYNTHETIC != 0 || flags and ACC_BRIDGE != 0

private fun fieldMember(cls: IrClass, field: IrField): MemberInfo {
    val typeText = safeType(field.type)
    return MemberInfo(
        kind = MemberKind.FIELD,
        displayName = field.name,
        signature = "${field.name}: $typeText",
        modifiers = modifiersOf(field.accessFlags, isField = true),
        isSynthetic = isSyntheticField(field.accessFlags),
        // Aligns with JavaCodeGenerator's FieldNodeRef(cls.fullName, field.name).
        key = FieldNodeRef(cls.fullName, field.name),
    )
}

private fun methodMember(cls: IrClass, method: IrMethod): MemberInfo {
    val params = method.argTypes.joinToString(", ") { safeType(it) }
    val kind = when (method.name) {
        CONSTRUCTOR_NAME -> MemberKind.CONSTRUCTOR
        STATIC_INIT_NAME -> MemberKind.STATIC_INITIALIZER
        else -> MemberKind.METHOD
    }
    val displayName: String
    val signature: String
    when (kind) {
        MemberKind.CONSTRUCTOR -> {
            val simple = simpleName(cls)
            displayName = simple
            signature = "$simple($params)"
        }
        MemberKind.STATIC_INITIALIZER -> {
            displayName = "static"
            signature = "static { … }"
        }
        else -> {
            displayName = method.name
            signature = "${method.name}($params): ${safeType(method.returnType)}"
        }
    }
    return MemberInfo(
        kind = kind,
        displayName = displayName,
        signature = signature,
        modifiers = modifiersOf(method.accessFlags, isField = false),
        isSynthetic = isSyntheticMethod(method.accessFlags),
        // Aligns with JavaCodeGenerator's MethodNodeRef(cls.fullName, method.name, argTypes.map { it.toString() }).
        // NB: the descriptors use the RAW type toString (as the backend does), not the display rendering.
        key = MethodNodeRef(cls.fullName, method.name, method.argTypes.map { it.toString() }),
    )
}

private fun nestedClassMember(inner: IrClass): MemberInfo {
    val simple = simpleName(inner)
    return MemberInfo(
        kind = MemberKind.NESTED_CLASS,
        displayName = simple,
        signature = simple,
        modifiers = modifiersOf(inner.accessFlags, isField = false),
        isSynthetic = isSyntheticField(inner.accessFlags),
        // Aligns with JavaCodeGenerator's ClassNodeRef(cls.fullName) for the nested class's own definition.
        key = ClassNodeRef(inner.fullName),
    )
}

/** The class's emitted simple name; a nested `$` segment is kept as the innermost segment. */
private fun simpleName(cls: IrClass): String =
    cls.fullName.substringAfterLast('.').substringAfterLast('$')

private fun modifiersOf(flags: Int, isField: Boolean): Set<Modifier> {
    val mods = LinkedHashSet<Modifier>()
    if (flags and ACC_PUBLIC != 0) mods.add(Modifier.PUBLIC)
    if (flags and ACC_PRIVATE != 0) mods.add(Modifier.PRIVATE)
    if (flags and ACC_PROTECTED != 0) mods.add(Modifier.PROTECTED)
    if (flags and ACC_STATIC != 0) mods.add(Modifier.STATIC)
    if (flags and ACC_FINAL != 0) mods.add(Modifier.FINAL)
    if (flags and ACC_ABSTRACT != 0) mods.add(Modifier.ABSTRACT)
    if (isField) {
        // 0x40 / 0x80 mean volatile/transient on a field.
        if (flags and ACC_VOLATILE != 0) mods.add(Modifier.VOLATILE)
        if (flags and ACC_TRANSIENT != 0) mods.add(Modifier.TRANSIENT)
    } else {
        // On a method the same bits mean bridge/varargs, which are not source modifiers; skip them.
        if (flags and ACC_SYNCHRONIZED != 0) mods.add(Modifier.SYNCHRONIZED)
        if (flags and ACC_NATIVE != 0) mods.add(Modifier.NATIVE)
    }
    return mods
}

/**
 * Render [type] to a short, source-like name (`int`, `String`, `Map<String, int[]>`) for the tree/search
 * label. Best-effort and fault-isolated (rule 4): any failure degrades to a plain `toString()` and, if
 * even that throws, a literal placeholder — a member's signature is never blank and never crashes the
 * enumeration.
 */
private fun safeType(type: IrType): String =
    try {
        renderSourceType(type)
    } catch (_: Throwable) {
        try {
            type.toString()
        } catch (_: Throwable) {
            "?"
        }
    }

private fun renderSourceType(type: IrType): String = when (type) {
    is IrType.Primitive -> type.toString() // int, boolean, void, …
    is IrType.Object -> {
        val simple = type.className.substringAfterLast('.').substringAfterLast('$')
        if (type.generics.isEmpty()) {
            simple
        } else {
            simple + "<" + type.generics.joinToString(", ") { renderSourceType(it) } + ">"
        }
    }
    is IrType.ArrayType -> renderSourceType(type.element) + "[]"
    is IrType.TypeVariable -> type.name
    // Wildcards and still-unresolved partial types have no clean simple form; their own toString is the
    // honest best effort (`?`, `? extends T`, `??`).
    else -> type.toString()
}
