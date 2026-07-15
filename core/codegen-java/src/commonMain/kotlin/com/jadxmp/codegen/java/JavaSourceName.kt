package com.jadxmp.codegen.java

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.ir.node.IrClass

/**
 * The **emitted Java source name** of a top-level class — its sanitized package path and simple name —
 * as the single source of truth shared by the class body and the output file path. **jadx: Deobfuscator
 * class/package alias (design reference only).**
 *
 * ## Why this exists
 * [IrClass.fullName] is the *binary* identity (`Ldo;` → `do`, `Ldo/if/A;` → `do.if.A`). It is a valid
 * bytecode name but not always a valid *Java source* name: a segment may be a reserved word (`do`,
 * `if`), contain an illegal character (`do-`), start with a digit, or be empty. The class body already
 * renders the sanitized spelling (`class doWord`, `package doWord.ifWord`) via [JavaIdentifiers], but the
 * output `.java` file name and its `pkg/Simple.class` location are derived by `core:api` from the *binary*
 * name — so `class doWord` would be written to `do.java` and fail to recompile. This object computes the
 * name the file path must use so **the file name and the class body always agree**.
 *
 * ## Contract
 *  - **Additive.** [IrClass.fullName] stays the binary identity key (metadata refs, hierarchy walks,
 *    `IrRoot.byName`); this is a *derived view* for source emission, never a rename of the model.
 *  - **Consistent with the body.** The simple name is produced by the exact same [JavaIdentifiers.sanitize]
 *    the body emits, and the package by the same [JavaIdentifiers.sanitizeQualified] on the package line, so
 *    file path and body can never disagree. (For that reason the body's top-level class name and constructor
 *    name are routed through [sourceSimpleName], not sanitized independently.)
 *  - **Pure & deterministic.** A function of the immutable model only — no stored/mutated alias, so the
 *    sequential and parallel codegen paths derive the identical name with no shared state (mirrors
 *    [JavaMemberAliases]).
 */
internal object JavaSourceName {

    /**
     * The full dotted Java source name of top-level [cls] — sanitized package (if any) + [sourceSimpleName].
     * This is what the output file path (`pkg/Simple.java`, `pkg/Simple.class`) must be derived from.
     */
    fun sourceName(cls: IrClass, aliasMap: AliasMap = AliasMap.EMPTY): String {
        val pkg = sourcePackage(cls)
        val simple = sourceSimpleName(cls, aliasMap)
        return if (pkg.isEmpty()) simple else "$pkg.$simple"
    }

    /** The sanitized package path (each segment a valid identifier), or `""` for the default package. */
    fun sourcePackage(cls: IrClass): String {
        val binaryPackage = cls.fullName.substringBeforeLast('.', "")
        if (binaryPackage.isEmpty()) return ""
        return JavaIdentifiers.sanitizeQualified(binaryPackage)
    }

    /**
     * The emitted simple name of [cls] — the identifier written for `class X`/`X(...)`/the file base name.
     *
     * Base spelling is [JavaIdentifiers.sanitize] of the class's [IrClass.shortName], so it matches the
     * body verbatim. A **nested** class is returned as-is (its outer owns its scope; standalone-file
     * disambiguation does not apply). For a **top-level** class it is disambiguated against the other
     * top-level classes that share its sanitized package (see [disambiguatedTopLevel]).
     */
    fun sourceSimpleName(cls: IrClass, aliasMap: AliasMap = AliasMap.EMPTY): String {
        val base = classBase(cls, aliasMap)
        // Only top-level classes become their own file, so only they can collide on a file name.
        if (cls.outerClass != null) return base
        return disambiguatedTopLevel(cls, aliasMap)
    }

    /**
     * The pre-disambiguation base spelling of [cls]'s simple name: an [aliasMap] override (deobfuscation/
     * user rename) when present, else the sanitized [IrClass.shortName]. The `isEmpty` fast path keeps the
     * no-override case byte-identical to the pre-feature behavior. Renamed classes are always leaf
     * top-level (the populator's restriction), so the override is a plain, already-valid identifier.
     */
    private fun classBase(cls: IrClass, aliasMap: AliasMap): String {
        if (!aliasMap.isEmpty) aliasMap.aliasOf(ClassNodeRef(cls.fullName))?.let { return it }
        return JavaIdentifiers.sanitize(cls.shortName)
    }

    /**
     * Resolve top-level-name collisions within one package: two classes whose distinct binary names
     * sanitize to the *same* identifier (`a-b` and `a_b` → `a_b`) would otherwise be written to the same
     * file with the same body name. The first in declaration order keeps the base; each later collider is
     * suffixed (`a_b`, `a_b2`, …). Recomputed from the immutable class list (declaration order) so the
     * definition site (body) and the file-path site derive the identical name without shared state.
     *
     * Scoped to same-package siblings, so the scan is proportional to a package's size, not the whole
     * program; the common no-collision case returns the base after one pass.
     */
    private fun disambiguatedTopLevel(cls: IrClass, aliasMap: AliasMap): String {
        val pkg = sourcePackage(cls)
        val used = HashSet<String>()
        for (other in cls.root.classes) {
            if (other.outerClass != null) continue
            if (sourcePackage(other) != pkg) continue
            val name = uniqueSuffixed(classBase(other, aliasMap), used)
            if (other === cls) return name
        }
        // cls is always present in its own root, so the loop returns above; this is an unreachable guard.
        return classBase(cls, aliasMap)
    }

    /** [base] if free, else `base2`, `base3`, … — the first form not already in [used]; records the pick. */
    private fun uniqueSuffixed(base: String, used: MutableSet<String>): String {
        if (used.add(base)) return base
        var n = 2
        while (!used.add("$base$n")) n++
        return "$base$n"
    }
}
