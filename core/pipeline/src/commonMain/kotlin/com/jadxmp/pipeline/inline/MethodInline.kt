package com.jadxmp.pipeline.inline

import com.jadxmp.input.AccessFlags
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.decode.MethodDecoder
import com.jadxmp.pipeline.ssa.MethodParams

/**
 * Inlines **simple synthetic/bridge forwarder methods** into their call sites and drops the forwarder.
 * **jadx: MethodInlineVisitor** (restricted to the provably-identity static-forwarder case).
 *
 * A forwarder is the compiler glue the Java/Kotlin compiler emits for generic-erasure bridges and
 * accessor thunks: a `synthetic`/`bridge` method whose whole body is a single `invoke` of another
 * method with the same signature and the same arguments, returning that call's result. Such a method
 * has **no observable behaviour of its own** — calling it is identical to calling its target — so:
 *
 *  - every call site (in any class of the loaded model) is rewritten to call the forwarded target
 *    directly, and
 *  - the forwarder itself is marked [AttrFlag.DONT_GENERATE] so it is not emitted.
 *
 * Together these are behaviour-identical (same target, same arguments, same result) and remove only
 * synthetic glue — never a method with real behaviour or a caller we cannot see.
 *
 * ## Faithfulness / safety envelope (deliberately narrow)
 * A method is treated as an inlinable forwarder ONLY when ALL of these hold, so the rewrite is provably
 * an identity:
 *  - it is `static` and marked `synthetic` and/or `bridge`;
 *  - its body decodes (no decode error, no `try`/handler) to exactly one `invoke-static` followed by a
 *    `return`, and nothing else;
 *  - the target's signature is **identical** (same return type and parameter types) — so no covariant
 *    cast is being erased away;
 *  - the `invoke`'s arguments are exactly the method's incoming parameter registers, in order (an
 *    identity argument mapping — no reordering, dropping, or injected constants);
 *  - for a non-void forwarder the returned register is the call's own result.
 *
 * Anything outside this envelope (an instance method, a real body, a covariant/renamed signature, a
 * permuted argument list) is left completely untouched. Forwarder **chains** are followed to the
 * terminal real method (with a cycle/budget guard) so a caller is never rewritten onto another
 * about-to-be-dropped forwarder, and a forwarder that only resolves through a cycle is not dropped.
 *
 * Works off a lightweight re-decode of each candidate body (like `ThrowsInference`), so it is
 * independent of whether the target's class has been lowered yet and of pass ordering. Runs after CFG
 * build and before SSA, so the rewritten call flows through every downstream stage as an ordinary
 * direct call. No shared mutable state across methods (a fresh instance per method), so it is safe on
 * the parallel decompile path and in `commonMain` (no threads/reflection).
 */
class MethodInliner(private val root: IrRoot) {

    /** Memoized forwarder analysis, local to one method's processing (no cross-method shared state). */
    private val forwarderCache = HashMap<IrMethod, MethodRef?>()

    /** Process one method: drop it if it is itself a forwarder, and inline any forwarder it calls. */
    fun process(method: IrMethod) {
        // Drop side: a forwarder whose chain resolves to a real terminal is pure glue — do not emit it.
        if (terminalTarget(method) != null) {
            method.add(AttrFlag.DONT_GENERATE)
        }
        // Caller side: rewrite each call to a forwarder into a direct call to the terminal target.
        for (block in method.blocks) {
            val insns = block.instructions
            for (i in insns.indices) {
                val invoke = insns[i] as? InvokeInstruction ?: continue
                if (invoke.opcode != IrOpcode.INVOKE || invoke.invokeKind != InvokeKind.STATIC) continue
                val callee = resolve(invoke.methodRef) ?: continue
                val terminal = terminalTarget(callee) ?: continue
                if (terminal == invoke.methodRef) continue // already direct (defensive)
                val replacement = InvokeInstruction(
                    methodRef = terminal,
                    invokeKind = InvokeKind.STATIC,
                    result = invoke.result,
                    args = ArrayList(invoke.args),
                    opcode = IrOpcode.INVOKE,
                )
                replacement.offset = invoke.offset
                insns[i] = replacement
            }
        }
    }

    /**
     * The terminal real method a forwarder [method] ultimately forwards to, or null if [method] is not
     * a safely-droppable/inlinable forwarder (not a forwarder, or its chain cycles / exceeds budget).
     */
    private fun terminalTarget(method: IrMethod): MethodRef? {
        var ref = forwarderTargetOf(method) ?: return null
        val visited = HashSet<IrMethod>()
        visited.add(method)
        var budget = 0
        while (true) {
            if (budget++ > CHAIN_BUDGET) return null
            val next = resolve(ref) ?: return ref // target not in the model → it is the terminal call
            val nextFwd = forwarderTargetOf(next) ?: return ref // resolved to a real method → call it
            if (!visited.add(next)) return null // forwarder cycle → not provably safe; leave it
            ref = nextFwd
        }
    }

    /** The immediate target of [method] if it is an inlinable synthetic/bridge forwarder, else null. */
    private fun forwarderTargetOf(method: IrMethod): MethodRef? =
        forwarderCache.getOrPut(method) { computeForwarderTarget(method) }

    private fun computeForwarderTarget(method: IrMethod): MethodRef? {
        if (!method.isStatic) return null
        if (method.accessFlags and (AccessFlags.SYNTHETIC or AccessFlags.BRIDGE) == 0) return null
        val reader = method[PipelineAttrs.CODE_READER] ?: return null
        val code = MethodDecoder().decode(reader)
        if (code.errors.isNotEmpty()) return null
        if (code.tries.isNotEmpty()) return null

        val body = code.instructions.map { it.insn }.filter { it.opcode != IrOpcode.NOP }
        if (body.size != 2) return null
        val invoke = body[0] as? InvokeInstruction ?: return null
        val ret = body[1]
        if (invoke.opcode != IrOpcode.INVOKE || invoke.invokeKind != InvokeKind.STATIC) return null
        if (ret.opcode != IrOpcode.RETURN) return null

        val target = invoke.methodRef
        // Pure same-signature forwarding: identical return + parameter types (no covariant erasure).
        if (target.returnType != method.returnType) return null
        if (target.paramTypes != method.argTypes) return null

        // Return contract: void forwards a void call; a value forwards exactly the call's own result.
        if (method.returnType == IrType.VOID) {
            if (ret.argCount != 0 || invoke.result != null) return null
        } else {
            if (ret.argCount != 1) return null
            val retReg = (ret.getArg(0) as? RegisterOperand)?.regNum ?: return null
            val callResult = invoke.result?.regNum ?: return null
            if (retReg != callResult) return null
        }

        // Identity argument mapping: the call passes the incoming parameter registers, in order.
        val params = MethodParams.of(method, code.registerCount)
        if (invoke.argCount != params.paramRegs.size) return null
        for (i in params.paramRegs.indices) {
            val arg = invoke.getArg(i) as? RegisterOperand ?: return null
            if (arg.regNum != params.paramRegs[i]) return null
        }
        return target
    }

    private fun resolve(ref: MethodRef): IrMethod? {
        val className = (ref.declaringType as? IrType.Object)?.className ?: return null
        val cls = root.findClass(className) ?: return null
        // Match on the full signature (incl. return type) so a static method overloaded on return type
        // alone — legal in bytecode though not producible by javac/kotlinc — can't resolve to the wrong one.
        return cls.methods.firstOrNull {
            it.name == ref.name && it.argTypes == ref.paramTypes && it.returnType == ref.returnType
        }
    }

    private companion object {
        /** Bound on forwarder-chain following (real chains are 1–2 deep; guards against pathological input). */
        const val CHAIN_BUDGET = 32
    }
}
