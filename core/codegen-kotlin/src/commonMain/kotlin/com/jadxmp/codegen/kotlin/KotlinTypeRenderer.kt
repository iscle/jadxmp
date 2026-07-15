package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.ImportCollector
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.type.TypeKind
import com.jadxmp.ir.type.WildcardBound

/**
 * Renders an [IrType] as Kotlin source, routing class names through the [imports] collector so they
 * come out short (with an import) or fully qualified on clash. **jadx: TypeGen (Kotlin projection)**
 *
 * Kotlin's type surface differs from Java's:
 *  - JVM primitives map to their capitalized Kotlin names (`int`→`Int`, `boolean`→`Boolean`) and
 *    `void`→`Unit`;
 *  - `java.lang.Object`→`Any`;
 *  - a **primitive array** is a dedicated class (`int[]`→`IntArray`), while a reference array is the
 *    generic `Array<T>` (`String[]`→`Array<String>`, `int[][]`→`Array<IntArray>`);
 *  - a wildcard becomes use-site variance (`?`→`*`, `? extends T`→`out T`, `? super T`→`in T`).
 *
 * Nullability is intentionally NOT inferred yet — every reference is emitted as the non-null `T`
 * (see the module TODOs); guessing `?` unsoundly would break the "semantics-preserving" goal.
 *
 * Partial/unknown types can still be present pre-inference; they render to a deterministic concrete
 * representative so output is stable.
 */
internal class KotlinTypeRenderer(
    private val imports: ImportCollector,
    // Deobfuscation/user rename overrides. [AliasMap.EMPTY] (the default) ⇒ the byte-identical
    // no-deobfuscation path: [aliasedClassName] returns every name verbatim, untouched.
    private val aliasMap: AliasMap = AliasMap.EMPTY,
    // The loaded model, needed only to spell a renamed-class *reference* by the same source-of-truth its
    // definition uses ([KotlinSourceName]). Null (and the empty map) ⇒ the byte-identical path.
    private val root: IrRoot? = null,
) {

    fun render(type: IrType): String = when (type) {
        is IrType.Primitive -> primitiveName(type.kind)
        is IrType.Object -> renderObject(type)
        is IrType.ArrayType -> renderArray(type)
        is IrType.TypeVariable -> KotlinIdentifiers.sanitize(type.name)
        is IrType.Wildcard -> renderWildcard(type)
        is IrType.Unknown -> renderUnknown(type)
    }

    /** The class name (short or FQN) for [type], without generics; also registers the import. */
    fun classNameOf(type: IrType): String = when (type) {
        is IrType.Object -> if (type.isRootObject) "Any" else KotlinIdentifiers.sanitizeQualified(imports.useClass(aliasedClassName(type.className)))
        is IrType.ArrayType -> render(type) // an array's "class" is the array type itself in Kotlin
        else -> render(type)
    }

    private fun renderArray(type: IrType.ArrayType): String {
        val element = type.element
        // A primitive-element array is a dedicated Kotlin class; everything else is Array<T>.
        if (element is IrType.Primitive) {
            return primitiveArrayName(element.kind)
        }
        return "Array<" + render(element) + ">"
    }

    private fun renderObject(type: IrType.Object): String {
        if (type.isRootObject) return "Any"
        val name = KotlinIdentifiers.sanitizeQualified(imports.useClass(aliasedClassName(type.className)))
        if (type.generics.isNotEmpty()) {
            return name + type.generics.joinToString(", ", "<", ">") { render(it) }
        }
        // A raw use of a type that Kotlin maps to a generic (`java.util.List`→`List<E>`,
        // `java.util.Map`→`Map<K, V>`, …) is rejected ("one type argument expected"). Bytecode carries
        // no generic arguments for such a raw reference, so project every parameter to `*` — the faithful
        // "unknown argument" rendering. This applies only in TYPE positions (via [render]); a bare-name
        // position (a static receiver, a constructor callee, `X::class`) goes through [classNameOf], which
        // never adds projections, because `List<*>.of(..)` / `Map<*, *>(..)` are illegal there.
        val requiredArgs = KOTLIN_MAPPED_GENERIC_ARITY[type.className]
        if (requiredArgs != null && requiredArgs > 0) {
            return name + (1..requiredArgs).joinToString(", ", "<", ">") { "*" }
        }
        return name
    }

    /**
     * Rewrite a binary class name to its deobfuscation/rename alias BEFORE it reaches [ImportCollector], so
     * a renamed class reference gets the same import / short-vs-qualified treatment as any other name and —
     * via [KotlinSourceName.sourceSimpleName] — spells the *identical* simple name the class's definition
     * uses (never a half-rename). Only a class we actually renamed is rewritten; every kept/library class is
     * returned verbatim, so the string handed to [ImportCollector] is unchanged and its decision is identical
     * to the no-deobfuscation path. The empty-map fast path makes that guarantee free. Mirrors
     * `JavaTypeRenderer.aliasedClassName`.
     *
     * A renamed class is always leaf top-level (the populator's restriction: no outer, no inner, no `$`), so
     * `package + simple` reconstructs its full name exactly and nested-reference spelling is never touched.
     */
    private fun aliasedClassName(className: String): String {
        if (aliasMap.isEmpty) return className
        if (aliasMap.aliasOf(ClassNodeRef(className)) == null) return className
        val cls = root?.findClass(className) ?: return className
        val pkg = className.substringBeforeLast('.', "")
        val simple = KotlinSourceName.sourceSimpleName(cls, aliasMap)
        return if (pkg.isEmpty()) simple else "$pkg.$simple"
    }

    private fun renderWildcard(type: IrType.Wildcard): String = when (type.bound) {
        WildcardBound.UNBOUNDED -> "*"
        WildcardBound.EXTENDS -> "out " + render(type.boundType ?: IrType.OBJECT)
        WildcardBound.SUPER -> "in " + render(type.boundType ?: IrType.OBJECT)
    }

    /** A deterministic concrete stand-in for a still-partial type. */
    private fun renderUnknown(type: IrType.Unknown): String {
        val kinds = type.possible
        if (TypeKind.OBJECT in kinds || TypeKind.ARRAY in kinds) return "Any"
        for (k in PRIMITIVE_PRIORITY) if (k in kinds) return primitiveName(k)
        return "Int"
    }

    private fun primitiveName(kind: TypeKind): String = when (kind) {
        TypeKind.BOOLEAN -> "Boolean"
        TypeKind.CHAR -> "Char"
        TypeKind.BYTE -> "Byte"
        TypeKind.SHORT -> "Short"
        TypeKind.INT -> "Int"
        TypeKind.FLOAT -> "Float"
        TypeKind.LONG -> "Long"
        TypeKind.DOUBLE -> "Double"
        TypeKind.VOID -> "Unit"
        TypeKind.OBJECT, TypeKind.ARRAY -> "Any"
    }

    private fun primitiveArrayName(kind: TypeKind): String = when (kind) {
        TypeKind.BOOLEAN -> "BooleanArray"
        TypeKind.CHAR -> "CharArray"
        TypeKind.BYTE -> "ByteArray"
        TypeKind.SHORT -> "ShortArray"
        TypeKind.INT -> "IntArray"
        TypeKind.FLOAT -> "FloatArray"
        TypeKind.LONG -> "LongArray"
        TypeKind.DOUBLE -> "DoubleArray"
        // VOID/OBJECT/ARRAY are not real primitive-array elements; fall back to a generic array.
        else -> "Array<Any>"
    }

    private companion object {
        val PRIMITIVE_PRIORITY = listOf(
            TypeKind.INT, TypeKind.LONG, TypeKind.DOUBLE, TypeKind.FLOAT,
            TypeKind.BOOLEAN, TypeKind.CHAR, TypeKind.BYTE, TypeKind.SHORT,
        )

        /**
         * JDK types that Kotlin maps onto a generic declaration, keyed to the number of type parameters
         * the mapped Kotlin type requires. A *raw* use of any of these (no generic arguments in the
         * bytecode) must be projected to `<*>`×arity or kotlinc rejects it. Only well-known JDK generics
         * are listed — an unknown user type's arity is not recoverable in the backend, so it is left raw
         * (rendering `<*>` for a non-generic type would itself be an error). **jadx: no direct analogue.**
         */
        val KOTLIN_MAPPED_GENERIC_ARITY: Map<String, Int> = buildMap {
            // Single-parameter collection & utility types.
            for (n in listOf(
                "java.lang.Iterable", "java.lang.Comparable", "java.lang.Class", "java.lang.ThreadLocal",
                "java.lang.ref.Reference", "java.lang.ref.WeakReference", "java.lang.ref.SoftReference",
                "java.util.Collection", "java.util.AbstractCollection",
                "java.util.List", "java.util.AbstractList", "java.util.ArrayList", "java.util.LinkedList",
                "java.util.Vector", "java.util.Stack",
                "java.util.Set", "java.util.AbstractSet", "java.util.HashSet", "java.util.LinkedHashSet",
                "java.util.SortedSet", "java.util.NavigableSet", "java.util.TreeSet",
                "java.util.Queue", "java.util.Deque", "java.util.ArrayDeque", "java.util.PriorityQueue",
                "java.util.Iterator", "java.util.ListIterator", "java.util.Enumeration",
                "java.util.Optional", "java.util.stream.Stream",
                "java.util.concurrent.Callable", "java.util.concurrent.Future",
                "java.util.concurrent.atomic.AtomicReference",
            )) put(n, 1)
            // Two-parameter map types.
            for (n in listOf(
                "java.util.Map", "java.util.AbstractMap", "java.util.HashMap", "java.util.LinkedHashMap",
                "java.util.SortedMap", "java.util.NavigableMap", "java.util.TreeMap",
                "java.util.Hashtable", "java.util.IdentityHashMap", "java.util.WeakHashMap",
                "java.util.concurrent.ConcurrentMap", "java.util.concurrent.ConcurrentHashMap",
            )) put(n, 2)
        }
    }
}
