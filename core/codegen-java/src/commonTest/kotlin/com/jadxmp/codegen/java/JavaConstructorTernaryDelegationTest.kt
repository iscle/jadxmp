package com.jadxmp.codegen.java

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.ConditionOp
import com.jadxmp.ir.insn.ConstStringInstruction
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/**
 * Codegen renders a `this()`/`super()` delegation whose argument's definition is a conditional as a
 * ternary argument — the shape [com.jadxmp.pipeline.constructor.ConstructorArgTernaryFold] produces for
 * a default-arguments synthetic constructor. This proves the illegal "statement before `this()`" output
 * is replaced by the legal folded call jadx emits.
 */
class JavaConstructorTernaryDelegationTest {

    private fun initInvoke(owner: IrType, argTypes: List<IrType>, args: List<Operand>) =
        InvokeInstruction(MethodRef(owner, MethodRef.CONSTRUCTOR_NAME, owner, argTypes), InvokeKind.DIRECT, result = null, args = args)

    /** `cond ? trueVal : falseVal`. */
    private fun ternary(cond: Instruction, trueVal: Operand, falseVal: Operand) =
        expr(Instruction(IrOpcode.TERNARY, result = null, args = listOf(expr(cond), trueVal, falseVal)))

    /** `(reg & mask) != 0`. */
    private fun maskTest(regRef: Operand, mask: Int) =
        IfInstruction(ConditionOp.NE, listOf(expr(arith(ArithOp.AND, regRef, intLit(mask))), intLit(0)))

    @Test
    fun conditionalArgsRenderAsTernariesInDelegation() {
        val self = IrType.objectType("conditions.Foo")
        val obj = IrType.OBJECT
        val cls = irClass("conditions.Foo", superType = obj)

        val thisRef = Local(0, self, isThis = true)
        val str = Local(1, IrType.STRING, name = "str", isParam = true)
        val str2 = Local(2, IrType.STRING, name = "str2", isParam = true)
        val str3 = Local(3, IrType.STRING, name = "str3", isParam = true)
        val z = Local(4, IrType.BOOLEAN, name = "z", isParam = true)
        val i = Local(5, IrType.INT, name = "i", isParam = true)

        cls.method(
            "<init>",
            argTypes = listOf(IrType.STRING, IrType.STRING, IrType.STRING, IrType.BOOLEAN, IrType.INT, IrType.INT),
        ) {
            thisArg = thisRef.ssaValue
            this[CodegenKeys.PARAM_NAMES] = listOf("str", "str2", "str3", "z", "i", "i2")
            body(
                initInvoke(
                    self,
                    listOf(IrType.STRING, IrType.STRING, IrType.STRING, IrType.BOOLEAN),
                    listOf(
                        thisRef.ref(),
                        str.ref(),
                        ternary(maskTest(i.ref(), 2), expr(ConstStringInstruction("")), str2.ref()),
                        ternary(maskTest(i.ref(), 4), expr(ConstStringInstruction("")), str3.ref()),
                        ternary(maskTest(i.ref(), 8), lit(0, IrType.BOOLEAN), z.ref()),
                    ),
                ),
            )
        }

        assertThatCode(generate(cls))
            .containsOne("this(str, (i & 2) != 0 ? \"\" : str2, (i & 4) != 0 ? \"\" : str3, (i & 8) != 0 ? false : z);")
            .doesNotContain("if (")
    }
}
