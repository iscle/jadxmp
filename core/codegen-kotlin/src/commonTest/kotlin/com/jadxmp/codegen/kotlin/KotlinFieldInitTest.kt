package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.insn.TypeInstruction
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Field-initializer reconstruction for the Kotlin backend: a non-literal `static final` field's single
 * unconditional `<clinit>` store becomes a `val X = <expr>` property initializer, and an enum's
 * synthetic `<clinit>` enum-construction is suppressed so the entry `val`s are never reassigned.
 */
class KotlinFieldInitTest {

    private val ACC_ENUM = 0x4000
    private val enumConst = Flags.PUBLIC or Flags.STATIC or Flags.FINAL or ACC_ENUM
    private val enumSuper = IrType.objectType("java.lang.Enum")

    /** An SSA value with a defining occurrence (bound as an instruction result) and fresh reads. */
    private class Ssa(regNum: Int, val type: IrType) {
        val ssa = SsaValue(regNum, 0, RegisterOperand(regNum, type))
        fun def(): RegisterOperand = ssa.assign
        fun use(): RegisterOperand = RegisterOperand(ssa.regNum, type).also { it.ssaValue = ssa; ssa.addUse(it) }
    }

    private fun fieldRef(cls: IrClass, name: String, type: IrType) =
        FieldRef(IrType.objectType(cls.fullName), name, type)

    private fun staticPut(field: FieldRef, value: Operand): Instruction =
        FieldInstruction(field, isStatic = true, isPut = true, args = listOf(value))

    private fun ctorCall(owner: IrType, argTypes: List<IrType>, args: List<Operand>, result: RegisterOperand): Instruction =
        InvokeInstruction(
            MethodRef(owner, MethodRef.CONSTRUCTOR_NAME, owner, argTypes),
            InvokeKind.DIRECT,
            result,
            args,
            opcode = IrOpcode.CONSTRUCTOR,
        )

    private fun filledNewArray(arrayType: IrType, elements: List<Operand>, result: RegisterOperand): Instruction =
        TypeInstruction(IrOpcode.FILLED_NEW_ARRAY, arrayType, result, elements)

    private fun clinit(cls: IrClass, vararg insns: Instruction) {
        cls.method("<clinit>", accessFlags = Flags.STATIC) {
            val block = BasicBlock(0)
            insns.forEach { block.instructions.add(it) }
            block.instructions.add(Instruction(IrOpcode.RETURN))
            blocks.add(block)
        }
    }

    // ---------- static-final field initializer ----------

    @Test
    fun staticFinalNonLiteralStoreBecomesValInitializer() {
        val cls = irClass("a.Foo")
        val tag = IrField(cls, "TAG", IrType.STRING, Flags.PUBLIC or Flags.STATIC or Flags.FINAL)
        cls.fields.add(tag)
        // TAG = Helper.name()
        val helper = IrType.objectType("a.Helper")
        val res = Ssa(0, IrType.STRING)
        val call = InvokeInstruction(
            MethodRef(helper, "name", IrType.STRING, emptyList()),
            InvokeKind.STATIC,
            res.def(),
            emptyList(),
        )
        clinit(cls, call, staticPut(fieldRef(cls, "TAG", IrType.STRING), res.use()))

        assertThatCode(generate(cls))
            .containsOne("val TAG: String = Helper.name()")
            .doesNotContain("JADXMP ERROR")
            // The store is the initializer now — never a reassignment in an init block.
            .doesNotContain("Foo.TAG =")
            .doesNotContain("init {")
    }

    @Test
    fun staticFinalReadingMutableStateNotHoistedAheadOfSurvivor() {
        // `static base = compute(); static final DERIVED = base + 1;` — DERIVED's tree READS the field
        // `base`, which a surviving init-block statement WRITES. Hoisting DERIVED to a `val` initializer
        // would evaluate `base` (still 0) at the property position, before `base = compute()` runs — a
        // silent wrong value. So DERIVED must NOT be hoisted; it stays in the init block.
        val cls = irClass("a.Foo")
        val base = IrField(cls, "base", IrType.INT, Flags.PUBLIC or Flags.STATIC) // non-final ⇒ store stays
        val derived = IrField(cls, "DERIVED", IrType.INT, Flags.PUBLIC or Flags.STATIC or Flags.FINAL)
        cls.fields.add(base)
        cls.fields.add(derived)
        val baseRef = fieldRef(cls, "base", IrType.INT)
        val computed = Ssa(0, IrType.INT)
        val compute = InvokeInstruction(
            MethodRef(IrType.objectType("a.Helper"), "compute", IrType.INT, emptyList()),
            InvokeKind.STATIC,
            computed.def(),
            emptyList(),
        )
        val sum = Ssa(1, IrType.INT)
        val add = arith(ArithOp.ADD, expr(staticGet(baseRef)), intLit(1), sum.def())
        clinit(
            cls,
            compute, staticPut(baseRef, computed.use()),
            add, staticPut(fieldRef(cls, "DERIVED", IrType.INT), sum.use()),
        )
        assertThatCode(generate(cls))
            // DERIVED is NOT hoisted (no `val DERIVED: Int = …` initializer); it stays in the init block.
            .doesNotContain("val DERIVED: Int = ")
            .containsOne("init {")
    }

    @Test
    fun anonymousBodyEnumFallsBackToOrdinaryPath() {
        // `enum E { A { override … } }` desugars A to `A = new E$1("A", 0)` (a subclass). The reconstructor
        // must NOT reconstruct it (it would drop the override body and leave a dangling `E$1(…)`); it bails
        // to the ordinary path.
        val cls = irClass("e.E", accessFlags = Flags.PUBLIC or Flags.FINAL or ACC_ENUM, superType = enumSuper)
        val eT = IrType.objectType("e.E")
        val subT = IrType.objectType("e.E\$1")
        val arrT = IrType.array(eT)
        IrField(cls, "A", eT, enumConst).also { cls.fields.add(it) }
        IrField(cls, "\$VALUES", arrT, Flags.STATIC or Flags.FINAL).also {
            it.add(AttrFlag.SYNTHETIC); cls.fields.add(it)
        }
        val a = Ssa(0, eT)
        val arr = Ssa(1, arrT)
        clinit(
            cls,
            // The construction is `new E$1(...)`, NOT `new E(...)`.
            ctorCall(subT, listOf(IrType.STRING, IrType.INT), listOf(expr(constString("A")), intLit(0)), a.def()),
            staticPut(fieldRef(cls, "A", eT), a.use()),
            filledNewArray(arrT, listOf(a.use()), arr.def()),
            staticPut(fieldRef(cls, "\$VALUES", arrT), arr.use()),
        )
        cls.method("<init>", argTypes = listOf(IrType.STRING, IrType.INT), accessFlags = Flags.PRIVATE) {
            val self = Local(0, eT, isThis = true)
            val name = Local(1, IrType.STRING, isParam = true)
            val ord = Local(2, IrType.INT, isParam = true)
            body(
                InvokeInstruction(
                    MethodRef(enumSuper, MethodRef.CONSTRUCTOR_NAME, enumSuper, listOf(IrType.STRING, IrType.INT)),
                    InvokeKind.DIRECT,
                    null,
                    listOf(self.ref(), name.ref(), ord.ref()),
                ),
                ret(),
            )
        }

        // The reconstructor bails: an anonymous-body entry is not a direct `new E` construction.
        assertNull(KotlinEnumReconstruction.analyze(cls))
    }

    @Test
    fun staticFinalConditionalStoreLeftAlone() {
        // A store that is not provably single+unconditional must NOT be inlined (rule 4): the field is
        // stored twice, so the initializer cannot be reconstructed to a single expression.
        val cls = irClass("a.Foo")
        val fld = IrField(cls, "X", IrType.INT, Flags.PUBLIC or Flags.STATIC or Flags.FINAL)
        cls.fields.add(fld)
        val ref = fieldRef(cls, "X", IrType.INT)
        clinit(cls, staticPut(ref, intLit(1)), staticPut(ref, intLit(2)))
        // Two stores ⇒ no single-initializer reconstruction ⇒ conservative fallback (not inlined).
        assertThatCode(generate(cls)).doesNotContain("val X: Int = 1")
    }

    // ---------- enum <clinit> suppression ----------

    @Test
    fun enumClinitConstructionSuppressedNoValReassign() {
        val cls = irClass("e.Color", accessFlags = Flags.PUBLIC or Flags.FINAL or ACC_ENUM, superType = enumSuper)
        val colorT = IrType.objectType("e.Color")
        val arrT = IrType.array(colorT)
        IrField(cls, "RED", colorT, enumConst).also { cls.fields.add(it) }
        IrField(cls, "GREEN", colorT, enumConst).also { cls.fields.add(it) }
        IrField(cls, "\$VALUES", arrT, Flags.STATIC or Flags.FINAL).also {
            it.add(AttrFlag.SYNTHETIC); cls.fields.add(it)
        }

        val red = Ssa(0, colorT)
        val green = Ssa(1, colorT)
        val arr = Ssa(2, arrT)
        clinit(
            cls,
            ctorCall(colorT, listOf(IrType.STRING, IrType.INT), listOf(expr(constString("RED")), intLit(0)), red.def()),
            staticPut(fieldRef(cls, "RED", colorT), red.use()),
            ctorCall(colorT, listOf(IrType.STRING, IrType.INT), listOf(expr(constString("GREEN")), intLit(1)), green.def()),
            staticPut(fieldRef(cls, "GREEN", colorT), green.use()),
            filledNewArray(arrT, listOf(red.use(), green.use()), arr.def()),
            staticPut(fieldRef(cls, "\$VALUES", arrT), arr.use()),
        )
        // Default enum constructor: super(name, ordinal); return.
        cls.method("<init>", argTypes = listOf(IrType.STRING, IrType.INT), accessFlags = Flags.PRIVATE) {
            val self = Local(0, colorT, isThis = true)
            val name = Local(1, IrType.STRING, isParam = true)
            val ord = Local(2, IrType.INT, isParam = true)
            body(
                InvokeInstruction(
                    MethodRef(enumSuper, MethodRef.CONSTRUCTOR_NAME, enumSuper, listOf(IrType.STRING, IrType.INT)),
                    InvokeKind.DIRECT,
                    null,
                    listOf(self.ref(), name.ref(), ord.ref()),
                ),
                ret(),
            )
        }
        cls.method("values", returnType = arrT, accessFlags = Flags.PUBLIC or Flags.STATIC)
        cls.method("valueOf", returnType = colorT, argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC or Flags.STATIC)

        assertThatCode(generate(cls))
            .containsOne("enum class Color {")
            .containsOne("RED")
            .containsOne("GREEN")
            // The enum-construction <clinit> is fully suppressed: no companion init reassigns the entries,
            // no `Color(...)` construction, no synthetic backing array / values()/valueOf survive.
            .doesNotContain("init {")
            .doesNotContain("= Color(")
            .doesNotContain("Color.RED =")
            .doesNotContain("VALUES")
            .doesNotContain("fun values")
    }
}
