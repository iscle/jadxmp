package com.jadxmp.pipeline.ssa

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.PhiInstruction
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SsaTest {

    private fun phis(method: com.jadxmp.ir.node.IrMethod) =
        method.blocks.sumOf { (it[PipelineAttrs.PHI_LIST]?.size) ?: 0 }

    @Test
    fun diamondPlacesOnePhiWithTwoArgs() {
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4),
                Insn(Opcode.CONST, 2, intArrayOf(0), literal = 10),
                Insn(Opcode.GOTO, 3, target = 5),
                Insn(Opcode.CONST, 4, intArrayOf(0), literal = 20),
                Insn(Opcode.RETURN, 5, intArrayOf(0)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.ssa(method)

        val d = TestPipeline.blockAt(method, 5)
        val b = TestPipeline.blockAt(method, 2)
        val c = TestPipeline.blockAt(method, 4)
        val phiList = d[PipelineAttrs.PHI_LIST]!!
        assertEquals(1, phiList.size)
        val phi = phiList[0] as PhiInstruction
        assertEquals(2, phi.argCount)
        assertEquals(setOf(b, c), phi.incoming.map { it.from }.toSet())

        // The return reads the φ's result value.
        val ret = d.instructions.first { it.opcode == IrOpcode.RETURN }
        val retVar = (ret.getArg(0) as RegisterOperand).ssaValue
        assertSame(phi.result!!.ssaValue, retVar)
    }

    @Test
    fun loopPhiMergesInitAndBackEdge() {
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // A
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 5), // H
                Insn(Opcode.ADD_INT, 2, intArrayOf(0, 0)), // B: v0 += v0
                Insn(Opcode.GOTO, 3, target = 1),
                Insn(Opcode.NOP, 4),
                Insn(Opcode.RETURN_VOID, 5),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.ssa(method)

        val h = TestPipeline.blockAt(method, 1)
        val phiList = h[PipelineAttrs.PHI_LIST]!!
        assertEquals(1, phiList.size)
        val phi = phiList[0] as PhiInstruction

        // Two incoming edges: the pre-header A and the back-edge block B.
        assertEquals(2, phi.argCount)
        // The loop condition and the add both read the φ result.
        val ifInsn = h.instructions.first { it.opcode == IrOpcode.IF }
        assertSame(phi.result!!.ssaValue, (ifInsn.getArg(0) as RegisterOperand).ssaValue)
    }

    @Test
    fun singleDefinitionNeedsNoPhi() {
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 7),
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 0),
                Insn(Opcode.IF_EQZ, 2, intArrayOf(1), target = 4),
                Insn(Opcode.GOTO, 3, target = 5),
                Insn(Opcode.NOP, 4),
                Insn(Opcode.RETURN, 5, intArrayOf(0)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.ssa(method)
        assertEquals(0, phis(method), "no φ expected when every register has a single definition")

        // The return still resolves to the one const definition.
        val ret = TestPipeline.blockAt(method, 5).instructions.first { it.opcode == IrOpcode.RETURN }
        val def = (ret.getArg(0) as RegisterOperand).ssaValue!!.assign.parent
        assertEquals(IrOpcode.CONST, def!!.opcode)
    }

    @Test
    fun everyRemainingPhiHasAtLeastTwoDistinctArgs() {
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4),
                Insn(Opcode.CONST, 2, intArrayOf(0), literal = 10),
                Insn(Opcode.GOTO, 3, target = 5),
                Insn(Opcode.CONST, 4, intArrayOf(0), literal = 20),
                Insn(Opcode.RETURN, 5, intArrayOf(0)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.ssa(method)
        for (block in method.blocks) {
            val list = block[PipelineAttrs.PHI_LIST] ?: continue
            for (phi in list) {
                phi as PhiInstruction
                val distinct = (0 until phi.argCount)
                    .mapNotNull { (phi.getArg(it) as RegisterOperand).ssaValue }
                    .filter { it !== phi.result!!.ssaValue }
                    .toSet()
                assertTrue(distinct.size >= 2, "useless φ survived cleanup: $phi")
            }
        }
    }

    @Test
    fun phiArgumentCanBeAnotherPhi() {
        // A loop whose body contains an if that reassigns v0: an inner-merge φ feeds the header φ.
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // A
                Insn(Opcode.IF_EQZ, 1, intArrayOf(1), target = 7), // H (header) -> exit
                Insn(Opcode.IF_EQZ, 2, intArrayOf(2), target = 5), // inner if
                Insn(Opcode.CONST, 3, intArrayOf(0), literal = 1), // then: v0 = 1
                Insn(Opcode.GOTO, 4, target = 6),
                Insn(Opcode.CONST, 5, intArrayOf(0), literal = 2), // else: v0 = 2
                Insn(Opcode.GOTO, 6, target = 1), // merge M -> back-edge to H
                Insn(Opcode.RETURN, 7, intArrayOf(0)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.ssa(method)

        val h = TestPipeline.blockAt(method, 1)
        val m = TestPipeline.blockAt(method, 6)
        val headerPhi = h[PipelineAttrs.PHI_LIST]!!.single() as PhiInstruction
        // The merge block M holds a φ; the header φ's back-edge argument is exactly that φ's result.
        val mergePhi = m[PipelineAttrs.PHI_LIST]!!.single() as PhiInstruction
        val argDefs = (0 until headerPhi.argCount)
            .map { (headerPhi.getArg(it) as RegisterOperand).ssaValue!!.assign.parent }
        assertTrue(argDefs.any { it === mergePhi }, "header φ should take the merge φ as an argument (φ-of-φ)")
    }

    @Test
    fun unusedInvokeResultIsDropped() {
        val ref = FakeMethodRef("Lcom/example/Foo;", "bar", "I", emptyList())
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = ref),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)),
                Insn(Opcode.RETURN_VOID, 2), // v0 never used
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.ssa(method)
        val invoke = method.blocks.flatMap { it.instructions }.first { it.opcode == IrOpcode.INVOKE }
        assertNull(invoke.result, "unused invoke result should be cleared")
    }

    @Test
    fun deadPureDefIsRemovedWithCascade() {
        // int f() { int a = 5; int b = 7; int u = a + b; return a; }  — u is never read, so the pure
        // `a + b` is dead code. DCE removes it; that in turn makes `b` dead, so its CONST is swept too.
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 5), // a = 5
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 7), // b = 7
                Insn(Opcode.ADD_INT, 2, intArrayOf(2, 0, 1)), // u = a + b  (dead)
                Insn(Opcode.RETURN, 3, intArrayOf(0)), // return a
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.ssa(method)
        val insns = method.blocks.flatMap { it.instructions }
        assertTrue(insns.none { it.opcode == IrOpcode.ARITH }, "the dead `a + b` must be removed")
        // b's CONST (literal 7) cascades to dead once its only reader (the ADD) is gone.
        assertTrue(insns.none { it.opcode == IrOpcode.CONST && it.getArg(0).let { a -> a is com.jadxmp.ir.insn.LiteralOperand && a.value == 7L } }, "b's now-dead CONST must be swept too")
        // a's CONST (literal 5) is still live (returned).
        assertTrue(insns.any { it.opcode == IrOpcode.CONST && it.getArg(0).let { a -> a is com.jadxmp.ir.insn.LiteralOperand && a.value == 5L } }, "the live `a` CONST is kept")
    }

    @Test
    fun usedPureDefIsKept() {
        // int f() { int a = 5; int b = 7; return a + b; }  — the sum IS used, so it must survive DCE.
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 5),
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 7),
                Insn(Opcode.ADD_INT, 2, intArrayOf(2, 0, 1)), // u = a + b  (used)
                Insn(Opcode.RETURN, 3, intArrayOf(2)), // return u
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.ssa(method)
        assertTrue(
            method.blocks.flatMap { it.instructions }.any { it.opcode == IrOpcode.ARITH },
            "a used pure def must be kept",
        )
    }

    @Test
    fun deadDivIsKeptBecauseItCanThrow() {
        // int f() { int a = 6; int b = 2; int u = a / b; return a; }  — u is dead, but integer division
        // throws ArithmeticException on a zero divisor, so removing it would drop an observable effect.
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 6),
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 2),
                Insn(Opcode.DIV_INT, 2, intArrayOf(2, 0, 1)), // u = a / b  (dead, but may throw)
                Insn(Opcode.RETURN, 3, intArrayOf(0)), // return a
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.ssa(method)
        assertTrue(
            method.blocks.flatMap { it.instructions }.any { it.opcode == IrOpcode.ARITH },
            "a dead but potentially-throwing div/rem must be kept",
        )
    }

    @Test
    fun instanceMethodBindsThisParameter() {
        val reader = FakeCodeReader(
            1,
            listOf(Insn(Opcode.RETURN_VOID, 0)),
        )
        val method = TestPipeline.buildMethod(reader, isStatic = false)
        TestPipeline.ssa(method)
        val thisVar = method.thisArg
        assertTrue(thisVar != null)
        assertEquals(IrType.objectType("com.example.Sample"), thisVar!!.type)
    }
}
