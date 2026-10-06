package com.jadxmp.codegen.java

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.DecompileError
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaMissingBodyTest {
    @Test fun missingConcreteBodiesStayDiagnosedAndCannotReturnNormally() {
        val cls = irClass("sample.Partial")
        val methods = listOf(
            cls.method("<init>"),
            cls.method("brokenVoid", accessFlags = Flags.PUBLIC or Flags.STATIC),
            cls.method("brokenInt", IrType.INT, accessFlags = Flags.PUBLIC or Flags.STATIC),
        )
        methods.forEach(::failed)
        cls.method("healthy", IrType.INT, accessFlags = Flags.PUBLIC or Flags.STATIC) { body(ret(intLit(7))) }
        val source = generate(cls)
        assertEquals(3, source.split("Method body unavailable").size - 1, source)
        assertEquals(3, source.split("JADXMP ERROR: body unavailable").size - 1, source)
        assertTrue(source.contains("return 7"), source)
        assertTrue(methods.all { it.contains(AttrFlag.HAS_ERROR) })
    }

    @Test fun failedStaticInitializerIsNotOmitted() {
        val cls = irClass("sample.FailedInit")
        failed(cls.method("<clinit>", accessFlags = Flags.STATIC))
        val source = generate(cls)
        assertTrue(source.contains("UnsupportedOperationException"), source)
        assertTrue(source.contains("JADXMP ERROR: body unavailable"), source)
    }

    @Test fun existingErroredBodyAndLegitimateEmptyBodyRemainIntact() {
        val cls = irClass("sample.Existing")
        failed(cls.method("existing", IrType.INT) { body(ret(intLit(19))) })
        cls.method("empty")
        val source = generate(cls)
        assertTrue(source.contains("return 19"), source)
        assertFalse(source.contains("UnsupportedOperationException"), source)
    }

    private fun failed(method: IrMethod) {
        method.add(AttrFlag.HAS_ERROR)
        method[IrAttrs.ERROR] = DecompileError("body unavailable")
    }
}
