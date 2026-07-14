package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/**
 * Constructor-delegation HEADER rendering (the Kotlin analogue of Java's body-position `super(...)`).
 *
 * A Kotlin secondary constructor's `this(...)`/`super(...)` delegation is header-only
 * (`constructor(args) : this(...)`), never a body statement — kotlinc rejects the body form. These tests
 * pin the codegen contract:
 *  - a clean leading no-arg `super()` (implicit `Any`/Object super) is OMITTED;
 *  - a clean leading `this(args)` moves to the `: this(args)` header;
 *  - anything that can't be faithfully hoisted (a statement precedes the delegation; a non-Object super
 *    that would collide with the class-header supertype parens) BAILS to the honest `// JADXMP ERROR`
 *    marker rather than emit an invalid header or drop the call (rule 4).
 */
class KotlinConstructorDelegationTest {

    private val fooType = IrType.objectType("a.Foo")

    /** An un-normalized `<init>` delegation invoke (`INVOKE`, DIRECT, receiver `this` at arg 0). */
    private fun initDelegation(owner: IrType, argTypes: List<IrType>, args: List<Operand>): InvokeInstruction =
        InvokeInstruction(
            MethodRef(owner, MethodRef.CONSTRUCTOR_NAME, owner, argTypes),
            InvokeKind.DIRECT,
            result = null,
            args = args,
            opcode = IrOpcode.INVOKE,
        )

    @Test
    fun noArgObjectSuperIsOmitted() {
        val cls = irClass("a.Foo", superType = IrType.OBJECT)
        val self = Local(0, fooType, isThis = true)
        cls.method("<init>") {
            body(
                initDelegation(IrType.OBJECT, emptyList(), listOf(self.ref())),
                ret(),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("constructor() {")
            .doesNotContain("super(")
            .doesNotContain("JADXMP ERROR")
    }

    @Test
    fun thisDelegationMovesToHeader() {
        val cls = irClass("a.Foo", superType = IrType.OBJECT)
        val self = Local(0, fooType, isThis = true)
        val s = Local(1, IrType.STRING, name = "s", isParam = true)
        cls.method("<init>", argTypes = listOf(IrType.STRING)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("s")
            body(
                initDelegation(
                    fooType,
                    listOf(IrType.STRING, IrType.INT),
                    listOf(self.ref(), s.ref(), intLit(7)),
                ),
                ret(),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("constructor(s: String) : this(s, 7) {")
            .doesNotContain("JADXMP ERROR")
    }

    @Test
    fun noArgSuperOmittedKeepsRestOfBody() {
        val cls = irClass("a.Foo", superType = IrType.OBJECT)
        cls.fields.add(IrField(cls, "x", IrType.INT, Flags.PRIVATE))
        val self = Local(0, fooType, isThis = true)
        val p = Local(1, IrType.INT, name = "x", isParam = true)
        val xField = FieldRef(fooType, "x", IrType.INT)
        cls.method("<init>", argTypes = listOf(IrType.INT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("x")
            body(
                initDelegation(IrType.OBJECT, emptyList(), listOf(self.ref())),
                instancePut(self.ref(), p.ref(), xField),
                ret(),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("constructor(x: Int) {")
            .doesNotContain("super(")
            .doesNotContain("JADXMP ERROR")
            .contains("this.x = x")
    }

    @Test
    fun statementBeforeDelegationBails() {
        val cls = irClass("a.Foo", superType = IrType.OBJECT)
        val self = Local(0, fooType, isThis = true)
        val tmp = Local(1, IrType.INT)
        cls.method("<init>") {
            body(
                assign(tmp.ref(), Instruction(IrOpcode.CONST, args = listOf(intLit(5)))),
                initDelegation(IrType.OBJECT, emptyList(), listOf(self.ref())),
                ret(),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("// JADXMP ERROR: constructor delegation not reconstructed (Kotlin header-only)")
            .contains("super()")
    }

    @Test
    fun nonObjectSuperBails() {
        // A super to a real base is rendered on the class header WITH parens (`: Base()`), implying a
        // primary constructor; a secondary-header `: super(...)` would collide, so we leave the honest
        // marker rather than emit conflicting headers.
        val cls = irClass("a.Foo", superType = IrType.objectType("a.Base"))
        val self = Local(0, fooType, isThis = true)
        cls.method("<init>") {
            body(
                initDelegation(IrType.objectType("a.Base"), emptyList(), listOf(self.ref())),
                ret(),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("// JADXMP ERROR: constructor delegation not reconstructed (Kotlin header-only)")
            .contains("super()")
    }
}
