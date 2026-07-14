package com.jadxmp.pipeline.structure

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopKind
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A loop whose header carries a real statement before its exit test (its condition depends on
 * per-iteration work, so it is neither a clean pre-test `while` nor a post-test `do/while`) structures
 * as `while (true) { <header stmt>; if (<exit>) break; <body> }` — the general infinite-loop form with a
 * `break` on the single conditional exit — rather than bailing.
 */
class RegionMakerLoopTest {

    private fun findLoop(region: Region?): LoopRegion? {
        val out = ArrayList<LoopRegion>()
        fun visit(r: Region?) {
            when (r) {
                is LoopRegion -> { out.add(r); visit(r.body) }
                is SequenceRegion -> r.children.forEach { if (it is Region) visit(it) }
                is IfRegion -> { visit(r.thenRegion); r.elseRegion?.let { visit(it) } }
                else -> {}
            }
        }
        visit(region)
        return out.firstOrNull()
    }

    @Test
    fun loopWithStatementBeforeTestBuildsWhileTrueWithBreak() {
        val use = FakeMethodRef("Lc/F;", "use", "V", listOf("I"))
        // i = 0;
        // while (true) { x = i + 1; if (x == 0) break; use(x); i = x; }   (x used in body ⇒ header not a pure test)
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // i = 0
                Insn(Opcode.ADD_INT_LIT, 1, intArrayOf(1, 0), literal = 1), // header: x = i + 1
                Insn(Opcode.IF_EQZ, 2, intArrayOf(1), target = 6), // if (x == 0) -> exit
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(1), indexType = IndexType.METHOD_REF, methodRef = use), // use(x)
                Insn(Opcode.MOVE, 4, intArrayOf(0, 1)), // i = x
                Insn(Opcode.GOTO, 5, target = 1), // loop back to header
                Insn(Opcode.RETURN_VOID, 6),
            ),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "m")
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "a header-with-statement loop must structure")
        assertFalse(method.contains(AttrFlag.HAS_ERROR))

        val loop = findLoop(method.region)
        assertNotNull(loop, "a LoopRegion must be produced")
        assertEquals(LoopKind.INFINITE, loop.kind, "a header carrying a statement ⇒ while (true) with a break")
        // The exit is modelled as a `break` inside the loop body (not a lost edge).
        val hasBreak = collectOpcodes(loop.body).any { it == com.jadxmp.ir.insn.IrOpcode.BREAK }
        assertTrue(hasBreak, "the single conditional exit must be a break")
    }

    @Test
    fun degenerateIfWhoseArmsShareOneTargetIsConsumedNotLeaked() {
        // `if (a == 0) {} return;` — the `if`'s two arms are the SAME target, so the block has a SINGLE CFG
        // successor: the condition is dead (both paths are identical) and side-effect-free (a register
        // compare). Phase-2 degenerate-branch consumption marks the `if` DONT_GENERATE and structures the
        // method as plain `return;` — NO code is dropped (there is no distinct arm to lose) and the
        // comparison does NOT leak as a bare `a == 0;` non-statement. This is a strict improvement over the
        // earlier honest bail: the shape is now provably structurable. (A THROWING/effectful degenerate
        // condition — an inlined call/field-read — still bails; see the mayThrow guard in RegionMaker.)
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 1), // target == fall-through == B1
                Insn(Opcode.RETURN_VOID, 1),
            ),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "m", argTypes = listOf(com.jadxmp.ir.type.IrType.INT))
        TestPipeline.structured(method)

        // Universal invariant: a structured method never leaves an emittable branch un-consumed.
        assertNoLeakedBranch(method)
        // The dead, side-effect-free branch is consumed and the method structures (not a bail).
        assertNotNull(method.region, "a degenerate if with a pure condition and one target structures as `return;`")
        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the pure degenerate if structures cleanly")
        // The comparison is SPECIFICALLY elided (DONT_GENERATE), never rendered as a bare statement.
        val ifInsn = method.blocks.flatMap { it.instructions }
            .first { it is com.jadxmp.ir.insn.IfInstruction }
        assertTrue(ifInsn.contains(AttrFlag.DONT_GENERATE), "the dead comparison must be consumed, not rendered")
    }

    @Test
    fun neverExitingInnerLoopArmStructuresOrBailsButNeverLeaks() {
        // while (true) { if (v1 == 0) break; if (v2 == 0) { while (true) {} } }   — one arm of the inner
        // `if` enters a never-exiting loop. Whatever the outcome, it must be correct (the while(true) arm
        // present) OR an honest bail — NEVER a dropped arm / leaked comparison reported as structured.
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(1), target = 5), // outer header: if (v1==0) break
                Insn(Opcode.IF_EQZ, 1, intArrayOf(2), target = 4), // if (v2==0) -> inner infinite loop
                Insn(Opcode.GOTO, 2, target = 0), // continue outer
                Insn(Opcode.NOP, 3),
                Insn(Opcode.GOTO, 4, target = 4), // inner while(true){} (self loop)
                Insn(Opcode.RETURN_VOID, 5),
            ),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "m", argTypes = listOf(com.jadxmp.ir.type.IrType.INT, com.jadxmp.ir.type.IrType.INT))
        TestPipeline.structured(method)
        assertNoLeakedBranch(method) // structured-or-null, but never a leaked/dropped branch
    }

    /** The invariant the net guarantees: no block reachable in the region tree leaks an un-consumed branch. */
    private fun assertNoLeakedBranch(method: com.jadxmp.ir.node.IrMethod) {
        val region = method.region ?: return // honest bail is fine
        val emitted = HashSet<com.jadxmp.ir.node.BasicBlock>()
        fun walk(c: com.jadxmp.ir.node.IrContainer?) {
            when (c) {
                is com.jadxmp.ir.node.BasicBlock -> emitted.add(c)
                is SequenceRegion -> c.children.forEach { walk(it) }
                is IfRegion -> { walk(c.thenRegion); c.elseRegion?.let { walk(it) } }
                is LoopRegion -> walk(c.body)
                is com.jadxmp.ir.region.SwitchRegion -> { c.cases.forEach { walk(it.body) }; c.defaultCase?.let { walk(it) } }
                is com.jadxmp.ir.region.TryCatchRegion -> {
                    walk(c.tryRegion); c.catches.forEach { walk(it.body) }; c.finallyRegion?.let { walk(it) }
                }
                is com.jadxmp.ir.region.SyncRegion -> walk(c.body)
                else -> {}
            }
        }
        walk(region)
        for (b in emitted) {
            val last = b.instructions.lastOrNull() ?: continue
            val leaks = (last is com.jadxmp.ir.insn.IfInstruction || last is com.jadxmp.ir.insn.SwitchInstruction) &&
                !last.contains(AttrFlag.DONT_GENERATE)
            assertFalse(leaks, "block B${b.id} leaks an un-consumed branch as a bare statement")
        }
    }

    private fun collectOpcodes(region: Region): List<com.jadxmp.ir.insn.IrOpcode> {
        val out = ArrayList<com.jadxmp.ir.insn.IrOpcode>()
        fun visit(c: com.jadxmp.ir.node.IrContainer?) {
            when (c) {
                is com.jadxmp.ir.node.BasicBlock -> c.instructions.forEach { out.add(it.opcode) }
                is SequenceRegion -> c.children.forEach { visit(it) }
                is IfRegion -> { visit(c.thenRegion); c.elseRegion?.let { visit(it) } }
                is LoopRegion -> visit(c.body)
                else -> {}
            }
        }
        visit(region)
        return out
    }
}
