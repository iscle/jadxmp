package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.ConditionOp
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.region.Condition
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/**
 * A Kotlin function parameter is `val` (immutable), so a body reassignment `p = …` does not compile.
 * The backend keeps the parameter `val` and, at method entry, introduces a mutable copy `var pCopy = p`,
 * redirecting every body use of the parameter to the copy. These tests pin that behaviour, including the
 * rule-4 boundary: only a genuinely reassigned parameter is copied.
 */
class KotlinValParamReassignTest {

    private val foo = IrType.objectType("a.Foo")

    private fun useCall(vararg args: Operand): Instruction =
        staticInvoke(foo, "use", IrType.VOID, args.map { it.type }, args.toList())

    private fun constInt(value: Int): Instruction =
        Instruction(IrOpcode.CONST, args = listOf(intLit(value)))

    private fun methodWith(
        argTypes: List<IrType>,
        paramNames: List<String>,
        vararg insns: Instruction,
    ): String {
        val cls = irClass("a.Foo")
        cls.method("m", argTypes = argTypes) {
            this[CodegenKeys.PARAM_NAMES] = paramNames
            body(*insns)
        }
        return generate(cls)
    }

    @Test
    fun reassignedParamGetsMutableCopyAndKeepsParamVal() {
        val i = Local(1, IrType.INT, name = "i", isParam = true)
        val out = methodWith(
            listOf(IrType.INT),
            listOf("i"),
            assign(i.ref(), arith(ArithOp.ADD, i.ref(), intLit(1))), // i = i + 1  → reassigns the param
            useCall(i.ref()),
        )
        assertThatCode(out)
            .containsOne("fun m(i: Int)")
            .containsLine(1, "var i2 = i") // mutable copy introduced at method entry
            .containsLine(1, "i2 = i2 + 1") // the reassignment is routed to the copy
            .containsLine(1, "Foo.use(i2)") // the later read is routed to the copy
            .notContainsLine(1, "i = i + 1") // the parameter itself is never reassigned
    }

    @Test
    fun readOnlyParamGetsNoCopy() {
        val i = Local(1, IrType.INT, name = "i", isParam = true)
        val out = methodWith(listOf(IrType.INT), listOf("i"), useCall(i.ref()))
        assertThatCode(out)
            .containsOne("Foo.use(i)") // the param is read directly — no copy needed
            .doesNotContain("var ") // no copy declaration for a param that is only read
    }

    @Test
    fun onlyReassignedParamIsCopiedNotReadOnlySibling() {
        val a = Local(1, IrType.INT, name = "a", isParam = true)
        val b = Local(2, IrType.INT, name = "b", isParam = true)
        val out = methodWith(
            listOf(IrType.INT, IrType.INT),
            listOf("a", "b"),
            assign(a.ref(), arith(ArithOp.ADD, a.ref(), b.ref())), // a = a + b  (a reassigned, b read-only)
            useCall(a.ref(), b.ref()),
        )
        assertThatCode(out)
            .containsLine(1, "var a2 = a") // only the reassigned param gets a copy
            .containsLine(1, "a2 = a2 + b") // reassigned param → copy; read-only sibling stays `b`
            .containsLine(1, "Foo.use(a2, b)")
            .doesNotContain("var b") // the read-only param gets no copy
    }

    @Test
    fun readsBeforeAndAfterReassignmentAllUseTheCopy() {
        val i = Local(1, IrType.INT, name = "i", isParam = true)
        val out = methodWith(
            listOf(IrType.INT),
            listOf("i"),
            useCall(i.ref()), // read BEFORE the reassignment
            assign(i.ref(), constInt(5)), // i = 5
            useCall(i.ref()), // read AFTER the reassignment
        )
        // Faithful: the copy is initialised to the parameter, so a read before the first write still sees the
        // same value — both reads route to the copy, and no assignment is dropped.
        assertThatCode(out)
            .containsLine(1, "var i2 = i")
            .countString(2, "Foo.use(i2)")
            .containsLine(1, "i2 = 5")
    }

    @Test
    fun reassignmentInsideRegionBranchIsDetected() {
        // The detection walk must cover the region tree, not just a linear block list: a param reassigned
        // only inside an `if` branch still needs its entry copy, and the branch/condition reads route to it.
        val cls = irClass("a.Foo")
        val a = Local(1, IrType.INT, name = "a", isParam = true)
        val thenBlk = BasicBlock(200).also { it.instructions.add(assign(a.ref(), constInt(0))) }
        val region = IfRegion(
            Condition.Compare(ConditionOp.GT, a.ref(), intLit(5)),
            thenRegion = SequenceRegion().apply { add(thenBlk) },
            elseRegion = null,
        )
        cls.method("m", argTypes = listOf(IrType.INT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("a")
            this.region = region
        }
        assertThatCode(generate(cls))
            .containsLine(1, "var a2 = a") // copy hoisted to method entry, ABOVE the `if`
            .containsOne("if (a2 > 5)") // condition read also routes to the copy
            .containsLine(2, "a2 = 0") // in-branch reassignment routes to the copy
    }

    @Test
    fun nonParamLocalReassignmentIsNotTreatedAsParamCopy() {
        // Rule-4 boundary: a plain (non-parameter) local written twice keeps ordinary declare-on-first-assign
        // and a plain second reassignment — it is not a `val` parameter, so NO entry copy is fabricated.
        val t = Local(3, IrType.INT, name = "t") // isParam = false
        val out = methodWith(
            emptyList(),
            emptyList(),
            assign(t.ref(), constInt(1)),
            assign(t.ref(), constInt(2)),
            useCall(t.ref()),
        )
        assertThatCode(out)
            .containsLine(1, "var t: Int = 1") // normal declare-on-first-assign
            .containsLine(1, "t = 2") // plain reassignment (a non-param local is already `var`)
            .doesNotContain("var t2") // no parameter-style copy for a non-parameter
    }
}
