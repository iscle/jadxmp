package com.jadxmp.ui.client

import com.jadxmp.api.ClassInfo
import com.jadxmp.api.ClassKind
import com.jadxmp.api.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the pure class-kind → tree-badge mapping ([classNodeKind]) that gives interfaces,
 * enums, annotations and abstract/plain classes distinct row icons. Engine-free apart from the
 * `core:api` [ClassInfo] value type, so the mapping (including the fault-isolated `null → generic`
 * fallback) is asserted directly with no `Decompiler`.
 */
class ClassBadgeTest {

    private fun info(kind: ClassKind) = ClassInfo(kind, modifiers = emptySet(), isInner = false)

    @Test
    fun interfaceMapsToInterfaceBadge() {
        assertEquals(NodeKind.INTERFACE, classNodeKind(info(ClassKind.INTERFACE)))
    }

    @Test
    fun enumMapsToEnumBadge() {
        assertEquals(NodeKind.ENUM, classNodeKind(info(ClassKind.ENUM)))
    }

    @Test
    fun annotationMapsToAnnotationClassBadge() {
        assertEquals(NodeKind.ANNOTATION_CLASS, classNodeKind(info(ClassKind.ANNOTATION)))
    }

    @Test
    fun plainClassMapsToClassBadge() {
        assertEquals(NodeKind.CLASS, classNodeKind(info(ClassKind.CLASS)))
    }

    @Test
    fun abstractClassStillMapsToClassBadge() {
        // Modifiers do not change the badge — an abstract class is still a CLASS-kind row.
        val abstractClass = ClassInfo(ClassKind.CLASS, modifiers = setOf(Modifier.ABSTRACT), isInner = false)
        assertEquals(NodeKind.CLASS, classNodeKind(abstractClass))
    }

    @Test
    fun nullInfoFallsBackToGenericClassBadge() {
        // Fault isolation (rule 4): an unknown class or a lookup fault yields the generic badge, never a crash.
        assertEquals(NodeKind.CLASS, classNodeKind(null))
    }
}
