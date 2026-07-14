package com.jadxmp.pipeline.inline

import com.jadxmp.input.AccessFlags
import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.cfg.CfgBuilder
import com.jadxmp.pipeline.decode.MethodDecoder
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The synthetic-bridge forwarder inline+drop pass ([MethodInliner]).
 *
 * Mirrors `corpus/smali/inline/TestMethodInline`: `inline.other.B.bridgeMth()` is a `bridge synthetic`
 * static forwarder to `inline.other.C.test()`, called from `inline.A.useMth()`. The positive test pins
 * that the forwarder is dropped and its call site rewritten to the target; the negatives pin that a
 * non-synthetic forwarder and a non-trivial synthetic method are both left completely untouched.
 */
class MethodInlineTest {

    private val cTestRef = FakeMethodRef("Linline/other/C;", "test", "V", emptyList())
    private val bBridgeRef = FakeMethodRef("Linline/other/B;", "bridgeMth", "V", emptyList())

    private fun addClass(root: IrRoot, binaryName: String): IrClass =
        IrClass(root, binaryName, accessFlags = 0, superType = IrType.OBJECT).also { root.addClass(it) }

    private fun addMethod(cls: IrClass, name: String, flags: Int, reader: FakeCodeReader): IrMethod {
        val m = IrMethod(cls, name, IrType.VOID, emptyList(), flags)
        m[PipelineAttrs.CODE_READER] = reader
        m[PipelineAttrs.REGISTER_COUNT] = reader.registerCount
        cls.methods.add(m)
        return m
    }

    /** Body: `invoke-static <ref>(); return-void`. */
    private fun forwarderBody(target: FakeMethodRef) = FakeCodeReader(
        0,
        listOf(
            Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = target),
            Insn(Opcode.RETURN_VOID, 1),
        ),
    )

    private fun buildCfg(method: IrMethod) {
        val code = MethodDecoder().decode(method[PipelineAttrs.CODE_READER]!!)
        CfgBuilder(method, code).build()
    }

    private fun invokesOf(method: IrMethod): List<InvokeInstruction> =
        method.blocks.flatMap { it.instructions }.filterIsInstance<InvokeInstruction>()

    private val staticSyntheticBridge =
        AccessFlags.STATIC or AccessFlags.SYNTHETIC or AccessFlags.BRIDGE

    @Test
    fun syntheticForwarderIsDroppedAndItsCallSiteRewrittenToTheTarget() {
        val root = IrRoot()
        val b = addClass(root, "inline.other.B")
        val a = addClass(root, "inline.A")
        val bridge = addMethod(b, "bridgeMth", staticSyntheticBridge, forwarderBody(cTestRef))
        val caller = addMethod(a, "useMth", AccessFlags.STATIC, forwarderBody(bBridgeRef))
        buildCfg(caller)

        MethodInliner(root).process(bridge)
        MethodInliner(root).process(caller)

        // Drop: the synthetic forwarder is not emitted.
        assertTrue(bridge.contains(AttrFlag.DONT_GENERATE), "synthetic forwarder must be dropped")

        // Inline: the caller now invokes C.test() directly, not the dropped bridge.
        val call = invokesOf(caller).single()
        val target = call.methodRef.declaringType as IrType.Object
        assertEquals("inline.other.C", target.className, "call site rewritten to the forwarded target class")
        assertEquals("test", call.methodRef.name, "call site rewritten to the forwarded target method")
    }

    @Test
    fun nonSyntheticForwarderIsNotDroppedOrInlined() {
        val root = IrRoot()
        val b = addClass(root, "inline.other.B")
        val a = addClass(root, "inline.A")
        // Same trivial body, but a plain (non-synthetic, non-bridge) static method: an observable API method.
        val bridge = addMethod(b, "bridgeMth", AccessFlags.STATIC, forwarderBody(cTestRef))
        val caller = addMethod(a, "useMth", AccessFlags.STATIC, forwarderBody(bBridgeRef))
        buildCfg(caller)

        MethodInliner(root).process(bridge)
        MethodInliner(root).process(caller)

        assertFalse(bridge.contains(AttrFlag.DONT_GENERATE), "a non-synthetic method must never be dropped")
        val call = invokesOf(caller).single()
        assertEquals("bridgeMth", call.methodRef.name, "call to a non-forwarder must be left unchanged")
    }

    @Test
    fun nonTrivialSyntheticMethodIsNotDroppedOrInlined() {
        val root = IrRoot()
        val b = addClass(root, "inline.other.B")
        val a = addClass(root, "inline.A")
        // Synthetic + static, but the body does real work: TWO calls, not a single forwarding invoke.
        val realWork = FakeCodeReader(
            0,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = cTestRef),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = cTestRef),
                Insn(Opcode.RETURN_VOID, 2),
            ),
        )
        val notForwarder = addMethod(b, "bridgeMth", staticSyntheticBridge, realWork)
        val caller = addMethod(a, "useMth", AccessFlags.STATIC, forwarderBody(bBridgeRef))
        buildCfg(caller)

        MethodInliner(root).process(notForwarder)
        MethodInliner(root).process(caller)

        assertFalse(notForwarder.contains(AttrFlag.DONT_GENERATE), "a synthetic method with a real body must not be dropped")
        val call = invokesOf(caller).single()
        assertEquals("bridgeMth", call.methodRef.name, "call to a non-trivial method must be left unchanged")
    }
}
