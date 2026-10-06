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

/**
 * Synthetic static forwarder call-site simplification. Declarations remain available to inherited
 * symbolic references and other callers; initialization, monitor and accessibility contracts gate
 * every rewrite. The original cross-owner B→C fixture is retained rather than blindly inlined.
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
    fun crossOwnerForwarderRetainsClassInitializationAndCallTarget() {
        val root = IrRoot()
        val b = addClass(root, "inline.other.B")
        val a = addClass(root, "inline.A")
        val bridge = addMethod(b, "bridgeMth", staticSyntheticBridge, forwarderBody(cTestRef))
        val caller = addMethod(a, "useMth", AccessFlags.STATIC, forwarderBody(bBridgeRef))
        buildCfg(caller)
        MethodInliner(root).process(bridge)
        MethodInliner(root).process(caller)
        assertFalse(bridge.contains(AttrFlag.DONT_GENERATE), "calling the bridge initializes its declaring class")
        assertEquals("bridgeMth", invokesOf(caller).single().methodRef.name)
    }

    @Test
    fun sameOwnerPublicDeclaredTargetCanStillBeInlined() {
        val root = IrRoot()
        val b = addClass(root, "inline.other.B")
        val a = addClass(root, "inline.A")
        val target = FakeMethodRef("Linline/other/B;", "target", "V", emptyList())
        addMethod(b, "target", AccessFlags.PUBLIC or AccessFlags.STATIC,
            FakeCodeReader(0, listOf(Insn(Opcode.RETURN_VOID, 0))))
        val bridge = addMethod(b, "bridgeMth", staticSyntheticBridge, forwarderBody(target))
        val caller = addMethod(a, "useMth", AccessFlags.STATIC, forwarderBody(bBridgeRef))
        buildCfg(caller)
        MethodInliner(root).process(bridge)
        MethodInliner(root).process(caller)
        assertFalse(bridge.contains(AttrFlag.DONT_GENERATE), "local rewrites do not prove global declaration removability")
        assertEquals("target", invokesOf(caller).single().methodRef.name)
    }

    @Test
    fun inheritedSymbolicCallerMustNotLoseItsForwarderDeclaration() {
        val root = IrRoot()
        val owner = IrClass(root, "inline.other.B", AccessFlags.PUBLIC, IrType.OBJECT).also(root::addClass)
        IrClass(root, "inline.Child", AccessFlags.PUBLIC, IrType.objectType(owner.fullName)).also(root::addClass)
        val callerClass = addClass(root, "inline.Caller")
        val target = FakeMethodRef("Linline/other/B;", "target", "V", emptyList())
        addMethod(owner, "target", AccessFlags.PUBLIC or AccessFlags.STATIC,
            FakeCodeReader(0, listOf(Insn(Opcode.RETURN_VOID, 0))))
        val bridge = addMethod(owner, "bridgeMth", staticSyntheticBridge or AccessFlags.PUBLIC, forwarderBody(target))
        val inherited = FakeMethodRef("Linline/Child;", "bridgeMth", "V", emptyList())
        val caller = addMethod(callerClass, "call", AccessFlags.PUBLIC or AccessFlags.STATIC, forwarderBody(inherited))
        buildCfg(caller)
        MethodInliner(root).process(bridge)
        MethodInliner(root).process(caller)
        assertFalse(bridge.contains(AttrFlag.DONT_GENERATE), "an inherited symbolic call still needs the declared bridge")
        assertEquals(owner.fullName, (invokesOf(caller).single().methodRef.declaringType as IrType.Object).className)
        assertEquals("target", invokesOf(caller).single().methodRef.name)
    }

    @Test
    fun inheritedPublicMethodMustNotExposeItsPackagePrivateDeclaringClass() {
        val root = IrRoot()
        val owner = addClass(root, "inline.other.Hidden")
        IrClass(root, "inline.other.PublicChild", AccessFlags.PUBLIC, IrType.objectType(owner.fullName)).also(root::addClass)
        val callerClass = addClass(root, "outside.Caller")
        val target = FakeMethodRef("Linline/other/Hidden;", "target", "V", emptyList())
        addMethod(owner, "target", AccessFlags.PUBLIC or AccessFlags.STATIC,
            FakeCodeReader(0, listOf(Insn(Opcode.RETURN_VOID, 0))))
        val bridge = addMethod(owner, "bridgeMth", staticSyntheticBridge or AccessFlags.PUBLIC, forwarderBody(target))
        val inherited = FakeMethodRef("Linline/other/PublicChild;", "bridgeMth", "V", emptyList())
        val caller = addMethod(callerClass, "call", AccessFlags.PUBLIC or AccessFlags.STATIC, forwarderBody(inherited))
        buildCfg(caller)
        MethodInliner(root).process(bridge)
        MethodInliner(root).process(caller)
        assertFalse(bridge.contains(AttrFlag.DONT_GENERATE))
        assertEquals("inline.other.PublicChild", (invokesOf(caller).single().methodRef.declaringType as IrType.Object).className)
    }

    @Test
    fun symbolicSameOwnerWithoutDeclaredTargetDoesNotProveInitializationIdentity() {
        val root = IrRoot()
        val b = addClass(root, "inline.other.B")
        val target = FakeMethodRef("Linline/other/B;", "inherited", "V", emptyList())
        val bridge = addMethod(b, "bridgeMth", staticSyntheticBridge, forwarderBody(target))
        MethodInliner(root).process(bridge)
        assertFalse(bridge.contains(AttrFlag.DONT_GENERATE), "resolution could find the method in a superclass")
    }

    @Test
    fun privateTargetKeepsAccessorInsteadOfMakingExternalCallerIllegal() {
        val root = IrRoot()
        val b = addClass(root, "inline.other.B")
        val target = FakeMethodRef("Linline/other/B;", "hidden", "V", emptyList())
        addMethod(b, "hidden", AccessFlags.PRIVATE or AccessFlags.STATIC,
            FakeCodeReader(0, listOf(Insn(Opcode.RETURN_VOID, 0))))
        val bridge = addMethod(b, "bridgeMth", staticSyntheticBridge, forwarderBody(target))
        MethodInliner(root).process(bridge)
        assertFalse(bridge.contains(AttrFlag.DONT_GENERATE))
    }

    @Test
    fun synchronizedSyntheticForwarderKeepsItsMonitorAndCallTarget() {
        val root = IrRoot()
        val b = addClass(root, "inline.other.B")
        val a = addClass(root, "inline.A")
        val bridge = addMethod(b, "bridgeMth", staticSyntheticBridge or AccessFlags.SYNCHRONIZED, forwarderBody(cTestRef))
        val caller = addMethod(a, "useMth", AccessFlags.STATIC, forwarderBody(bBridgeRef))
        buildCfg(caller)
        MethodInliner(root).process(bridge)
        MethodInliner(root).process(caller)
        assertFalse(bridge.contains(AttrFlag.DONT_GENERATE), "implicit monitor acquisition is observable behavior")
        assertEquals("bridgeMth", invokesOf(caller).single().methodRef.name)
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
