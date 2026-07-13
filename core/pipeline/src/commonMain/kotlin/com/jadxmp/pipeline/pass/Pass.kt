package com.jadxmp.pipeline.pass

import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.pipeline.types.ClassHierarchy

/**
 * A unit of IR transformation, ordered relative to other passes by name via [runAfter]/[runBefore]
 * (never by list position — see CONVENTIONS "Passes"). Three granularities mirror the pipeline's data
 * dependencies: whole-program [RootPass]es run once up front, then [ClassPass] / [MethodPass] run
 * per node, lazily and fault-isolated by [PassRunner].
 *
 * jadx: IDexTreeVisitor + the `@JadxVisitor(runAfter/runBefore)` ordering hints, made explicit.
 */
sealed interface Pass {
    val name: String

    /** Names of passes that must run before this one. */
    val runAfter: List<String> get() = emptyList()

    /** Names of passes that must run after this one. */
    val runBefore: List<String> get() = emptyList()
}

/** A pass that runs once over the whole loaded model (usage graph, deobfuscation, signatures, …). */
interface RootPass : Pass {
    fun run(root: IrRoot, context: PassContext)
}

/** A pass that runs once per class. */
interface ClassPass : Pass {
    fun run(cls: IrClass, context: PassContext)
}

/** A pass that runs once per method — the CFG/SSA/type stages are these. */
interface MethodPass : Pass {
    fun run(method: IrMethod, context: PassContext)
}

/**
 * Shared services handed to every pass: the loaded model root, the resolved [ClassHierarchy] (built
 * once over [IrRoot]), and the cooperative [CancellationCheck] the hot loops poll.
 */
class PassContext(
    val root: IrRoot,
    val cancellation: CancellationCheck = CancellationCheck.None,
) {
    /** Lazily-built class hierarchy over the loaded model; shared across passes. */
    val hierarchy: ClassHierarchy by lazy { ClassHierarchy(root) }
}
