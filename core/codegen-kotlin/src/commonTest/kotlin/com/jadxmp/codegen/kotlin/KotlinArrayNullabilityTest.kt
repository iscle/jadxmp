package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinArrayNullabilityTest {
    @Test
    fun arrayParametersAllowNullUntilDereference() {
        val cls = irClass("a.C")
        val array = Local(0, IrType.array(IrType.INT), "values", isParam = true)
        cls.method("length", IrType.INT, listOf(array.type)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("values")
            body(ret(expr(Instruction(IrOpcode.ARRAY_LENGTH, reg(-1, IrType.INT), listOf(array.ref())))))
        }
        assertThatCode(generate(cls)).containsOne("values: IntArray?").containsOne("return values!!.size")
    }

    @Test
    fun arrayCopiesAndReturnsKeepNull() {
        val cls = irClass("a.C")
        val array = Local(0, IrType.array(IrType.INT), "values", isParam = true)
        val copy = Local(1, array.type, "copy")
        cls.method("copy", array.type, listOf(array.type)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("values")
            body(Instruction(IrOpcode.MOVE, copy.ref(), listOf(array.ref())), ret(copy.ref()))
        }
        assertThatCode(generate(cls))
            .containsOne("fun copy(values: IntArray?): IntArray?")
            .containsOne("var copy: IntArray? = values")
            .containsOne("return copy")
    }

    @Test
    fun newArrayLocalDoesNotAcquireUnnecessaryNullability() {
        val cls = irClass("a.C")
        val array = Local(0, IrType.array(IrType.INT), "values")
        cls.method("length", IrType.INT) {
            body(newArray(array.type, intLit(3), array.ref()), ret(expr(Instruction(IrOpcode.ARRAY_LENGTH, reg(-1, IrType.INT), listOf(array.ref())))))
        }
        assertThatCode(generate(cls)).containsOne("var values: IntArray = IntArray(3)").containsOne("return values.size")
    }

    @Test
    fun referenceArrayElementsCanRemainNull() {
        val cls = irClass("a.C")
        val array = Local(0, IrType.array(IrType.STRING), "values", isParam = true)
        cls.method("read", IrType.STRING, listOf(array.type)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("values")
            body(ret(expr(Instruction(IrOpcode.ARRAY_GET, reg(-1, IrType.STRING), listOf(array.ref(), intLit(0))))))
        }
        assertThatCode(generate(cls)).containsOne("values: Array<String?>?").containsOne("): String?")
    }
}
