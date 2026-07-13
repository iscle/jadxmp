package com.jadxmp.pipeline.types

import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClassHierarchyTest {

    private fun hierarchy(): ClassHierarchy {
        val root = IrRoot()
        root.addClass(IrClass(root, "com.example.Animal", 0, superType = IrType.OBJECT))
        root.addClass(IrClass(root, "com.example.Dog", 0, superType = IrType.objectType("com.example.Animal")))
        root.addClass(
            IrClass(
                root, "com.example.Puppy", 0,
                superType = IrType.objectType("com.example.Dog"),
                interfaces = listOf(IrType.objectType("com.example.Cute")),
            ),
        )
        root.addClass(IrClass(root, "com.example.Cat", 0, superType = IrType.objectType("com.example.Animal")))
        return ClassHierarchy(root)
    }

    @Test
    fun directAndTransitiveSubtype() {
        val h = hierarchy()
        assertTrue(h.isSubtype(IrType.objectType("com.example.Dog"), IrType.objectType("com.example.Animal")))
        assertTrue(h.isSubtype(IrType.objectType("com.example.Puppy"), IrType.objectType("com.example.Animal")))
        assertTrue(h.isSubtype(IrType.objectType("com.example.Puppy"), IrType.objectType("com.example.Cute")))
        assertFalse(h.isSubtype(IrType.objectType("com.example.Cat"), IrType.objectType("com.example.Dog")))
    }

    @Test
    fun everythingIsSubtypeOfObject() {
        val h = hierarchy()
        assertTrue(h.isSubtype(IrType.STRING, IrType.OBJECT))
        assertTrue(h.isSubtype(IrType.objectType("com.example.Cat"), IrType.OBJECT))
        assertTrue(h.isSubtype(IrType.OBJECT_ARRAY, IrType.OBJECT))
    }

    @Test
    fun commonSuperTypeIsNearestAncestor() {
        val h = hierarchy()
        assertEquals(
            IrType.objectType("com.example.Animal"),
            h.commonSuperType(IrType.objectType("com.example.Dog"), IrType.objectType("com.example.Cat")),
        )
        assertEquals(
            IrType.objectType("com.example.Dog"),
            h.commonSuperType(IrType.objectType("com.example.Puppy"), IrType.objectType("com.example.Dog")),
        )
        // Unrelated named classes not in the graph fall back to Object.
        assertEquals(
            IrType.OBJECT,
            h.commonSuperType(IrType.STRING, IrType.objectType("com.example.Cat")),
        )
    }

    @Test
    fun primitiveArrayInvariance() {
        val h = hierarchy()
        assertTrue(h.isSubtype(IrType.array(IrType.INT), IrType.array(IrType.INT)))
        assertFalse(h.isSubtype(IrType.array(IrType.INT), IrType.array(IrType.LONG)))
        // reference-element arrays are covariant
        assertTrue(h.isSubtype(IrType.array(IrType.STRING), IrType.OBJECT_ARRAY))
    }
}
