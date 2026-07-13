package com.jadxmp.ir.node

import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.type.IrType

/**
 * A class or interface.  **jadx: ClassNode**
 *
 * Owns its [fields], [methods] and any [innerClasses]. [superType]/[interfaces] are the declared
 * supertypes (null super only for `java.lang.Object`). Members are added during load; passes then
 * fill each member's own state.
 */
class IrClass(
    val root: IrRoot,
    val fullName: String,
    val accessFlags: Int,
    val superType: IrType? = null,
    val interfaces: List<IrType> = emptyList(),
) : AttrNode() {

    val fields: MutableList<IrField> = ArrayList()
    val methods: MutableList<IrMethod> = ArrayList()

    /** Nested classes; [outerClass] is the reverse link (null for top-level classes). */
    val innerClasses: MutableList<IrClass> = ArrayList()
    var outerClass: IrClass? = null

    /**
     * The class's emitted simple name.
     *
     * For a **nested** class (one that has been placed under an [outerClass]) this is the segment after
     * the last `$` — `com.example.Outer$Inner` emits as `Inner` inside `Outer`. For a **top-level** class
     * the whole simple name is kept, `$` included: a `$`-containing binary name that could NOT be nested
     * (its outer is absent from the model) stays `B$BB`, so `public class B$BB` lands in `B$BB.java` and
     * recompiles — rather than a wrong `class BB` in a mismatched file. **jadx: ClassInfo name split /
     * notInner()**
     */
    val shortName: String
        get() {
            val simple = fullName.substringAfterLast('.')
            return if (outerClass != null) simple.substringAfterLast('$') else simple
        }

    override fun toString(): String = fullName
}
