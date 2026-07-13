package com.jadxmp.pipeline

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.DecompileError
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.pipeline.cfg.CfgBuilder
import com.jadxmp.pipeline.cfg.Dominators
import com.jadxmp.pipeline.constructor.ConstructorReconstruction
import com.jadxmp.pipeline.decode.MethodDecoder
import com.jadxmp.pipeline.pass.MethodPass
import com.jadxmp.pipeline.pass.PassContext
import com.jadxmp.pipeline.pass.PassRunner
import com.jadxmp.pipeline.ssa.SsaBuilder
import com.jadxmp.pipeline.staticinit.StaticFieldInitPass
import com.jadxmp.pipeline.structure.ExpressionShaping
import com.jadxmp.pipeline.structure.OutOfSsa
import com.jadxmp.pipeline.structure.RegionMaker
import com.jadxmp.pipeline.throwsinfer.ThrowsInference
import com.jadxmp.pipeline.types.TypeInference

/** Canonical pass names, referenced by ordering hints. */
object PassNames {
    const val BUILD_CFG = "BuildCfg"
    const val DOMINATORS = "Dominators"
    const val SSA = "Ssa"
    const val TYPE_INFERENCE = "TypeInference"
    const val CONSTRUCTOR_RECONSTRUCTION = "ConstructorReconstruction"
    const val THROWS_INFERENCE = "ThrowsInference"
    const val OUT_OF_SSA = "OutOfSsa"
    const val EXPRESSION_SHAPING = "ExpressionShaping"
    const val REGION_MAKER = "RegionMaker"
}

/**
 * Decodes a method body and builds its CFG. **Deliverables 2 + 3.** A method with no code (abstract/
 * native, i.e. no attached [PipelineAttrs.CODE_READER]) is skipped.
 */
class BuildCfgPass : MethodPass {
    override val name: String get() = PassNames.BUILD_CFG

    override fun run(method: IrMethod, context: PassContext) {
        val reader = method[PipelineAttrs.CODE_READER] ?: return
        val code = MethodDecoder(context.cancellation).decode(reader)
        if (code.errors.isNotEmpty()) {
            // Undecodable opcodes were preserved as placeholders; flag the method so the no-error
            // signal fails rather than masking the loss of fidelity.
            if (!method.contains(IrAttrs.ERROR)) {
                method[IrAttrs.ERROR] = DecompileError(code.errors.joinToString("; "))
            }
            method.add(AttrFlag.HAS_ERROR)
        }
        CfgBuilder(method, code, context.cancellation).build()
    }
}

/** Dominator tree + dominance frontier + post-dominators. **Deliverable 4.** */
class DominatorsPass : MethodPass {
    override val name: String get() = PassNames.DOMINATORS
    override val runAfter: List<String> get() = listOf(PassNames.BUILD_CFG)

    override fun run(method: IrMethod, context: PassContext) {
        if (method.entryBlock == null) return
        Dominators.compute(method, context.cancellation)
        Dominators.computePostDominators(method, context.cancellation)
    }
}

/** SSA construction: φ placement + renaming + useless-φ cleanup. **Deliverable 5.** */
class SsaPass : MethodPass {
    override val name: String get() = PassNames.SSA
    override val runAfter: List<String> get() = listOf(PassNames.DOMINATORS)

    override fun run(method: IrMethod, context: PassContext) {
        if (method.entryBlock == null) return
        val registerCount = method[PipelineAttrs.REGISTER_COUNT] ?: return
        SsaBuilder(method, registerCount, context.cancellation).build()
    }
}

/** Type inference over the typed SSA. **Deliverable 6.** */
class TypeInferencePass : MethodPass {
    override val name: String get() = PassNames.TYPE_INFERENCE
    override val runAfter: List<String> get() = listOf(PassNames.SSA)

    override fun run(method: IrMethod, context: PassContext) {
        if (method.entryBlock == null) return
        TypeInference(method, context.hierarchy, context.cancellation).run()
    }
}

/**
 * Fuse `new-instance` + `<init>` into a normalized `CONSTRUCTOR` (`new T(args)`). Runs on SSA form,
 * after types and before out-of-SSA (it needs def-use to trace the uninitialized reference).
 */
class ConstructorReconstructionPass : MethodPass {
    override val name: String get() = PassNames.CONSTRUCTOR_RECONSTRUCTION
    override val runAfter: List<String> get() = listOf(PassNames.TYPE_INFERENCE)
    override val runBefore: List<String> get() = listOf(PassNames.OUT_OF_SSA)

    override fun run(method: IrMethod, context: PassContext) {
        if (method.entryBlock == null) return
        ConstructorReconstruction(method, context.cancellation).run()
    }
}

/**
 * Infer each method's checked-exception `throws` clause (whole-program, via the call graph). Sets
 * [com.jadxmp.codegen.CodegenKeys.THROWS] for codegen. Independent of CFG/SSA (works off a lightweight
 * re-decode), so it may run any time after the model is built.
 */
class ThrowsInferencePass : MethodPass {
    override val name: String get() = PassNames.THROWS_INFERENCE
    override val runAfter: List<String> get() = listOf(PassNames.TYPE_INFERENCE)
    override val runBefore: List<String> get() = listOf(PassNames.OUT_OF_SSA)

    override fun run(method: IrMethod, context: PassContext) {
        ThrowsInference(context.root, context.hierarchy).apply(method)
    }
}

/**
 * SSA destruction: replace φ with coalesced/copied ordinary variables. **Phase 3, deliverable 1.**
 * After this pass no [com.jadxmp.ir.insn.PhiInstruction] remains (or, if de-SSA could not be done
 * safely, the pass throws and the method is flagged — φ then stay in place and structuring bails).
 */
class OutOfSsaPass : MethodPass {
    override val name: String get() = PassNames.OUT_OF_SSA
    override val runAfter: List<String> get() = listOf(PassNames.TYPE_INFERENCE)

    override fun run(method: IrMethod, context: PassContext) {
        if (method.entryBlock == null) return
        OutOfSsa(method, context.cancellation).run()
    }
}

/** Fold single-use pure defs into their use (readable conditions/expressions). **Phase 3, deliverable 4.** */
class ExpressionShapingPass : MethodPass {
    override val name: String get() = PassNames.EXPRESSION_SHAPING
    override val runAfter: List<String> get() = listOf(PassNames.OUT_OF_SSA)

    override fun run(method: IrMethod, context: PassContext) {
        if (method.entryBlock == null) return
        ExpressionShaping(method, context.cancellation).run()
    }
}

/**
 * Control-flow structuring: build the nested region tree (if/else, loops) with a non-crashing
 * irreducible/unsupported fallback. **Phase 3, deliverables 2 + 3.** Runs after de-SSA and shaping so
 * the branch instructions it reads already carry inlined conditions and no φ remain.
 */
class RegionMakerPass : MethodPass {
    override val name: String get() = PassNames.REGION_MAKER
    override val runAfter: List<String> get() = listOf(PassNames.EXPRESSION_SHAPING)

    override fun run(method: IrMethod, context: PassContext) {
        if (method.entryBlock == null) return
        RegionMaker(method, context.cancellation).run()
    }
}

/**
 * The standard analysis pipeline: decode → CFG → dominators → SSA → type inference → **out-of-SSA →
 * expression shaping → region structuring**. The output is a method with a nested region tree over a
 * fully de-SSA'd body (`region != null` ⇒ φ-free, renderable), or — for irreducible/unsupported
 * shapes — a null region that leaves the method flagged unstructured (never wrong code).
 */
object AnalysisPipeline {
    val methodPasses: List<MethodPass> = listOf(
        BuildCfgPass(),
        DominatorsPass(),
        SsaPass(),
        TypeInferencePass(),
        ConstructorReconstructionPass(),
        ThrowsInferencePass(),
        OutOfSsaPass(),
        ExpressionShapingPass(),
        RegionMakerPass(),
        // Absorb <clinit> static-field init into the field model (declaration initializers / static { })
        // after the body is fully structured, so it reads the same statements codegen renders.
        StaticFieldInitPass(),
    )

    /** A [PassRunner] wired with the standard method passes. */
    fun runner(): PassRunner = PassRunner(methodPasses = methodPasses)
}
