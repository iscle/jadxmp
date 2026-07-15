package com.jadxmp.api

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.CodeNodeRef
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot

/**
 * Builds the deobfuscation [AliasMap]: it scans the loaded model for clearly-obfuscated class/field/method
 * names and assigns each a stable, readable, collision-free replacement. **jadx: Deobfuscator (design
 * reference only — jadxmp emits the rename as a codegen-consumed alias map, not a mutation of the model).**
 *
 * This is the *populator*; the map it returns is applied at codegen (see [AliasMap] and the Java naming
 * seams). It is invoked only when [DecompilerArgs.deobfuscation] is on — when off, no map is built and
 * output stays byte-identical.
 *
 * ## Guarantees
 *  - **Deterministic.** Names are handed out while walking the model in its stable, input-derived order
 *    ([IrRoot.classes], then each class's declaration-order [IrClass.fields]/[IrClass.methods]) with a
 *    monotonic per-scope counter. No hashing, iteration-order-of-hash-map, randomness or clock is used, so
 *    the same input always yields the same names.
 *  - **Collision-free.** Generated names live in a per-scope [Allocator] that first *reserves* every kept
 *    (non-renamed) name in that scope, then issues `<prefix>NNNN` values skipping anything reserved or
 *    already issued. (Codegen additionally re-runs its own within-scope uniqueness pass over the resulting
 *    bases, so a rename is disambiguated even in the pathological case an override still clashed.)
 *  - **Consistent.** Keys are the exact [CodeNodeRef] identities the backend records as definition/
 *    reference metadata, so one entry renames a symbol at its definition and at every use.
 *  - **Owned symbols only.** Only in-model classes are scanned; a library/framework class or member is
 *    never in the model, so it is never renamed (and a reference to it resolves to no override).
 *
 * ## Scope of this first cut
 * Classes are renamed only when they are *leaf top-level* (no outer, no inner, no `$` in the simple name)
 * and neither enum nor annotation — the shape whose every reference is a flat fully-qualified name, so a
 * flat generated name re-references safely with no nested-name bookkeeping. Fields and methods are renamed
 * in any non-enum/non-annotation class (including nested ones). Enums and annotations are skipped wholesale
 * because dedicated reconstructors reshape their members. Kotlin output is not yet renamed (a follow-up).
 */
internal object Deobfuscator {

    /**
     * An identifier strictly shorter than 3 characters (length 1–2) is treated as obfuscated. This mirrors
     * jadx's default deobfuscation `minLength` of 3 (names below it are renamed). A documented threshold,
     * deliberately conservative: it catches the classic single/double-letter obfuscation (`a`, `b`, `ab`)
     * without touching readable names.
     */
    const val MAX_OBFUSCATED_NAME_LENGTH = 2

    private const val ACC_ENUM = 0x4000
    private const val ACC_ANNOTATION = 0x2000

    /** Scan [root] and return the (possibly empty) alias map of renamed symbols. */
    fun buildAliasMap(root: IrRoot): AliasMap {
        val overrides = LinkedHashMap<CodeNodeRef, String>()
        renameClasses(root, overrides)
        renameMembers(root, overrides)
        return AliasMap.of(overrides)
    }

    // ---- classes ------------------------------------------------------------

    private fun renameClasses(root: IrRoot, out: MutableMap<CodeNodeRef, String>) {
        // Per-package allocator: a top-level class becomes its own source file, so a generated name must be
        // unique among that package's file names — otherwise a rename would collide with a kept class's
        // file path. Reserve every kept top-level simple name first, then hand out `C0001`, `C0002`, ….
        val perPackage = HashMap<String, Allocator>()
        for (cls in root.classes) {
            if (cls.outerClass != null) continue
            val alloc = perPackage.getOrPut(packageOf(cls.fullName)) { Allocator("C") }
            if (!isClassRenameCandidate(cls)) alloc.reserve(cls.shortName)
        }
        for (cls in root.classes) {
            if (!isClassRenameCandidate(cls)) continue
            out[ClassNodeRef(cls.fullName)] = perPackage.getValue(packageOf(cls.fullName)).next()
        }
    }

    /**
     * A class we can rename *and* re-reference safely with a flat generated name: a leaf top-level class
     * (no outer ⇒ its references are the plain FQN; no inner classes ⇒ it is never the `Outer` in a nested
     * `$` reference; no `$` in its own simple name) that is neither enum nor annotation, and whose name is
     * obfuscated.
     */
    private fun isClassRenameCandidate(cls: IrClass): Boolean =
        cls.outerClass == null &&
            cls.innerClasses.isEmpty() &&
            '$' !in cls.shortName &&
            !isSpecialClass(cls) &&
            isObfuscated(cls.shortName)

    // ---- members ------------------------------------------------------------

    private fun renameMembers(root: IrRoot, out: MutableMap<CodeNodeRef, String>) {
        for (cls in root.classes) {
            // Enums/annotations are reshaped by dedicated reconstructors; a blind member rename could fight
            // that, so skip them wholesale. Plain classes and interfaces (incl. nested) are safe.
            if (isSpecialClass(cls)) continue
            renameFields(cls, out)
            renameMethods(cls, out)
        }
    }

    private fun renameFields(cls: IrClass, out: MutableMap<CodeNodeRef, String>) {
        val alloc = Allocator("f")
        for (f in cls.fields) if (!isObfuscated(f.name)) alloc.reserve(f.name)
        for (f in cls.fields) {
            if (isObfuscated(f.name)) out[FieldNodeRef(cls.fullName, f.name)] = alloc.next()
        }
    }

    private fun renameMethods(cls: IrClass, out: MutableMap<CodeNodeRef, String>) {
        val alloc = Allocator("m")
        for (m in cls.methods) if (!isRenamableMethod(m)) alloc.reserve(m.name)
        for (m in cls.methods) {
            if (isRenamableMethod(m)) {
                out[MethodNodeRef(cls.fullName, m.name, m.argTypes.map { it.toString() })] = alloc.next()
            }
        }
    }

    /** Constructors/static-initializers are never renamed (they are spelled specially by codegen). */
    private fun isRenamableMethod(m: IrMethod): Boolean =
        m.name != CONSTRUCTOR_NAME && m.name != STATIC_INIT_NAME && isObfuscated(m.name)

    // ---- heuristic ----------------------------------------------------------

    /**
     * The conservative "looks obfuscated" test: a very short identifier (length ≤
     * [MAX_OBFUSCATED_NAME_LENGTH]) made only of identifier characters — the classic single/double-letter
     * obfuscation (`a`, `b`, `ab`, `a1`). Longer readable names, and the special `<init>`/`<clinit>`, are
     * left untouched.
     */
    private fun isObfuscated(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_OBFUSCATED_NAME_LENGTH) return false
        if (name == CONSTRUCTOR_NAME || name == STATIC_INIT_NAME) return false
        return name.all { it.isLetterOrDigit() || it == '_' || it == '$' }
    }

    private fun isSpecialClass(cls: IrClass): Boolean =
        cls.accessFlags and (ACC_ENUM or ACC_ANNOTATION) != 0

    private fun packageOf(fullName: String): String = fullName.substringBeforeLast('.', "")

    private const val CONSTRUCTOR_NAME = "<init>"
    private const val STATIC_INIT_NAME = "<clinit>"

    /**
     * Deterministic generator of collision-free names `<prefix>0001`, `<prefix>0002`, …: [next] hands out
     * the next zero-padded index not already reserved or issued. Determinism comes from the caller driving
     * it in a stable model order; [reserve] pre-claims kept names so a generated name never duplicates one.
     * The zero-padding is cosmetic — uniqueness holds at any width.
     */
    private class Allocator(private val prefix: String) {
        private val used = HashSet<String>()
        private var counter = 0

        fun reserve(name: String) {
            used.add(name)
        }

        fun next(): String {
            while (true) {
                counter++
                val candidate = prefix + counter.toString().padStart(4, '0')
                if (used.add(candidate)) return candidate
            }
        }
    }
}
