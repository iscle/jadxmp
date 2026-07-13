package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.DecompileError
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinFallbackTest {

    @Test
    fun errorNodeEmitsHonestMarker() {
        val cls = irClass("a.Foo")
        cls.method("m") {
            add(AttrFlag.HAS_ERROR)
            this[IrAttrs.ERROR] = DecompileError("boom")
            body()
        }
        // Same honesty invariant as the Java backend — the "no-error" signal must see this.
        assertThatCode(generate(cls)).containsOne("// JADXMP ERROR: boom")
    }

    @Test
    fun unhandledOpcodeIsMarkedNotDropped() {
        val cls = irClass("a.Foo")
        val r = Local(1, IrType.INT)
        cls.method("m") {
            body(assign(r.ref(), Instruction(IrOpcode.FILL_ARRAY, args = listOf(intLit(0)))))
        }
        assertThatCode(generate(cls)).containsOne("/* FILL_ARRAY */")
    }
}
