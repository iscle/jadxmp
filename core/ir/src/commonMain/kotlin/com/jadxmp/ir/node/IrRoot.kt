package com.jadxmp.ir.node

import com.jadxmp.ir.attr.AttrNode

/**
 * The root of the whole IR model — the set of loaded classes for a decompilation run.
 * **jadx: RootNode**
 *
 * This is the top of the node ownership tree ([IrClass] → [IrMethod]/[IrField]) and the anchor
 * whole-program "prepare" passes (usage graph, deobfuscation, signatures) hang their results off via
 * attributes. It intentionally holds no class-hierarchy resolver: that is supplied by the pipeline,
 * keeping `core:ir` free of lookup services.
 */
class IrRoot : AttrNode() {
    val classes: MutableList<IrClass> = ArrayList()

    /** Fast lookup by fully-qualified name; kept in sync as classes are added via [addClass]. */
    private val byName: MutableMap<String, IrClass> = HashMap()

    fun addClass(cls: IrClass) {
        classes.add(cls)
        byName[cls.fullName] = cls
    }

    fun findClass(fullName: String): IrClass? = byName[fullName]
}
