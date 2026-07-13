package com.jadxmp.codegen.java

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class JavaArrayPutTest {

    /** Canonical IR order (jadx: aput): args = [value, array, index]. */
    private fun arrayPut(value: com.jadxmp.ir.insn.Operand, array: com.jadxmp.ir.insn.Operand, index: com.jadxmp.ir.insn.Operand) =
        Instruction(IrOpcode.ARRAY_PUT, args = listOf(value, array, index))

    @Test
    fun arrayPutEmitsArrayIndexEqualsValueInCorrectOrder() {
        val arr = Local(0, IrType.array(IrType.STRING), name = "arr", isParam = true)
        val cls = irClass("a.Foo")
        cls.method("m", argTypes = listOf(IrType.array(IrType.STRING))) {
            this[com.jadxmp.codegen.CodegenKeys.PARAM_NAMES] = listOf("arr")
            body(arrayPut(constString("a").let { expr(it) }, arr.ref(), intLit(0)))
        }
        assertThatCode(generate(cls))
            .containsOne("arr[0] = \"a\";")
            // The previously-broken order produced `"a"[arr] = 0;`.
            .doesNotContain("[arr]")
            .doesNotContain("\"a\"[")
    }
}
