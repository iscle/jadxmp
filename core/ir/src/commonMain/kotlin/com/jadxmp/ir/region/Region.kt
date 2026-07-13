package com.jadxmp.ir.region

import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.type.IrType

/**
 * The structured control-flow tree produced by the structuring stage.  **jadx: IRegion / AbstractRegion**
 *
 * A `Region` is an [IrContainer], so region kinds nest inside one another and inside a
 * [SequenceRegion] alongside plain `BasicBlock`s. Codegen walks this tree top-down; leaf blocks emit
 * straight-line code and each region kind emits its construct. The tree is the single structure
 * codegen reads — building it correctly is where jadx has its worst bugs (ARCHITECTURE §6), so the
 * kinds are `sealed` for exhaustive handling and irreducible fallbacks are explicit, not exceptions.
 *
 * `Region` is mutable (children can be re-parented as structuring proceeds) but exposes structure via
 * read-only lists; [parent] is the up-link.
 */
sealed class Region : AttrNode(), IrContainer {
    var parent: Region? = null
}

/**
 * A plain sequence: ordered children (blocks and/or nested regions) executed one after another.
 * This is also the region kind used for a simple "block region". jadx: Region
 */
class SequenceRegion : Region() {
    val children: MutableList<IrContainer> = ArrayList()

    fun add(child: IrContainer) {
        if (child is Region) child.parent = this
        children.add(child)
    }
}

/**
 * `if (condition) then [else elseRegion]`. jadx: IfRegion
 */
class IfRegion(
    val condition: Condition,
    val thenRegion: Region,
    val elseRegion: Region? = null,
) : Region() {
    init {
        thenRegion.parent = this
        elseRegion?.parent = this
    }
}

/**
 * A loop of the given [kind]. [condition] is null for [LoopKind.INFINITE]. jadx: LoopRegion
 */
class LoopRegion(
    val kind: LoopKind,
    val condition: Condition?,
    val body: Region,
) : Region() {
    init {
        body.parent = this
    }
}

/** One arm of a [SwitchRegion]: the constant [keys] that fall into [body]. jadx: SwitchRegion case */
class SwitchCase(
    val keys: List<Long>,
    val body: Region,
)

/**
 * `switch (selector) { … }`. [defaultCase] is the `default:` arm, if any. jadx: SwitchRegion
 */
class SwitchRegion(
    val selector: Operand,
    val cases: List<SwitchCase>,
    val defaultCase: Region? = null,
) : Region() {
    init {
        cases.forEach { it.body.parent = this }
        defaultCase?.parent = this
    }
}

/**
 * One `catch` arm: handles any of [exceptionTypes], binding the exception to [exceptionVar].
 * jadx: ExceptionHandler
 */
class CatchClause(
    val exceptionTypes: List<IrType>,
    val exceptionVar: Operand?,
    val body: Region,
)

/**
 * `try { … } catch … [finally …]`. jadx: TryCatchRegion
 */
class TryCatchRegion(
    val tryRegion: Region,
    val catches: List<CatchClause>,
    val finallyRegion: Region? = null,
) : Region() {
    init {
        tryRegion.parent = this
        catches.forEach { it.body.parent = this }
        finallyRegion?.parent = this
    }
}

/**
 * `synchronized (monitor) { … }`. jadx: SynchronizedRegion
 */
class SyncRegion(
    val monitor: Operand,
    val body: Region,
) : Region() {
    init {
        body.parent = this
    }
}
