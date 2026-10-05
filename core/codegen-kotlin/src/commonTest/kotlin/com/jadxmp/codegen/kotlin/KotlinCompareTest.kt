package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.CompareInstruction
import com.jadxmp.ir.insn.CompareKind
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinCompareTest {
    @Test
    fun floatingComparisonUsesOrderedTestsInsteadOfBoxedComparison() {
        val cls = irClass("a.C")
        val a = Local(1, IrType.FLOAT, name = "a", isParam = true)
        val b = Local(2, IrType.FLOAT, name = "b", isParam = true)
        cls.method("m", returnType = IrType.INT, argTypes = listOf(IrType.FLOAT, IrType.FLOAT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("a", "b")
            body(ret(expr(CompareInstruction(CompareKind.FLOAT_LESS, reg(0, IrType.INT), listOf(a.ref(), b.ref())))))
        }
        assertThatCode(generate(cls)).containsOne("return if (a > b) 1 else if (a == b) 0 else -1").doesNotContain(".compare")
    }

    @Test
    fun missingComparisonKindFailsHonestly() {
        val cls = irClass("a.C")
        cls.method("m", returnType = IrType.INT) {
            body(ret(expr(Instruction(IrOpcode.CMP, reg(0, IrType.INT), listOf(intLit(0), intLit(1))))))
        }
        assertThatCode(generate(cls)).contains("comparison without operand kind / NaN bias")
    }
}
