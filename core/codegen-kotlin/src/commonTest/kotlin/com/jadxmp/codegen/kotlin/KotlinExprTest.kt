package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.TypeInstruction
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinExprTest {

    /** `fun m(<params>): <returnType> { return <value> }` and generate. */
    private fun retExpr(
        returnType: IrType,
        argTypes: List<IrType> = emptyList(),
        paramNames: List<String> = emptyList(),
        value: Operand,
    ): String {
        val cls = irClass("a.C")
        cls.method("m", returnType = returnType, argTypes = argTypes) {
            if (paramNames.isNotEmpty()) this[CodegenKeys.PARAM_NAMES] = paramNames
            body(ret(value))
        }
        return generate(cls)
    }

    @Test
    fun constructionHasNoNewKeyword() {
        val cls = irClass("a.C")
        val p = Local(1, IrType.objectType("a.Point"))
        cls.method("m") {
            body(
                assign(p.ref(), constructor(IrType.objectType("a.Point"), listOf(IrType.INT, IrType.INT), listOf(intLit(1), intLit(2)))),
                ret(),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("Point(1, 2)")
            .doesNotContain("new ")
    }

    @Test
    fun checkCastUsesAs() {
        val o = Local(1, IrType.objectType("a.Base"), name = "o", isParam = true)
        val code = retExpr(
            returnType = IrType.objectType("a.Derived"),
            argTypes = listOf(IrType.objectType("a.Base")),
            paramNames = listOf("o"),
            value = expr(checkCast(IrType.objectType("a.Derived"), o.ref())),
        )
        assertThatCode(code).containsOne("return o as Derived")
    }

    @Test
    fun instanceOfUsesIs() {
        val o = Local(1, IrType.objectType("a.Base"), name = "o", isParam = true)
        val code = retExpr(
            returnType = IrType.BOOLEAN,
            argTypes = listOf(IrType.objectType("a.Base")),
            paramNames = listOf("o"),
            value = expr(instanceOf(IrType.objectType("a.Derived"), o.ref())),
        )
        assertThatCode(code).containsOne("return o is Derived")
    }

    @Test
    fun bitwiseAndIsNamedInfix() {
        val a = Local(1, IrType.INT, name = "a", isParam = true)
        val b = Local(2, IrType.INT, name = "b", isParam = true)
        val code = retExpr(
            returnType = IrType.INT,
            argTypes = listOf(IrType.INT, IrType.INT),
            paramNames = listOf("a", "b"),
            value = expr(arith(ArithOp.AND, a.ref(), b.ref())),
        )
        assertThatCode(code)
            .containsOne("return a and b")
            .doesNotContain("&")
    }

    @Test
    fun unsignedShiftIsUshrInfix() {
        val a = Local(1, IrType.INT, name = "a", isParam = true)
        val b = Local(2, IrType.INT, name = "b", isParam = true)
        val code = retExpr(
            returnType = IrType.INT,
            argTypes = listOf(IrType.INT, IrType.INT),
            paramNames = listOf("a", "b"),
            value = expr(arith(ArithOp.USHR, a.ref(), b.ref())),
        )
        assertThatCode(code).containsOne("return a ushr b")
    }

    @Test
    fun bitwiseNotUsesInv() {
        val a = Local(1, IrType.INT, name = "a", isParam = true)
        val code = retExpr(
            returnType = IrType.INT,
            argTypes = listOf(IrType.INT),
            paramNames = listOf("a"),
            value = expr(Instruction(IrOpcode.NOT, args = listOf(a.ref()))),
        )
        assertThatCode(code).containsOne("return a.inv()")
    }

    @Test
    fun additionKeepsOperatorSyntax() {
        val a = Local(1, IrType.INT, name = "a", isParam = true)
        val b = Local(2, IrType.INT, name = "b", isParam = true)
        val code = retExpr(
            returnType = IrType.INT,
            argTypes = listOf(IrType.INT, IrType.INT),
            paramNames = listOf("a", "b"),
            value = expr(arith(ArithOp.ADD, a.ref(), b.ref())),
        )
        assertThatCode(code).containsOne("return a + b")
    }

    @Test
    fun primitiveCastUsesConversionFunction() {
        val a = Local(1, IrType.INT, name = "a", isParam = true)
        val code = retExpr(
            returnType = IrType.LONG,
            argTypes = listOf(IrType.INT),
            paramNames = listOf("a"),
            value = expr(cast(IrType.LONG, a.ref())),
        )
        assertThatCode(code).containsOne("return a.toLong()")
    }

    @Test
    fun primitiveArrayCreation() {
        val cls = irClass("a.C")
        val arr = Local(1, IrType.array(IrType.INT))
        cls.method("m") { body(assign(arr.ref(), newArray(IrType.array(IrType.INT), intLit(5))), ret()) }
        assertThatCode(generate(cls)).containsOne("IntArray(5)")
    }

    @Test
    fun referenceArrayCreation() {
        val cls = irClass("a.C")
        val arr = Local(1, IrType.array(IrType.STRING))
        cls.method("m") { body(assign(arr.ref(), newArray(IrType.array(IrType.STRING), intLit(3))), ret()) }
        assertThatCode(generate(cls)).containsOne("arrayOfNulls<String>(3)")
    }

    @Test
    fun booleanLiteral() {
        assertThatCode(retExpr(IrType.BOOLEAN, value = lit(1L, IrType.BOOLEAN))).containsOne("return true")
    }

    @Test
    fun charLiteral() {
        assertThatCode(retExpr(IrType.CHAR, value = lit('A'.code.toLong(), IrType.CHAR))).containsOne("return 'A'")
    }

    @Test
    fun longLiteralHasSuffix() {
        assertThatCode(retExpr(IrType.LONG, value = lit(5L, IrType.LONG))).containsOne("return 5L")
    }

    @Test
    fun integerValuedFloatLiteral() {
        val bits = 2.0f.toRawBits().toLong()
        assertThatCode(retExpr(IrType.FLOAT, value = lit(bits, IrType.FLOAT))).containsOne("return 2.0f")
    }

    @Test
    fun nullLiteralForReferenceZero() {
        assertThatCode(retExpr(IrType.objectType("a.Foo"), value = lit(0L, IrType.objectType("a.Foo"))))
            .containsOne("return null")
    }

    @Test
    fun instanceFieldAccess() {
        val o = Local(1, IrType.objectType("a.Foo"), name = "o", isParam = true)
        val field = FieldRef(IrType.objectType("a.Foo"), "x", IrType.INT)
        val code = retExpr(
            returnType = IrType.INT,
            argTypes = listOf(IrType.objectType("a.Foo")),
            paramNames = listOf("o"),
            value = expr(instanceGet(o.ref(), field)),
        )
        assertThatCode(code).containsOne("return o.x")
    }

    @Test
    fun staticFieldAccess() {
        val field = FieldRef(IrType.objectType("a.Foo"), "COUNT", IrType.INT)
        assertThatCode(retExpr(IrType.INT, value = expr(staticGet(field)))).containsOne("return Foo.COUNT")
    }

    @Test
    fun classLiteral() {
        val value = expr(TypeInstruction(IrOpcode.CONST_CLASS, IrType.objectType("a.Foo"), reg(-1, IrType.CLASS)))
        assertThatCode(retExpr(IrType.CLASS, value = value)).containsOne("return Foo::class.java")
    }
}
