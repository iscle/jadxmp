package com.jadxmp.codegen.kotlin

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
 * The Kotlin projection of the Java backend's `JavaMemberAliases`: it spells a class's own field/method
 * with its [AliasMap] override (deobfuscation / user rename) when one exists, and resolves a *reference*
 * to the same override its definition uses, keyed by the SAME [FieldNodeRef]/[MethodNodeRef] identities
 * the Java backend records — so a renamed member renders coherently at its definition and at every use in
 * BOTH source views (find-usages / go-to-def / rename align across Java and Kotlin). **jadx: RenameVisitor
 * / Deobfuscator (design reference only).**
 *
 * ## Why simpler than the Java sibling
 * `JavaMemberAliases` additionally runs a *within-class uniqueness* pass (two members that sanitize to the
 * same identifier get suffixed `x`, `x2`) because that collision is legal in `.dex`/`.class` but not in
 * Java source. The Kotlin backend never had that pass — it spells the raw name with
 * [KotlinIdentifiers.sanitize] — so this projection mirrors only the *alias* seam and leaves the raw path
 * exactly as it was. Adding the uniqueness pass here would change output for the no-alias case (breaking
 * the byte-identical guarantee) and is out of scope.
 *
 * ## The safety invariant (load-bearing)
 * An [AliasMap.EMPTY] map (the default everywhere) makes every entry point return the exact pre-feature
 * spelling — [KotlinIdentifiers.sanitize] of the raw name, with **no** model resolution — so output with
 * no overrides is byte-for-byte identical to output built without this feature. The reference resolvers
 * therefore short-circuit on [AliasMap.isEmpty] *before* touching the model: the hierarchy walk runs only
 * when overrides actually exist.
 *
 * ## Why a pure function, not a stored alias
 * Rendering is lazy and per-class (and may run in parallel over distinct class nodes). Resolving a
 * reference to its declaring member from the immutable model — rather than reading a mutable alias stored
 * on another node — means the definition site and every reference site derive the identical spelling with
 * no cross-node shared state, exactly as the Java backend does.
 */
internal object KotlinMemberAliases {

    // ---- fields -------------------------------------------------------------

    /**
     * The emitted Kotlin name of [field] at its own definition: an [aliasMap] override when present, else
     * the sanitized raw name. The `isEmpty` fast path keeps the no-override case byte-identical.
     */
    fun aliasOf(field: IrField, aliasMap: AliasMap = AliasMap.EMPTY): String {
        if (!aliasMap.isEmpty) {
            aliasMap.aliasOf(FieldNodeRef(field.declaringClass.fullName, field.name))?.let { return it }
        }
        return KotlinIdentifiers.sanitize(field.name)
    }

    /**
     * The name a *reference* to [ref] must render. With no overrides this is exactly the pre-feature
     * spelling ([KotlinIdentifiers.sanitize] of the ref's raw name) — the byte-identical fast path, taken
     * before any model lookup. Otherwise the referenced [IrField] is resolved against the loaded model so
     * the use renders the same alias as that field's definition (the override is keyed by the field's
     * *declaring* identity, so an inherited reference through a subclass still finds it); an unresolved
     * (library) field is never renamed.
     */
    fun aliasForFieldRef(root: IrRoot?, ref: FieldRef, aliasMap: AliasMap = AliasMap.EMPTY): String {
        if (aliasMap.isEmpty) return KotlinIdentifiers.sanitize(ref.name)
        val field = resolveField(root, ref) ?: return KotlinIdentifiers.sanitize(ref.name)
        return aliasOf(field, aliasMap)
    }

    private fun resolveField(root: IrRoot?, ref: FieldRef): IrField? {
        // Exact (name + type) match anywhere in the in-model hierarchy wins over a name-only match, so a
        // shadowed super field never binds to a same-named subclass field. dex field refs point at the
        // definer (own-class fast path at the start of the walk); the walk covers an inherited ref that
        // names an in-model subclass whose super declares (and may have renamed) the field.
        return resolveInHierarchy(root, ref.declaringType) { cls ->
            cls.fields.firstOrNull { it.name == ref.name && it.type == ref.type }
        } ?: resolveInHierarchy(root, ref.declaringType) { cls ->
            cls.fields.firstOrNull { it.name == ref.name }
        }
    }

    // ---- methods ------------------------------------------------------------

    /**
     * The emitted Kotlin name of [method] at its own definition: an [aliasMap] override when present, else
     * the sanitized raw name. Constructors (`<init>`) and the static initializer (`<clinit>`) are rendered
     * specially by the backend (as `constructor` / an `init {}` block), never through this seam, but they
     * are guarded here too so a stray lookup can never spell a `<init>` alias.
     */
    fun aliasOf(method: IrMethod, aliasMap: AliasMap = AliasMap.EMPTY): String {
        if (isSpecial(method.name)) return KotlinIdentifiers.sanitize(method.name)
        if (!aliasMap.isEmpty) {
            aliasMap.aliasOf(
                MethodNodeRef(method.declaringClass.fullName, method.name, method.argTypes.map { it.toString() }),
            )?.let { return it }
        }
        return KotlinIdentifiers.sanitize(method.name)
    }

    /**
     * The name a *call* of [ref] must render. With no overrides this is exactly the pre-feature spelling
     * ([KotlinIdentifiers.sanitize] of the ref's raw name) — the byte-identical fast path, taken before any
     * model lookup. Otherwise the referenced [IrMethod] is resolved against the model so the call renders
     * the same alias as the method's definition (keyed by the method's *declaring* identity, so an
     * inherited call still finds it); an unresolved (library / inherited-out-of-model) method is not
     * renamed.
     */
    fun aliasForMethodRef(root: IrRoot?, ref: MethodRef, aliasMap: AliasMap = AliasMap.EMPTY): String {
        if (aliasMap.isEmpty) return KotlinIdentifiers.sanitize(ref.name)
        if (isSpecial(ref.name)) return KotlinIdentifiers.sanitize(ref.name)
        val method = resolveMethod(root, ref) ?: return KotlinIdentifiers.sanitize(ref.name)
        return aliasOf(method, aliasMap)
    }

    private fun resolveMethod(root: IrRoot?, ref: MethodRef): IrMethod? {
        // The exact signature (name + params + return) anywhere in the in-model hierarchy wins first, then a
        // name+params match — so an inherited call whose ref names an in-model subclass, but whose method is
        // declared (and possibly renamed) on an in-model super, resolves to that super's node and renders
        // the super's alias. Own-class refs resolve immediately at the start of the walk.
        return resolveInHierarchy(root, ref.declaringType) { cls ->
            cls.methods.firstOrNull {
                it.name == ref.name && it.argTypes == ref.paramTypes && it.returnType == ref.returnType
            }
        } ?: resolveInHierarchy(root, ref.declaringType) { cls ->
            cls.methods.firstOrNull { it.name == ref.name && it.argTypes == ref.paramTypes }
        }
    }

    // ---- shared -------------------------------------------------------------

    /**
     * Depth-first over the in-model type hierarchy starting at the class named by [type] — the class
     * itself, then its superclass, then its interfaces (declared order), recursively — returning the first
     * [find] result. The walk order is fully determined by the immutable model (identical across runs and
     * on sequential/parallel paths); a visited-set of full names guards against cycles. Only in-model
     * classes are visited, so a library ancestor ends that branch (the caller then keeps the raw name — a
     * library member is never renamed). Mirrors `JavaMemberAliases.resolveInHierarchy`.
     */
    private inline fun <T : Any> resolveInHierarchy(
        root: IrRoot?,
        type: IrType,
        find: (IrClass) -> T?,
    ): T? {
        root ?: return null
        val visited = HashSet<String>()
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
}
