package com.jadxmp.ir.region

import com.jadxmp.ir.insn.ConditionOp
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RegionTest {

    private fun op(n: Int) = RegisterOperand(n, IrType.INT)
    private val cond = Condition.Compare(ConditionOp.LT, op(0), op(1))

    @Test
    fun sequenceRegionParentsNestedRegions() {
        val seq = SequenceRegion()
        val block = BasicBlock(0)
        val inner = SequenceRegion()
        seq.add(block)
        seq.add(inner)
        assertEquals(2, seq.children.size)
        assertSame(seq, inner.parent)
        // a plain block is a container but not a region, so no parent link
        assertTrue(seq.children[0] is BasicBlock)
    }

    @Test
    fun ifRegionParentLinks() {
        val thenR = SequenceRegion()
        val elseR = SequenceRegion()
        val ifR = IfRegion(cond, thenR, elseR)
        assertSame(ifR, thenR.parent)
        assertSame(ifR, elseR.parent)
        assertEquals(cond, ifR.condition)
    }

    @Test
    fun loopRegionInfiniteHasNoCondition() {
        val body = SequenceRegion()
        val loop = LoopRegion(LoopKind.INFINITE, condition = null, body = body)
        assertNull(loop.condition)
        assertSame(loop, body.parent)
        assertEquals(LoopKind.INFINITE, loop.kind)
    }

    @Test
    fun switchRegionCasesAndDefault() {
        val caseA = SwitchCase(listOf(1L, 2L), SequenceRegion())
        val caseB = SwitchCase(listOf(3L), SequenceRegion())
        val def = SequenceRegion()
        val sw = SwitchRegion(op(0), listOf(caseA, caseB), def)
        assertEquals(2, sw.cases.size)
        assertSame(sw, caseA.body.parent)
        assertSame(sw, def.parent)
        assertEquals(listOf(1L, 2L), sw.cases[0].keys)
    }

    @Test
    fun tryCatchRegionStructure() {
        val tryR = SequenceRegion()
        val handler = SequenceRegion()
        val catch = CatchClause(listOf(IrType.THROWABLE), op(2), handler)
        val finallyR = SequenceRegion()
        val tc = TryCatchRegion(tryR, listOf(catch), finallyR)
        assertSame(tc, tryR.parent)
        assertSame(tc, handler.parent)
        assertSame(tc, finallyR.parent)
        assertEquals(1, tc.catches.size)
    }

    @Test
    fun syncRegionStructure() {
        val body = SequenceRegion()
        val sync = SyncRegion(op(0), body)
        assertSame(sync, body.parent)
    }

    @Test
    fun regionKindsAreExhaustive() {
        // Compile-time proof the hierarchy is sealed and every kind is handled.
        val regions: List<Region> = listOf(
            SequenceRegion(),
            IfRegion(cond, SequenceRegion()),
            LoopRegion(LoopKind.WHILE, cond, SequenceRegion()),
            SwitchRegion(op(0), emptyList()),
            TryCatchRegion(SequenceRegion(), emptyList()),
            SyncRegion(op(0), SequenceRegion()),
        )
        for (r in regions) {
            val label = when (r) {
                is SequenceRegion -> "seq"
                is IfRegion -> "if"
                is LoopRegion -> "loop"
                is SwitchRegion -> "switch"
                is TryCatchRegion -> "try"
                is SyncRegion -> "sync"
            }
            assertTrue(label.isNotEmpty())
            val container: IrContainer = r // every region is a container (compile-time proof)
            assertEquals(r, container)
        }
    }

    @Test
    fun conditionTreeShape() {
        val c = Condition.And(
            listOf(
                Condition.Compare(ConditionOp.GT, op(0), op(1)),
                Condition.Not(Condition.BoolTest(op(2))),
            ),
        )
        assertEquals(2, c.terms.size)
        assertTrue(c.terms[1] is Condition.Not)
    }
}
