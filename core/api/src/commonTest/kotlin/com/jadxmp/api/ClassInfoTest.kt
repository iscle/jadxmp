package com.jadxmp.api

import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-model unit tests for class-kind/modifier derivation ([classInfoOf]/[classKindOf], which back
 * [Decompiler.classInfo]). Builds synthetic [IrClass] nodes with the exact access-flag bitmasks a
 * `.class`/dex would carry — no fixture, no decompilation — to lock the kind precedence (annotation
 * before interface), the modifier extraction (no bogus `synchronized` from `ACC_SUPER`), and the
 * absent-name → null contract. commonTest so it runs on every target (the tree runs in-browser too).
 */
class ClassInfoTest {

    private companion object {
        const val ACC_PUBLIC = 0x0001
        const val ACC_FINAL = 0x0010
        const val ACC_SUPER = 0x0020 // set by nearly every real class; must NOT surface as a modifier
        const val ACC_INTERFACE = 0x0200
        const val ACC_ABSTRACT = 0x0400
        const val ACC_ANNOTATION = 0x2000
        const val ACC_ENUM = 0x4000
    }

    private fun cls(name: String, flags: Int, outer: IrClass? = null): IrClass {
        val root = IrRoot()
        val c = IrClass(root, name, flags)
        root.addClass(c)
        if (outer != null) c.outerClass = outer
        return c
    }

    @Test
    fun plainPublicClassIsCLASS() {
        val info = classInfoOf(cls("com.example.Widget", ACC_PUBLIC or ACC_SUPER))
        assertEquals(ClassKind.CLASS, info.kind)
        // ACC_SUPER (0x0020) is NOT a source modifier — it must not leak in as SYNCHRONIZED.
        assertEquals(setOf(Modifier.PUBLIC), info.modifiers)
        assertFalse(info.isInner)
    }

    @Test
    fun interfaceIsINTERFACEWithAbstractModifier() {
        // The JVM marks interfaces ACC_INTERFACE|ACC_ABSTRACT.
        val info = classInfoOf(cls("com.example.Runnable", ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT))
        assertEquals(ClassKind.INTERFACE, info.kind)
        assertEquals(setOf(Modifier.PUBLIC, Modifier.ABSTRACT), info.modifiers)
    }

    @Test
    fun annotationIsANNOTATIONNotInterface() {
        // An annotation type also sets ACC_INTERFACE — kind must resolve to ANNOTATION, never INTERFACE.
        val info = classInfoOf(
            cls("com.example.Nullable", ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT or ACC_ANNOTATION),
        )
        assertEquals(ClassKind.ANNOTATION, info.kind)
    }

    @Test
    fun enumIsENUMWithFlagDrivenModifiers() {
        // A final enum (no abstract methods) carries ACC_ENUM|ACC_FINAL.
        val finalEnum = classInfoOf(cls("com.example.Color", ACC_PUBLIC or ACC_FINAL or ACC_SUPER or ACC_ENUM))
        assertEquals(ClassKind.ENUM, finalEnum.kind)
        assertEquals(setOf(Modifier.PUBLIC, Modifier.FINAL), finalEnum.modifiers)

        // An enum with a constant body is emitted abstract, carrying ACC_ENUM|ACC_ABSTRACT.
        val abstractEnum = classInfoOf(cls("com.example.Op", ACC_PUBLIC or ACC_ABSTRACT or ACC_SUPER or ACC_ENUM))
        assertEquals(ClassKind.ENUM, abstractEnum.kind)
        assertEquals(setOf(Modifier.PUBLIC, Modifier.ABSTRACT), abstractEnum.modifiers)
    }

    @Test
    fun abstractClassReportsAbstractModifier() {
        val info = classInfoOf(cls("com.example.Base", ACC_PUBLIC or ACC_ABSTRACT or ACC_SUPER))
        assertEquals(ClassKind.CLASS, info.kind)
        assertTrue(Modifier.ABSTRACT in info.modifiers)
    }

    @Test
    fun nestedClassIsInner() {
        val outer = cls("com.example.Outer", ACC_PUBLIC or ACC_SUPER)
        val inner = cls("com.example.Outer\$Inner", ACC_PUBLIC or ACC_SUPER, outer = outer)
        assertTrue(classInfoOf(inner).isInner)
        assertFalse(classInfoOf(outer).isInner)
    }

    @Test
    fun classInfoReturnsNullForUnknownOrUnloaded() {
        val decompiler = Decompiler()
        // Nothing loaded → null (not a crash).
        assertNull(decompiler.classInfo("com.example.Nope"))
    }
}
