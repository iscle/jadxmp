package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.insn.InvokeCustomInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/** Golden tests for raw `invoke-custom` (invokedynamic) rendering in the Kotlin backend. */
class KotlinInvokeCustomTest {

    private val fooType = IrType.objectType("invoke.Foo")

    private fun invokeCustom(renderable: Boolean = true) = InvokeCustomInstruction(
        bootstrapMethod = MethodRef(
            fooType,
            "staticBootstrap",
            IrType.objectType("java.lang.invoke.CallSite"),
            listOf(
                IrType.objectType("java.lang.invoke.MethodHandles.Lookup"),
                IrType.STRING,
                IrType.objectType("java.lang.invoke.MethodType"),
            ),
        ),
        bootstrapKind = InvokeKind.STATIC,
        callSiteName = "func",
        protoReturnType = IrType.STRING,
        protoParamTypes = listOf(IrType.INT, IrType.DOUBLE),
        renderable = renderable,
        result = null,
        args = listOf(intLit(1), lit(2.0.toRawBits(), IrType.DOUBLE)),
    )

    private fun generateReturning(insn: InvokeCustomInstruction): String {
        val cls = irClass("invoke.Foo")
        cls.method("test", returnType = IrType.STRING) {
            body(ret(expr(insn)))
        }
        return generate(cls)
    }

    @Test
    fun rawInvokeCustomRendersPolymorphicCall() {
        val code = generateReturning(invokeCustom())
        assertThatCode(code).containsOne(
            "staticBootstrap(MethodHandles.lookup(), \"func\", " +
                "MethodType.methodType(String::class.java, Integer.TYPE, Double.TYPE))" +
                ".dynamicInvoker().invoke(1, 2.0) as String /* invoke-custom */",
        )
    }

    @Test
    fun nonRenderableInvokeCustomBailsToErrorMarker() {
        val code = generateReturning(invokeCustom(renderable = false))
        assertThatCode(code).contains("JADXMP ERROR")
        assertThatCode(code).doesNotContain("dynamicInvoker")
    }
}
