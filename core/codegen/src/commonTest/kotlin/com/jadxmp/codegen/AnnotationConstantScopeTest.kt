package com.jadxmp.codegen

import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.type.IrType
import kotlin.test.*

class AnnotationConstantScopeTest {
    @Test fun terminalCallbackPrecedesSuccessAndLoadedDeclarationsTakePrecedence() {
        val root = com.jadxmp.ir.node.IrRoot()
        val owner = IrClass(root, "p.Choice", 1, IrType.objectType("java.lang.Enum")).also(root::addClass)
        val terminals = mutableListOf<String>()
        val proof = AnnotationConstantScope(owner, AnnotationRenderBudget(), visitTerminal = terminals::add)
        assertTrue(proof.isComplete())
        assertEquals(listOf("java.lang.Enum"), terminals)
        assertTrue(proof.isComplete())
        assertEquals(1, terminals.size)
        val loaded = IrClass(root, "java.lang.Enum", 1, IrType.OBJECT).also(root::addClass)
        val declarations = mutableListOf<IrClass>()
        terminals.clear()
        assertTrue(AnnotationConstantScope(owner, AnnotationRenderBudget(), visitTerminal = terminals::add,
            visit = declarations::add).isComplete())
        assertEquals(listOf("java.lang.Object"), terminals)
        assertTrue(loaded in declarations)
    }

    @Test fun failedTerminalCallbackCannotPublishPartialProofOrRetry() {
        val root = com.jadxmp.ir.node.IrRoot()
        val owner = IrClass(root, "p.Choice", 1, IrType.objectType("java.lang.Enum")).also(root::addClass)
        val budget = AnnotationRenderBudget()
        var calls = 0
        val proof = AnnotationConstantScope(owner, budget, visitTerminal = {
            calls++
            budget.text(Int.MAX_VALUE)
        })
        assertFailsWith<IllegalArgumentException> { proof.isComplete() }
        repeat(100) { assertFalse(proof.isComplete()) }
        assertEquals(1, calls)
        calls = 0
        assertFailsWith<IllegalArgumentException> {
            AnnotationConstantScope(owner, AnnotationRenderBudget(characters = 0), visitTerminal = { calls++ }).isComplete()
        }
        assertEquals(0, calls)
    }

    @Test fun longHierarchyIsIterativeAndProofIsCached() {
        val root = com.jadxmp.ir.node.IrRoot()
        var parent: IrType = IrType.OBJECT
        var last: IrClass? = null
        repeat(2_000) {
            last = IrClass(root, "p.C$it", 1, parent).also(root::addClass)
            parent = IrType.objectType(last!!.fullName)
        }
        var visits = 0
        val proof = AnnotationConstantScope(last!!, AnnotationRenderBudget()) { visits++ }
        repeat(1_000) { assertTrue(proof.isComplete()) }
        assertEquals(2_000, visits)
    }

    @Test fun cyclesAndUnknownParentsRemainUnproven() {
        val root = com.jadxmp.ir.node.IrRoot()
        val a = IrClass(root, "p.A", 1, IrType.objectType("p.B")).also(root::addClass)
        IrClass(root, "p.B", 1, IrType.objectType("p.A")).also(root::addClass)
        assertFalse(AnnotationConstantScope(a, AnnotationRenderBudget()).isComplete())
        val missing = IrClass(root, "p.Missing", 1, IrType.objectType("external.Base")).also(root::addClass)
        assertFalse(AnnotationConstantScope(missing, AnnotationRenderBudget()).isComplete())
    }

    @Test fun exhaustedProofDoesNotResumeOrGrowOnRepeatedQueries() {
        val root = com.jadxmp.ir.node.IrRoot()
        val a = IrClass(root, "p.A", 1, IrType.objectType("external." + "A".repeat(1_000))).also(root::addClass)
        val proof = AnnotationConstantScope(a, AnnotationRenderBudget(characters = 100))
        assertFailsWith<IllegalArgumentException> { proof.isComplete() }
        repeat(1_000) { assertFalse(proof.isComplete()) }
    }
}
