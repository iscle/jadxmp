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

    private fun firstTryCatch(method: IrMethod): TryCatchRegion? {
        var found: TryCatchRegion? = null
        fun visit(r: Region?) {
            when (r) {
                is TryCatchRegion -> { if (found == null) found = r; visit(r.tryRegion); r.catches.forEach { visit(it.body) } }
                is SyncRegion -> visit(r.body)
                is SequenceRegion -> r.children.forEach { if (it is Region) visit(it) }
                is IfRegion -> { visit(r.thenRegion); r.elseRegion?.let { visit(it) } }
                is LoopRegion -> visit(r.body)
                else -> {}
            }
        }
        visit(method.region)
        return found
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
    fun multiExitSyncWithInBodyReturnStructuresWithSingleFollow() {
        // `synchronized (lock) { if (p) return; work(); }` — the sync body has TWO exits: an in-body
        // `return` (one arm) and the normal fall-through to a block AFTER the lock (the single follow).
        // Each exit path carries its OWN monitor-exit in the bytecode (the release before the in-body
        // return, and the normal release), and Java's synchronized{} auto-unlocks on every exit, so those
        // monitor-exits are implicit and never rendered. The in-body return must render INSIDE the lock,
        // the follow block OUTSIDE it, and the synthetic release catch-all is consumed — one monitor-enter,
        // one SyncRegion, unlock-once-per-path. (This is the TestSynchronized5 shape.)
        val reader = FakeCodeReader(
            3, // v0 = lock, v1 = exc, v2 = boolean param (the condition)
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = getLock),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 = getLock()
                Insn(Opcode.MONITOR_ENTER, 2, intArrayOf(0)),
                Insn(Opcode.IF_EQZ, 3, intArrayOf(2), target = 6), // try_start = 3; if (p == 0) goto normal
                Insn(Opcode.MONITOR_EXIT, 4, intArrayOf(0)), // p != 0: release then in-body return
                Insn(Opcode.RETURN_VOID, 5),
                Insn(Opcode.INVOKE_STATIC, 6, intArrayOf(), methodRef = work), // p == 0: normal work
                Insn(Opcode.MONITOR_EXIT, 7, intArrayOf(0)), // normal release
                Insn(Opcode.GOTO, 8, target = 12), // jump over the handler to the follow
                Insn(Opcode.MOVE_EXCEPTION, 9, intArrayOf(1)), // handler = 9
                Insn(Opcode.MONITOR_EXIT, 10, intArrayOf(0)), // try_end after this
                Insn(Opcode.THROW, 11, intArrayOf(1)),
                Insn(Opcode.RETURN_VOID, 12), // FOLLOW — the single normal exit, OUTSIDE the lock
            ),
            tries = listOf(FakeTryBlock(3, 10, FakeCatchHandler(emptyList(), emptyList(), 9))),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "sync", argTypes = listOf(IrType.BOOLEAN), isStatic = true)
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "a multi-exit sync with an in-body return must structure")
        val sync = firstSync(method)
        assertNotNull(sync, "a SyncRegion is produced")

        // The in-body return renders INSIDE the synchronized body.
        val bodyBlocks = HashSet<BasicBlock>().also { blocksInTree(sync.body, it) }
        val inBodyReturn = method.blocks.first { b -> b.instructions.any { it.opcode == IrOpcode.RETURN && it.offset == 5 } }
        assertTrue(inBodyReturn in bodyBlocks, "the in-body return renders inside the lock")

        // The follow (offset 12) renders OUTSIDE the synchronized body (the makeIf chain-follow clamp keeps
        // the sync-body if from absorbing the post-lock block).
        val follow = method.blocks.first { b -> b.instructions.any { it.offset == 12 } }
        assertTrue(follow !in bodyBlocks, "the post-lock follow renders outside the lock")
        val allTree = HashSet<BasicBlock>().also { blocksInTree(method.region, it) }
        assertTrue(follow in allTree, "the follow is still emitted (not dropped)")

        // Monitor-balance / no-leak: exactly one monitor-enter, consumed into the single SyncRegion; the
        // cleanup handler's re-throw is not emitted (release is implicit via synchronized{}).
        assertTrue(
            allTree.none { b -> b.instructions.any { it.opcode == IrOpcode.THROW } },
            "the cleanup handler's re-throw is not emitted (unlock is implicit on every exit)",
        )
        assertTrue(
            allTree.none { b -> b.contains(PipelineAttrs.EXC_HANDLER) },
            "the synthetic release handler is consumed, not in the tree",
        )
    }

    @Test
    fun multiExitSyncWithSharedInBodyReturnMergeBailsHonestly() {
        // The TestSynchronized4 shape: TWO paths converge on a SHARED in-body `monitor-exit; return` block
        // (`goto_1d`), reached both by fall-through and via a `goto` from another arm. That shared block is
        // PROTECTED (inside the critical section) and contains a monitor-exit (which may throw), so it
        // cannot be duplicated into each arm — and structuring it as a single-follow would misplace the
        // second exit. There is no single-follow proof here, so structuring must BAIL honestly (region
        // stays null) rather than emit a synchronized whose auto-unlock doesn't match the bytecode's paths
        // (rule 4). This locks in the shared-merge honest bail.
        val reader = FakeCodeReader(
            3, // v0 = lock, v1 = exc, v2 = boolean param
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = getLock),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 = getLock()
                Insn(Opcode.MONITOR_ENTER, 2, intArrayOf(0)),
                Insn(Opcode.IF_EQZ, 3, intArrayOf(2), target = 6), // try_start = 3; if (p == 0) goto 6
                Insn(Opcode.MONITOR_EXIT, 4, intArrayOf(0)), // p != 0: release then return
                Insn(Opcode.RETURN_VOID, 5),
                Insn(Opcode.IF_NEZ, 6, intArrayOf(2), target = 12), // p == 0; if (p != 0) goto cond_22
                Insn(Opcode.MONITOR_EXIT, 7, intArrayOf(0)), // SHARED block: reached from 6-fallthrough AND 12
                Insn(Opcode.RETURN_VOID, 8),
                Insn(Opcode.MOVE_EXCEPTION, 9, intArrayOf(1)), // handler = 9
                Insn(Opcode.MONITOR_EXIT, 10, intArrayOf(0)), // try_end after this
                Insn(Opcode.THROW, 11, intArrayOf(1)),
                Insn(Opcode.GOTO, 12, target = 7), // cond_22 → the SHARED in-body return block
            ),
            tries = listOf(FakeTryBlock(3, 10, FakeCatchHandler(emptyList(), emptyList(), 9))),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "sync", argTypes = listOf(IrType.BOOLEAN), isStatic = true)
        TestPipeline.structured(method)
        assertNull(
            method.region,
            "a shared in-body return merge inside the lock has no single-follow proof — it must bail, not miscompile",
        )
    }

    @Test
    fun multiBlockCatchAllRethrowRendersAsExplicitCatch() {
        // A multi-block catch-all whose ENTRY does not end in THROW but a later block re-throws, with real
        // cleanup in between (`move-exception; cleanup(); goto; throw`). It renders faithfully as
        // `catch (Throwable e) { cleanup(); throw e; }` — the multi-block handler body is structured by
        // makeRegion and the rethrow preserved. Faithful 1:1 transcription; nothing dropped or doubled.
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
        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "multi-block catch-all structures")
        val tc = firstTryCatch(method)
        assertNotNull(tc, "a TryCatchRegion is produced")
        assertNull(tc.finallyRegion, "no finally is factored")
        assertEquals(1, tc.catches.size)
        // The cleanup call (body + handler = 2 total) and the rethrow are all kept.
        val insns = method.blocks.flatMap { it.instructions }
        assertEquals(2, insns.count { it.opcode == IrOpcode.INVOKE }, "the handler cleanup call is preserved")
        assertNotNull(insns.firstOrNull { it.opcode == IrOpcode.THROW }, "the rethrow is emitted")
    }

    @Test
    fun singleBlockCatchAllRethrowRendersAsExplicitCatch() {
        // A single-block catch-all that just re-throws (`move-exception; throw`). It cannot be factored
        // into a `finally {}` (no cleanup on the normal path), so it renders FAITHFULLY as an explicit
        // `catch (Throwable e) { throw e; }` — a 1:1 transcription of the bytecode handler (observably a
        // no-op catch, but correct). This is a transcription, NOT a finally-factoring, so it can neither
        // drop nor double any cleanup.
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
        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "re-throwing catch-all structures")
        val tc = firstTryCatch(method)
        assertNotNull(tc, "a TryCatchRegion is produced")
        assertNull(tc.finallyRegion, "no finally is factored")
        assertEquals(1, tc.catches.size)
        // The rethrow is preserved (a real `throw e`, not hidden as a finally would).
        assertNotNull(
            method.blocks.flatMap { it.instructions }.firstOrNull { it.opcode == IrOpcode.THROW },
            "the rethrow is emitted",
        )
    }
}
