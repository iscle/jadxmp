package com.jadxmp.pipeline

import com.jadxmp.input.CodeReader
import com.jadxmp.ir.attr.AttrKey
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.SsaValue

/**
 * Attribute keys produced/consumed by the analysis passes and read by later stages (codegen and the
 * future structuring pass). Kept here (not in `core:ir`) because they are pass artifacts, not part of
 * the pass-agnostic IR contract (see `IrAttrs` for the cross-cutting keys).
 */
object PipelineAttrs {
    /**
     * The method's parameter SSA values, in source declaration order (no implicit `this`; `this` is on
     * [com.jadxmp.ir.node.IrMethod.thisArg]). Set by SSA construction so codegen can name parameters.
     */
    val PARAMETERS: AttrKey<List<SsaValue>> = AttrKey("PARAMETERS")

    /**
     * On an exception-handler entry block: the [com.jadxmp.pipeline.cfg.ExceptionHandler] it realises.
     * Placed by the CFG stage; read by SSA (to bind the caught exception) and by structuring.
     */
    val EXC_HANDLER: AttrKey<com.jadxmp.pipeline.cfg.ExceptionHandler> = AttrKey("EXC_HANDLER")

    /**
     * On a method: the set of **exception edges** (`u.id → handler.id`, packed as a `Long` via
     * `(from.id shl 32) or to.id`) — the CFG edges that exist only because a block lies inside a try's
     * protected range and can transfer to a handler on a throw. **jadx: the distinction between a block's
     * full successors and its `cleanSuccessors`.**
     *
     * Marked by the CFG stage so that control-flow structuring can compute dominance/post-dominance and
     * walk the region tree over *normal* flow only (the "clean" CFG). Without this an exception edge from
     * every protected block to its handler pollutes post-dominators (pulling merges toward the handler)
     * and makes an otherwise straight-line/branchy try body look unstructurable. An edge is recorded here
     * **only** when it is purely exceptional — never when the same `u → h` also exists as a normal
     * (fall-through/branch) edge (a handler block that is also a normal merge, e.g. an empty catch that
     * shares the follow), so removing marked edges never drops real control flow.
     */
    val EXCEPTION_EDGES: AttrKey<Set<Long>> = AttrKey("EXCEPTION_EDGES")

    /**
     * On a **protected** block: the handler entry blocks of the try(s) it lies inside, in try-table order
     * (deduplicated). This is *membership* derived from the try ranges — independent of CFG edges — so a
     * try whose handler is ALSO a normal merge/follow (the common empty-catch-that-continues, where the
     * exception edge coincides with a normal edge and thus can't be marked in [EXCEPTION_EDGES]) is still
     * visible to structuring. Structuring identifies a try region as the maximal set of blocks sharing
     * one of these handler lists. Placed by the CFG stage; read by structuring only.
     */
    val PROTECTING_HANDLERS: AttrKey<List<BasicBlock>> = AttrKey("PROTECTING_HANDLERS")

    /**
     * On a block: the φ-instructions placed at its head (order matches the block's predecessor order
     * for argument binding). Maintained by SSA construction; empty/absent means no φ.
     */
    val PHI_LIST: AttrKey<MutableList<Instruction>> = AttrKey("PHI_LIST")

    /**
     * Immediate post-dominator of a block (the reverse-CFG idom), or absent when the block cannot
     * reach the method exit (inside an infinite loop). `core:ir`'s [BasicBlock] stores forward
     * dominators directly; post-dominators are a pipeline artifact, so they live on this attribute.
     */
    val IMMEDIATE_POST_DOMINATOR: AttrKey<BasicBlock> = AttrKey("IMMEDIATE_POST_DOMINATOR")

    /** The input-model [CodeReader] for a method body, attached at model-build time for the decode pass. */
    val CODE_READER: AttrKey<CodeReader> = AttrKey("CODE_READER")

    /** The method frame's register count, captured at model-build time (needed by SSA construction). */
    val REGISTER_COUNT: AttrKey<Int> = AttrKey("REGISTER_COUNT")

    /**
     * On a method: set to `true` by structuring **only** when the method is completely reduced to a
     * region tree over a fully de-SSA'd body (no [com.jadxmp.ir.insn.PhiInstruction] anywhere). It is the
     * explicit contract at the structuring↔codegen seam: `region != null` must imply φ-freedom, so
     * codegen / core:api can trust a regioned method to render compilable Java. A method left
     * unstructured (irreducible / unsupported shape / failed de-SSA) never carries this and keeps
     * `region == null`, so the renderability guard flags it. Absence means "not proven structured".
     */
    val FULLY_STRUCTURED: AttrKey<Boolean> = AttrKey("FULLY_STRUCTURED")
}
