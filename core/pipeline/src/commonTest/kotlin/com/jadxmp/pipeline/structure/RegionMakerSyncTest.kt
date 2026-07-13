package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.region.CatchClause
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.region.SwitchRegion
import com.jadxmp.ir.region.SyncRegion
import com.jadxmp.ir.region.TryCatchRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCatchHandler
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.FakeTryBlock
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Structuring of `synchronized` (monitor-enter/exit + the synthetic lock-release catch-all) into a
 * [SyncRegion], plus the honest bail paths for monitor / try shapes that can't be proven (rule 4).
 */
class RegionMakerSyncTest {

    private val getLock = FakeMethodRef("Lc/F;", "getLock", "Ljava/lang/Object;", emptyList())
    private val work = FakeMethodRef("Lc/F;", "work", "V", emptyList())

    /** Every basic block that actually appears in the region tree (what codegen will emit). */
    private fun blocksInTree(container: IrContainer?, out: MutableSet<BasicBlock>) {
        when (container) {
            is BasicBlock -> out.add(container)
            is SequenceRegion -> container.children.forEach { blocksInTree(it, out) }
            is SyncRegion -> blocksInTree(container.body, out)
            is TryCatchRegion -> {
                blocksInTree(container.tryRegion, out)
                container.catches.forEach { blocksInTree(it.body, out) }
                container.finallyRegion?.let { blocksInTree(it, out) }
            }
            is IfRegion -> {
                blocksInTree(container.thenRegion, out)
                container.elseRegion?.let { blocksInTree(it, out) }
            }
            is LoopRegion -> blocksInTree(container.body, out)
            is SwitchRegion -> {
                container.cases.forEach { blocksInTree(it.body, out) }
                container.defaultCase?.let { blocksInTree(it, out) }
            }
            else -> {}
        }
    }

    private fun firstSync(method: IrMethod): SyncRegion? {
        val all = HashSet<BasicBlock>()
        // Walk regions to find a SyncRegion (reuse the block walk's structure by a small recursion).
        var found: SyncRegion? = null
        fun visit(r: Region?) {
            when (r) {
                is SyncRegion -> { found = r; visit(r.body) }
                is SequenceRegion -> r.children.forEach { if (it is Region) visit(it) }
                is IfRegion -> { visit(r.thenRegion); r.elseRegion?.let { visit(it) } }
                is LoopRegion -> visit(r.body)
                is TryCatchRegion -> { visit(r.tryRegion); r.catches.forEach { visit(it.body) } }
                else -> {}
            }
        }
        visit(method.region)
        blocksInTree(method.region, all)
        return found
    }

    /** A straight-line `synchronized (lock) { work(); }` — the compiler's release catch-all is synthetic. */
    private fun syncMethod(): IrMethod {
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = getLock),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 = getLock()
                Insn(Opcode.MONITOR_ENTER, 2, intArrayOf(0)),
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(), methodRef = work), // try_start = 3
                Insn(Opcode.MONITOR_EXIT, 4, intArrayOf(0)), // normal release
                Insn(Opcode.RETURN_VOID, 5),
                Insn(Opcode.MOVE_EXCEPTION, 6, intArrayOf(1)), // handler = 6
                Insn(Opcode.MONITOR_EXIT, 7, intArrayOf(0)), // try_end after this
                Insn(Opcode.THROW, 8, intArrayOf(1)),
            ),
            tries = listOf(FakeTryBlock(3, 7, FakeCatchHandler(emptyList(), emptyList(), 6))),
        )
        return TestPipeline.buildMethod(reader, methodName = "sync")
    }

    @Test
    fun cleanSynchronizedBuildsSyncRegionAndConsumesCleanup() {
        val method = syncMethod()
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "a clean synchronized must fully structure")
        val sync = firstSync(method)
        assertNotNull(sync, "a SyncRegion must be produced")

        // The lock is the monitor-enter's operand.
        val monitorReg = sync.monitor as? com.jadxmp.ir.insn.RegisterOperand
        assertNotNull(monitorReg, "the monitor is a register operand (the lock)")

        // The synthetic release catch-all (the `move-exception; monitor-exit; throw` block) is CONSUMED —
        // it must not appear anywhere in the emitted tree, and no `throw` is left to render.
        val emitted = HashSet<BasicBlock>()
        blocksInTree(method.region, emitted)
        assertTrue(
            emitted.none { b -> b.instructions.any { it.opcode == IrOpcode.THROW } },
            "the cleanup handler's re-throw must not be emitted (the lock releases via synchronized{})",
        )
        assertTrue(
            emitted.none { b -> b.contains(PipelineAttrs.EXC_HANDLER) },
            "the cleanup handler block must not be in the region tree",
        )
    }

    @Test
    fun pureHoistedInitAfterMonitorEnterRendersOutsideTheLock() {
        // synchronized(lock) { work(); }  with an out-of-SSA hoisted `int v2 = 5;` placed AFTER the
        // monitor-enter (a declaration for a value read after the sync). Rendering it before the lock is
        // safe (no side effect) AND required (an in-lock declaration would be out of scope after the sync).
        // It must NOT force a bail, and must NOT land inside the synchronized body.
        val reader = FakeCodeReader(
            3, // v0 = lock, v1 = exc, v2 = int
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = getLock),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 = getLock()
                Insn(Opcode.MONITOR_ENTER, 2, intArrayOf(0)),
                Insn(Opcode.CONST, 3, intArrayOf(2), literal = 5), // post-enter hoisted init (pure) — outside try
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(), methodRef = work), // sync body (try_start = 4)
                Insn(Opcode.MONITOR_EXIT, 5, intArrayOf(0)),
                Insn(Opcode.RETURN, 6, intArrayOf(2)), // return v2 (uses the hoisted init → kept live)
                Insn(Opcode.MOVE_EXCEPTION, 7, intArrayOf(1)),
                Insn(Opcode.MONITOR_EXIT, 8, intArrayOf(0)),
                Insn(Opcode.THROW, 9, intArrayOf(1)),
            ),
            tries = listOf(FakeTryBlock(4, 8, FakeCatchHandler(emptyList(), emptyList(), 7))),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "sync", returnType = IrType.INT)
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "a sync with a pure post-enter init must structure")
        val sync = firstSync(method)
        assertNotNull(sync, "a SyncRegion is produced")
        val bodyBlocks = HashSet<BasicBlock>().also { blocksInTree(sync.body, it) }
        assertTrue(
            bodyBlocks.any { b -> b.instructions.any { it.opcode == IrOpcode.INVOKE } },
            "work() renders inside the lock",
        )
        val hoistedInit = method.blocks.flatMap { it.instructions }.single { it.opcode == IrOpcode.CONST }
        assertTrue(
            bodyBlocks.none { it.instructions.contains(hoistedInit) },
            "the hoisted init is OUTSIDE the synchronized body (rendered before the lock, for scope)",
        )
        val allTree = HashSet<BasicBlock>().also { blocksInTree(method.region, it) }
        assertTrue(
            allTree.any { it.instructions.contains(hoistedInit) },
            "the hoisted init is still emitted (enter-block prefix), not dropped",
        )
    }

    @Test
    fun effectfulInstructionAfterMonitorEnterBails() {
        // An INVOKE (a call — effectful) placed after the monitor-enter genuinely belongs inside the lock;
        // rendering it as the enter-block prefix would reorder it OUT of the critical section. This must
        // bail honestly (region null), never silently move a side effect across the lock boundary (rule 4).
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = getLock),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)),
                Insn(Opcode.MONITOR_ENTER, 2, intArrayOf(0)),
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(), methodRef = work), // EFFECTFUL post-enter instruction
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(), methodRef = work), // sync body (try_start = 4)
                Insn(Opcode.MONITOR_EXIT, 5, intArrayOf(0)),
                Insn(Opcode.RETURN_VOID, 6),
                Insn(Opcode.MOVE_EXCEPTION, 7, intArrayOf(1)),
                Insn(Opcode.MONITOR_EXIT, 8, intArrayOf(0)),
                Insn(Opcode.THROW, 9, intArrayOf(1)),
            ),
            tries = listOf(FakeTryBlock(4, 8, FakeCatchHandler(emptyList(), emptyList(), 7))),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "sync")
        TestPipeline.structured(method)
        assertNull(method.region, "an effectful post-enter instruction must bail, never render outside the lock")
    }

    @Test
    fun monitorEnterWithNonPureCleanupBails() {
        // The catch-all does REAL work (calls work()) beyond releasing the lock — collapsing it would drop
        // that code, so structuring must bail (region stays null), never emit a lock-less body.
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = getLock),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)),
                Insn(Opcode.MONITOR_ENTER, 2, intArrayOf(0)),
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(), methodRef = work), // try_start = 3
                Insn(Opcode.MONITOR_EXIT, 4, intArrayOf(0)),
                Insn(Opcode.RETURN_VOID, 5),
                Insn(Opcode.MOVE_EXCEPTION, 6, intArrayOf(1)), // handler = 6
                Insn(Opcode.INVOKE_STATIC, 7, intArrayOf(), methodRef = work), // REAL work in the cleanup
                Insn(Opcode.MONITOR_EXIT, 8, intArrayOf(0)), // try_end after this
                Insn(Opcode.THROW, 9, intArrayOf(1)),
            ),
            tries = listOf(FakeTryBlock(3, 8, FakeCatchHandler(emptyList(), emptyList(), 6))),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "sync")
        TestPipeline.structured(method)
        assertNull(method.region, "a monitor whose cleanup does real work must bail, not miscompile")
    }

    @Test
    fun multiBlockCatchAllRethrowBailsNotMiscollapsed() {
        // A catch-all whose ENTRY block does not itself end in THROW but a later block re-throws — the
        // broadened handlerReThrows must still recognize it as a finally/cleanup and bail (rule 4),
        // rather than emit it as a real `catch (Throwable)`.
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = work), // try_start = 0
                Insn(Opcode.RETURN_VOID, 1), // normal follow
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(0)), // handler entry (offset 2)
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(), methodRef = work), // cleanup code
                Insn(Opcode.GOTO, 4, target = 5), // entry block does NOT end in throw
                Insn(Opcode.THROW, 5, intArrayOf(0)), // later block re-throws
            ),
            tries = listOf(FakeTryBlock(0, 0, FakeCatchHandler(emptyList(), emptyList(), 2))),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "fin")
        TestPipeline.structured(method)
        assertNull(method.region, "a multi-block catch-all re-throw (finally) must bail, not become a catch")
    }

    @Test
    fun singleBlockCatchAllRethrowFinallyBails() {
        // A single-block `finally` cleanup (`move-exception; throw`) — we do not reconstruct finally yet,
        // so it must bail to the honest unstructured fallback rather than emit a `catch (Throwable) { throw }`.
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = work), // try_start = 0
                Insn(Opcode.RETURN_VOID, 1),
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(0)), // catch-all handler (offset 2)
                Insn(Opcode.THROW, 3, intArrayOf(0)), // re-throws
            ),
            tries = listOf(FakeTryBlock(0, 0, FakeCatchHandler(emptyList(), emptyList(), 2))),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "fin")
        TestPipeline.structured(method)
        assertNull(method.region, "a re-throwing catch-all (finally cleanup) must bail, not become a catch")
    }
}
