package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.ConditionOp
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.region.Condition
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinReferenceComparisonTest {
    @Test
    fun referenceEqualityUsesIdentity() {
        assertThatCode(comparison(IrType.STRING, ConditionOp.EQ)).containsOne("if (a === b)")
    }

    @Test
    fun referenceInequalityUsesIdentity() {
        assertThatCode(comparison(IrType.OBJECT, ConditionOp.NE)).containsOne("if (a !== b)")
    }

    @Test
    fun numericEqualityKeepsValueComparison() {
        assertThatCode(comparison(IrType.INT, ConditionOp.EQ)).containsOne("if (a == b)")
    }

    @Test
    fun charShortEqualityPromotesBothOperandsWithoutTruncation() {
        assertThatCode(comparison(IrType.CHAR, ConditionOp.EQ, IrType.SHORT)).containsOne("if (a.code == b.toInt())")
    }

    @Test
    fun booleanByteEqualityPromotesBothOperandsToInt() {
        assertThatCode(comparison(IrType.BOOLEAN, ConditionOp.EQ, IrType.BYTE))
            .containsOne("if ((if (a) 1 else 0) == b.toInt())")
    }

    private fun comparison(type: IrType, op: ConditionOp, rightType: IrType = type): String {
        val cls = irClass("a.C")
        val a = Local(1, type, name = "a", isParam = true)
        val b = Local(2, rightType, name = "b", isParam = true)
        cls.method("m", returnType = IrType.BOOLEAN, argTypes = listOf(type, rightType)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("a", "b")
            region = IfRegion(
                Condition.Compare(op, a.ref(), b.ref()),
                SequenceRegion().apply { add(BasicBlock(0).apply { instructions.add(ret(lit(1, IrType.BOOLEAN))) }) },
                SequenceRegion().apply { add(BasicBlock(1).apply { instructions.add(ret(lit(0, IrType.BOOLEAN))) }) },
            )
        }
        return generate(cls)
    }
}
