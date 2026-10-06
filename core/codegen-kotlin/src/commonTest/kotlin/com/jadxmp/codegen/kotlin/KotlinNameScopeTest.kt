package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinNameScopeTest {
    @Test fun requiresLoadedHierarchyAndEveryLexicalOwnerButNotUnrelatedSiblings() {
        val outer = irClass("Outer", superType = IrType.objectType("Missing"))
        val child = irClass("Outer\$Child", superType = IrType.OBJECT, root = outer.root)
        child.outerClass = outer
        val sibling = irClass("Good", superType = IrType.OBJECT, root = outer.root)
        val scope = KotlinNameScope()
        assertFalse(scope.hasCompleteNameScope(child))
        assertTrue(scope.hasCompleteNameScope(sibling))
        assertFalse(scope.hasCompleteNameScope(irClass("UnknownRoot")))
    }

    @Test fun loadedDiamondsAreCompleteButInheritanceAndLexicalCyclesAreNot() {
        val base = irClass("Base", superType = IrType.OBJECT)
        val left = irClass("Left", superType = IrType.objectType(base.fullName), root = base.root)
        val right = irClass("Right", superType = IrType.objectType(base.fullName), root = base.root)
        val diamond = irClass("Diamond", superType = IrType.objectType(left.fullName),
            interfaces = listOf(IrType.objectType(right.fullName)), root = base.root)
        val scope = KotlinNameScope()
        assertTrue(scope.hasCompleteNameScope(diamond))
        val cycle = irClass("Cycle", superType = IrType.objectType("Cycle"), root = base.root)
        assertFalse(scope.hasCompleteNameScope(cycle))
        val lexical = irClass("Lexical", superType = IrType.OBJECT, root = base.root)
        lexical.outerClass = lexical
        assertFalse(scope.hasCompleteNameScope(lexical))
        // An ancestor's enclosing class is not a lexical owner of the subclass.
        val hiddenOuter = irClass("Hidden", superType = IrType.objectType("Missing"), root = base.root)
        base.outerClass = hiddenOuter
        assertTrue(KotlinNameScope().hasCompleteNameScope(diamond))
    }

    @Test fun exhaustionDoesNotRetainNewFailuresOrDiscardPriorProofs() {
        val good = irClass("Good", superType = IrType.OBJECT)
        val scope = KotlinNameScope(entryLimit = 1)
        assertTrue(scope.hasCompleteNameScope(good))
        repeat(1000) {
            assertFalse(scope.hasCompleteNameScope(irClass("Next$it", superType = IrType.OBJECT)))
        }
        assertEquals(1, scope.retainedProofCount)
        assertTrue(scope.hasCompleteNameScope(good))
    }

    @Test fun longRepeatedKeysAndInterfaceListsChargeBeforeLookupOrAllocation() {
        val good = irClass("Good", superType = IrType.OBJECT)
        val scope = KotlinNameScope(workLimit = 100)
        assertTrue(scope.hasCompleteNameScope(good))
        val longName = "Long".repeat(1000)
        irClass(longName, superType = IrType.OBJECT, root = good.root)
        val oversized = irClass("Over", superType = IrType.objectType(longName), root = good.root)
        assertFalse(scope.hasCompleteNameScope(oversized))
        val retained = scope.retainedProofCount
        repeat(1000) { assertFalse(scope.hasCompleteNameScope(irClass("More$it", superType = IrType.OBJECT))) }
        assertEquals(retained, scope.retainedProofCount)
        assertTrue(scope.hasCompleteNameScope(good))
        val many = irClass("Many", superType = IrType.OBJECT,
            interfaces = List(10000) { IrType.OBJECT })
        assertFalse(KotlinNameScope(workLimit = 50).hasCompleteNameScope(many))
    }
}
