package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.ConditionOp
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.region.CatchClause
import com.jadxmp.ir.region.Condition
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopKind
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.region.SwitchCase
import com.jadxmp.ir.region.SwitchRegion
import com.jadxmp.ir.region.SyncRegion
import com.jadxmp.ir.region.TryCatchRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinRegionTest {

    private val foo = IrType.objectType("a.Foo")
    private val a = Local(1, IrType.INT, name = "a", isParam = true)
    private val b = Local(2, IrType.INT, name = "b", isParam = true)
    private var blockId = 100

    private fun seq(vararg insns: Instruction): SequenceRegion {
        val s = SequenceRegion()
        val block = BasicBlock(blockId++)
        insns.forEach { block.instructions.add(it) }
        s.add(block)
        return s
    }

    private fun useCall(vararg args: Operand): Instruction =
        staticInvoke(foo, "use", IrType.VOID, args.map { it.type }, args.toList())

    private fun withRegion(region: Region): String {
        val cls = irClass("a.Foo")
        cls.method("m", argTypes = listOf(IrType.INT, IrType.INT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("a", "b")
            this.region = region
        }
        return generate(cls)
    }

    @Test
    fun ifElse() {
        val r = IfRegion(
            Condition.Compare(ConditionOp.LT, a.ref(), b.ref()),
            thenRegion = seq(useCall(a.ref())),
            elseRegion = seq(useCall(b.ref())),
        )
        assertThatCode(withRegion(r))
            .containsOne("if (a < b) {")
            .containsOne("} else {")
            .containsLine(2, "Foo.use(a)")
            .containsLine(2, "Foo.use(b)")
    }

    @Test
    fun elseIfChaining() {
        val inner = IfRegion(
            Condition.Compare(ConditionOp.GT, a.ref(), b.ref()),
            thenRegion = seq(useCall(a.ref())),
            elseRegion = seq(useCall(b.ref())),
        )
        val outer = IfRegion(
            Condition.Compare(ConditionOp.EQ, a.ref(), b.ref()),
            thenRegion = seq(useCall()),
            elseRegion = SequenceRegion().apply { add(inner) },
        )
        assertThatCode(withRegion(outer))
            .containsOne("if (a == b) {")
            .containsOne("} else if (a > b) {")
    }

    @Test
    fun forLoopLoweredToWhile() {
        val i = Local(0, IrType.INT)
        val loop = LoopRegion(
            LoopKind.FOR,
            condition = Condition.Compare(ConditionOp.LT, i.ref(), intLit(10)),
            body = seq(useCall(i.ref())),
        ).apply {
            this[CodegenKeys.LOOP_INIT] = assign(i.ref(), Instruction(IrOpcode.CONST, args = listOf(intLit(0))))
            this[CodegenKeys.LOOP_UPDATE] = assign(i.ref(), arith(ArithOp.ADD, i.ref(), intLit(1)))
        }
        // Kotlin has no C-style for: init before, update at the end of the while body. No clause is lost.
        assertThatCode(withRegion(loop))
            .containsLine(1, "var i: Int = 0")
            .containsLine(1, "while (i < 10) {")
            .containsLine(2, "Foo.use(i)")
            .containsLine(2, "i = i + 1")
    }

    @Test
    fun whileLoop() {
        val loop = LoopRegion(
            LoopKind.WHILE,
            condition = Condition.Compare(ConditionOp.NE, a.ref(), intLit(0)),
            body = seq(useCall(a.ref())),
        )
        assertThatCode(withRegion(loop)).containsOne("while (a != 0) {")
    }

    @Test
    fun infiniteLoop() {
        val loop = LoopRegion(LoopKind.INFINITE, condition = null, body = seq(useCall()))
        assertThatCode(withRegion(loop)).containsOne("while (true) {")
    }

    @Test
    fun doWhileLoopHasNoTrailingSemicolon() {
        val loop = LoopRegion(
            LoopKind.DO_WHILE,
            condition = Condition.Compare(ConditionOp.LE, a.ref(), intLit(5)),
            body = seq(useCall(a.ref())),
        )
        assertThatCode(withRegion(loop))
            .containsOne("do {")
            .containsOne("} while (a <= 5)")
            .doesNotContain("} while (a <= 5);")
    }

    @Test
    fun switchBecomesWhen() {
        val r = SwitchRegion(
            selector = a.ref(),
            cases = listOf(
                SwitchCase(listOf(1L), seq(useCall(intLit(10)))),
                SwitchCase(listOf(2L, 3L), seq(useCall(intLit(20)))),
            ),
            defaultCase = seq(useCall(intLit(0))),
        )
        assertThatCode(withRegion(r))
            .containsOne("when (a) {")
            .containsLine(2, "1 -> {")
            .containsLine(2, "2, 3 -> {")
            .containsLine(2, "else -> {")
            .containsLine(3, "Foo.use(10)")
    }

    @Test
    fun tryCatchFinally() {
        val e = Local(5, IrType.objectType("java.io.IOException"), name = "e")
        val r = TryCatchRegion(
            tryRegion = seq(useCall()),
            catches = listOf(
                CatchClause(
                    exceptionTypes = listOf(IrType.objectType("java.io.IOException")),
                    exceptionVar = e.ref(),
                    body = seq(useCall()),
                ),
            ),
            finallyRegion = seq(useCall()),
        )
        assertThatCode(withRegion(r))
            .containsOne("try {")
            .containsOne("} catch (e: IOException) {")
            .containsOne("} finally {")
    }

    @Test
    fun multiCatchBecomesSeparateCatchesPerType() {
        val e = Local(5, IrType.THROWABLE, name = "e")
        val r = TryCatchRegion(
            tryRegion = seq(useCall()),
            catches = listOf(
                CatchClause(
                    exceptionTypes = listOf(
                        IrType.objectType("java.io.IOException"),
                        IrType.objectType("java.lang.RuntimeException"),
                    ),
                    exceptionVar = e.ref(),
                    body = seq(useCall()),
                ),
            ),
        )
        // Kotlin has no `A | B` multi-catch; each type gets its own catch, no type dropped.
        assertThatCode(withRegion(r))
            .containsOne("catch (e: IOException) {")
            .containsOne("catch (e: RuntimeException) {")
            .doesNotContain("|")
    }

    @Test
    fun synchronizedBlock() {
        val r = SyncRegion(monitor = a.ref(), body = seq(useCall(a.ref())))
        assertThatCode(withRegion(r)).containsOne("synchronized(a) {")
    }

    @Test
    fun duplicatedBlockRedeclaresBlockLocalTempPerCopy() {
        // Same coordinated feature as the Java backend: a DUPLICATED block (same block object in both `if`
        // arms) that carries a BLOCK-LOCAL temp `t` (marked BLOCK_LOCAL_TEMP) must re-declare `t` in EACH
        // copy; an unmarked value keeps its single declaration (deduped), never re-declared per copy.
        val t = Local(3, IrType.INT, name = "t")
        val u = Local(4, IrType.INT, name = "u")
        val shared = BasicBlock(200)
        shared.instructions.add(
            assign(t.ref(), arith(ArithOp.ADD, a.ref(), b.ref())).also { it.add(AttrFlag.BLOCK_LOCAL_TEMP) },
        )
        shared.instructions.add(useCall(t.ref()))
        shared.instructions.add(assign(u.ref(), arith(ArithOp.ADD, a.ref(), intLit(1)))) // NOT marked

        val r = IfRegion(
            Condition.Compare(ConditionOp.LT, a.ref(), b.ref()),
            thenRegion = SequenceRegion().apply { add(shared) },
            elseRegion = SequenceRegion().apply { add(shared) }, // SAME block object ⇒ duplicated emission
        )
        assertThatCode(withRegion(r))
            .countString(2, "var t: Int = a + b") // block-local temp re-declared in EACH copy
            .countString(1, "var u: Int = a + 1") // unmarked value declared ONCE (deduped, not per-copy)
    }
}
