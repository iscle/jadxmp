package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.ir.node.IrClass

/**
 * The **emitted Kotlin simple name** of a class — the single source of truth shared by the class
 * declaration (`class X`) and every renamed-class *reference* ([KotlinTypeRenderer]). The Kotlin
 * projection of the class-name portion of the Java backend's `JavaSourceName`. **jadx: Deobfuscator
 * class alias (design reference only).**
 *
 * ## Key scheme (identical to the Java backend)
 * The override is looked up by [ClassNodeRef]`(cls.fullName)` — the SAME identity the Java backend keys
 * on and the same identity find-usages / go-to-def / rename speak — so a class renamed via deobfuscation
 * or a user rename spells the identical alias at its definition and at every reference, and the two
 * source views (Java and Kotlin) stay coherent for cross-navigation.
 *
 * ## Why simpler than [com.jadxmp.codegen.java.JavaSourceName]
 * The Java version additionally disambiguates colliding *top-level* class names (two binary names that
 * sanitize to one identifier) because each top-level class becomes its own `.java` file. The Kotlin
 * backend does not (yet) do that pass — it spells the raw name with [KotlinIdentifiers.sanitize] — so
 * this projection deliberately mirrors only the *alias* seam and leaves the raw path exactly as it was.
 * Porting the disambiguation here would change output for the no-alias case and is out of scope.
 *
 * ## The safety invariant (load-bearing)
 * [AliasMap.EMPTY] (the default everywhere) short-circuits to the exact pre-feature spelling
 * ([KotlinIdentifiers.sanitize] of [IrClass.shortName]), so output with no overrides is byte-for-byte
 * identical to output built without this feature — which is what keeps the differential oracle (always
 * run with zero overrides) completely unaffected.
 */
internal object KotlinSourceName {

    /**
     * The emitted Kotlin simple name of [cls]: an [aliasMap] override (deobfuscation / user rename) when
     * present, else the sanitized [IrClass.shortName]. The `isEmpty` fast path keeps the no-override case
     * byte-identical to the pre-feature backend. A renamed class is always a leaf top-level class (the
     * populator's restriction), so the override is a plain, already-valid identifier.
     */
    fun sourceSimpleName(cls: IrClass, aliasMap: AliasMap = AliasMap.EMPTY): String {
        if (!aliasMap.isEmpty) aliasMap.aliasOf(ClassNodeRef(cls.fullName))?.let { return it }
        return KotlinIdentifiers.sanitize(cls.shortName)
    }
}
