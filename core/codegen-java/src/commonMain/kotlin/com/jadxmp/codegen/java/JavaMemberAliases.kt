package com.jadxmp.codegen.java

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType

/**
 * Scope-unique Java identifiers for a class's own members. **jadx: RenameVisitor / Deobfuscator
 * member-name collision handling.**
 *
 * [JavaIdentifiers.sanitize] already makes every name a *valid* identifier and — being a pure function
 * of the raw name — makes a definition and every reference agree on spelling. What it cannot do is
 * enforce *uniqueness within a class*: two distinct members can sanitize to the same identifier and
 * produce uncompilable "already defined" output. Two shapes occur (both legal in `.dex`/`.class`,
 * illegal in Java source):
 *  - **two fields with the same name** but different types (`fieldName: String` and `fieldName: Object`);
 *  - **two methods with the same name AND parameter types** but different return types (only the return
 *    type differs, which the JVM permits but Java overloading does not).
 *
 * This object disambiguates those collisions deterministically. Legal Java overloads (same name,
 * *different* parameter types) are preserved — they are NOT collisions.
 *
 * ## Why a pure function, not a stored alias
 * Rendering is lazy and per-class (and runs in parallel over distinct class nodes). When class `A`
 * references a member of class `B`, `B` may not have been lowered yet, so an alias *stored* on `B`'s
 * node might be absent — and writing it from `A`'s render would race `B`'s own render (a cardinal-rule
 * violation: no cross-node mutation off the owning path). Instead every alias is recomputed from the
 * declaring class's member list, which is fixed at load time. The computation depends only on that
 * immutable order, so the definition site and every reference site — sequential or parallel — derive
 * the identical spelling with no shared state.
 */
internal object JavaMemberAliases {

    // ---- fields -------------------------------------------------------------

    /**
     * The unique, valid identifier for [field] within its declaring class. When [aliasMap] carries a
     * (deobfuscation/user) override for the field it is used as the base spelling *before* the same
     * within-class uniqueness pass runs — so an override that happens to clash with a kept member is still
     * disambiguated by the existing, tested machinery. An [AliasMap.EMPTY] map falls through to the exact
     * prior behavior (byte-identical output).
     */
    fun aliasOf(field: IrField, aliasMap: AliasMap = AliasMap.EMPTY): String =
        buildFieldAliases(field.declaringClass, aliasMap)[field] ?: JavaIdentifiers.sanitize(field.name)

    /**
     * The identifier a *reference* to [ref] must render. Resolves the referenced [IrField] against the
     * loaded model so it renders the exact same disambiguated alias as that field's definition (including
     * any [aliasMap] override, which is keyed by the field's declaring identity so def and use agree);
     * falls back to a plain sanitize when the field is not in the model (a library field is never renamed).
     */
    fun aliasForFieldRef(root: IrRoot?, ref: FieldRef, aliasMap: AliasMap = AliasMap.EMPTY): String {
        val field = resolveField(root, ref) ?: return JavaIdentifiers.sanitize(ref.name)
        return aliasOf(field, aliasMap)
    }

    private fun resolveField(root: IrRoot?, ref: FieldRef): IrField? {
        // Exact (name + type) anywhere in the in-model hierarchy wins over a name-only match, so a shadowed
        // super field never binds to a same-named subclass field. dex field refs point at the definer, so
        // the own-class fast path (the start of the walk) is the common case; the walk covers an inherited
        // ref that names an in-model subclass whose super declares (and may have renamed) the field.
        return resolveInHierarchy(root, ref.declaringType) { cls ->
            cls.fields.firstOrNull { it.name == ref.name && it.type == ref.type }
        } ?: resolveInHierarchy(root, ref.declaringType) { cls ->
            cls.fields.firstOrNull { it.name == ref.name }
        }
    }

    /** Field-name aliases for every field of [cls], keyed by field identity, in declaration order. */
    private fun buildFieldAliases(cls: IrClass, aliasMap: AliasMap): Map<IrField, String> {
        val used = HashSet<String>()
        val result = HashMap<IrField, String>()
        for (f in cls.fields) {
            result[f] = uniqueSuffixed(fieldBase(cls, f, aliasMap), used)
        }
        return result
    }

    /**
     * The pre-uniqueness base name of [f]: an [aliasMap] override (deobfuscation/user rename) when present,
     * else the sanitized raw name. The `isEmpty` fast path keeps the no-override case allocation-free and
     * byte-identical to the pre-feature behavior.
     */
    private fun fieldBase(cls: IrClass, f: IrField, aliasMap: AliasMap): String {
        if (!aliasMap.isEmpty) aliasMap.aliasOf(FieldNodeRef(cls.fullName, f.name))?.let { return it }
        return JavaIdentifiers.sanitize(f.name)
    }

    // ---- methods ------------------------------------------------------------

    /**
     * The unique, valid identifier for [method] within its declaring class. Constructors (`<init>`) and
     * the static initializer (`<clinit>`) are never renamed here — the backend renders them specially
     * (as the class name / a `static {}` block) — so their raw name is returned unchanged.
     */
    fun aliasOf(method: IrMethod, aliasMap: AliasMap = AliasMap.EMPTY): String {
        if (isSpecial(method.name)) return method.name
        return buildMethodAliases(method.declaringClass, aliasMap)[method] ?: JavaIdentifiers.sanitize(method.name)
    }

    /**
     * The identifier a *call* of [ref] must render. Resolves the referenced [IrMethod] against the model
     * so an invoke renders the same disambiguated alias as the method's definition (including any
     * [aliasMap] override, keyed by the method's declaring identity so call and definition agree); falls
     * back to a plain sanitize when the method is not in the model (a library/inherited method is never
     * renamed here).
     */
    fun aliasForMethodRef(root: IrRoot?, ref: MethodRef, aliasMap: AliasMap = AliasMap.EMPTY): String {
        if (isSpecial(ref.name)) return JavaIdentifiers.sanitize(ref.name)
        val method = resolveMethod(root, ref) ?: return JavaIdentifiers.sanitize(ref.name)
        return aliasOf(method, aliasMap)
    }

    private fun resolveMethod(root: IrRoot?, ref: MethodRef): IrMethod? {
        // The exact signature (name + params + return) anywhere in the in-model hierarchy wins first, then a
        // name+params match. This is what makes an INHERITED call correct: when a ref names an in-model
        // subclass but the method is declared (and possibly return-only-collision renamed) on an in-model
        // super, resolving to that super's node renders the super's alias — never a silent wrong binding.
        // An invoke's declaringType can carry the static receiver's subclass type for an inherited method,
        // so this walk is DEX-reachable. Own-class refs resolve immediately at the start of the walk.
        return resolveInHierarchy(root, ref.declaringType) { cls ->
            cls.methods.firstOrNull {
                it.name == ref.name && it.argTypes == ref.paramTypes && it.returnType == ref.returnType
            }
        } ?: resolveInHierarchy(root, ref.declaringType) { cls ->
            cls.methods.firstOrNull { it.name == ref.name && it.argTypes == ref.paramTypes }
        }
    }

    /**
     * Method-name aliases for every method of [cls], keyed by identity. Uniqueness is per (name +
     * parameter types): a real Java overload (same name, different parameters) keeps its name, while two
     * methods sharing a name AND parameter types are a hard collision and the later ones are suffixed.
     */
    private fun buildMethodAliases(cls: IrClass, aliasMap: AliasMap): Map<IrMethod, String> {
        // Set of "name(paramSignature)" already committed, so a rename never re-collides with an overload.
        val usedSignatures = HashSet<String>()
        val result = HashMap<IrMethod, String>()
        for (m in cls.methods) {
            if (isSpecial(m.name)) continue
            val base = methodBase(cls, m, aliasMap)
            val paramKey = m.argTypes.joinToString(",") { it.toString() }
            var candidate = base
            var n = 1
            while (!usedSignatures.add("$candidate($paramKey)")) {
                n++
                candidate = "$base$n"
            }
            result[m] = candidate
        }
        return result
    }

    /**
     * The pre-uniqueness base name of [m]: an [aliasMap] override (deobfuscation/user rename) when present,
     * else the sanitized raw name. Keyed by the method's declaring identity + erased arg descriptors — the
     * exact [MethodNodeRef] the backend records as metadata — so a call site resolves to the same override.
     */
    private fun methodBase(cls: IrClass, m: IrMethod, aliasMap: AliasMap): String {
        if (!aliasMap.isEmpty) {
            aliasMap.aliasOf(MethodNodeRef(cls.fullName, m.name, m.argTypes.map { it.toString() }))?.let { return it }
        }
        return JavaIdentifiers.sanitize(m.name)
    }

    // ---- shared -------------------------------------------------------------

    /**
     * Depth-first over the in-model type hierarchy starting at the class named by [type] — the class
     * itself, then its superclass, then its interfaces (in declared order), recursively — returning the
     * first [find] result. The walk order is fully determined by the immutable model (stable across runs
     * and identical on the sequential and parallel paths); a visited-set of fully-qualified names guards
     * against cycles. Only in-model classes are visited, so a library ancestor simply ends that branch
     * (the caller then falls back to a plain sanitize — a library member is never renamed).
     */
    private inline fun <T : Any> resolveInHierarchy(
        root: IrRoot?,
        type: IrType,
        find: (IrClass) -> T?,
    ): T? {
        root ?: return null
        val visited = HashSet<String>()
        // Explicit work stack (pre-order DFS) so the function can stay non-recursive and `inline`-able.
        val stack = ArrayDeque<IrClass>()
        classOf(root, type)?.let { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val cls = stack.removeLast()
            if (!visited.add(cls.fullName)) continue
            find(cls)?.let { return it }
            // Push interfaces first, then super, so super is popped (visited) before interfaces.
            for (itf in cls.interfaces.asReversed()) classOf(root, itf)?.let { stack.addLast(it) }
            classOf(root, cls.superType)?.let { stack.addLast(it) }
        }
        return null
    }

    private fun classOf(root: IrRoot?, type: IrType?): IrClass? {
        val className = (type as? IrType.Object)?.className ?: return null
        return root?.findClass(className)
    }

    private fun isSpecial(name: String): Boolean =
        name == MethodRef.CONSTRUCTOR_NAME || name == MethodRef.STATIC_INIT_NAME

    /** [base] if free, else `base2`, `base3`, … — the first form not already in [used]; records the pick. */
    private fun uniqueSuffixed(base: String, used: MutableSet<String>): String {
        if (used.add(base)) return base
        var n = 2
        while (!used.add("$base$n")) n++
        return "$base$n"
    }
}
