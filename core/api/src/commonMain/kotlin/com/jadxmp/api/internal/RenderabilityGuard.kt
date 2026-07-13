package com.jadxmp.api.internal

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.DecompileError
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.pipeline.PipelineAttrs

/**
 * Decides whether a method's IR renders to *correct* Java today, and — critically — **bails honestly**
 * when it does not (CONVENTIONS: "a transform that can't preserve semantics must bail, correct-but-
 * uglier, not emit wrong code").
 *
 * In Phase 2 a method is renderable iff it is either already structured (a region tree — none yet) or
 * **branch-free, acyclic, and exception-free** (the straight-line form the codegen linear path emits
 * flat). A branchy method has an unresolved φ at every merge; the out-of-SSA bridge does not yet
 * coalesce those, so codegen would emit references to never-assigned φ-result registers —
 * plausible-looking but wrong Java. A try/catch method is even more insidious: its exception edge can
 * dedup with the normal edge (e.g. an empty catch whose handler coincides with the try's follow), so it
 * *looks* linear, and the flat path then drops the handler entirely — non-compilable "unreported
 * exception must be caught or declared". Such output must be **unmistakably flagged**, never read as
 * clean, so a later branchy/try-catch corpus sample cannot silently score a false no-error PASS.
 *
 * Phase 3 (control-flow structuring + real out-of-SSA) makes branchy methods renderable; this bail
 * invariant then still guards the irreducible/unsupported fallback.
 */
internal object RenderabilityGuard {

    /** The diagnostic message surfaced (as `// JADXMP ERROR: …`) for an unrenderable method. */
    const val UNSTRUCTURED_MESSAGE: String = "unstructured control flow (phi unresolved)"

    /** Whether [method] renders to compilable Java today (structured region, or branch-free/acyclic). */
    fun isRenderable(method: IrMethod): Boolean {
        if (method.region != null) return true
        val blocks = method.blocks
        if (blocks.isEmpty()) return true // abstract/native: nothing to render
        // Any exception handling (a try/catch — CFG-marked by a handler-entry block) has no structured
        // form yet; the flat/linear path would silently drop the handler. Flag it, never flat-render it.
        // A handler entry carries PipelineAttrs.EXC_HANDLER whenever the method has protected ranges,
        // even when its edge dedups with a normal edge and the graph therefore *looks* linear.
        if (blocks.any { it.contains(PipelineAttrs.EXC_HANDLER) }) return false
        val orderOf = HashMap<Int, Int>(blocks.size)
        blocks.forEachIndexed { i, b -> orderOf[b.id] = i }
        blocks.forEachIndexed { i, b ->
            if (b.successors.size > 1) return false // a fork ⇒ a merge ⇒ an unresolved φ
            for (s in b.successors) {
                val si = orderOf[s.id] ?: return false
                if (si <= i) return false // back edge ⇒ a loop
            }
        }
        return true
    }

    /**
     * If [method] is not [renderable][isRenderable], attach an error so the failure is surfaced: the
     * "no-error" accuracy signal fails ([com.jadxmp.api.ClassMetadata.errorCount] rises) and codegen
     * emits its `// JADXMP ERROR:` marker. A method that already failed a pass keeps its original error.
     */
    fun flagIfUnrenderable(method: IrMethod) {
        if (isRenderable(method)) return
        if (!method.contains(IrAttrs.ERROR)) {
            method[IrAttrs.ERROR] = DecompileError(UNSTRUCTURED_MESSAGE)
        }
        method.add(AttrFlag.HAS_ERROR)
    }
}
