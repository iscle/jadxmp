package com.jadxmp.codegen.java

import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class JavaNullArgCastTest {

    private val foo = IrType.objectType("a.Foo")

    @Test
    fun nullArgumentIsCastToReferenceParamType() {
        // A bare `null` to an overloaded method is ambiguous; cast to the declared param type.
        val nullArr = lit(0, IrType.array(IrType.LONG))
        val call = staticInvoke(foo, "put", IrType.VOID, listOf(IrType.array(IrType.LONG)), listOf(nullArr))
        val cls = irClass("a.Foo")
        cls.method("m") { body(call) }
        assertThatCode(generate(cls))
            .containsOne("Foo.put((long[]) null);")
            .doesNotContain("put(null)")
    }

    @Test
    fun nullArgumentToObjectParamIsCast() {
        val nullObj = lit(0, IrType.STRING)
        val call = staticInvoke(foo, "use", IrType.VOID, listOf(IrType.STRING), listOf(nullObj))
        val cls = irClass("a.Foo")
        cls.method("m") { body(call) }
        assertThatCode(generate(cls)).containsOne("Foo.use((String) null);")
    }

    @Test
    fun nonNullReferenceArgumentIsNotCast() {
        val s = Local(1, IrType.STRING, name = "s", isParam = true)
        val call = staticInvoke(foo, "use", IrType.VOID, listOf(IrType.STRING), listOf(s.ref()))
        val cls = irClass("a.Foo")
        cls.method("m", argTypes = listOf(IrType.STRING)) {
            this[com.jadxmp.codegen.CodegenKeys.PARAM_NAMES] = listOf("s")
            body(call)
        }
        assertThatCode(generate(cls))
            .containsOne("Foo.use(s);")
            .doesNotContain("(String)")
    }
}
