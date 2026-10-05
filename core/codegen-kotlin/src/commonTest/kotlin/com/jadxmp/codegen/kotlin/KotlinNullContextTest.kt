package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinNullContextTest {
    @Test
    fun nullArgumentsRetainDeclaredOverloadTypes() {
        val cls = irClass("a.C")
        cls.method("m") {
            body(staticInvoke(
                IrType.objectType("a.Overloads"), "accept", IrType.VOID,
                listOf(IrType.STRING, IrType.array(IrType.INT), IrType.INT),
                listOf(lit(0, IrType.OBJECT), lit(0, IrType.OBJECT), intLit(0)),
            ))
        }
        assertThatCode(generate(cls)).containsOne("Overloads.accept(null as String?, null as IntArray?, 0)")
    }

    @Test
    fun literalNullThrowCreatesNullPointerException() {
        val cls = irClass("a.C")
        cls.method("m") { body(Instruction(IrOpcode.THROW, args = listOf(lit(0, IrType.THROWABLE)))) }
        assertThatCode(generate(cls)).containsOne("throw kotlin.NullPointerException()")
    }

    @Test
    fun nonNullThrowPreservesOperand() {
        val cls = irClass("a.C")
        val failure = Local(0, IrType.THROWABLE, "failure", isParam = true)
        cls.method("m", argTypes = listOf(IrType.THROWABLE)) {
            body(Instruction(IrOpcode.THROW, args = listOf(failure.ref())))
        }
        assertThatCode(generate(cls)).containsOne("throw failure").doesNotContain("NullPointerException")
    }
}
