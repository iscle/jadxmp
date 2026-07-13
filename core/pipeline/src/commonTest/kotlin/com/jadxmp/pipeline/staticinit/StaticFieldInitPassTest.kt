package com.jadxmp.pipeline.staticinit

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.ConstStringInstruction
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrFieldConst
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.pass.PassContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `<clinit>` static-field-init absorption. Mirrors jadx's `ExtractFieldInit.moveStaticFieldsInit`:
 * compile-time consts fold onto the field declaration; a `final` field written at runtime loses its
 * encoded constant and keeps a `static { }` assignment (blank final); conditional writes are never
 * hoisted.
 */
class StaticFieldInitPassTest {

    private companion object {
        const val OWNER = "com.example.Foo"
        const val ACC_STATIC = 0x0008
        const val ACC_FINAL = 0x0010
    }

    private class Fixture {
        val root = IrRoot()
        val cls = IrClass(root, OWNER, accessFlags = ACC_STATIC).also { root.addClass(it) }
        val clinit = IrMethod(cls, "<clinit>", IrType.VOID, emptyList(), ACC_STATIC)
            .also { cls.methods.add(it) }

        fun field(name: String, type: IrType, final: Boolean, const: IrFieldConst? = null): IrField {
            val flags = ACC_STATIC or (if (final) ACC_FINAL else 0)
            return IrField(cls, name, type, flags).also { it.constValue = const; cls.fields.add(it) }
        }

        fun staticPut(name: String, type: IrType, value: Operand): FieldInstruction =
            FieldInstruction(FieldRef(IrType.objectType(OWNER), name, type), isStatic = true, isPut = true, args = listOf(value))

        /** Wire [blocks] as the method body; the first is the entry, successors set from [edges]. */
        fun body(vararg blocks: BasicBlock, edges: List<Pair<Int, Int>> = emptyList()) {
            for (b in blocks) clinit.blocks.add(b)
            clinit.entryBlock = blocks.first()
            for ((from, to) in edges) {
                blocks[from].successors.add(blocks[to])
                blocks[to].predecessors.add(blocks[from])
            }
        }

        fun run() = StaticFieldInitPass().run(clinit, PassContext(root))
    }

    private fun block(vararg insns: Instruction): BasicBlock =
        BasicBlock(0).also { for (i in insns) it.instructions.add(i) }

    private fun blockId(id: Int, vararg insns: Instruction): BasicBlock =
        BasicBlock(id).also { for (i in insns) it.instructions.add(i) }

    private fun retVoid() = Instruction(IrOpcode.RETURN)

    @Test
    fun foldsPrimitiveAndStringConstsThenDropsEmptyClinit() {
        val f = Fixture()
        val intField = f.field("A", IrType.INT, final = true)
        val strField = f.field("S", IrType.STRING, final = true)
        val putInt = f.staticPut("A", IrType.INT, LiteralOperand(5, IrType.INT))
        val putStr = f.staticPut("S", IrType.STRING, InstructionOperand(ConstStringInstruction("hi", result = RegisterOperand(0, IrType.STRING))))
        f.body(block(putInt, putStr, retVoid()))

        f.run()

        assertEquals(IrFieldConst.Primitive(5, IrType.INT), intField.constValue)
        assertEquals(IrFieldConst.Str("hi"), strField.constValue)
        assertTrue(putInt.contains(AttrFlag.DONT_GENERATE), "folded int assignment removed from <clinit>")
        assertTrue(putStr.contains(AttrFlag.DONT_GENERATE), "folded string assignment removed from <clinit>")
        // Nothing left but folded puts + return-void ⇒ the whole <clinit> is dropped.
        assertTrue(f.clinit.contains(AttrFlag.DONT_GENERATE), "empty <clinit> dropped")
    }

    @Test
    fun clearsEncodedConstOfFinalFieldWrittenAtRuntimeAndKeepsStaticBlock() {
        val f = Fixture()
        // Models an AGP BuildConfig.DEBUG: static_values slot `false` + <clinit> parseBoolean("true").
        val debug = f.field("DEBUG", IrType.BOOLEAN, final = true, const = IrFieldConst.Primitive(0, IrType.BOOLEAN))
        val parse = InvokeInstruction(
            MethodRef(IrType.objectType("java.lang.Boolean"), "parseBoolean", IrType.BOOLEAN, listOf(IrType.STRING)),
            InvokeKind.STATIC,
            result = RegisterOperand(0, IrType.BOOLEAN),
            args = listOf(InstructionOperand(ConstStringInstruction("true", result = RegisterOperand(1, IrType.STRING)))),
        )
        val put = f.staticPut("DEBUG", IrType.BOOLEAN, InstructionOperand(parse))
        f.body(block(put, retVoid()))

        f.run()

        assertNull(debug.constValue, "encoded const cleared so declaration is a blank final (no double assign)")
        assertFalse(put.contains(AttrFlag.DONT_GENERATE), "runtime assignment stays as a static { } statement")
        assertFalse(f.clinit.contains(AttrFlag.DONT_GENERATE), "<clinit> with residual logic is kept")
    }

    @Test
    fun refusesToFoldConstStorePrecededByASideEffect() {
        val f = Fixture()
        val a = f.field("A", IrType.INT, final = true)
        // boom(); A = 5; return — folding A=5 onto the declaration hoists the store BEFORE boom(); if boom()
        // observes A the output would diverge from the DEX, so the store must stay in the static block.
        val boom = InvokeInstruction(
            MethodRef(IrType.objectType(OWNER), "boom", IrType.VOID, emptyList()),
            InvokeKind.STATIC,
        )
        val put = f.staticPut("A", IrType.INT, LiteralOperand(5, IrType.INT))
        f.body(block(boom, put, retVoid()))

        f.run()

        assertNull(a.constValue, "final field encoded const still cleared")
        assertFalse(put.contains(AttrFlag.DONT_GENERATE), "const store NOT hoisted past the preceding call")
        assertFalse(f.clinit.contains(AttrFlag.DONT_GENERATE), "<clinit> kept (call + assignment remain)")
    }

    @Test
    fun keepsNonFinalStaticFieldEncodedConstAndAssignment() {
        val f = Fixture()
        // A non-final static field: jadx keeps its encoded value AND the reassignment (both legal).
        val counter = f.field("COUNTER", IrType.INT, final = false, const = IrFieldConst.Primitive(3, IrType.INT))
        val put = f.staticPut("COUNTER", IrType.INT, LiteralOperand(7, IrType.INT))
        f.body(block(put, retVoid()))

        f.run()

        assertEquals(IrFieldConst.Primitive(3, IrType.INT), counter.constValue, "non-final encoded const not cleared")
        assertFalse(put.contains(AttrFlag.DONT_GENERATE), "non-final assignment kept in static { }")
        assertFalse(f.clinit.contains(AttrFlag.DONT_GENERATE))
    }

    @Test
    fun doesNotFoldConditionalAssignment() {
        val f = Fixture()
        val cond = f.field("A", IrType.INT, final = true)
        // b0 forks (IF) → b1 (assign) / b2 (skip) → b3 (return): the assignment is not on the single path.
        val put = f.staticPut("A", IrType.INT, LiteralOperand(5, IrType.INT))
        val b0 = blockId(0, IfInstruction(com.jadxmp.ir.insn.ConditionOp.EQ, emptyList()))
        val b1 = blockId(1, put)
        val b2 = blockId(2)
        val b3 = blockId(3, retVoid())
        f.body(b0, b1, b2, b3, edges = listOf(0 to 1, 0 to 2, 1 to 3, 2 to 3))

        f.run()

        assertNull(cond.constValue, "final field written in <clinit> still loses its encoded const")
        assertFalse(put.contains(AttrFlag.DONT_GENERATE), "conditional assignment is not hoisted to the declaration")
    }
}
