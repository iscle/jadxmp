package com.jadxmp.api

import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.CodeNodeRef
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrRoot

/**
 * The session-local store of **user-chosen** renames for one loaded [Decompiler]. It is the second
 * populator of the codegen [com.jadxmp.codegen.AliasMap] (the deobfuscation heuristic being the first):
 * every accepted rename is one `CodeNodeRef → name` override, merged — with **precedence over the
 * deobfuscation auto-map** — into the single effective alias map codegen already reads at every
 * definition and use site. Because the map key is the exact symbolic identity the backend records as
 * metadata (and find-usages inverts), one entry renames a symbol everywhere with no codegen change.
 *
 * ## Why the store lives here, not in codegen
 * A rename must be *validated and collision-checked against the loaded model* before it is allowed, which
 * needs the IR — a `core:api` concern. Codegen stays a pure read of the immutable model + the map. The
 * store holds only accepted overrides; the map it feeds is rebuilt (and the render caches invalidated) by
 * the [Decompiler] on every change.
 *
 * ## Validation invariants (why each check exists)
 * Codegen copies a user override into the source **verbatim** (it does *not* re-sanitize an alias the way
 * it does a raw name), so this store is the sole guard that an override is emittable:
 *  - **Legal identifier.** Exactly `[A-Za-z_][A-Za-z0-9_]*` — the subset every Java version accepts as a
 *    plain identifier with no sanitization. `$` is deliberately excluded (it is the binary nested-name
 *    separator; keeping it out of user names keeps class-reference reconstruction unambiguous).
 *  - **Not a reserved word.** The full JLS keyword set (plus the literals and contextual keywords codegen
 *    itself treats as reserved), so a rename to `int`/`var`/`_` is rejected rather than emitted as
 *    `class int {`.
 *  - **Collision-free within scope.** Mirrors the deobfuscation `Allocator`, which reserves the *raw*
 *    sibling names: a class rename must not equal another top-level class's effective name in the same
 *    package; a field rename must not equal a sibling field's; a method rename must not equal a sibling
 *    method's **of the same parameter types** (a different-parameter overload is legal, not a collision).
 *    "Effective name" folds in prior user renames and the deobfuscation map, so renaming onto a name a
 *    sibling has itself vacated (it was deobfuscated away) is correctly allowed.
 *
 * ## Renamable shapes (this version)
 * Only symbols the codegen alias seams can spell coherently at *every* reference:
 *  - **Classes:** leaf top-level only (no outer, no inner, no `$` in the simple name) — the same shape the
 *    deobfuscator renames, because a class *reference* is rewritten by reconstructing `package + simple`,
 *    which is exact only for a flat name. Nested/inner class rename is a documented follow-up.
 *  - **Fields / methods:** any field/method of a plain class or interface. Constructors and the static
 *    initializer are spelled specially by codegen and are never renamed; members of enum/annotation types
 *    are left to their dedicated reconstructors (mirrors the deobfuscator's wholesale skip).
 *
 * Not thread-safe: it is mutated only on the [Decompiler]'s single-threaded cached path, alongside the
 * class cache it shares invalidation with.
 */
internal class UserRenameStore {

    // Insertion-ordered so [snapshot]/[overrides] enumerate deterministically (a UI list, a future .jadx
    // persistence). Keyed by the SAME CodeNodeRef identity codegen looks up and find-usages inverts.
    private val overrides = LinkedHashMap<CodeNodeRef, String>()

    /** True when the user has applied no renames — the [Decompiler] uses this to keep the map deobf-only. */
    val isEmpty: Boolean get() = overrides.isEmpty()

    /**
     * The accepted overrides, in application order. Returned as a read-only view for merging into the
     * effective alias map; `AliasMap.of` copies defensively, so the live map never leaks into codegen.
     */
    fun overrides(): Map<CodeNodeRef, String> = overrides

    /** An immutable snapshot for enumeration (UI list / future persistence), independent of later renames. */
    fun snapshot(): Map<CodeNodeRef, String> = LinkedHashMap(overrides)

    /** Drop every user rename (the [Decompiler] then rebuilds the map back to deobf-only / empty). */
    fun clear() {
        overrides.clear()
    }

    /**
     * Validate [newName] and resolve [target] against [root], and on success record the override and return
     * [RenameResult.Applied]. On any rejection the store is left **unchanged** and the reason is returned.
     * [deobfOverrides] is the deobfuscation auto-map, consulted only to compute siblings' *effective* names
     * for the collision check (user renames already recorded here take precedence over it).
     */
    fun tryRename(
        root: IrRoot,
        deobfOverrides: Map<CodeNodeRef, String>,
        target: CodeNodeRef,
        newName: String,
    ): RenameResult {
        validateName(newName)?.let { return RenameResult.InvalidName(target, newName, it) }
        return when (target) {
            is ClassNodeRef -> renameClass(root, deobfOverrides, target, newName)
            is FieldNodeRef -> renameField(root, deobfOverrides, target, newName)
            is MethodNodeRef -> renameMethod(root, deobfOverrides, target, newName)
            else -> RenameResult.UnrenamableTarget(target, "only classes, fields and methods can be renamed")
        }
    }

    // ---- per-kind resolution + collision ------------------------------------

    private fun renameClass(
        root: IrRoot,
        deobf: Map<CodeNodeRef, String>,
        target: ClassNodeRef,
        newName: String,
    ): RenameResult {
        val cls = root.findClass(target.fullName) ?: return reject(target, "class is not in the loaded model")
        if (cls.outerClass != null || cls.innerClasses.isNotEmpty() || '$' in cls.shortName) {
            return reject(target, "only a leaf top-level class can be renamed yet (nested/outer classes are a follow-up)")
        }
        // Collision against every OTHER top-level class that shares this class's package (same file scope).
        val pkg = packageOf(cls.fullName)
        for (other in root.classes) {
            if (other === cls || other.outerClass != null || packageOf(other.fullName) != pkg) continue
            if (effectiveClassName(deobf, other) == newName) {
                return RenameResult.Collision(target, newName, "package '$pkg' already has a class named '$newName'")
            }
        }
        overrides[ClassNodeRef(cls.fullName)] = newName
        return RenameResult.Applied(target, newName)
    }

    private fun renameField(
        root: IrRoot,
        deobf: Map<CodeNodeRef, String>,
        target: FieldNodeRef,
        newName: String,
    ): RenameResult {
        val cls = root.findClass(target.ownerClass) ?: return reject(target, "declaring class is not in the loaded model")
        val field = cls.fields.firstOrNull { it.name == target.name }
            ?: return reject(target, "no such field on the declaring class")
        specialClassRejection(cls, target)?.let { return it }
        for (other in cls.fields) {
            if (other === field) continue
            if (effectiveFieldName(deobf, cls, other.name) == newName) {
                return RenameResult.Collision(target, newName, "'${cls.fullName}' already has a field named '$newName'")
            }
        }
        overrides[FieldNodeRef(cls.fullName, field.name)] = newName
        return RenameResult.Applied(target, newName)
    }

    private fun renameMethod(
        root: IrRoot,
        deobf: Map<CodeNodeRef, String>,
        target: MethodNodeRef,
        newName: String,
    ): RenameResult {
        val cls = root.findClass(target.ownerClass) ?: return reject(target, "declaring class is not in the loaded model")
        val method = cls.methods.firstOrNull {
            it.name == target.name && it.argTypes.map { a -> a.toString() } == target.argTypeDescriptors
        } ?: return reject(target, "no such method on the declaring class")
        specialClassRejection(cls, target)?.let { return it }
        if (method.name == CONSTRUCTOR_NAME || method.name == STATIC_INIT_NAME) {
            return reject(target, "constructors and static initializers cannot be renamed")
        }
        // Only a sibling of the SAME parameter types is a collision — a different-parameter method is a
        // legal Java overload, exactly as codegen's within-class uniqueness key (name + parameter types).
        val targetParams = method.argTypes.map { it.toString() }
        for (other in cls.methods) {
            if (other === method || other.argTypes.map { it.toString() } != targetParams) continue
            if (effectiveMethodName(deobf, cls, other.name, targetParams) == newName) {
                return RenameResult.Collision(
                    target,
                    newName,
                    "class '${cls.fullName}' already has a method '$newName(${targetParams.joinToString(", ")})'",
                )
            }
        }
        overrides[MethodNodeRef(cls.fullName, method.name, targetParams)] = newName
        return RenameResult.Applied(target, newName)
    }

    // ---- effective (post-alias) sibling names -------------------------------
    // Precedence: a prior USER rename first, then the deobfuscation map, then the raw model name — the same
    // order the effective AliasMap resolves, so the collision check sees exactly what will be emitted.

    private fun effectiveClassName(deobf: Map<CodeNodeRef, String>, cls: IrClass): String {
        val ref = ClassNodeRef(cls.fullName)
        return overrides[ref] ?: deobf[ref] ?: cls.shortName
    }

    private fun effectiveFieldName(deobf: Map<CodeNodeRef, String>, cls: IrClass, fieldName: String): String {
        val ref = FieldNodeRef(cls.fullName, fieldName)
        return overrides[ref] ?: deobf[ref] ?: fieldName
    }

    private fun effectiveMethodName(
        deobf: Map<CodeNodeRef, String>,
        cls: IrClass,
        methodName: String,
        params: List<String>,
    ): String {
        val ref = MethodNodeRef(cls.fullName, methodName, params)
        return overrides[ref] ?: deobf[ref] ?: methodName
    }

    // ---- helpers ------------------------------------------------------------

    private fun reject(target: CodeNodeRef, reason: String): RenameResult.UnrenamableTarget =
        RenameResult.UnrenamableTarget(target, reason)

    /**
     * Reject a member rename when its declaring class is an enum/annotation — those types are reshaped by
     * dedicated reconstructors, so their members are left alone (mirrors the deobfuscator's wholesale skip).
     */
    private fun specialClassRejection(cls: IrClass, target: CodeNodeRef): RenameResult? =
        if (cls.accessFlags and (ACC_ENUM or ACC_ANNOTATION) != 0) {
            reject(target, "members of an enum/annotation type cannot be renamed yet")
        } else {
            null
        }

    private fun packageOf(fullName: String): String = fullName.substringBeforeLast('.', "")

    /**
     * `null` if [name] is a legal, non-reserved Java identifier we can emit verbatim, else the reason it is
     * rejected. The accepted set is exactly `[A-Za-z_][A-Za-z0-9_]*` minus the reserved words, which is
     * precisely the set codegen's sanitize would return unchanged — so an accepted name reaches the source
     * exactly as typed.
     */
    private fun validateName(name: String): String? {
        if (name.isEmpty()) return "a name cannot be empty"
        val first = name[0]
        if (!(first in 'A'..'Z' || first in 'a'..'z' || first == '_')) {
            return "a name must start with a letter or underscore"
        }
        for (c in name) {
            if (!(c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_')) {
                return "'$c' is not a legal Java identifier character"
            }
        }
        if (name in RESERVED) return "'$name' is a Java reserved word"
        return null
    }

    private companion object {
        const val ACC_ENUM = 0x4000
        const val ACC_ANNOTATION = 0x2000
        const val CONSTRUCTOR_NAME = "<init>"
        const val STATIC_INIT_NAME = "<clinit>"

        /**
         * The names that must never be emitted as a bare identifier. Kept in sync with codegen's
         * `JavaIdentifiers.RESERVED` (that seam sanitizes a *raw* name into a keyword-free one, but it does
         * NOT touch a user override, so we must reject the same set up front). Includes the JLS keywords,
         * the boolean/null literals, the contextual keywords that break in a type/declaration position, and
         * the lone `_` (reserved since Java 9).
         */
        val RESERVED: Set<String> = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while",
            "true", "false", "null",
            "var", "record", "yield", "sealed", "permits",
            "_",
        )
    }
}
