package com.jadxmp.codegen

import kotlin.test.*

class AnnotationRenderBudgetTest {
    @Test fun aggregateEscapingAndNodeWorkAreBoundedWithoutIntegerOverflow() {
        val budget = AnnotationRenderBudget(nodes = 2, characters = 12)
        budget.enter(0)
        budget.text(2, expansion = 6)
        assertFailsWith<IllegalArgumentException> { budget.text(Int.MAX_VALUE, expansion = 6) }
        assertFailsWith<IllegalArgumentException> { budget.enter(0) }
    }

    @Test fun discoveryAndFinalRenderingHaveIndependentEquivalentBudgets() {
        repeat(2) {
            val pass = AnnotationRenderBudget(nodes = 1)
            pass.enter(32)
            assertFailsWith<IllegalArgumentException> { pass.enter(0) }
        }
        assertFailsWith<IllegalArgumentException> { AnnotationRenderBudget().enter(33) }
    }
}
