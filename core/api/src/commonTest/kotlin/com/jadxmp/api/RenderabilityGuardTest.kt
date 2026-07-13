package com.jadxmp.api

import com.jadxmp.api.internal.RenderabilityGuard
import com.jadxmp.codegen.java.JavaCodeGenerator
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.PhiInstruction
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.cfg.ExceptionHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1 (cardinal-rule) guard: a method the Phase-2 bridge cannot render correctly — a branchy/φ-bearing
 * method with no region tree yet — must be **flagged**, so the "no-error" accuracy signal fails and
 * codegen marks it, rather than emitting clean-looking Java that references never-assigned φ registers.
 */
class RenderabilityGuardTest {

    private fun classWith(method: IrMethod): IrClass = method.declaringClass

    /** A fork (b0 → b1/b2) joining at b3 with a φ whose result is returned — the classic if-join. */
    private fun branchyMethod(): IrMethod {
        val root = IrRoot()
        val cls = IrClass(root, "a.Foo", accessFlags = 0x0001)
        root.addClass(cls)
        val m = IrMethod(cls, "pick", IrType.INT, emptyList(), accessFlags = 0x0009) // public static
        val b0 = BasicBlock(0)
        val b1 = BasicBlock(1)
        val b2 = BasicBlock(2)
        val b3 = BasicBlock(3)
        b0.successors.add(b1) // fork ⇒ merge ⇒ φ
        b0.successors.add(b2)
        b1.successors.add(b3)
        b2.successors.add(b3)
        val phiResult = RegisterOperand(1, IrType.INT)
        b3.instructions.add(PhiInstruction(phiResult))
        b3.instructions.add(Instruction(IrOpcode.RETURN, args = listOf(RegisterOperand(1, IrType.INT))))
        m.blocks.addAll(listOf(b0, b1, b2, b3))
        cls.methods.add(m)
        return m
    }

    /**
     * A try/catch method whose handler edge dedups with the normal flow, so the CFG *looks* linear
     * (b0 → b1, no fork) — but b1 is a handler entry (EXC_HANDLER). The flat path would drop the catch.
     */
    private fun tryCatchMethod(): IrMethod {
        val root = IrRoot()
        val cls = IrClass(root, "a.Guarded", accessFlags = 0x0001)
        root.addClass(cls)
        val m = IrMethod(cls, "guarded", IrType.VOID, emptyList(), accessFlags = 0x0009)
        val b0 = BasicBlock(0).apply { instructions.add(Instruction(IrOpcode.RETURN)) }
        val b1 = BasicBlock(1).apply { instructions.add(Instruction(IrOpcode.RETURN)) }
        b0.successors.add(b1) // single forward edge ⇒ passes the fork/back-edge check
        // b1 is the handler entry — this is what must make it non-renderable.
        b1[PipelineAttrs.EXC_HANDLER] = ExceptionHandler(b1, emptyList(), catchAll = true)
        m.blocks.addAll(listOf(b0, b1))
        cls.methods.add(m)
        return m
    }

    private fun straightLineMethod(): IrMethod {
        val root = IrRoot()
        val cls = IrClass(root, "a.Bar", accessFlags = 0x0001)
        root.addClass(cls)
        val m = IrMethod(cls, "go", IrType.VOID, emptyList(), accessFlags = 0x0009)
        m.blocks.add(BasicBlock(0).apply { instructions.add(Instruction(IrOpcode.RETURN)) })
        cls.methods.add(m)
        return m
    }

    @Test
    fun branchyMethodIsNotRenderable() {
        assertFalse(RenderabilityGuard.isRenderable(branchyMethod()))
    }

    @Test
    fun straightLineMethodIsRenderable() {
        assertTrue(RenderabilityGuard.isRenderable(straightLineMethod()))
    }

    @Test
    fun tryCatchMethodWithoutRegionIsNotRenderable() {
        // Even though its CFG is a single forward chain, the exception handler makes it non-renderable.
        assertFalse(RenderabilityGuard.isRenderable(tryCatchMethod()))
    }

    @Test
    fun flaggingAndRenderingATryCatchMethodSurfacesTheError() {
        val m = tryCatchMethod()
        RenderabilityGuard.flagIfUnrenderable(m)
        assertTrue(m.contains(AttrFlag.HAS_ERROR), "try/catch method without a region must be flagged")
        val code = JavaCodeGenerator().generate(classWith(m)).code
        assertTrue(
            code.contains("// JADXMP ERROR: unstructured control flow"),
            "dropped exception handling must be flagged, not rendered as clean straight-line:\n$code",
        )
    }

    @Test
    fun flaggingAttachesErrorToBranchyMethod() {
        val m = branchyMethod()
        RenderabilityGuard.flagIfUnrenderable(m)
        assertTrue(m.contains(AttrFlag.HAS_ERROR), "branchy method must carry HAS_ERROR")
        assertEquals(RenderabilityGuard.UNSTRUCTURED_MESSAGE, m[IrAttrs.ERROR]?.message)
    }

    @Test
    fun flaggingLeavesStraightLineMethodClean() {
        val m = straightLineMethod()
        RenderabilityGuard.flagIfUnrenderable(m)
        assertFalse(m.contains(AttrFlag.HAS_ERROR))
    }

    @Test
    fun flaggedMethodRendersWithJadxmpErrorMarker() {
        val m = branchyMethod()
        RenderabilityGuard.flagIfUnrenderable(m)
        val code = JavaCodeGenerator().generate(classWith(m)).code
        assertTrue(
            code.contains("// JADXMP ERROR: unstructured control flow"),
            "unrenderable method must be flagged in the source, not read as clean:\n$code",
        )
    }
}
