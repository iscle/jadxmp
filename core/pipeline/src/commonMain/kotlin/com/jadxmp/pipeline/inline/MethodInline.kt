package com.jadxmp.pipeline.inline

import com.jadxmp.input.AccessFlags
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.decode.MethodDecoder
import com.jadxmp.pipeline.ssa.MethodParams

/**
 * Rewrites calls through **simple synthetic/bridge forwarder methods** when their contracts agree.
 * **jadx: MethodInlineVisitor** (restricted to the provably-identity static-forwarder case).
 *
 * A forwarder is the compiler glue the Java/Kotlin compiler emits for generic-erasure bridges and
 * accessor thunks: a `synthetic`/`bridge` method whose whole body is a single `invoke` of another
 * method with the same signature and the same arguments, returning that call's result. Such a method
 * may have rewritable call sites once its implicit monitor, initialization and access contracts are checked:
 *
 *  - each proven call site is rewritten to call the forwarded target
 *    directly, and
 *  - the forwarder declaration remains available for inherited symbolic references, method handles
 *    and callers outside the loaded model. Local call-site proof never implies global removability.
 *
 * The checks preserve the executed target, arguments, initialization trigger and access contract.
 * This is source reconstruction of synthetic glue, not a promise of identical reflection metadata
 * or stack traces.
 *
 * ## Faithfulness / safety envelope (deliberately narrow)
 * The current instruction-shape checks require all of these:
 *  - it is `static`, not `synchronized`, and marked `synthetic` and/or `bridge`;
 *  - its body decodes (no decode error, no `try`/handler) to exactly one `invoke-static` followed by a
 *    `return`, and nothing else;
 *  - the resolved target is a public static method declared in the same class, preserving both
 *    the class-initialization trigger and accessibility from external callers;
 *  - changing an inherited symbolic owner does not expose a non-public declaring or enclosing class;
 *  - the target's signature is **identical** (same return type and parameter types) — so no covariant
 *    cast is being erased away;
 *  - the `invoke`'s arguments are exactly the method's incoming parameter registers, in order (an
 *    identity argument mapping — no reordering, dropping, or injected constants);
 *  - for a non-void forwarder the returned register is the call's own result.
 *
 * Anything outside this envelope (an instance method, a cross-class/accessor thunk, a real body,
 * a covariant/renamed signature or permuted argument list) is left completely untouched.
 * Forwarder **chains** are followed to the terminal real method (with a cycle/budget guard) so a caller is never rewritten onto another
 * intermediate forwarder, and a forwarder that only resolves through a cycle is left untouched.
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

    /** Rewrite proved call sites; declarations remain available to inherited or unseen references. */
    fun process(method: IrMethod) {
        // Caller side: rewrite each call to a forwarder into a direct call to the terminal target.
        for (block in method.blocks) {
            val insns = block.instructions
            for (i in insns.indices) {
                val invoke = insns[i] as? InvokeInstruction ?: continue
                if (invoke.opcode != IrOpcode.INVOKE || invoke.invokeKind != InvokeKind.STATIC) continue
                val callee = resolve(invoke.methodRef) ?: continue
                val terminal = terminalTarget(callee) ?: continue
                if (terminal.declaringType != invoke.methodRef.declaringType && !isPublicOwner(terminal)) continue
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
     * a safely-inlinable forwarder (not a forwarder, or its chain cycles / exceeds budget).
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
        // ACC_SYNCHRONIZED acquires the declaring Class monitor outside the instruction body.
        // An otherwise identity forwarder still has observable locking behavior of its own.
        if (method.accessFlags and AccessFlags.SYNCHRONIZED != 0) return null
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
        // invokestatic initializes the *resolved declaring class*, not necessarily its symbolic
        // owner. Removing a cross-class (or unresolved/inherited) thunk can skip initialization of
        // its class, superclass and default-method interfaces, including their failures.
        // Keep inherited symbolic target aliases explicit until their source-level access contract
        // can also be represented. This pass only redirects to a directly named declaration.
        if (target.declaringType != IrType.objectType(method.declaringClass.fullName)) return null
        val declaration = resolve(target) ?: return null
        if (declaration.declaringClass !== method.declaringClass) return null
        // Accessor thunks may expose a private/package/protected target to another class. Without
        // a caller-specific accessibility proof, only a public static target can replace that API.
        if (!declaration.isStatic || declaration.accessFlags and AccessFlags.PUBLIC == 0) return null
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

    private fun isPublicOwner(ref: MethodRef): Boolean {
        val className = (ref.declaringType as? IrType.Object)?.className ?: return false
        var cls: IrClass? = root.findClass(className) ?: return false
        val visited = HashSet<IrClass>()
        // An inherited public member can be exposed through a public subclass of a hidden owner.
        // Rewriting that call to the declaring class must not introduce an inaccessible type name.
        while (cls != null) {
            if (visited.size >= HIERARCHY_BUDGET || !visited.add(cls)) return false
            if (cls.accessFlags and AccessFlags.PUBLIC == 0) return false
            cls = cls.outerClass
        }
        return true
    }

    private fun resolve(ref: MethodRef): IrMethod? {
        val className = (ref.declaringType as? IrType.Object)?.className ?: return null
        var cls = root.findClass(className) ?: return null
        val visited = HashSet<IrClass>()
        // invokestatic may name a subclass while resolution finds the declaration in a superclass.
        // Static interface methods are not inherited; unknown/cyclic/deep hierarchies stay explicit.
        while (visited.size < HIERARCHY_BUDGET && visited.add(cls)) {
            cls.methods.firstOrNull {
                it.name == ref.name && it.argTypes == ref.paramTypes && it.returnType == ref.returnType
            }?.let { return it }
            if (cls.accessFlags and AccessFlags.INTERFACE != 0) return null
            val parent = (cls.superType as? IrType.Object)?.className ?: return null
            cls = root.findClass(parent) ?: return null
        }
        return null
    }

    private companion object {
        /** Bound on forwarder-chain following (real chains are 1–2 deep; guards against pathological input). */
        const val CHAIN_BUDGET = 32
        const val HIERARCHY_BUDGET = 64
    }
}
