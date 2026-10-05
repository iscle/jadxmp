package com.jadxmp.codegen.java

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class JavaAssignmentCoercionTest {
    @Test
    fun integerConstantsUseTheCoalescedBooleanLocalType() {
        val cls = irClass("a.C")
        val flag = Local(0, IrType.INT, name = "flag")
        flag.localVar.type = IrType.BOOLEAN
        cls.method("m", returnType = IrType.BOOLEAN) {
            body(
                Instruction(IrOpcode.CONST, flag.ref(), listOf(intLit(1))),
                ret(flag.ref()),
            )
        }
        assertThatCode(generate(cls)).containsOne("boolean flag = true;").doesNotContain("flag = 1")
    }
}
