package com.jadxmp.pipeline.structure

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.node.IrMethod
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A value DEFINED inside a `try {}` and read AFTER it (or in the `catch`) must have its declaration
 * HOISTED out of the try scope by out-of-SSA — source scope is narrower than dominance, so an in-try
 * declaration is invisible outside. Without the hoist the structuring stage bails ("try-scoped value
 * escapes"); with it the method structures as jadx's `T v = null; try { v = … } … v`.
 */
class OutOfSsaEscapeHoistTest {

    private val compute = FakeMethodRef("Lc/F;", "compute", "I", emptyList())
    private val use = FakeMethodRef("Lc/F;", "use", "V", listOf("I"))

    /** The full analysis pipeline (constructor fusion + out-of-SSA + shaping + structuring). */
    private fun process(method: IrMethod) {
        TestPipeline.full(method)
        com.jadxmp.pipeline.constructor.ConstructorReconstruction(method).run()
        OutOfSsa(method).run()
        ExpressionShaping(method).run()
        RegionMaker(method).run()
    }

    /** The hoisted default-init (a `CONST` whose result shares a >1-member local), or null if none. */
    private fun hoistedInit(method: IrMethod) =
        method.blocks.flatMap { it.instructions }.firstOrNull { insn ->
            insn.opcode == IrOpcode.CONST &&
                insn.result?.ssaValue?.localVar?.let { it.ssaValues.size > 1 } == true
        }

    @Test
    fun tryDefinedValueUsedAfterIsHoistedAndStructures() {
        // v0 = compute();            (inside try)
        // } catch (Exception e) {}
        // use(v0);                   (after the try — v0 escapes)
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = compute), // try_start
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 = compute()   (try_end covers this)
                Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = use), // use(v0) — after
                Insn(Opcode.RETURN_VOID, 3),
                Insn(Opcode.MOVE_EXCEPTION, 4, intArrayOf(1)), // handler
                Insn(Opcode.GOTO, 5, target = 2), // catch continues to the follow
            ),
            tries = listOf(FakeTryBlock(0, 1, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(4), -1))),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "m")
        process(method)

        // The win: previously this bailed with "try-scoped value escapes"; the hoist makes it structure.
        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the try-escape method must structure (not bail)")
        assertFalse(method.contains(AttrFlag.HAS_ERROR), "correct hoisting ⇒ no error flag")
        assertNotNull(method.region)

        // Evidence of the hoist: a synthetic default-init (`CONST 0`) whose result shares a LocalVar with
        // the in-try definition (a two-member local), placed OUTSIDE the try in a dominating block. This
        // is jadx's `T v = null; try { v = … }`. Without the hoist there is no such multi-member local.
        val hoistedInit = method.blocks
            .flatMap { b -> b.instructions.map { it to b } }
            .firstOrNull { (insn, _) ->
                insn.opcode == IrOpcode.CONST &&
                    insn.result?.ssaValue?.localVar?.let { it.ssaValues.size > 1 } == true
            }
        assertNotNull(hoistedInit, "a hoisted default init (declaring the escaping var outside the try) must exist")
        val initBlock = hoistedInit.second
        // It is emitted before the try body: its block is not itself protected.
        assertFalse(
            initBlock.successors.any { it.contains(PipelineAttrs.EXC_HANDLER) },
            "the hoisted declaration must sit OUTSIDE the try (an unprotected block)",
        )
    }

    @Test
    fun tryDefinedValueUsedInCatchIsHoisted() {
        // v0 = compute();            (inside try)          <- defined in try body
        // } catch (Exception e) { use(v0); }               <- read on the EXCEPTIONAL path (highest risk)
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = compute), // try_start
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 = compute()  (try_end covers this)
                Insn(Opcode.RETURN_VOID, 2), // normal exit
                Insn(Opcode.MOVE_EXCEPTION, 3, intArrayOf(1)), // handler
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = use), // use(v0) in catch
                Insn(Opcode.RETURN_VOID, 5),
            ),
            tries = listOf(FakeTryBlock(0, 1, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(3), -1))),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "m")
        process(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "a value read in the catch must be hoisted, not bail")
        assertFalse(method.contains(AttrFlag.HAS_ERROR))
        val init = hoistedInit(method)
        assertNotNull(init, "the catch-escaping value must be hoisted (default init)")
        // The hoisted declaration must dominate the catch handler block (visible on the exceptional path).
        val initBlock = method.blocks.first { it.instructions.any { i -> i === init } }
        val catchBlock = method.blocks.first { b -> b.instructions.any { it.opcode == IrOpcode.MOVE_EXCEPTION } }
        assertTrue(initBlock.id in catchBlock.dominators, "the hoisted init must dominate the catch")
    }

    @Test
    fun wideAndObjectEscapingValuesGetTypeCorrectDefault() {
        // A hoisted default must be TYPE-correct: a long → `0L`, an object → `null` — not a bare int 0.
        // We assert the synthetic init literal carries the escaping value's own type (portably, no oracle).
        for ((ret, expected) in listOf("J" to IrType.LONG, "Ljava/lang/Object;" to IrType.objectType("java.lang.Object"))) {
            val comp = FakeMethodRef("Lc/F;", "compute", ret, emptyList())
            val us = FakeMethodRef("Lc/F;", "use", "V", listOf(ret))
            val reader = FakeCodeReader(
                2,
                listOf(
                    Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = comp),
                    Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)),
                    Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = us),
                    Insn(Opcode.RETURN_VOID, 3),
                    Insn(Opcode.MOVE_EXCEPTION, 4, intArrayOf(1)),
                    Insn(Opcode.GOTO, 5, target = 2),
                ),
                tries = listOf(FakeTryBlock(0, 1, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(4), -1))),
            )
            val method = TestPipeline.buildMethod(reader, methodName = "m")
            process(method)

            assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "escape of $ret must structure")
            val init = hoistedInit(method)
            assertNotNull(init, "escape of $ret must be hoisted")
            val litType = (init.getArg(0) as com.jadxmp.ir.insn.LiteralOperand).type
            when (expected) {
                is IrType.Primitive -> assertEquals(
                    com.jadxmp.ir.type.TypeKind.LONG, (litType as? IrType.Primitive)?.kind,
                    "a long escaping value's default must be typed long (renders `0L`)",
                )
                else -> assertTrue(litType is IrType.Object, "an object escaping value's default must be an object type (renders `null`)")
            }
        }
    }

    @Test
    fun loopDefinedValueUsedAfterLoopIsHoisted() {
        // compute();                       (preheader, before the loop)
        // while (true) { v0 = compute(); if (v0 != 0) break; }
        // use(v0);                          (AFTER the loop — v0 escapes the loop body scope)
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, indexType = IndexType.METHOD_REF, methodRef = compute), // preheader
                Insn(Opcode.INVOKE_STATIC, 1, indexType = IndexType.METHOD_REF, methodRef = compute), // loop header
                Insn(Opcode.MOVE_RESULT, 2, intArrayOf(0)), // v0 = compute()  (def INSIDE the loop)
                Insn(Opcode.IF_NEZ, 3, intArrayOf(0), target = 5), // if (v0 != 0) break
                Insn(Opcode.GOTO, 4, target = 1), // back-edge (latch)
                // use(v0) after the loop — v0 escapes the loop body
                Insn(Opcode.INVOKE_STATIC, 5, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = use),
                Insn(Opcode.RETURN_VOID, 6),
            ),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "m")
        process(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the loop-escape method must structure (not bail)")
        assertFalse(method.contains(AttrFlag.HAS_ERROR), "correct loop hoisting ⇒ no error flag")

        // Evidence: a synthetic default-init whose result shares a >1-member local with the in-loop def —
        // jadx's `int v = 0; while (…) { v = …; } … v`. And it sits OUTSIDE the loop, dominating the use.
        val init = hoistedInit(method)
        assertNotNull(init, "the loop-escaping value must be hoisted to a default init before the loop")
        val initBlock = method.blocks.first { b -> b.instructions.any { it === init } }
        val useBlock = method.blocks.first { b ->
            b.instructions.any { it.opcode == IrOpcode.INVOKE && it.offset == 5 }
        }
        assertTrue(initBlock.id in useBlock.dominators, "the hoisted declaration must dominate the post-loop use")
        // The hoist point is not itself part of the loop it escaped (it precedes the back-edge header).
        assertTrue(initBlock.successors.none { it.id in initBlock.dominators }, "the hoist block is not a loop header")
    }

    @Test
    fun loopLocalValueUsedOnlyInLoopIsNotHoisted() {
        // while (true) { v0 = compute(); use(v0); if (v0 != 0) break; }   (v0 read ONLY inside the loop)
        // A value that never escapes the loop keeps its natural in-loop declaration — no over-hoisting.
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, indexType = IndexType.METHOD_REF, methodRef = compute), // preheader
                Insn(Opcode.INVOKE_STATIC, 1, indexType = IndexType.METHOD_REF, methodRef = compute), // header
                Insn(Opcode.MOVE_RESULT, 2, intArrayOf(0)), // v0 = compute()  (def in loop)
                // use(v0) INSIDE the loop
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = use),
                Insn(Opcode.IF_NEZ, 4, intArrayOf(0), target = 6), // if (v0 != 0) break
                Insn(Opcode.GOTO, 5, target = 1), // back-edge
                Insn(Opcode.RETURN_VOID, 6), // after loop — does NOT read v0
            ),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "m")
        process(method)

        assertNull(hoistedInit(method), "a loop-local value must NOT be hoisted (no post-loop escape)")
    }
}
