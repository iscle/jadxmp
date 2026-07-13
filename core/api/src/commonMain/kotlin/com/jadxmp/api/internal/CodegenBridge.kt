package com.jadxmp.api.internal

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.codegen.NameGenerator
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.LocalVar
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.pipeline.PipelineAttrs

/**
 * The **pipeline → codegen bridge**: a minimal out-of-SSA step that turns the analysis output (typed
 * SSA over a finished CFG) into the shape the Java backend expects (named source locals with
 * declaration points and parameter names).
 *
 * ## Why this exists
 * The pipeline emits *versioned SSA values*; codegen renders *source-level [LocalVar]s*. The two are
 * bridged by giving every SSA value a `LocalVar` and by naming/marking the method parameters so a body
 * reference to a parameter matches its signature name and is never re-declared.
 *
 * ## What it does (Phase-2 scope)
 * For a **φ-free** method this is exact:
 *  1. Each parameter SSA value ([PipelineAttrs.PARAMETERS]) gets a `LocalVar` with a stable
 *     type-derived name (via the shared [NameGenerator], matching codegen's own conventions) and the
 *     [AttrFlag.METHOD_ARGUMENT] marker; the same names are published on [CodegenKeys.PARAM_NAMES] so
 *     the signature and body agree. `this` is marked on its own `LocalVar`.
 *  2. Every other SSA value maps 1:1 to its own `LocalVar` — since SSA guarantees a single definition,
 *     that definition is the variable's declaration point and codegen declares-on-first-assign.
 *
 * ## What it does NOT do (documented Phase-3 TODOs)
 *  - **φ resolution.** When φ-functions remain (branchy/merging control flow), collapsing the versions
 *    they join into one source variable needs real out-of-SSA (copy insertion / coalescing). Here each
 *    φ result still becomes its own `LocalVar`, which renders best-effort but is generally not
 *    compilable — the enclosing method is flagged `fullyStructured = false` upstream.
 *  - **Expression inlining / shrinking** (single-use defs folded into their use site).
 *  - **Control-flow structuring** (region tree). Codegen falls back to a linear per-block form.
 *
 * Kept in `core:api` (not `core:pipeline`) on purpose: it is the one place that sees both
 * `com.jadxmp.pipeline` artifacts ([PipelineAttrs]) and `com.jadxmp.codegen` contracts
 * ([CodegenKeys]/[NameGenerator]), so no new cross-module dependency is introduced into the engine.
 */
internal object CodegenBridge {

    fun prepareForCodegen(method: IrMethod) {
        // `this` is rendered as the `this` keyword (codegen keys off IrMethod.thisArg identity); give it
        // a marked LocalVar so it is never treated as an ordinary local.
        method.thisArg?.let { localVarFor(it).isThis = true }

        // Parameters: stable, unique, type-derived names shared between signature and body.
        val names = NameGenerator()
        val params = method[PipelineAttrs.PARAMETERS].orEmpty()
        val paramNames = ArrayList<String>(method.argTypes.size)
        for ((i, value) in params.withIndex()) {
            val declared = method.argTypes.getOrNull(i)
            val type = when {
                value.type.isTypeKnown -> value.type
                declared != null && declared.isTypeKnown -> declared
                else -> declared ?: value.type
            }
            val name = names.forType(type)
            val local = localVarFor(value)
            local.name = name
            local.type = type
            local.add(AttrFlag.METHOD_ARGUMENT)
            value.add(AttrFlag.METHOD_ARGUMENT)
            paramNames.add(name)
        }
        // PARAM_NAMES must line up 1:1 with argTypes; only publish when we produced a full set.
        if (paramNames.size == method.argTypes.size && paramNames.isNotEmpty()) {
            method[CodegenKeys.PARAM_NAMES] = paramNames
        }

        // Every remaining SSA value collapses to its own source local (φ-free out-of-SSA). Non-param
        // locals are left un-named so codegen assigns readable type-based names at emit time.
        for (value in method.ssaValues) {
            if (value === method.thisArg) continue
            if (value.localVar != null) continue
            val local = LocalVar()
            if (value.type.isTypeKnown) local.type = value.type
            local.addSsaValue(value)
        }
    }

    private fun localVarFor(value: SsaValue): LocalVar {
        value.localVar?.let { return it }
        return LocalVar().also { it.addSsaValue(value) }
    }
}
