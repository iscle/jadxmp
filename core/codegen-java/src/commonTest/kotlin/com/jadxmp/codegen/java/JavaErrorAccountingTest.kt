package com.jadxmp.codegen.java

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The error-accounting honesty invariant (CLAUDE rule 4) for the Java backend: whenever it emits an
 * honesty marker for broken / not-reconstructed output, the node that `Decompiler.countErrors` sums over
 * MUST also carry [AttrFlag.HAS_ERROR] — otherwise `countErrors` / `reportedErrors` / the accuracy
 * scoreboard silently undercount a knowingly-wrong class as error-free.
 *
 * Each test asserts BOTH halves together (marker present AND the flag set), because the bug being
 * defended against is exactly the two drifting apart. Reproduces `countErrors`' summation locally so the
 * assertions read the same signal the pipeline does. Mirrors `codegen-kotlin`'s KotlinErrorAccountingTest.
 */
class JavaErrorAccountingTest {

    /** Mirror of `Decompiler.countErrors`: the class node + its methods + the whole nested tree. */
    private fun errorCount(cls: IrClass): Int {
        var count = if (cls.contains(AttrFlag.HAS_ERROR)) 1 else 0
        for (m in cls.methods) if (m.contains(AttrFlag.HAS_ERROR)) count++
        for (inner in cls.innerClasses) count += errorCount(inner)
        return count
    }

    @Test
    fun bareUnfusedNewInstanceBailsInsteadOfGuessingNoArgConstructor() {
        // HIGH: a bare NEW_INSTANCE reaching expression codegen is an un-fused orphan (its paired
        // `<init>(args)` was not folded in). Rendering `new Bar()` here silently drops the constructor
        // args, so the writer must BAIL to an honest marker instead. (A real no-arg `new` is a CONSTRUCTOR
        // insn and does NOT hit this path — see legitNoArgConstructorStillEmitsCall.)
        val cls = irClass("a.Foo")
        val bar = IrType.objectType("a.Bar")
        val r = Local(1, bar)
        val m = cls.method("m") { body(assign(r.ref(), newInstance(bar)), ret()) }

        val code = generate(cls)

        assertThatCode(code).containsOne("// JADXMP ERROR: unfused new-instance / constructor not reconstructed")
        // The silent guess (a bare constructor call) must NOT be emitted for the orphan allocation.
        assertThatCode(code).doesNotContain("new Bar()")
        assertTrue(m.contains(AttrFlag.HAS_ERROR), "bare new-instance marker must flag HAS_ERROR on the method")
        assertTrue(errorCount(cls) > 0, "errorCount must count the un-fused new-instance's broken output")
    }

    @Test
    fun legitNoArgConstructorStillEmitsCall() {
        // The HIGH bail must NOT catch a genuine no-arg constructor: that is a CONSTRUCTOR insn and renders
        // `new Bar()` cleanly with no error. Guards against the bail over-firing.
        val cls = irClass("a.Foo")
        val bar = IrType.objectType("a.Bar")
        val r = Local(1, bar)
        cls.method("m") { body(assign(r.ref(), constructor(bar, emptyList(), emptyList())), ret()) }

        val code = generate(cls)

        assertThatCode(code).containsOne("new Bar()")
        assertThatCode(code).doesNotContain("JADXMP ERROR")
        assertEquals(0, errorCount(cls), "a real no-arg constructor must report zero errors")
    }

    @Test
    fun unhandledOpcodeInLinearMethodFlagsMethod() {
        // MEDIUM: a single unhandled opcode in an otherwise-linear (RenderabilityGuard-"renderable")
        // method: the flag is NOT set upstream, so codegen must set it here or the class scores a false
        // no-error PASS.
        val cls = irClass("a.Foo")
        val r = Local(1, IrType.INT)
        val m = cls.method("m") {
            body(assign(r.ref(), Instruction(IrOpcode.FILL_ARRAY, args = listOf(intLit(0)))), ret())
        }

        val code = generate(cls)

        assertThatCode(code).containsOne("/* FILL_ARRAY */")
        assertTrue(m.contains(AttrFlag.HAS_ERROR), "unhandled-opcode marker must flag HAS_ERROR on the method")
        assertTrue(errorCount(cls) > 0, "errorCount must count the unhandled opcode's broken output")
    }

    @Test
    fun emptyMoveResultInLinearMethodFlagsMethod() {
        // MEDIUM: a degenerate 0-arg MOVE_RESULT has no source operand ⇒ `= /* empty */` (uncompilable) in
        // an otherwise-linear method: nothing upstream flags it, so codegen must.
        val cls = irClass("a.Foo")
        val r = Local(1, IrType.INT)
        val m = cls.method("m") { body(assign(r.ref(), Instruction(IrOpcode.MOVE_RESULT)), ret()) }

        val code = generate(cls)

        assertThatCode(code).containsOne("/* empty */")
        assertTrue(m.contains(AttrFlag.HAS_ERROR), "empty move/one-arg marker must flag HAS_ERROR on the method")
        assertTrue(errorCount(cls) > 0, "errorCount must count the empty-move's broken output")
    }

    @Test
    fun branchyMethodLabeledFallbackFlagsMethod() {
        // MEDIUM: the pre-structuring labeled-block stopgap (`blockN:`) is an honesty marker for
        // not-yet-reconstructed control flow ⇒ the method must be flagged so error accounting sees it.
        val cls = irClass("a.Foo")
        val use = staticInvoke(IrType.objectType("a.Foo"), "use", IrType.VOID, emptyList(), emptyList())
        val m = cls.method("m") {
            val b0 = BasicBlock(0).apply { instructions.add(use) }
            val b1 = BasicBlock(1).apply { instructions.add(ret()) }
            val b2 = BasicBlock(2).apply { instructions.add(ret()) }
            b0.successors.add(b1)
            b0.successors.add(b2) // fork ⇒ not linear ⇒ labeled fallback
            blocks.add(b0)
            blocks.add(b1)
            blocks.add(b2)
        }

        val code = generate(cls)

        assertThatCode(code).containsOne("block0: {")
        assertTrue(m.contains(AttrFlag.HAS_ERROR), "labeled-block fallback must flag HAS_ERROR on the method")
        assertTrue(errorCount(cls) > 0, "errorCount must count the unstructured method's broken output")
    }

    @Test
    fun fieldOpcodeWithoutFieldInstructionBails() {
        // LOW (unreachable today): an INSTANCE_GET whose insn is not a FieldInstruction has no field name.
        // The old code fabricated a `field` identifier; it must now bail honestly.
        val cls = irClass("a.Foo")
        val recv = Local(0, IrType.objectType("a.Bar"))
        val r = Local(1, IrType.INT)
        val m = cls.method("m") {
            body(assign(r.ref(), Instruction(IrOpcode.INSTANCE_GET, args = listOf(recv.ref()))), ret())
        }

        val code = generate(cls)

        assertThatCode(code).containsOne("// JADXMP ERROR: field opcode without a FieldInstruction (no field name)")
        assertThatCode(code).doesNotContain(".field")
        assertTrue(m.contains(AttrFlag.HAS_ERROR), "missing-field-name marker must flag HAS_ERROR on the method")
        assertTrue(errorCount(cls) > 0)
    }

    @Test
    fun constWithoutLiteralOperandBails() {
        // LOW (unreachable today): a CONST with no operand fabricated `0`; it must now bail honestly.
        val cls = irClass("a.Foo")
        val r = Local(1, IrType.INT)
        val m = cls.method("m") { body(assign(r.ref(), Instruction(IrOpcode.CONST)), ret()) }

        val code = generate(cls)

        assertThatCode(code).containsOne("// JADXMP ERROR: const without a literal operand")
        assertTrue(m.contains(AttrFlag.HAS_ERROR), "empty-const marker must flag HAS_ERROR on the method")
        assertTrue(errorCount(cls) > 0)
    }

    @Test
    fun constStringWithoutConstStringInstructionBails() {
        // LOW (unreachable today): a CONST_STRING that is not a ConstStringInstruction fabricated `""`.
        val cls = irClass("a.Foo")
        val r = Local(1, IrType.STRING)
        val m = cls.method("m") { body(assign(r.ref(), Instruction(IrOpcode.CONST_STRING)), ret()) }

        val code = generate(cls)

        assertThatCode(code).containsOne("// JADXMP ERROR: const-string without a ConstStringInstruction")
        assertTrue(m.contains(AttrFlag.HAS_ERROR), "empty-const-string marker must flag HAS_ERROR on the method")
        assertTrue(errorCount(cls) > 0)
    }

    @Test
    fun cleanClassReportsNoError() {
        // Guard the other direction: a fully-reconstructed class must NOT be flagged (no false positive).
        val cls = irClass("a.Foo")
        cls.fields.add(IrField(cls, "count", IrType.INT, Flags.PRIVATE))
        cls.method("m") { body(ret()) }

        val code = generate(cls)

        assertThatCode(code).doesNotContain("JADXMP ERROR")
        assertEquals(0, errorCount(cls), "a clean class must report zero errors")
    }
}
