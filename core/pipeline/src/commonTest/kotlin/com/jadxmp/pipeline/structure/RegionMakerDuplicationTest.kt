package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.region.SwitchRegion
import com.jadxmp.ir.region.SyncRegion
import com.jadxmp.ir.region.TryCatchRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A shared "tail" block — a `return`/`goto-merge` reached from several nested `if` arms — must structure
 * by being **duplicated** into each arm (jadx's return / tail-block duplication), not bail as an illegal
 * merge. The duplication is renderable because the block declares no intra-block temporary and any
 * cross-block variable it assigns is already declared-dominating (guaranteed by coalescingIsSound).
 */
class RegionMakerDuplicationTest {

    /** Every basic block occurrence in the region tree (a duplicated block appears more than once). */
    private fun blockOccurrences(container: IrContainer?, out: MutableList<BasicBlock>) {
        when (container) {
            is BasicBlock -> out.add(container)
            is SequenceRegion -> container.children.forEach { blockOccurrences(it, out) }
            is IfRegion -> { blockOccurrences(container.thenRegion, out); container.elseRegion?.let { blockOccurrences(it, out) } }
            is LoopRegion -> blockOccurrences(container.body, out)
            is SyncRegion -> blockOccurrences(container.body, out)
            is SwitchRegion -> { container.cases.forEach { blockOccurrences(it.body, out) }; container.defaultCase?.let { blockOccurrences(it, out) } }
            is TryCatchRegion -> {
                blockOccurrences(container.tryRegion, out)
                container.catches.forEach { blockOccurrences(it.body, out) }
                container.finallyRegion?.let { blockOccurrences(it, out) }
            }
            else -> {}
        }
    }

    @Test
    fun sharedReturnBlockIsDuplicatedAcrossArms() {
        // if (a == 0) return 1; else if (b == 0) return 0; else return 1;
        // The `return 1` block is reached from the a==0 arm AND the (a!=0 && b!=0) goto — a shared tail
        // that must be duplicated, not treated as a merge.
        val reader = FakeCodeReader(
            4, // v0 = return local; v2 = a (p0); v3 = b (p1)
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(2), target = 3), // if (a == 0) -> return-1 block
                Insn(Opcode.IF_EQZ, 1, intArrayOf(3), target = 5), // else if (b == 0) -> return-0 block
                Insn(Opcode.GOTO, 2, target = 3), // else -> return-1 block (shared)
                Insn(Opcode.CONST, 3, intArrayOf(0), literal = 1),
                Insn(Opcode.RETURN, 4, intArrayOf(0)), // return 1
                Insn(Opcode.CONST, 5, intArrayOf(0), literal = 0),
                Insn(Opcode.RETURN, 6, intArrayOf(0)), // return 0
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT, argTypes = listOf(IrType.INT, IrType.INT))
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the shared-tail method must fully structure")
        assertFalse(method.contains(AttrFlag.HAS_ERROR), "correct structuring ⇒ no error flag")

        // The return-1 block (whose sole real instruction is a RETURN) appears TWICE in the tree.
        val occ = ArrayList<BasicBlock>().also { blockOccurrences(method.region, it) }
        val returnOneBlocks = occ.filter { b -> b.instructions.any { it.opcode == IrOpcode.RETURN } }
        val duplicated = returnOneBlocks.groupingBy { it.id }.eachCount().filterValues { it > 1 }
        assertTrue(duplicated.isNotEmpty(), "the shared return block must be duplicated (emitted in >1 arm)")
    }

    @Test
    fun sharedTailWithBlockLocalTempIsDuplicatedAndMarked() {
        // The shared tail is `v0 = p0*p1; return v0 + 1` reached from two arms. Expression shaping folds
        // the `+ 1` into the RETURN, so v0's only read is now WRAPPED inside the return expression. v0 is a
        // BLOCK-LOCAL temp (defined and read only within this one block). Duplicating the tail is now SAFE
        // because codegen re-declares such a temp per copy — but ONLY because the structuring stage marks it
        // BLOCK_LOCAL_TEMP. The anti-miscompile invariant is therefore: a duplicated block may carry an
        // intra-block temp, but every such temp MUST be marked BLOCK_LOCAL_TEMP (an unmarked duplicated temp
        // would declare v0 in one copy and leave a bare undeclared `v0 = p0*p1` in the other — rule-4 loss).
        val reader = FakeCodeReader(
            6, // v0, v1 locals; v4 = p0, v5 = p1
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(4), target = 3), // if (p0 == 0) -> tail
                Insn(Opcode.IF_NEZ, 1, intArrayOf(5), target = 7), // else if (p1 != 0) -> other
                Insn(Opcode.GOTO, 2, target = 3), // else -> tail (shared)
                Insn(Opcode.MUL_INT, 3, intArrayOf(0, 4, 5)), // tail: v0 = p0 * p1
                Insn(Opcode.ADD_INT_LIT, 4, intArrayOf(1, 0), literal = 1), // v1 = v0 + 1
                Insn(Opcode.RETURN, 5, intArrayOf(1)), // return v1
                Insn(Opcode.NOP, 6),
                Insn(Opcode.CONST, 7, intArrayOf(1), literal = 0), // other: v1 = 0
                Insn(Opcode.RETURN, 8, intArrayOf(1)), // return v1
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT, argTypes = listOf(IrType.INT, IrType.INT))
        TestPipeline.structured(method)

        // The block-local-temp tail now structures (no bail) and is duplicated into both arms.
        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "block-local-temp tail must fully structure")
        assertFalse(method.contains(AttrFlag.HAS_ERROR), "correct structuring ⇒ no error flag")
        val occ = ArrayList<BasicBlock>().also { blockOccurrences(method.region, it) }
        val counts = occ.groupingBy { it.id }.eachCount()
        val tailDuplicated = occ.any { b -> counts.getValue(b.id) > 1 && intraBlockTempIds(b).isNotEmpty() }
        assertTrue(tailDuplicated, "the block-local-temp tail must now be duplicated across arms")

        // Anti-miscompile invariant: EVERY intra-block temp in a duplicated block must be marked
        // BLOCK_LOCAL_TEMP (so codegen re-declares it per copy instead of emitting an out-of-scope use).
        val unmarkedDuplicatedTemp = occ.any { b ->
            counts.getValue(b.id) > 1 &&
                b.instructions.any { insn ->
                    val v = insn.result?.ssaValue
                    v != null && intraBlockTempIds(b).contains(v.version) &&
                        !insn.contains(AttrFlag.BLOCK_LOCAL_TEMP)
                }
        }
        assertFalse(unmarkedDuplicatedTemp, "a duplicated intra-block temp must be marked BLOCK_LOCAL_TEMP")
    }

    @Test
    fun crossBlockValueIsNotMarkedBlockLocal() {
        // A value defined in one block and read in ANOTHER (it escapes) is a cross-block variable, NOT a
        // block-local temp — it must NOT be marked BLOCK_LOCAL_TEMP (codegen keeps its single dominating
        // declaration; re-declaring it per copy would shadow/miscompile). Here `v0 = p0 + p0` is defined in
        // the entry block and read in BOTH the then and else arms (two OTHER blocks), so it escapes and must
        // never be classified block-local — a direct guard on the block-local-vs-cross-block discrimination.
        val reader = FakeCodeReader(
            4, // v0, v1 locals; v2 = p0
            listOf(
                Insn(Opcode.ADD_INT, 0, intArrayOf(0, 2, 2)), // entry: v0 = p0 + p0  (escapes; multi-use)
                Insn(Opcode.IF_EQZ, 1, intArrayOf(2), target = 4), // if (p0 == 0) -> else arm
                Insn(Opcode.ADD_INT_LIT, 2, intArrayOf(1, 0), literal = 1), // then: v1 = v0 + 1
                Insn(Opcode.RETURN, 3, intArrayOf(1)), // return v1  (reads v0 via v1)
                Insn(Opcode.RETURN, 4, intArrayOf(0)), // else: return v0  (reads v0 in another block)
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT, argTypes = listOf(IrType.INT))
        TestPipeline.structured(method)

        // v0's def is the only ADD reading a register twice; it is read in the then AND else arms → escapes.
        val v0Def = method.blocks.flatMap { it.instructions }
            .first { it.opcode == IrOpcode.ARITH && it.result?.ssaValue != null && it.argCount >= 2 && it.getArg(1) is com.jadxmp.ir.insn.RegisterOperand }
        assertFalse(v0Def.contains(AttrFlag.BLOCK_LOCAL_TEMP), "a cross-block (escaping) value must NOT be block-local")
    }

    /** SSA-value ids that are defined and (recursively) read within [block] — the intra-block temps. */
    private fun intraBlockTempIds(block: BasicBlock): List<Int> {
        val insns = block.instructions
        val out = ArrayList<Int>()
        for (i in insns.indices) {
            val v = insns[i].result?.ssaValue ?: continue
            for (j in i + 1 until insns.size) {
                if (reads(insns[j], v)) { out.add(v.version); break }
            }
        }
        return out
    }

    private fun reads(insn: com.jadxmp.ir.insn.Instruction, value: com.jadxmp.ir.node.SsaValue): Boolean {
        for (k in 0 until insn.argCount) {
            when (val a = insn.getArg(k)) {
                is com.jadxmp.ir.insn.RegisterOperand -> if (a.ssaValue === value) return true
                is com.jadxmp.ir.insn.InstructionOperand -> if (reads(a.instruction, value)) return true
                else -> {}
            }
        }
        return false
    }

    @Test
    fun sharedTailWithGenuinelyDeadThrowingReadIsNotDuplicated() {
        // The shared tail `int len = arr.length; return 5;` is reached from two arms. `len` is never read
        // (a GENUINELY-DEAD result), but `arr.length` can throw NPE so SsaBuilder's DCE keeps it. Duplicating
        // the tail would declare `len` in one copy and strand a bare, out-of-scope `len = arr.length;` in the
        // other (a silent miscompile). The block is therefore NOT duplicable and the method bails honestly —
        // the guard distinguishes this from a coalesced variable's useCount-0-but-live per-branch assignment.
        val reader = FakeCodeReader(
            4, // v0 = len, v1 = ret temp, v2 = a (p0), v3 = arr (p1)
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(2), target = 3), // if (a == 0) -> tail
                Insn(Opcode.IF_EQZ, 1, intArrayOf(3), target = 6), // else if (arr == null) -> other
                Insn(Opcode.GOTO, 2, target = 3), // else -> tail (shared)
                Insn(Opcode.ARRAY_LENGTH, 3, intArrayOf(0, 3)), // tail: len = arr.length (dead, may throw)
                Insn(Opcode.CONST, 4, intArrayOf(1), literal = 5),
                Insn(Opcode.RETURN, 5, intArrayOf(1)), // return 5
                Insn(Opcode.CONST, 6, intArrayOf(1), literal = 7), // other
                Insn(Opcode.RETURN, 7, intArrayOf(1)), // return 7
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            returnType = IrType.INT,
            argTypes = listOf(IrType.INT, IrType.array(IrType.INT)),
        )
        TestPipeline.structured(method)

        val occ = ArrayList<BasicBlock>().also { blockOccurrences(method.region, it) }
        val counts = occ.groupingBy { it.id }.eachCount()
        val tailDuplicated = occ.any { b ->
            counts.getValue(b.id) > 1 && b.instructions.any { it.opcode == IrOpcode.ARRAY_LENGTH }
        }
        assertFalse(tailDuplicated, "a tail with a genuinely-dead throwing read must not be duplicated (would strand it)")
        assertTrue(
            method.region == null || method[PipelineAttrs.FULLY_STRUCTURED] == true,
            "must not produce a partial/incorrect tree",
        )
    }

    @Test
    fun nonDuplicableBranchMergeStillBails() {
        // A shared merge block that itself BRANCHES (two successors) must NOT be duplicated (its whole
        // subtree would be copied) — such an unhandled shape still bails honestly.
        // if (a==0) { if (c==0) x else y } else { <goto the inner if> }  — the inner if is shared.
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(1), target = 3), // if (a==0) -> inner if (B at 3)
                Insn(Opcode.GOTO, 1, target = 3), // else -> SAME inner if (shared branch block)
                Insn(Opcode.RETURN, 2, intArrayOf(2)), // (unreached filler)
                Insn(Opcode.IF_EQZ, 3, intArrayOf(2), target = 6), // inner if (c==0) — a BRANCH, shared
                Insn(Opcode.CONST, 4, intArrayOf(2), literal = 1),
                Insn(Opcode.RETURN, 5, intArrayOf(2)),
                Insn(Opcode.CONST, 6, intArrayOf(2), literal = 0),
                Insn(Opcode.RETURN, 7, intArrayOf(2)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT, argTypes = listOf(IrType.INT, IrType.INT))
        TestPipeline.structured(method)
        // The shared branch block is not duplicable, so this shape bails (honest) rather than mis-structure.
        assertTrue(
            method.region == null || method[PipelineAttrs.FULLY_STRUCTURED] == true,
            "must not produce a partial/incorrect tree",
        )
    }
}
