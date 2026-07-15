package com.jadxmp.pipeline.types

import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType

/**
 * Resolves the reference-subtype questions the `core:ir` lattice deliberately leaves open: two
 * distinct named classes are [com.jadxmp.ir.type.TypeRelation.UNRELATED] there because their subtype
 * edge needs a class graph, which this module supplies from the loaded [IrRoot].
 *
 * Only classes present in [IrRoot] contribute edges; unloaded library types (e.g. `java.lang.String`)
 * are known to be subtypes of `java.lang.Object` and otherwise treated as unrelated. That keeps
 * results conservative — never claiming a subtype relation we cannot justify — which is what the type
 * engine needs to avoid emitting an invalid cast.
 */
class ClassHierarchy(private val root: IrRoot) {

    /** Is [sub] assignable to [sup] (i.e. `sub` a subtype of `sup`), as far as the loaded graph shows? */
    fun isSubtype(sub: IrType, sup: IrType): Boolean {
        if (sub == sup) return true
        if (sup == IrType.OBJECT) return isReference(sub)
        if (sub is IrType.ArrayType && sup is IrType.ArrayType) {
            // Reference-element arrays are covariant; primitive-element arrays are invariant.
            val se = sub.element
            val pe = sup.element
            if (se is IrType.Primitive || pe is IrType.Primitive) return se == pe
            return isSubtype(se, pe)
        }
        val subName = (sub as? IrType.Object)?.className ?: return false
        val supName = (sup as? IrType.Object)?.className ?: return false
        if (subName == supName) return true
        return supName in allSupertypeNames(subName)
    }

    /**
     * A best-effort common supertype (join) of [a] and [b] — used for φ merges. Returns the more
     * general of the two when one is assignable to the other, else the nearest loaded common ancestor,
     * falling back to `java.lang.Object`.
     */
    fun commonSuperType(a: IrType, b: IrType): IrType {
        if (a == b) return a
        if (!isReference(a) || !isReference(b)) return IrType.OBJECT
        if (isSubtype(a, b)) return b
        if (isSubtype(b, a)) return a
        val aName = (a as? IrType.Object)?.className ?: return IrType.OBJECT
        val bName = (b as? IrType.Object)?.className ?: return IrType.OBJECT
        // Walk a's ancestor chain (nearest first); the first that b is assignable to is the join.
        for (name in supertypeChain(aName)) {
            if (isSubtype(b, IrType.objectType(name))) return IrType.objectType(name)
        }
        return IrType.OBJECT
    }

    /** Whether [type] names a class present in the loaded model (so its subtype edges are complete). */
    fun isLoaded(type: IrType): Boolean {
        val name = (type as? IrType.Object)?.className ?: return false
        return root.findClass(name) != null
    }

    /**
     * Whether [type] is a **provably-final** reference class: a loaded `ACC_FINAL` class, or one of the
     * well-known final JDK reference types (which are not in the loaded model but are final by definition).
     * A final class has no subtypes other than itself, so nothing but itself is assignable to it.
     */
    fun isFinalReference(type: IrType): Boolean {
        val name = (type as? IrType.Object)?.className ?: return false
        root.findClass(name)?.let { return it.accessFlags and ACC_FINAL != 0 }
        return name in KNOWN_FINAL_REFS
    }

    /**
     * Whether [sub] is **provably NOT** a subtype of [sup] — distinguished from merely *not provably* a
     * subtype (the conservative default for two unloaded, unrelated-looking types). Proof comes from two
     * sources: (1) [sup] is a final class, so only [sup] itself is assignable to it and any distinct [sub]
     * is definitely not; or (2) both are loaded, so a negative [isSubtype] is taken as authoritative. When
     * neither holds the relation is UNKNOWN and this returns false — the caller must then treat the pair as
     * *possibly compatible* (never as a conflict).
     *
     * Caveat on (2): the negative is only truly authoritative when the whole ancestor chain of [sub] is
     * loaded; a subtype relation routed through an *unloaded* intermediate would be missed and this could
     * over-report. That chain (a loaded input class reached via an unloaded library link) doesn't arise in
     * practice, and even if it did the sole consequence is an over-eager param split → `Object` widening,
     * which stays compilable (rule-4 safe) — never a miscompile.
     */
    fun provablyNotSubtype(sub: IrType, sup: IrType): Boolean {
        if (isSubtype(sub, sup)) return false
        if (sub !is IrType.Object || sup !is IrType.Object) return false
        if (isFinalReference(sup)) return true // final sup ⇒ only sup is assignable; sub≠sup ⇒ not assignable
        return isLoaded(sub) && isLoaded(sup) // both loaded ⇒ negative isSubtype taken as authoritative (see caveat above)
    }

    private fun isReference(t: IrType): Boolean =
        t is IrType.Object || t is IrType.ArrayType || t is IrType.TypeVariable || t is IrType.Wildcard ||
            (t is IrType.Unknown && t.possible.all { it.isReference })

    /** Direct + transitive supertype names (superclass and interfaces) from the loaded graph. */
    private fun allSupertypeNames(className: String): Set<String> {
        val out = LinkedHashSet<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(className)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val cls = root.findClass(cur) ?: continue
            (cls.superType as? IrType.Object)?.className?.let { if (out.add(it)) stack.addLast(it) }
            for (itf in cls.interfaces) {
                (itf as? IrType.Object)?.className?.let { if (out.add(it)) stack.addLast(it) }
            }
        }
        out.add(IrType.OBJECT_CLASS)
        return out
    }

    /** [className] then its supertypes in nearest-first order (superclass before interfaces). */
    private fun supertypeChain(className: String): List<String> {
        val result = ArrayList<String>()
        val seen = HashSet<String>()
        var cur: String? = className
        // Superclass spine first (nearest ancestors), then interfaces, then Object.
        while (cur != null && seen.add(cur)) {
            result.add(cur)
            cur = (root.findClass(cur)?.superType as? IrType.Object)?.className
        }
        result.addAll(allSupertypeNames(className))
        val ordered = LinkedHashSet(result)
        ordered.add(IrType.OBJECT_CLASS)
        return ordered.toList()
    }

    private companion object {
        /** JVM `ACC_FINAL`. */
        const val ACC_FINAL = 0x0010

        /**
         * Well-known JDK reference types that are `final` (no subtypes). Absent from the loaded model, but
         * their finality is fixed by the platform — the boxed primitives, `String`, and the other common
         * final classes that surface as coalescing conflicts (autoboxing / string builders).
         */
        val KNOWN_FINAL_REFS: Set<String> = setOf(
            "java.lang.String",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Character", "java.lang.Boolean", "java.lang.Float", "java.lang.Double",
            "java.lang.Class", "java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.Void",
        )
    }
}
