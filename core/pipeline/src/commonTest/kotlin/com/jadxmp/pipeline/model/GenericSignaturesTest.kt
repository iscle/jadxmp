package com.jadxmp.pipeline.model

import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.type.WildcardBound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GenericSignaturesTest {
    @Test fun classAndScopedMethodRetainTypesWithoutChangingErasure() {
        val cls = GenericSignatures.parseClass("<T:Ljava/lang/Number;>Ljava/lang/Object;")
        assertEquals("T", cls.parameters.single().name)
        val method = GenericSignatures.parseMethod("<U:TT;>(TU;[TT;)TU;", cls.parameters)
        assertEquals(IrType.typeVariable("U"), method.argumentTypes.first())
        assertEquals(IrType.array(IrType.typeVariable("T")), method.argumentTypes[1])
        assertEquals(IrType.objectType("java.lang.Number"), GenericSignatures.erase(method.returnType, cls.parameters + method.parameters))
    }

    @Test fun wildcardsAndInterfaceBoundsRetainVariance() {
        val cls = GenericSignatures.parseClass("<T::Ljava/lang/Comparable<-TT;>;>Ljava/lang/Object;")
        val field = GenericSignatures.parseField("Ljava/util/Map<Ljava/lang/String;+[TT;>;", cls.parameters)
        assertEquals(IrType.generic("java.util.Map", IrType.STRING,
            IrType.wildcard(WildcardBound.EXTENDS, IrType.array(IrType.typeVariable("T")))), field)
        assertEquals(IrType.objectType("java.lang.Comparable"), GenericSignatures.erase(IrType.typeVariable("T"), cls.parameters))
    }

    @Test fun methodScopeShadowsClassScopeAndThrowsAreRetained() {
        val outer = GenericSignatures.parseClass("<T:Ljava/lang/Number;>Ljava/lang/Object;")
        val method = GenericSignatures.parseMethod("<T:Ljava/lang/Exception;>(TT;)V^TT;", outer.parameters)
        assertEquals(IrType.VOID, method.returnType)
        assertEquals(listOf(IrType.typeVariable("T")), method.throwsTypes)
        assertEquals(IrType.objectType("java.lang.Exception"), GenericSignatures.erase(method.argumentTypes.single(), outer.parameters + method.parameters))
    }

    @Test fun omittedClassBoundUsesObjectErasure() {
        for (text in listOf("<T:>Ljava/lang/Object;", "<T:U:Ljava/lang/Object;>Ljava/lang/Object;", "<T:TNext:Ljava/lang/Object;>Ljava/lang/Object;", "<T:LNext:Ljava/lang/Object;>Ljava/lang/Object;")) {
            val cls = GenericSignatures.parseClass(text)
            assertEquals(IrType.OBJECT, GenericSignatures.erase(IrType.typeVariable("T"), cls.parameters))
        }
    }

    @Test fun methodShadowingDoesNotRebindEnclosingParameterBounds() {
        val cls = GenericSignatures.parseClass("<T:Ljava/lang/Number;U:TT;>Ljava/lang/Object;")
        val method = GenericSignatures.parseMethod("<T:Ljava/lang/String;>(TU;)TU;", cls.parameters)
        assertEquals(IrType.objectType("java.lang.Number"), GenericSignatures.erase(method.returnType, cls.parameters + method.parameters))
        assertEquals(IrType.STRING, GenericSignatures.erase(IrType.typeVariable("T"), cls.parameters + method.parameters))
    }

    @Test fun absentBoundsStillConsumeTheSignatureWorkBudget() {
        val parameters = (0..4096).joinToString("") { "P$it:" }
        assertFailsWith<UnsupportedGenericSignature> {
            GenericSignatures.parseClass("<$parameters>Ljava/lang/Object;")
        }
    }

    @Test fun wildcardsAndNestedClassSegmentsConsumeTheWorkBudget() {
        assertFailsWith<UnsupportedGenericSignature> {
            GenericSignatures.parseField("Ljava/util/List<" + "*".repeat(5000) + ">;")
        }
        assertFailsWith<UnsupportedGenericSignature> {
            GenericSignatures.parseField("LRoot" + ".Inner".repeat(5000) + ";")
        }
    }

    @Test fun invalidOwnerSuffixIsNotMisclassifiedAsUnsupportedOwnerType() {
        assertFailsWith<InvalidGenericSignature> {
            GenericSignatures.parseField("Louter/Outer<Ljava/lang/String;>.outer/Inner<Ljava/lang/String;>;")
        }
        assertFailsWith<UnsupportedGenericSignature> {
            GenericSignatures.parseField("Louter/Outer<Ljava/lang/String;>.Inner<Ljava/lang/String;>;")
        }
    }

    @Test fun malformedUnscopedCyclicAndDeepSignaturesFailBoundedly() {
        for (text in listOf("", "I", "TT;", "Ljava/lang/String;;", "Ljava/util/List<I>;", "[V", "Lfoo//Bar;")) {
            assertFailsWith<IllegalArgumentException>(text) { GenericSignatures.parseField(text) }
        }
        for (text in listOf("<T:TU;U:TT;>Ljava/lang/Object;", "<T:Ljava/lang/Object;T:Ljava/lang/Object;>Ljava/lang/Object;")) {
            assertFailsWith<IllegalArgumentException>(text) { GenericSignatures.parseClass(text) }
        }
        assertFailsWith<IllegalArgumentException> { GenericSignatures.parseField("[".repeat(1000) + "Ljava/lang/Object;") }
        assertFailsWith<IllegalArgumentException> { GenericSignatures.parseField("L" + "a".repeat(100000) + ";") }
    }
}
