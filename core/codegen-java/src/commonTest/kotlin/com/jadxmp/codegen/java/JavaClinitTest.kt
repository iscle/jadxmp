package com.jadxmp.codegen.java

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/**
 * `<clinit>` renders as a `static { … }` block, indented as a class member. Two codegen contracts this
 * pins:
 *  - a blank `static final` own field is assigned by its **simple name** (the qualified `Cls.F = …` is
 *    rejected by javac for a blank final); every other static put stays **qualified** so a same-named
 *    local can't silently capture the assignment;
 *  - only the **terminal** `return-void` is dropped (illegal inside a static block); an early
 *    conditional return-void is preserved, not silently discarded.
 */
class JavaClinitTest {

    private val staticFinal = Flags.PUBLIC or Flags.STATIC or Flags.FINAL
    private val staticNonFinal = Flags.PUBLIC or Flags.STATIC
    private val clinitFlags = Flags.STATIC

    private fun fieldRefOf(name: String, type: IrType): FieldRef =
        FieldRef(IrType.objectType("a.Foo"), name, type)

    private fun staticPut(field: FieldRef, value: Operand): Instruction =
        FieldInstruction(field, isStatic = true, isPut = true, args = listOf(value))

    @Test
    fun blankFinalClinitAssignmentUsesSimpleNameAndNoTerminalReturn() {
        val cls = irClass("a.Foo")
        // A blank final (no constValue) assigned a runtime value in <clinit> — the BuildConfig.DEBUG shape.
        cls.fields.add(IrField(cls, "DEBUG", IrType.BOOLEAN, staticFinal))
        val debug = fieldRefOf("DEBUG", IrType.BOOLEAN)
        val init = expr(
            staticInvoke(
                owner = IrType.objectType("java.lang.Boolean"),
                name = "parseBoolean",
                returnType = IrType.BOOLEAN,
                argTypes = listOf(IrType.STRING),
                args = listOf(expr(constString("true"))),
            ),
        )
        cls.method("<clinit>", accessFlags = clinitFlags) {
            body(staticPut(debug, init), ret())
        }
        assertThatCode(generate(cls))
            .containsOne("static {")
            .containsOne("DEBUG = Boolean.parseBoolean(\"true\");")
            .doesNotContain("Foo.DEBUG") // blank final requires the simple name, not a qualifier
            .doesNotContain("return") // terminal return-void suppressed (illegal in a static block)
    }

    @Test
    fun nonFinalStaticPutInClinitStaysQualified() {
        val cls = irClass("a.Foo")
        cls.fields.add(IrField(cls, "COUNTER", IrType.INT, staticNonFinal))
        cls.method("<clinit>", accessFlags = clinitFlags) {
            body(staticPut(fieldRefOf("COUNTER", IrType.INT), intLit(7)), ret())
        }
        assertThatCode(generate(cls))
            .containsOne("static {")
            .containsOne("Foo.COUNTER = 7;") // assignable static keeps its qualifier
            .doesNotContain("return")
    }

    @Test
    fun ownStaticPutInRegularMethodStaysQualifiedEvenWhenAParamShadowsIt() {
        val cls = irClass("a.Foo")
        cls.fields.add(IrField(cls, "count", IrType.INT, staticNonFinal))
        // A regular method with a parameter named `count`: an UNqualified `count = 5` would assign the
        // PARAM, not the field — a silent miscompile. The field put must stay `Foo.count = 5`.
        cls.method("set", returnType = IrType.VOID, argTypes = listOf(IrType.INT), accessFlags = staticNonFinal) {
            this[CodegenKeys.PARAM_NAMES] = listOf("count")
            body(staticPut(fieldRefOf("count", IrType.INT), intLit(5)), ret())
        }
        assertThatCode(generate(cls))
            .containsOne("Foo.count = 5;")
            .containsOne("(int count)")
    }

    @Test
    fun onlyTerminalReturnVoidIsDroppedEarlyReturnPreserved() {
        val cls = irClass("a.Foo")
        val m = cls.method("<clinit>", accessFlags = clinitFlags)
        // Two blocks in a sequence, each ending in return-void: the first is an early return (preserved),
        // the second is the terminal fall-off (dropped).
        val early = BasicBlock(0).also { it.instructions.add(Instruction(IrOpcode.RETURN)) }
        val terminal = BasicBlock(1).also { it.instructions.add(Instruction(IrOpcode.RETURN)) }
        m.blocks.add(early)
        m.blocks.add(terminal)
        m.region = SequenceRegion().apply { add(early); add(terminal) }
        assertThatCode(generate(cls))
            .containsOne("static {")
            .containsOne("return;") // exactly the early return survives; terminal one is gone
    }

    @Test
    fun droppedClinitEmitsNoStaticBlock() {
        val cls = irClass("a.Foo")
        cls.fields.add(IrField(cls, "COUNTER", IrType.INT, staticNonFinal))
        cls.method("<clinit>", accessFlags = clinitFlags) {
            body(staticPut(fieldRefOf("COUNTER", IrType.INT), intLit(7)), ret())
            // A <clinit> the pipeline fully absorbed is flagged; codegen must skip it entirely.
            add(AttrFlag.DONT_GENERATE)
        }
        assertThatCode(generate(cls))
            .doesNotContain("static {")
            .doesNotContain("COUNTER = 7")
    }
}
