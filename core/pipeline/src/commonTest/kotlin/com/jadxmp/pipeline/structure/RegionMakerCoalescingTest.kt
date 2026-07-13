package com.jadxmp.pipeline.structure

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.constructor.ConstructorReconstruction
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Out-of-SSA coalescing renderability: the structuring stage must never emit a variable used before its
 * definition (a forward reference / out-of-scope use) — that recompiles nowhere yet slips past a
 * region==null guard on branch-free bodies. Two paths: fix the common cause (a dead receiver copy left
 * behind by constructor fusion) so it renders correctly, and, for what can't be fixed, flag the method
 * honestly (rule 4) instead of emitting clean-but-wrong source.
 */
class RegionMakerCoalescingTest {

    private val FOO = "Lcom/example/Foo;"
    private fun ctorRef(vararg params: String) = FakeMethodRef(FOO, "<init>", "V", params.toList())
    private fun allInsns(method: IrMethod) = method.blocks.flatMap { it.instructions }

    /** The real analysis pipeline order (constructor fusion runs before out-of-SSA / structuring). */
    private fun process(method: IrMethod) {
        TestPipeline.full(method)
        ConstructorReconstruction(method).run()
        OutOfSsa(method).run()
        ExpressionShaping(method).run()
        RegionMaker(method).run()
    }

    @Test
    fun deadReceiverMoveFromConstructorFusionIsRemovedAndStructures() {
        // new-instance v1; move v0<-v1 (receiver copy); <init>(v0, "x" in v2); return v1
        // After fusion the receiver copy `v0 = v1` is dead; if kept it would read v1 before its relocated
        // (constructor) definition — a forward reference. It must be removed so the body renders.
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.NEW_INSTANCE, 0, intArrayOf(1), indexType = IndexType.TYPE_REF, typeValue = FOO),
                Insn(Opcode.MOVE_OBJECT, 1, intArrayOf(0, 1)), // v0 = v1 (receiver plumbing)
                Insn(Opcode.CONST_STRING, 2, intArrayOf(2), indexType = IndexType.STRING_REF, stringValue = "x"),
                Insn(Opcode.INVOKE_DIRECT, 3, intArrayOf(0, 2), indexType = IndexType.METHOD_REF, methodRef = ctorRef("Ljava/lang/String;")),
                Insn(Opcode.RETURN, 4, intArrayOf(1)), // return the constructed object
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.objectType("com.example.Foo"))
        process(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the fused constructor body must structure")
        assertFalse(method.contains(AttrFlag.HAS_ERROR), "no forward reference ⇒ no error flag")
        assertNotNull(method.region)
        // The dead receiver copy is gone; one CONSTRUCTOR remains as the object's definition.
        assertTrue(allInsns(method).none { it.opcode == IrOpcode.NEW_INSTANCE })
        assertEquals(1, allInsns(method).count { it.opcode == IrOpcode.CONSTRUCTOR })
    }

    @Test
    fun multiUseReceiverCopyCollapsesIntoConstructor() {
        // new-instance v1; move v0<-v1 (v0 read TWICE); <init>(v1); use(v0); use(v0)
        // v0 and v1 are the SAME object (a copy of the uninitialized reference), and both `use(v0)` read it
        // only AFTER `<init>`. The whole copy cluster collapses into the constructor's variable, so the
        // reads become `v1.use()` — renderable and correct, not a forward reference.
        val use = FakeMethodRef(FOO, "use", "V", emptyList())
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.NEW_INSTANCE, 0, intArrayOf(1), indexType = IndexType.TYPE_REF, typeValue = FOO),
                Insn(Opcode.MOVE_OBJECT, 1, intArrayOf(0, 1)), // v0 = v1 (a copy of the object)
                Insn(Opcode.INVOKE_DIRECT, 2, intArrayOf(1), indexType = IndexType.METHOD_REF, methodRef = ctorRef()),
                Insn(Opcode.INVOKE_VIRTUAL, 3, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = use),
                Insn(Opcode.INVOKE_VIRTUAL, 4, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = use),
                Insn(Opcode.RETURN_VOID, 5),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        process(method)

        assertNotNull(method.region, "the collapsed copy cluster renders — no bail")
        assertFalse(method.contains(AttrFlag.HAS_ERROR), "no forward reference remains after the cluster collapse")
        assertTrue(allInsns(method).none { it.opcode == IrOpcode.NEW_INSTANCE }, "new-instance fused away")
        assertEquals(1, allInsns(method).count { it.opcode == IrOpcode.CONSTRUCTOR })
    }

    @Test
    fun copyReadOnAnArmTheConstructorDoesNotDominateBailsHonestly() {
        // new-instance v0; v1 = v0; if (p) goto USE; <init>(v0); return; USE: use(v1); return
        // v1 copies the uninitialized reference and is read on the branch that SKIPS `<init>` — the
        // constructor does not dominate that use. The move-cluster collapse redirects the read to the
        // constructed object, but the constructor cannot reach it, so `coalescingIsSound` must catch the
        // forward reference and bail honestly (HAS_ERROR / region==null) rather than emit clean-but-wrong.
        // This locks in the safety net the collapse relies on (the removed domination pre-gate).
        val use = FakeMethodRef(FOO, "use", "V", emptyList())
        val reader = FakeCodeReader(
            3, // v0 (object), v1 (copy), v2 = boolean param
            listOf(
                Insn(Opcode.NEW_INSTANCE, 0, intArrayOf(0), indexType = IndexType.TYPE_REF, typeValue = FOO),
                Insn(Opcode.MOVE_OBJECT, 1, intArrayOf(1, 0)), // v1 = v0 (copy of the uninitialized ref)
                Insn(Opcode.IF_NEZ, 2, intArrayOf(2), target = 5), // if (p) goto USE — skips <init>
                Insn(Opcode.INVOKE_DIRECT, 3, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = ctorRef()),
                Insn(Opcode.RETURN_VOID, 4),
                Insn(Opcode.INVOKE_VIRTUAL, 5, intArrayOf(1), indexType = IndexType.METHOD_REF, methodRef = use),
                Insn(Opcode.RETURN_VOID, 6),
            ),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.BOOLEAN))
        process(method)

        assertNull(method.region, "a copy read before its (relocated) definition must bail — region stays null")
        assertTrue(method.contains(AttrFlag.HAS_ERROR), "the forward reference is flagged, never emitted clean-but-wrong")
    }

    @Test
    fun allocAliasedToBothReceiverAndSeparateUseFusesAndCollapses() {
        // The `TestConstructorWithMoves` shape: the allocation is copied onto TWO branches of a move web —
        // one reaches the `<init>` receiver, the other is read (after `<init>`) by a later use. The
        // constructor's result is the new-instance value, which a pre-`<init>` copy reads; the whole
        // cluster must collapse so the later use reads the CONSTRUCTED object, not a forward reference.
        val use = FakeMethodRef(FOO, "use", "V", emptyList())
        val reader = FakeCodeReader(
            4,
            listOf(
                // v0 = new Foo
                Insn(Opcode.NEW_INSTANCE, 0, intArrayOf(0), indexType = IndexType.TYPE_REF, typeValue = FOO),
                Insn(Opcode.MOVE_OBJECT, 1, intArrayOf(1, 0)), // v1 = v0
                Insn(Opcode.MOVE_OBJECT, 2, intArrayOf(2, 1)), // v2 = v1  (receiver branch)
                Insn(Opcode.CONST_STRING, 3, intArrayOf(3), indexType = IndexType.STRING_REF, stringValue = "x"),
                // <init>(v2, "x") — receiver is the moved copy v2
                Insn(
                    Opcode.INVOKE_DIRECT, 4, intArrayOf(2, 3),
                    indexType = IndexType.METHOD_REF, methodRef = ctorRef("Ljava/lang/String;"),
                ),
                // use(v1) after <init>
                Insn(Opcode.INVOKE_VIRTUAL, 5, intArrayOf(1), indexType = IndexType.METHOD_REF, methodRef = use),
                Insn(Opcode.RETURN_VOID, 6),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        process(method)

        assertNotNull(method.region, "the aliased allocation fuses and renders")
        assertFalse(method.contains(AttrFlag.HAS_ERROR), "no forward reference after collapse")
        assertTrue(allInsns(method).none { it.opcode == IrOpcode.NEW_INSTANCE }, "new-instance fused away")
        val ctor = allInsns(method).single { it.opcode == IrOpcode.CONSTRUCTOR }
        // The later use reads the CONSTRUCTOR's own result value (the collapsed object), not a stale copy.
        val useInsn = allInsns(method).single { it.opcode == IrOpcode.INVOKE }
        val useReceiver = (useInsn.getArg(0) as RegisterOperand).ssaValue
        assertEquals(ctor.result?.ssaValue, useReceiver, "use reads the constructed object")
    }
}
