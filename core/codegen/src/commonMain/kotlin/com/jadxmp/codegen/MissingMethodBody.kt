package com.jadxmp.codegen

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.node.IrMethod

/** A visible failure stub is needed only when a concrete method has no recovered body at all. */
object MissingMethodBody {
    fun isRequired(method: IrMethod): Boolean =
        method.contains(AttrFlag.HAS_ERROR) && method.region == null && method.blocks.isEmpty() &&
            method.accessFlags and (0x0400 or 0x0100) == 0 // abstract/native declarations have no body
}
