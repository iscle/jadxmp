package com.jadxmp.pipeline.cfg

import com.jadxmp.input.Opcode
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DominatorsTest {

    private fun diamond() = FakeCodeReader(
        1,
        listOf(
            Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
            Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4),
            Insn(Opcode.CONST, 2, intArrayOf(0), literal = 10),
            Insn(Opcode.GOTO, 3, target = 5),
            Insn(Opcode.CONST, 4, intArrayOf(0), literal = 20),
            Insn(Opcode.RETURN_VOID, 5),
        ),
    )

    private fun loop() = FakeCodeReader(
        1,
        listOf(
            Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // A
            Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 5), // H (header)
            Insn(Opcode.ADD_INT, 2, intArrayOf(0, 0)), // B (body): v0 += v0  (2addr)
            Insn(Opcode.GOTO, 3, target = 1), // back-edge to H
            Insn(Opcode.NOP, 4),
            Insn(Opcode.RETURN_VOID, 5), // E (exit)
        ),
    )

    @Test
    fun diamondDominatorTree() {
        val method = TestPipeline.buildMethod(diamond())
        TestPipeline.dominators(method)
        val entry = method.entryBlock!!
        val a = TestPipeline.blockAt(method, 0)
        val b = TestPipeline.blockAt(method, 2)
        val c = TestPipeline.blockAt(method, 4)
        val d = TestPipeline.blockAt(method, 5)

        assertEquals(entry, a.immediateDominator)
        assertEquals(a, b.immediateDominator)
        assertEquals(a, c.immediateDominator)
        assertEquals(a, d.immediateDominator) // merge idom is the split, not B or C
    }

    @Test
    fun diamondDominanceFrontier() {
        val method = TestPipeline.buildMethod(diamond())
        TestPipeline.dominators(method)
        val b = TestPipeline.blockAt(method, 2)
        val c = TestPipeline.blockAt(method, 4)
        val d = TestPipeline.blockAt(method, 5)
        assertEquals(setOf(d), b.dominanceFrontier.toSet())
        assertEquals(setOf(d), c.dominanceFrontier.toSet())
        assertEquals(emptySet(), d.dominanceFrontier.toSet())
    }

    @Test
    fun diamondDominatorSetsIncludeSelfAndChain() {
        val method = TestPipeline.buildMethod(diamond())
        TestPipeline.dominators(method)
        val entry = method.entryBlock!!
        val a = TestPipeline.blockAt(method, 0)
        val d = TestPipeline.blockAt(method, 5)
        // D is dominated by D, A, entry.
        assertEquals(setOf(d.id, a.id, entry.id), d.dominators.toSet())
    }

    @Test
    fun loopHeaderIsInOwnDominanceFrontier() {
        val method = TestPipeline.buildMethod(loop())
        TestPipeline.dominators(method)
        val h = TestPipeline.blockAt(method, 1)
        val b = TestPipeline.blockAt(method, 2)
        // A loop header appears in the dominance frontier of its body (and of itself).
        assertEquals(setOf(h), b.dominanceFrontier.toSet())
        assertEquals(setOf(h), h.dominanceFrontier.toSet())
    }

    @Test
    fun loopDominatorTree() {
        val method = TestPipeline.buildMethod(loop())
        TestPipeline.dominators(method)
        val entry = method.entryBlock!!
        val a = TestPipeline.blockAt(method, 0)
        val h = TestPipeline.blockAt(method, 1)
        val b = TestPipeline.blockAt(method, 2)
        val e = TestPipeline.blockAt(method, 5)
        assertEquals(entry, a.immediateDominator)
        assertEquals(a, h.immediateDominator)
        assertEquals(h, b.immediateDominator)
        assertEquals(h, e.immediateDominator)
    }

    @Test
    fun irreducibleTwoEntryLoopStillHasCorrectIdoms() {
        // entry -> {n1, n2}; n1 -> n2; n2 -> n1 (two loop entries => irreducible). No exception thrown.
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 2), // A: -> n2(2) or n1(1)
                Insn(Opcode.GOTO, 1, target = 2), // n1 -> n2
                Insn(Opcode.IF_EQZ, 2, intArrayOf(1), target = 1), // n2 -> n1(1) or fallthrough(3)
                Insn(Opcode.RETURN_VOID, 3),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.dominators(method)
        val entry = method.entryBlock!!
        val a = TestPipeline.blockAt(method, 0)
        val n1 = TestPipeline.blockAt(method, 1)
        val n2 = TestPipeline.blockAt(method, 2)
        assertEquals(entry, a.immediateDominator)
        // Both loop nodes are reachable from A by disjoint paths, so neither dominates the other.
        assertEquals(a, n1.immediateDominator)
        assertEquals(a, n2.immediateDominator)
    }

    @Test
    fun nestedLoopsDominatorChain() {
        // outer header O at 1, inner header I at 2; inner body 3 -> I, inner exit -> outer latch 4 -> O.
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // A
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 6), // O (outer header) -> exit(6)
                Insn(Opcode.IF_EQZ, 2, intArrayOf(1), target = 4), // I (inner header) -> latch(4)
                Insn(Opcode.GOTO, 3, target = 2), // inner body -> I
                Insn(Opcode.IF_EQZ, 4, intArrayOf(2), target = 1), // latch -> O (outer back-edge) or fall(5)
                Insn(Opcode.GOTO, 5, target = 1), // -> O
                Insn(Opcode.RETURN_VOID, 6),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.dominators(method)
        val a = TestPipeline.blockAt(method, 0)
        val o = TestPipeline.blockAt(method, 1)
        val i = TestPipeline.blockAt(method, 2)
        val body = TestPipeline.blockAt(method, 3)
        assertEquals(a, o.immediateDominator)
        assertEquals(o, i.immediateDominator)
        assertEquals(i, body.immediateDominator)
    }

    @Test
    fun selfEdgeBlock() {
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.ADD_INT, 0, intArrayOf(0, 0)), // L body
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 0), // loop back to own block start
                Insn(Opcode.RETURN_VOID, 2),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.dominators(method)
        val l = TestPipeline.blockAt(method, 0)
        assertTrue(l in l.successors, "self-loop edge must be present")
        // A self-loop block is in its own dominance frontier.
        assertTrue(l in l.dominanceFrontier)
    }

    @Test
    fun unreachableBlocksArePruned() {
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.RETURN_VOID, 0),
                Insn(Opcode.CONST, 1, intArrayOf(0), literal = 1), // dead code, nothing jumps here
                Insn(Opcode.RETURN_VOID, 2),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.dominators(method)
        assertFalse(
            method.blocks.any { b -> b.instructions.any { it.offset == 1 } },
            "unreachable block should be pruned",
        )
    }

    @Test
    fun multipleExitsAllReachTheSingleExitBlock() {
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 3),
                Insn(Opcode.RETURN_VOID, 1),
                Insn(Opcode.NOP, 2),
                Insn(Opcode.RETURN_VOID, 3),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.dominators(method)
        val exit = method.exitBlock!!
        assertEquals(2, exit.predecessors.size, "both return blocks must flow into the single exit")
    }

    @Test
    fun pureInfiniteLoopNullsUnreachableExit() {
        // `goto 0` — the synthetic exit is unreachable; it must be pruned and exitBlock cleared (not left
        // dangling), and the analysis must not crash.
        val reader = FakeCodeReader(0, listOf(Insn(Opcode.GOTO, 0, target = 0)))
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.dominators(method)
        assertEquals(null, method.exitBlock)
        val loop = TestPipeline.blockAt(method, 0)
        assertTrue(loop in loop.successors)
    }

    @Test
    fun diamondPostDominators() {
        val method = TestPipeline.buildMethod(diamond())
        TestPipeline.dominators(method)
        val a = TestPipeline.blockAt(method, 0)
        val b = TestPipeline.blockAt(method, 2)
        val c = TestPipeline.blockAt(method, 4)
        val d = TestPipeline.blockAt(method, 5)
        val exit = method.exitBlock!!
        // Both branches reconverge at D, so D post-dominates A, B and C.
        assertEquals(d, a[PipelineAttrs.IMMEDIATE_POST_DOMINATOR])
        assertEquals(d, b[PipelineAttrs.IMMEDIATE_POST_DOMINATOR])
        assertEquals(d, c[PipelineAttrs.IMMEDIATE_POST_DOMINATOR])
        assertEquals(exit, d[PipelineAttrs.IMMEDIATE_POST_DOMINATOR])
    }

    @Test
    fun postDominators() {
        val method = TestPipeline.buildMethod(loop())
        TestPipeline.dominators(method)
        val entry = method.entryBlock!!
        val exit = method.exitBlock!!
        val a = TestPipeline.blockAt(method, 0)
        val h = TestPipeline.blockAt(method, 1)
        val b = TestPipeline.blockAt(method, 2)
        val e = TestPipeline.blockAt(method, 5)
        assertEquals(a, entry[PipelineAttrs.IMMEDIATE_POST_DOMINATOR])
        assertEquals(h, a[PipelineAttrs.IMMEDIATE_POST_DOMINATOR])
        assertEquals(e, h[PipelineAttrs.IMMEDIATE_POST_DOMINATOR])
        assertEquals(h, b[PipelineAttrs.IMMEDIATE_POST_DOMINATOR])
        assertEquals(exit, e[PipelineAttrs.IMMEDIATE_POST_DOMINATOR])
    }
}
