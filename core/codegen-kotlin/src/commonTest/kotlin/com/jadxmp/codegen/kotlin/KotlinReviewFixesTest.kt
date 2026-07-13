package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.ConditionOp
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.region.Condition
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopKind
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/** Regression tests for the adversarial-review must/should-fixes (M1–M4, S1, S2). */
class KotlinReviewFixesTest {

    private var blockId = 200

    private fun block(vararg insns: Instruction): BasicBlock =
        BasicBlock(blockId++).also { b -> insns.forEach { b.instructions.add(it) } }

    private fun seq(vararg insns: Instruction): SequenceRegion =
        SequenceRegion().also { it.add(block(*insns)) }

    // ---------- M1: for + continue replays the update ----------

    @Test
    fun forLoopContinueReplaysUpdate() {
        val i = com.jadxmp.codegen.kotlin.Local(0, IrType.INT)
        val foo = IrType.objectType("a.Foo")
        val use = staticInvoke(foo, "use", IrType.VOID, listOf(IrType.INT), listOf(i.ref()))

        // Body: if (i == 5) { continue }  then  Foo.use(i)
        val ifRegion = IfRegion(
            Condition.Compare(ConditionOp.EQ, i.ref(), intLit(5)),
            thenRegion = seq(Instruction(IrOpcode.CONTINUE)),
        )
        val body = SequenceRegion().apply {
            add(ifRegion)
            add(block(use))
        }
        val loop = LoopRegion(LoopKind.FOR, Condition.Compare(ConditionOp.LT, i.ref(), intLit(10)), body).apply {
            this[CodegenKeys.LOOP_INIT] = assign(i.ref(), Instruction(IrOpcode.CONST, args = listOf(intLit(0))))
            this[CodegenKeys.LOOP_UPDATE] = assign(i.ref(), arith(ArithOp.ADD, i.ref(), intLit(1)))
        }

        val cls = irClass("a.Foo")
        cls.method("m") { region = loop }

        // The update `i = i + 1` runs both before `continue` (inside the `if`, indent 4) AND at the
        // back-edge — never skipped.
        assertThatCode(generate(cls))
            .countString(2, "i = i + 1")
            .containsLines(4, "i = i + 1", "continue")
    }

    // ---------- M2 follow-up: a type-variable field is marked, never `lateinit` ----------

    @Test
    fun typeVariableFieldIsMarkedNotLateinit() {
        // `lateinit var x: T` is illegal (unbounded T has upper bound Any?); such a field must route to
        // the honest marker branch, never emit silently-invalid `lateinit`.
        val cls = irClass("a.Box")
        cls.fields.add(IrField(cls, "value", IrType.typeVariable("T"), Flags.PRIVATE))
        assertThatCode(generate(cls))
            .containsOne("// JADXMP ERROR: field initializer not reconstructed")
            .containsLine(1, "private var value: T")
            .doesNotContain("lateinit")
    }

    // ---------- M3: INSTANCE + a real constructor is NOT an object ----------

    @Test
    fun instanceFieldWithParameterizedConstructorIsNotObject() {
        val cls = irClass("a.Holder", accessFlags = Flags.PUBLIC or Flags.FINAL)
        cls.fields.add(IrField(cls, "INSTANCE", IrType.objectType("a.Holder"), Flags.PUBLIC or Flags.STATIC or Flags.FINAL))
        cls.method("<init>", argTypes = listOf(IrType.INT), accessFlags = Flags.PUBLIC) {
            this[CodegenKeys.PARAM_NAMES] = listOf("v")
            body()
        }
        // Must stay a class; the real constructor and its parameter must survive (no silent drop).
        assertThatCode(generate(cls))
            .containsOne("class Holder {")
            .doesNotContain("object Holder")
            .containsOne("constructor(v: Int) {")
    }

    // ---------- M4: equals override has the compilable Any? signature ----------

    @Test
    fun equalsOverrideUsesNullableAny() {
        val cls = irClass("a.Foo")
        cls.method("equals", returnType = IrType.BOOLEAN, argTypes = listOf(IrType.objectType("java.lang.Object"))) {
            this[CodegenKeys.PARAM_NAMES] = listOf("o")
            body(ret(lit(1L, IrType.BOOLEAN)))
        }
        assertThatCode(generate(cls))
            .containsOne("override fun equals(o: Any?): Boolean {")
    }

    // ---------- S1: a static-final alias is not mistaken for an enum entry ----------

    @Test
    fun enumAliasFieldIsNotAnEntryAndUserValueOfSurvives() {
        val cls = irClass("a.Color", accessFlags = Flags.PUBLIC or Flags.FINAL or Flags.ENUM)
        val self = IrType.objectType("a.Color")
        cls.fields.add(IrField(cls, "RED", self, Flags.PUBLIC or Flags.STATIC or Flags.FINAL or Flags.ENUM))
        // An alias WITHOUT the enum flag: must be treated as a property, not a fake entry.
        cls.fields.add(IrField(cls, "DEFAULT", self, Flags.PUBLIC or Flags.STATIC or Flags.FINAL))
        // A user valueOf(int) overload must NOT be filtered like the synthetic valueOf(String).
        cls.method("valueOf", returnType = self, argTypes = listOf(IrType.INT), accessFlags = Flags.PUBLIC or Flags.STATIC) {
            this[CodegenKeys.PARAM_NAMES] = listOf("x")
            body(ret(lit(0L, self)))
        }
        assertThatCode(generate(cls))
            .containsLine(1, "RED;")
            .doesNotContain("DEFAULT,")
            .containsOne("val DEFAULT: Color")
            .containsOne("fun valueOf(x: Int): Color {")
    }

    // ---------- S2: constructor delegation in a body is flagged, not silently invalid ----------

    @Test
    fun constructorDelegationIsMarked() {
        val cls = irClass("a.Foo")
        val self = IrType.objectType("a.Foo")
        val thisLocal = com.jadxmp.codegen.kotlin.Local(0, self, isThis = true)
        cls.method("<init>", argTypes = emptyList()) {
            val delegation = InvokeInstruction(
                MethodRef(self, MethodRef.CONSTRUCTOR_NAME, IrType.VOID, listOf(IrType.INT)),
                InvokeKind.DIRECT,
                result = null,
                args = listOf(thisLocal.ref(), intLit(1)),
                opcode = IrOpcode.INVOKE,
            )
            body(delegation)
        }
        assertThatCode(generate(cls))
            .containsOne("// JADXMP ERROR: constructor delegation not reconstructed (Kotlin header-only)")
            .containsOne("this(1)")
    }
}
