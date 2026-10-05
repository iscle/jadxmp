package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinAssignmentCoercionTest {
    @Test
    fun integerConstantsUseCoalescedBooleanLocalType() {
        val cls = irClass("a.C")
        val flag = Local(0, IrType.INT, name = "flag")
        flag.localVar.type = IrType.BOOLEAN
        cls.method("m", returnType = IrType.BOOLEAN) {
            body(
                Instruction(IrOpcode.CONST, flag.ref(), listOf(intLit(1))),
                Instruction(IrOpcode.CONST, flag.ref(), listOf(intLit(0))),
                ret(flag.ref()),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("var flag: Boolean = true")
            .containsOne("flag = false")
            .doesNotContain("flag = 1")
    }
}
