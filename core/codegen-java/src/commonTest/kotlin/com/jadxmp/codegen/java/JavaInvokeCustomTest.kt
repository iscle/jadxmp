package com.jadxmp.codegen.java

import com.jadxmp.ir.insn.InvokeCustomInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/** Golden tests for raw `invoke-custom` (invokedynamic) rendering — jadx's polymorphic-call shape. */
class JavaInvokeCustomTest {

    private val fooType = IrType.objectType("invoke.Foo")
    private val lookupType = IrType.objectType("java.lang.invoke.MethodHandles.Lookup")
    private val methodTypeType = IrType.objectType("java.lang.invoke.MethodType")
    private val callSiteType = IrType.objectType("java.lang.invoke.CallSite")

    /** The `staticBootstrap(Lookup, String, MethodType) -> CallSite` bootstrap of the corpus sample. */
    private fun bootstrapRef(owner: IrType = fooType) = MethodRef(
        owner,
        "staticBootstrap",
        callSiteType,
        listOf(lookupType, IrType.STRING, methodTypeType),
    )

    private fun invokeCustom(
        bootstrap: MethodRef = bootstrapRef(),
        returnType: IrType = IrType.STRING,
        paramTypes: List<IrType> = listOf(IrType.INT, IrType.DOUBLE),
        args: List<Operand> = listOf(intLit(1), lit(2.0.toRawBits(), IrType.DOUBLE)),
        renderable: Boolean = true,
    ) = InvokeCustomInstruction(
        bootstrapMethod = bootstrap,
        bootstrapKind = InvokeKind.STATIC,
        callSiteName = "func",
        protoReturnType = returnType,
        protoParamTypes = paramTypes,
        renderable = renderable,
        result = null,
        args = args,
    )

    /** Wrap the invoke-custom as the returned expression of `String test()` and generate the source. */
    private fun generateReturning(insn: InvokeCustomInstruction): String {
        val cls = irClass("invoke.Foo")
        cls.method("test", returnType = IrType.STRING) {
            body(ret(expr(insn)))
        }
        return generate(cls)
    }

    @Test
    fun rawInvokeCustomRendersPolymorphicCallWithCast() {
        val code = generateReturning(invokeCustom())
        // Same-class static bootstrap => no class qualifier; primitives spelled `X.TYPE`, refs `X.class`.
        assertThatCode(code).containsOne(
            "return (String) staticBootstrap(MethodHandles.lookup(), \"func\", " +
                "MethodType.methodType(String.class, Integer.TYPE, Double.TYPE))" +
                ".dynamicInvoker().invoke(1, 2.0) /* invoke-custom */;",
        )
    }

    @Test
    fun rawInvokeCustomImportsInvokeSupportTypes() {
        val code = generateReturning(invokeCustom())
        assertThatCode(code)
            .containsOne("import java.lang.invoke.MethodHandles;")
            .containsOne("import java.lang.invoke.MethodType;")
    }

    @Test
    fun rawInvokeCustomOnOtherClassQualifiesBootstrap() {
        val other = IrType.objectType("invoke.Bootstraps")
        val code = generateReturning(invokeCustom(bootstrap = bootstrapRef(other)))
        assertThatCode(code).containsOne("Bootstraps.staticBootstrap(MethodHandles.lookup(),")
    }

    @Test
    fun nonRenderableInvokeCustomBailsToErrorMarker() {
        val code = generateReturning(invokeCustom(renderable = false))
        // A non-renderable shape must NOT emit a fabricated call; it bails to a visible error marker.
        assertThatCode(code)
            .contains("JADXMP ERROR")
        assertThatCode(code).doesNotContain("dynamicInvoker")
    }
}
