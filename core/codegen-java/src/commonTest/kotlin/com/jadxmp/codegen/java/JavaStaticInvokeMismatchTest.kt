package com.jadxmp.codegen.java

import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/**
 * A same-class `invoke-static` whose target actually resolves to a **non-static** instance method is a
 * static-invoke / instance-declaration MISMATCH. Rendering it as `Class.method(...)` makes javac reject
 * it ("non-static method … cannot be referenced from a static context"); it must instead render as an
 * UNQUALIFIED implicit-`this` call, which compiles and is behavior-preserving (the callee was invoked
 * with no receiver, so it cannot depend on `this`, and the argument list is identical). Matches jadx.
 *
 * The consistency tests pin the guard: a genuine static target and a virtual call are untouched.
 */
class JavaStaticInvokeMismatchTest {

    private val foo = IrType.objectType("a.Foo")

    /** Build class `a.Foo` with the given helper (declared) and a caller `m` doing the given invoke. */
    private fun render(
        helperName: String,
        helperFlags: Int,
        callerFlags: Int = Flags.PUBLIC,
        invoke: (com.jadxmp.ir.node.IrMethod.() -> Unit),
    ): String {
        val cls = irClass("a.Foo")
        cls.method(helperName, returnType = IrType.INT, argTypes = listOf(IrType.STRING), accessFlags = helperFlags)
        cls.method("m", returnType = IrType.VOID, accessFlags = callerFlags, configure = invoke)
        return generate(cls)
    }

    @Test
    fun sameClassStaticInvokeOfInstanceMethodRendersUnqualified() {
        val code = render("helper", Flags.PRIVATE) {
            body(
                staticInvoke(foo, "helper", IrType.INT, listOf(IrType.STRING), listOf(expr(constString("x")))),
                ret(),
            )
        }
        // Unqualified implicit-`this` call — NOT `Foo.helper(...)`.
        assertThatCode(code)
            .containsOne("helper(\"x\");")
            .doesNotContain("Foo.helper(")
    }

    @Test
    fun staticEnclosingMethodDoesNotDropQualifier() {
        // The invoke is inside a STATIC method: no implicit `this` is in scope, so the guard must NOT fire —
        // dropping the qualifier would be illegal (or silently rebind). The class qualifier must stay.
        val code = render("helper", Flags.PRIVATE, callerFlags = Flags.PRIVATE or Flags.STATIC) {
            body(
                staticInvoke(foo, "helper", IrType.INT, listOf(IrType.STRING), listOf(expr(constString("x")))),
                ret(),
            )
        }
        assertThatCode(code).containsOne("Foo.helper(\"x\");")
    }

    @Test
    fun sameClassStaticInvokeOfStaticMethodStaysQualified() {
        val code = render("helper", Flags.PRIVATE or Flags.STATIC) {
            body(
                staticInvoke(foo, "helper", IrType.INT, listOf(IrType.STRING), listOf(expr(constString("x")))),
                ret(),
            )
        }
        // A genuine static target keeps the class qualifier — the guard must NOT fire here.
        assertThatCode(code).containsOne("Foo.helper(\"x\");")
    }

    @Test
    fun sameClassVirtualInvokeOfInstanceMethodStaysReceiverQualified() {
        val self = Local(0, foo, isThis = true)
        val code = render("helper", Flags.PRIVATE) {
            thisArg = self.ssaValue
            body(
                virtualInvoke(self.ref(), foo, "helper", IrType.INT, listOf(IrType.STRING), listOf(expr(constString("x")))),
                ret(),
            )
        }
        // A consistent virtual call renders through its receiver (`this`) — unchanged by the fix.
        assertThatCode(code).containsOne("this.helper(\"x\");")
    }
}
