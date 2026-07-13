package com.jadxmp.codegen.java

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class JavaBodyTest {

    private val foo = IrType.objectType("a.Foo")
    private val stringBuilder = IrType.objectType("java.lang.StringBuilder")

    private fun singleMethod(configure: com.jadxmp.ir.node.IrMethod.() -> Unit): String {
        val cls = irClass("a.Foo")
        cls.method("m", returnType = IrType.INT, configure = configure)
        return generate(cls)
    }

    @Test
    fun arithmeticAssignmentAndReturn() {
        val a = Local(0, IrType.INT)
        val code = singleMethod {
            body(
                assign(a.ref(), arith(ArithOp.ADD, intLit(1), intLit(2))),
                ret(a.ref()),
            )
        }
        assertThatCode(code)
            .containsOne("int i = 1 + 2;")
            .containsOne("return i;")
    }

    @Test
    fun staticInvokeExpression() {
        val r = Local(1, IrType.INT)
        val code = singleMethod {
            body(
                assign(
                    r.ref(),
                    staticInvoke(
                        IrType.objectType("java.lang.Integer"),
                        "parseInt",
                        IrType.INT,
                        listOf(IrType.STRING),
                        listOf(expr(constString("5"))),
                    ),
                ),
                ret(r.ref()),
            )
        }
        assertThatCode(code).containsOne("int i = Integer.parseInt(\"5\");")
    }

    @Test
    fun constructorAndVirtualInvokeStatement() {
        val sb = Local(2, stringBuilder)
        val cls = irClass("a.Foo")
        cls.method("m") {
            body(
                assign(sb.ref(), constructor(stringBuilder, emptyList(), emptyList())),
                virtualInvoke(sb.ref(), stringBuilder, "append", stringBuilder, listOf(IrType.STRING), listOf(expr(constString("x")))),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("StringBuilder stringBuilder = new StringBuilder();")
            .containsOne("stringBuilder.append(\"x\");")
    }

    @Test
    fun instanceFieldReadThroughParameter() {
        val obj = Local(1, foo, name = "obj", isParam = true)
        val c = Local(2, IrType.INT)
        val cls = irClass("a.Foo")
        cls.method("m", returnType = IrType.INT, argTypes = listOf(foo)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("obj")
            body(
                assign(c.ref(), instanceGet(obj.ref(), FieldRef(foo, "count", IrType.INT))),
                ret(c.ref()),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("int i = obj.count;")
            .containsOne("return i;")
    }

    @Test
    fun thisFieldWrite() {
        val self = Local(0, foo, isThis = true)
        val cls = irClass("a.Foo")
        cls.method("m") {
            thisArg = self.ssaValue
            body(instancePut(self.ref(), intLit(5), FieldRef(foo, "count", IrType.INT)))
        }
        assertThatCode(generate(cls)).containsOne("this.count = 5;")
    }

    @Test
    fun stringAndNullLiterals() {
        val s = Local(0, IrType.STRING)
        val o = Local(1, IrType.OBJECT)
        val cls = irClass("a.Foo")
        cls.method("m") {
            body(
                assign(s.ref(), constString("hi")),
                assign(o.ref(), Instruction(IrOpcode.CONST, args = listOf(lit(0, IrType.OBJECT)))),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("String str = \"hi\";")
            .containsOne("Object obj = null;")
    }

    @Test
    fun longAndBooleanLiterals() {
        val cls = irClass("a.Foo")
        val l = Local(0, IrType.LONG)
        val b = Local(1, IrType.BOOLEAN)
        cls.method("m") {
            body(
                assign(l.ref(), Instruction(IrOpcode.CONST, args = listOf(lit(42, IrType.LONG)))),
                assign(b.ref(), Instruction(IrOpcode.CONST, args = listOf(lit(1, IrType.BOOLEAN)))),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("long j = 42L;")
            .containsOne("boolean z = true;")
    }

    @Test
    fun floatAndDoubleLiteralsAreDeterministic() {
        val cls = irClass("a.Foo")
        val f1 = Local(0, IrType.FLOAT)
        val f2 = Local(1, IrType.FLOAT)
        val d1 = Local(2, IrType.DOUBLE)
        val d2 = Local(3, IrType.DOUBLE)
        cls.method("m") {
            body(
                assign(f1.ref(), Instruction(IrOpcode.CONST, args = listOf(lit(1.0f.toRawBits().toLong(), IrType.FLOAT)))),
                assign(f2.ref(), Instruction(IrOpcode.CONST, args = listOf(lit(2.5f.toRawBits().toLong(), IrType.FLOAT)))),
                assign(d1.ref(), Instruction(IrOpcode.CONST, args = listOf(lit(100.0.toRawBits(), IrType.DOUBLE)))),
                assign(d2.ref(), Instruction(IrOpcode.CONST, args = listOf(lit(0.5.toRawBits(), IrType.DOUBLE)))),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("float f = 1.0f;")
            // Non-integer values use platform-stable hex float literals (Kotlin toString is unspecified).
            .containsOne("float f2 = 0x1.4p1f;")
            .containsOne("double d = 100.0;")
            .containsOne("double d2 = 0x1.0p-1;")
    }

    @Test
    fun sizedNewArrayOneDimension() {
        val a = Local(0, IrType.array(IrType.INT))
        val cls = irClass("a.Foo")
        cls.method("m") { body(assign(a.ref(), newArray(IrType.array(IrType.INT), intLit(4)))) }
        assertThatCode(generate(cls)).containsOne("new int[4]")
    }

    @Test
    fun sizedNewArrayTwoDimensionsBindsSizeToOuterDimension() {
        // int[][] sized new-array must be `new int[4][]`, never the invalid `new int[][4]`.
        val a = Local(0, IrType.array(IrType.INT, 2))
        val cls = irClass("a.Foo")
        cls.method("m") { body(assign(a.ref(), newArray(IrType.array(IrType.INT, 2), intLit(4)))) }
        assertThatCode(generate(cls))
            .containsOne("new int[4][]")
            .doesNotContain("int[][4]")
    }

    @Test
    fun sizedNewArrayThreeDimensions() {
        val a = Local(0, IrType.array(IrType.INT, 3))
        val cls = irClass("a.Foo")
        cls.method("m") { body(assign(a.ref(), newArray(IrType.array(IrType.INT, 3), intLit(4)))) }
        assertThatCode(generate(cls))
            .containsOne("new int[4][][]")
            .doesNotContain("int[][][4]")
    }

    @Test
    fun filledNewArrayOneAndTwoDimensions() {
        val cls = irClass("a.Foo")
        val one = Local(0, IrType.array(IrType.INT))
        val two = Local(1, IrType.array(IrType.INT, 2))
        cls.method("m") {
            body(
                assign(one.ref(), filledNewArray(IrType.array(IrType.INT), listOf(intLit(1), intLit(2)))),
                assign(two.ref(), filledNewArray(IrType.array(IrType.INT, 2), listOf(reg(8, IrType.array(IrType.INT))))),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("new int[]{1, 2}")
            .containsOne("new int[][]{")
    }

    @Test
    fun unhandledOpcodeAndUnresolvedInvokeAreMarkedNotDropped() {
        val cls = irClass("a.Foo")
        val r = Local(0, IrType.objectType("a.Foo"))
        cls.method("m") {
            body(
                // An opcode the expression emitter does not model: kept, flagged with a comment marker.
                assign(r.ref(), Instruction(IrOpcode.FILL_ARRAY, args = listOf(reg(9, IrType.OBJECT)))),
                // A bare Instruction(INVOKE) (not an InvokeInstruction — e.g. invoke-custom): must not vanish silently.
                Instruction(IrOpcode.INVOKE, args = listOf(reg(9, IrType.OBJECT))),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("/* FILL_ARRAY */")
            .containsOne("/* INVOKE */")
    }
}
