package com.jadxmp.ir.node

import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.type.IrType

/**
 * A method, and the container for all per-method analysis state.  **jadx: MethodNode**
 *
 * A method flows through the pipeline in place: after IR-build it holds [blocks]; after CFG/SSA the
 * blocks carry edges/dominators and [ssaValues] is populated; after structuring [region] holds the
 * nested control-flow tree that codegen walks. Each stage fills the next field — they are null/empty
 * until the owning stage runs.
 */
class IrMethod(
    val declaringClass: IrClass,
    val name: String,
    val returnType: IrType,
    val argTypes: List<IrType>,
    val accessFlags: Int,
) : AttrNode() {

    /** Basic blocks, in reverse-postorder once CFG numbering has run. */
    val blocks: MutableList<BasicBlock> = ArrayList()

    /** CFG entry block; null until the CFG is built. */
    var entryBlock: BasicBlock? = null

    /** Exit block(s) unify here; null until the CFG is built. */
    var exitBlock: BasicBlock? = null

    /** All SSA values in the method; populated by SSA construction. */
    val ssaValues: MutableList<SsaValue> = ArrayList()

    /** `this` for instance methods; null for static methods (set during IR build). */
    var thisArg: SsaValue? = null

    /** Root of the structured region tree; null until control-flow structuring runs. */
    var region: Region? = null

    val isStatic: Boolean get() = accessFlags and ACC_STATIC != 0

    override fun toString(): String =
        "${declaringClass.fullName}.$name(${argTypes.joinToString(", ")}): $returnType"

    companion object {
        /** JVM `ACC_STATIC`. */
        const val ACC_STATIC = 0x0008
    }
}
