package com.jadxmp.codegen.java

import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals

class JavaNaNLiteralTest {
    @Test fun rawFloatNaNsRetainEverySignAndPayloadBit() {
        for (bits in listOf(0x7fc00001, 0x7fffffff, 0x7f800001, 0xffc00000.toInt(), 0xffc12345.toInt(), 0xff800001.toInt())) {
            assertEquals("((Float) null).intBitsToFloat($bits)", JavaLiterals.format(LiteralOperand(bits.toLong(), IrType.FLOAT)))
        }
    }

    @Test fun rawDoubleNaNsRetainEverySignAndPayloadBit() {
        for (bits in listOf(0x7ff8000000000001L, 0x7fffffffffffffffL, 0x7ff0000000000001L,
            0xfff8000000000000UL.toLong(), 0xfff923456789abcdUL.toLong(), 0xfff0000000000001UL.toLong())) {
            assertEquals("((Double) null).longBitsToDouble(${bits}L)", JavaLiterals.format(LiteralOperand(bits, IrType.DOUBLE)))
        }
    }

    @Test fun canonicalNaNsInfinitiesAndSignedZerosKeepTheirSourceForms() {
        assertEquals("Float.NaN", JavaLiterals.format(LiteralOperand(0x7fc00000, IrType.FLOAT)))
        assertEquals("Double.NaN", JavaLiterals.format(LiteralOperand(0x7ff8000000000000, IrType.DOUBLE)))
        assertEquals("Float.POSITIVE_INFINITY", JavaLiterals.format(LiteralOperand(0x7f800000, IrType.FLOAT)))
        assertEquals("Double.NEGATIVE_INFINITY", JavaLiterals.format(LiteralOperand(0xfff0000000000000UL.toLong(), IrType.DOUBLE)))
        assertEquals("-0x0.0p0f", JavaLiterals.format(LiteralOperand(Int.MIN_VALUE.toLong(), IrType.FLOAT)))
        assertEquals("-0x0.0p0", JavaLiterals.format(LiteralOperand(Long.MIN_VALUE, IrType.DOUBLE)))
    }
    @Test fun repeatedOwnersShareHierarchyAnalysisButObserveLaterImports() {
        val root = com.jadxmp.ir.node.IrRoot()
        val imports = com.jadxmp.codegen.ImportCollector("fixture")
        var parent = com.jadxmp.ir.node.IrClass(root, "fixture.Base", 0)
        root.addClass(parent)
        repeat(2_000) { index ->
            val child = com.jadxmp.ir.node.IrClass(root, "fixture.Child$index", 0,
                superType = IrType.objectType(parent.fullName))
            root.addClass(child)
            parent = child
        }
        val types = JavaTypeRenderer(imports, root = root)
        repeat(20_000) { assertEquals("java.lang.Float", types.literalTypeName("Float", parent)) }
        // Import discovery continues during the first codegen pass: this fact must not be cached
        // together with immutable hierarchy classification.
        imports.useClass("external.java")
        assertEquals("Float", types.literalTypeName("Float", parent))
    }
    @Test fun importedWrapperAndPackageNamesAreDiagnosedTogether() {
        val root = com.jadxmp.ir.node.IrRoot()
        val cls = com.jadxmp.ir.node.IrClass(root, "fixture.Example", 0)
        root.addClass(cls)
        val imports = com.jadxmp.codegen.ImportCollector("fixture")
        imports.useClass("external.java")
        imports.useClass("external.Float")
        val types = JavaTypeRenderer(imports, root = root)
        assertEquals("void /* JADXMP ERROR: cannot resolve NaN helper owner java.lang.Float in shadowed source scope */", types.literalTypeName("Float", cls))
        kotlin.test.assertTrue(cls.contains(com.jadxmp.ir.attr.AttrFlag.HAS_ERROR))
    }
}
