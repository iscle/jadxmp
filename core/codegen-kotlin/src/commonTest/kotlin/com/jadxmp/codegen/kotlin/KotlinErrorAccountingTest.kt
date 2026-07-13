package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.TypeInstruction
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The error-accounting honesty invariant (CLAUDE rule 4): whenever the Kotlin backend emits an honesty
 * marker for broken / not-reconstructed output, the node that `Decompiler.countErrors` sums over MUST
 * also carry [AttrFlag.HAS_ERROR] — otherwise `countErrors` / `reportedErrors` / the Kotlin accuracy
 * scoreboard silently undercount a knowingly-wrong class as error-free.
 *
 * Each test asserts BOTH halves together (marker present AND the flag set), because the bug being
 * defended against is exactly the two drifting apart. Reproduces `countErrors`' summation locally so the
 * assertions read the same signal the pipeline does.
 */
class KotlinErrorAccountingTest {

    /** Mirror of `Decompiler.countErrors`: the class node + its methods + the whole nested tree. */
    private fun errorCount(cls: IrClass): Int {
        var count = if (cls.contains(AttrFlag.HAS_ERROR)) 1 else 0
        for (m in cls.methods) if (m.contains(AttrFlag.HAS_ERROR)) count++
        for (inner in cls.innerClasses) count += errorCount(inner)
        return count
    }

    @Test
    fun fieldInitializerNotReconstructedFlagsOwningClass() {
        // A `val` reference field with no reconstructed initializer hits the M2 honesty marker branch.
        val cls = irClass("a.Foo")
        cls.fields.add(IrField(cls, "bar", IrType.objectType("a.Bar"), Flags.PRIVATE or Flags.FINAL))

        val code = generate(cls)

        assertThatCode(code).containsOne("// JADXMP ERROR: field initializer not reconstructed")
        // The marker must not stand alone: the owning class node (what countErrors sums) is flagged.
        assertTrue(cls.contains(AttrFlag.HAS_ERROR), "field-init marker must flag HAS_ERROR on the class")
        assertTrue(errorCount(cls) > 0, "errorCount must count the marked field's broken output")
    }

    @Test
    fun constructorDelegationNotReconstructedFlagsMethod() {
        // A body-position `this(...)`/`super(...)` delegation hits the S2 honesty marker branch.
        val cls = irClass("a.Foo")
        val self = IrType.objectType("a.Foo")
        val thisLocal = Local(0, self, isThis = true)
        val ctor = cls.method("<init>", argTypes = emptyList()) {
            val delegation = InvokeInstruction(
                MethodRef(self, MethodRef.CONSTRUCTOR_NAME, IrType.VOID, listOf(IrType.INT)),
                InvokeKind.DIRECT,
                result = null,
                args = listOf(thisLocal.ref(), intLit(1)),
                opcode = IrOpcode.INVOKE,
            )
            body(delegation)
        }

        val code = generate(cls)

        assertThatCode(code)
            .containsOne("// JADXMP ERROR: constructor delegation not reconstructed (Kotlin header-only)")
        assertTrue(ctor.contains(AttrFlag.HAS_ERROR), "delegation marker must flag HAS_ERROR on the method")
        assertTrue(errorCount(cls) > 0, "errorCount must count the marked constructor's broken output")
    }

    @Test
    fun unhandledOpcodeInLinearMethodFlagsMethod() {
        // A single unhandled opcode in an otherwise-linear (RenderabilityGuard-"renderable") method:
        // the flag is NOT set upstream, so codegen must set it here or the class scores false no-error.
        val cls = irClass("a.Foo")
        val r = Local(1, IrType.INT)
        val m = cls.method("m") {
            body(assign(r.ref(), Instruction(IrOpcode.FILL_ARRAY, args = listOf(intLit(0)))))
        }

        val code = generate(cls)

        assertThatCode(code).containsOne("/* FILL_ARRAY */")
        assertTrue(m.contains(AttrFlag.HAS_ERROR), "unhandled-opcode marker must flag HAS_ERROR on the method")
        assertTrue(errorCount(cls) > 0, "errorCount must count the unhandled opcode's broken output")
    }

    @Test
    fun emptyMoveResultInLinearMethodFlagsMethod() {
        // A degenerate 0-arg MOVE_RESULT has no source operand ⇒ `= /* empty */` (uncompilable) in an
        // otherwise-linear (RenderabilityGuard-"renderable") method: nothing upstream flags it, so codegen
        // must, or the class scores a false no-error PASS.
        val cls = irClass("a.Foo")
        val r = Local(1, IrType.INT)
        val m = cls.method("m") {
            body(assign(r.ref(), Instruction(IrOpcode.MOVE_RESULT)))
        }

        val code = generate(cls)

        assertThatCode(code).containsOne("/* empty */")
        assertTrue(m.contains(AttrFlag.HAS_ERROR), "empty move/one-arg marker must flag HAS_ERROR on the method")
        assertTrue(errorCount(cls) > 0, "errorCount must count the empty-move's broken output")
    }

    @Test
    fun bareUnfusedNewInstanceBailsInsteadOfGuessingNoArgConstructor() {
        // HIGH: a bare NEW_INSTANCE reaching expression codegen is an un-fused orphan (its paired
        // `<init>(args)` was not folded in). Rendering `Bar()` here silently drops the constructor args, so
        // the writer must BAIL to an honest marker instead. (A real no-arg `new` is a CONSTRUCTOR insn and
        // does NOT hit this path — see legitNoArgConstructorStillEmitsCall.)
        val cls = irClass("a.Foo")
        val bar = IrType.objectType("a.Bar")
        val r = Local(1, bar)
        val m = cls.method("m") { body(assign(r.ref(), newInstance(bar)), ret()) }

        val code = generate(cls)

        assertThatCode(code).containsOne("// JADXMP ERROR: unfused new-instance / constructor not reconstructed")
        // The silent guess (a bare constructor call) must NOT be emitted for the orphan allocation.
        assertThatCode(code).doesNotContain("Bar()")
        assertTrue(m.contains(AttrFlag.HAS_ERROR), "bare new-instance marker must flag HAS_ERROR on the method")
        assertTrue(errorCount(cls) > 0, "errorCount must count the un-fused new-instance's broken output")
    }

    @Test
    fun legitNoArgConstructorStillEmitsCall() {
        // The HIGH bail must NOT catch a genuine no-arg constructor: that is a CONSTRUCTOR insn and renders
        // `Bar()` cleanly with no error. Guards against the bail over-firing.
        val cls = irClass("a.Foo")
        val bar = IrType.objectType("a.Bar")
        val r = Local(1, bar)
        cls.method("m") { body(assign(r.ref(), constructor(bar, emptyList(), emptyList())), ret()) }

        val code = generate(cls)

        assertThatCode(code).containsOne("Bar()")
        assertThatCode(code).doesNotContain("JADXMP ERROR")
        assertEquals(0, errorCount(cls), "a real no-arg constructor must report zero errors")
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
    fun newArrayWithoutSizeOperandBails() {
        // LOW (unreachable today): a NEW_ARRAY with no size operand fabricated `0`; it must now bail honestly.
        val cls = irClass("a.Foo")
        val arr = Local(1, IrType.array(IrType.INT))
        val m = cls.method("m") {
            body(assign(arr.ref(), TypeInstruction(IrOpcode.NEW_ARRAY, IrType.array(IrType.INT), result = null)), ret())
        }

        val code = generate(cls)

        assertThatCode(code).containsOne("// JADXMP ERROR: new-array without a size operand")
        assertTrue(m.contains(AttrFlag.HAS_ERROR), "empty-new-array marker must flag HAS_ERROR on the method")
        assertTrue(errorCount(cls) > 0)
    }

    @Test
    fun cleanClassReportsNoError() {
        // Guard the other direction: a fully-reconstructed class must NOT be flagged (no false positive).
        val cls = irClass("a.Foo")
        cls.fields.add(
            IrField(cls, "count", IrType.INT, Flags.PRIVATE), // non-final primitive → `var count = 0`
        )
        cls.method("m") { body(ret()) }

        val code = generate(cls)

        assertThatCode(code).doesNotContain("JADXMP ERROR")
        assertEquals(0, errorCount(cls), "a clean class must report zero errors")
    }
}
