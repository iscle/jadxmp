package com.jadxmp.codegen.java

import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class JavaFallbackTest {

    private val foo = IrType.objectType("a.Foo")

    private fun useCall() = staticInvoke(foo, "use", IrType.VOID, emptyList(), emptyList())

    @Test
    fun branchyMethodEmitsLabeledBlocks() {
        // Genuine control flow (a fork) with no region tree yet: the pre-structuring stopgap emits each
        // block as a labeled block. (A branch-free block list instead emits flat straight-line code.)
        val cls = irClass("a.Foo")
        cls.method("m") {
            val b0 = BasicBlock(0).apply { instructions.add(useCall()) }
            val b1 = BasicBlock(1).apply { instructions.add(ret()) }
            val b2 = BasicBlock(2).apply { instructions.add(ret()) }
            b0.successors.add(b1)
            b0.successors.add(b2) // fork ⇒ not linear ⇒ labeled fallback
            blocks.add(b0)
            blocks.add(b1)
            blocks.add(b2)
        }
        assertThatCode(generate(cls))
            .containsOne("block0: {")
            .containsOne("block1: {")
            .containsOne("block2: {")
            .containsOne("Foo.use();")
    }

    @Test
    fun linearMultiBlockMethodEmitsFlat() {
        // Several blocks but a single straight-line path (b0 → b1): emit flat, compilable, no labels.
        val cls = irClass("a.Foo")
        cls.method("m") {
            val b0 = BasicBlock(0).apply { instructions.add(useCall()) }
            val b1 = BasicBlock(1).apply { instructions.add(ret()) }
            b0.successors.add(b1)
            blocks.add(b0)
            blocks.add(b1)
        }
        assertThatCode(generate(cls))
            .doesNotContain("block0:")
            .containsOne("Foo.use();")
            .containsOne("return;")
    }

    @Test
    fun singleBlockMethodEmitsStraightLine() {
        // One block: straight-line statements, no labels.
        val cls = irClass("a.Foo")
        cls.method("m") { body(useCall(), ret()) }
        assertThatCode(generate(cls))
            .doesNotContain("block0:")
            .containsOne("Foo.use();")
            .containsOne("return;")
    }
}
