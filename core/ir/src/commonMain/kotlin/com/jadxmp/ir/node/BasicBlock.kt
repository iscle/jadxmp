package com.jadxmp.ir.node

import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.insn.Instruction

/**
 * A basic block: a maximal straight-line run of [instructions] with a single entry and single exit.
 * **jadx: BlockNode**
 *
 * Holds the CFG edges ([predecessors]/[successors]) and the dominator data the CFG/SSA stage fills
 * in: [immediateDominator], the full [dominators] set, and the [dominanceFrontier] (where φ-functions
 * are placed). These fields start empty/null and are populated once by the dominator pass; they are
 * read heavily by SSA construction and structuring.
 *
 * [id] is stable and unique within a method — it indexes the block in dominator bitsets and is used
 * for identity in worklists.
 */
class BasicBlock(val id: Int) : AttrNode(), IrContainer {

    val instructions: MutableList<Instruction> = ArrayList()

    val predecessors: MutableList<BasicBlock> = ArrayList(1)
    val successors: MutableList<BasicBlock> = ArrayList(1)

    /** Immediate dominator; null for the method entry block. */
    var immediateDominator: BasicBlock? = null

    /** All blocks that dominate this one (including itself), by [id]. */
    val dominators: MutableSet<Int> = HashSet()

    /** Blocks this block immediately dominates (children in the dominator tree). */
    val dominatedBlocks: MutableList<BasicBlock> = ArrayList()

    /** Dominance frontier — the φ-placement set for SSA. */
    val dominanceFrontier: MutableSet<BasicBlock> = HashSet()

    /** Reverse-postorder position, assigned during CFG numbering; -1 until then. */
    var order: Int = -1

    override fun toString(): String = "B$id"
}
