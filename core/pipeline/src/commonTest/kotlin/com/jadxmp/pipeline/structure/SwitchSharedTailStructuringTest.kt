package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.input.SwitchPayload
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.region.SwitchRegion
import com.jadxmp.ir.region.TryCatchRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A switch whose `default` case body is a straight-line block ALSO reached from the enclosing `if`'s
 * else-arm — the shape of `conditions/TestIfAndSwitch`. The shared block is not dominated by the switch,
 * so it falls outside the switch body; before the fix, building the default case reached it and the
 * non-local-exit guard bailed ("non-local exit from loop"). It is a mutually-exclusive DUPLICABLE tail
 * (its sole successor is the switch merge), so it is now copied into the default case (jadx tail
 * duplication) and the method fully structures.
 */
class SwitchSharedTailStructuringTest {

    private fun firstSwitch(region: Region?): SwitchRegion? {
        var found: SwitchRegion? = null
        fun walk(c: IrContainer) {
            when (c) {
                is SwitchRegion -> if (found == null) found = c
                is IfRegion -> { walk(c.thenRegion); c.elseRegion?.let { walk(it) } }
                is LoopRegion -> walk(c.body)
                is SequenceRegion -> c.children.forEach { walk(it) }
                else -> {}
            }
        }
        region?.let { walk(it) }
        return found
    }

    private fun firstIf(region: Region?): IfRegion? {
        var found: IfRegion? = null
        fun walk(c: IrContainer) {
            when (c) {
                is IfRegion -> { if (found == null) found = c }
                is LoopRegion -> walk(c.body)
                is SequenceRegion -> c.children.forEach { walk(it) }
                else -> {}
            }
        }
        region?.let { walk(it) }
        return found
    }

    private fun leaves(c: IrContainer): List<Instruction> {
        val out = ArrayList<Instruction>()
        fun walk(c: IrContainer) {
            when (c) {
                is BasicBlock -> out.addAll(c.instructions)
                is IfRegion -> { walk(c.thenRegion); c.elseRegion?.let { walk(it) } }
                is LoopRegion -> walk(c.body)
                is SequenceRegion -> c.children.forEach { walk(it) }
                is SwitchRegion -> { c.cases.forEach { walk(it.body) }; c.defaultCase?.let { walk(it) } }
                is TryCatchRegion -> { walk(c.tryRegion); c.catches.forEach { walk(it.body) }; c.finallyRegion?.let { walk(it) } }
                else -> {}
            }
        }
        walk(c)
        return out
    }

    private fun hasConst(c: IrContainer, value: Long): Boolean =
        leaves(c).any { insn ->
            insn.opcode == IrOpcode.CONST && insn.argCount > 0 &&
                insn.getArg(0).let { it is LiteralOperand && it.value == value }
        }

    // conditions/TestIfAndSwitch:
    //   if (p0 != 0) { switch (p1) { case 0: update=1; } } else { update=0; }
    //   if (update == 0) return; return;
    // The switch DEFAULT target (merge14) is ALSO the outer-if else target — a straight-line block
    // (`update=0`) not dominated by the switch, whose sole successor is the switch merge.
    @Test
    fun switchDefaultTailSharedWithEnclosingElseDuplicatesAndStructures() {
        val payload = SwitchPayload(keys = intArrayOf(0), targets = intArrayOf(2)) // rel to switch off1 -> off3
        val reader = FakeCodeReader(
            3, // v0 = update; v1 = p0 (outer cond); v2 = p1 (selector)
            listOf(
                Insn(Opcode.IF_NEZ, 0, intArrayOf(1), target = 5), // B: if p0!=0 goto merge14(off5); fall to switch
                Insn(Opcode.PACKED_SWITCH, 1, intArrayOf(2), target = 40), // switch(p1); default falls to off2
                Insn(Opcode.GOTO, 2, target = 5), // switch default -> merge14(off5)
                Insn(Opcode.CONST, 3, intArrayOf(0), literal = 1), // case 0: update = 1
                Insn(Opcode.GOTO, 4, target = 6), // -> merge15(off6)
                Insn(Opcode.CONST, 5, intArrayOf(0), literal = 0), // merge14 (shared): update = 0; fall to merge15
                Insn(Opcode.IF_EQZ, 6, intArrayOf(0), target = 8), // merge15: if update==0 goto off8; fall off7
                Insn(Opcode.RETURN_VOID, 7),
                Insn(Opcode.RETURN_VOID, 8),
                Insn(Opcode.PACKED_SWITCH_PAYLOAD, 40, payload = payload),
            ),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT, IrType.INT))
        TestPipeline.structured(method)

        val region = method.region
        assertNotNull(region, "switch-in-if with a shared default tail must structure (tail duplication)")
        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "must be flagged fully structured")

        val outerIf = firstIf(region)
        assertNotNull(outerIf, "the outer if must be present")

        val sw = firstSwitch(region)
        assertNotNull(sw, "a SwitchRegion must be present")
        assertEquals(1, sw.cases.size, "one explicit case (key 0)")
        val default = sw.defaultCase
        assertNotNull(default, "the shared tail becomes the (duplicated) default case body")

        // The shared `update = 0` tail was DUPLICATED into the switch default (the fix): before the fix the
        // default case reached that out-of-body block and bailed. It must now appear inside the default.
        assertTrue(hasConst(default, 0L), "the shared tail (update = 0) is duplicated into the default case")

        // Both mutually-exclusive breaks (case 0 and default) reach the merge.
        assertTrue(
            leaves(region).count { it.opcode == IrOpcode.BREAK } >= 1,
            "case/default breaks to the switch merge are emitted",
        )
        // Rule-4 net: no un-consumed branch leaked as a bare statement.
        for (insn in leaves(region)) {
            if (insn is IfInstruction && !insn.contains(AttrFlag.DONT_GENERATE)) {
                error("an un-consumed IF leaked as a bare statement")
            }
        }
    }

    // NEGATIVE: the duplication is restricted to a straight-line tail whose SOLE successor is the switch
    // merge. When the out-of-body shared block is a two-way BRANCH (not duplicable), the switch default
    // reaching it is a genuine cross-structure shape we do not model — it must still BAIL honestly, never
    // duplicate a branch subtree. This pins the `isDuplicable && sole-succ === follow` restriction.
    //
    //   if (p0 != 0) -> SHARED   else -> switch(p1) { case 0: r=1; default: -> SHARED }
    //   SHARED: if (p2 == 0) r=3 else r=2   (both arms -> merge M)   // a two-way branch, not duplicable
    //   M: if (r == 0) return; return;
    @Test
    fun switchDefaultReachingSharedBranchStillBailsHonestly() {
        val payload = SwitchPayload(keys = intArrayOf(0), targets = intArrayOf(2)) // rel to switch off1 -> off3
        val reader = FakeCodeReader(
            4, // v0 = r; v1 = p0; v2 = p1 (selector); v3 = p2
            listOf(
                Insn(Opcode.IF_NEZ, 0, intArrayOf(1), target = 5), // if p0!=0 goto SHARED(off5); fall to switch
                Insn(Opcode.PACKED_SWITCH, 1, intArrayOf(2), target = 40), // switch(p1); default falls to off2
                Insn(Opcode.GOTO, 2, target = 5), // switch default -> SHARED(off5)
                Insn(Opcode.CONST, 3, intArrayOf(0), literal = 1), // case 0: r = 1
                Insn(Opcode.GOTO, 4, target = 10), // -> M(off10)
                Insn(Opcode.IF_EQZ, 5, intArrayOf(3), target = 8), // SHARED (two-way): if p2==0 goto Sb(off8); fall Sa
                Insn(Opcode.CONST, 6, intArrayOf(0), literal = 2), // Sa: r = 2
                Insn(Opcode.GOTO, 7, target = 10), // -> M
                Insn(Opcode.CONST, 8, intArrayOf(0), literal = 3), // Sb: r = 3
                Insn(Opcode.GOTO, 9, target = 10), // -> M
                Insn(Opcode.IF_EQZ, 10, intArrayOf(0), target = 12), // M: if r==0 goto off12; fall off11
                Insn(Opcode.RETURN_VOID, 11),
                Insn(Opcode.RETURN_VOID, 12),
                Insn(Opcode.PACKED_SWITCH_PAYLOAD, 40, payload = payload),
            ),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT, IrType.INT, IrType.INT))
        TestPipeline.structured(method)

        assertNull(method.region, "a switch default reaching a shared two-way branch must bail (no wrong structure)")
        assertNull(method[PipelineAttrs.FULLY_STRUCTURED], "a bailed method is not flagged structured")
    }

    // The failure CLASS of conditions/TestComplexIf3: a switch whose case body contains an IRREDUCIBLE
    // region (two entries into a 2-node cycle — the shape produced there by the obfuscated
    // move/goto back-patches into :goto_1a5 / :goto_1b4 / :goto_1cc). Reducibility is a GLOBAL property
    // checked before any structuring (RegionMaker.run's `isReducible()` gate), so the whole method is
    // left unstructured (region == null) regardless of the switch. This is NOT a switch-structuring gap
    // and NOT a bounded fix — it needs irreducible-loop handling (node splitting). Documented as a
    // known limitation: must not crash, must leave region null (no wrong structure).
    @Test
    fun switchCaseWithIrreducibleRegionIsLeftUnstructured() {
        val payload = SwitchPayload(keys = intArrayOf(0), targets = intArrayOf(2)) // rel to switch off0 -> off2
        val reader = FakeCodeReader(
            3, // v0 = local; v1 = p0 (selector); v2 = p1 (cond)
            listOf(
                Insn(Opcode.PACKED_SWITCH, 0, intArrayOf(1), target = 40), // switch(p0); default fall to off1
                Insn(Opcode.RETURN_VOID, 1), // default
                Insn(Opcode.IF_EQZ, 2, intArrayOf(2), target = 5), // case 0: if p1==0 goto L2(off5); fall L1(off3)
                Insn(Opcode.ADD_INT_LIT, 3, intArrayOf(0, 0), literal = 1), // L1
                Insn(Opcode.GOTO, 4, target = 5), // L1 -> L2
                Insn(Opcode.ADD_INT_LIT, 5, intArrayOf(0, 0), literal = 1), // L2 (2nd entry) — irreducible
                Insn(Opcode.IF_EQZ, 6, intArrayOf(2), target = 3), // L2 -> L1 (back) or exit
                Insn(Opcode.RETURN_VOID, 7),
                Insn(Opcode.PACKED_SWITCH_PAYLOAD, 40, payload = payload),
            ),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT, IrType.INT))
        TestPipeline.structured(method) // must not throw

        assertNull(method.region, "an irreducible region in a switch case leaves the method unstructured")
        assertNull(method[PipelineAttrs.FULLY_STRUCTURED], "not flagged structured")
    }
}
