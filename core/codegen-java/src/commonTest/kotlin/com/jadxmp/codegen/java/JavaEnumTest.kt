package com.jadxmp.codegen.java

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.insn.Instruction
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
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Java `enum` reconstruction from a desugared `ACC_ENUM` class: constants first in ordinal order, the
 * synthetic `$VALUES`/`values()`/`valueOf(String)` hidden, the enum-construction `<clinit>` suppressed,
 * the `super(name, ordinal)` constructor stripped. **jadx: EnumVisitor**
 */
class JavaEnumTest {

    private val ACC_ENUM = 0x4000
    private val enumConst = Flags.PUBLIC or Flags.STATIC or Flags.FINAL or ACC_ENUM
    private val staticFinal = Flags.PUBLIC or Flags.STATIC or Flags.FINAL

    private val enumSuper = IrType.objectType("java.lang.Enum")

    /** An SSA value with a defining occurrence (bound as an instruction result) and fresh reads. */
    private class Ssa(regNum: Int, type: IrType) {
        val ssa = SsaValue(regNum, 0, RegisterOperand(regNum, type))
        val type = type

        /** The defining occurrence — assign it as an instruction's result. */
        fun def(): RegisterOperand = ssa.assign

        /** A fresh read of this value (links it into the SSA use list). */
        fun use(): RegisterOperand = RegisterOperand(ssa.regNum, type).also { it.ssaValue = ssa; ssa.addUse(it) }
    }

    private fun enumField(cls: IrClass, name: String, type: IrType, flags: Int): IrField =
        IrField(cls, name, type, flags).also { cls.fields.add(it) }

    private fun ctorCall(owner: IrType, argTypes: List<IrType>, args: List<Operand>, result: RegisterOperand): Instruction =
        InvokeInstruction(
            MethodRef(owner, MethodRef.CONSTRUCTOR_NAME, owner, argTypes),
            InvokeKind.DIRECT,
            result,
            args,
            opcode = IrOpcode.CONSTRUCTOR,
        )

    private fun staticPut(field: FieldRef, value: Operand): Instruction =
        FieldInstruction(field, isStatic = true, isPut = true, args = listOf(value))

    private fun clinitOf(cls: IrClass, vararg insns: Instruction) {
        val m = cls.method("<clinit>", accessFlags = Flags.STATIC)
        val block = BasicBlock(0)
        insns.forEach { block.instructions.add(it) }
        block.instructions.add(Instruction(IrOpcode.RETURN))
        m.blocks.add(block)
    }

    /** A simple two-constant enum with no constructor args and a values()/valueOf to be filtered. */
    private fun simpleColorEnum(): IrClass {
        val cls = irClass("e.Color", accessFlags = Flags.PUBLIC or Flags.FINAL or ACC_ENUM, superType = enumSuper)
        val colorT = IrType.objectType("e.Color")
        val arrT = IrType.array(colorT)
        enumField(cls, "\$VALUES", arrT, staticFinal or 0x1000).also { it.add(AttrFlag.SYNTHETIC) }
        enumField(cls, "RED", colorT, enumConst)
        enumField(cls, "GREEN", colorT, enumConst)

        val red = Ssa(0, colorT)
        val green = Ssa(1, colorT)
        val arr = Ssa(2, arrT)
        val redCtor = ctorCall(colorT, listOf(IrType.STRING, IrType.INT), listOf(strLit("RED"), intLit(0)), red.def())
        val greenCtor = ctorCall(colorT, listOf(IrType.STRING, IrType.INT), listOf(strLit("GREEN"), intLit(1)), green.def())
        val arrInsn = filledNewArray(arrT, listOf(red.use(), green.use()), arr.def())
        clinitOf(
            cls,
            redCtor, staticPut(fieldRef(cls, "RED", colorT), red.use()),
            greenCtor, staticPut(fieldRef(cls, "GREEN", colorT), green.use()),
            arrInsn, staticPut(fieldRef(cls, "\$VALUES", arrT), arr.use()),
        )

        // Synthetic ctor <init>(String,int): super(name, ordinal).
        cls.method("<init>", argTypes = listOf(IrType.STRING, IrType.INT), accessFlags = Flags.PRIVATE) {
            val self = Local(0, colorT, isThis = true)
            val name = Local(1, IrType.STRING, isParam = true)
            val ord = Local(2, IrType.INT, isParam = true)
            val superCall = InvokeInstruction(
                MethodRef(enumSuper, MethodRef.CONSTRUCTOR_NAME, enumSuper, listOf(IrType.STRING, IrType.INT)),
                InvokeKind.DIRECT,
                null,
                listOf(self.ref(), name.ref(), ord.ref()),
            )
            body(superCall, ret())
        }
        // Synthetic values()/valueOf(String) to be hidden.
        cls.method("values", returnType = arrT, accessFlags = Flags.PUBLIC or Flags.STATIC)
        cls.method("valueOf", returnType = colorT, argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC or Flags.STATIC)
        return cls
    }

    private fun fieldRef(cls: IrClass, name: String, type: IrType) =
        FieldRef(IrType.objectType(cls.fullName), name, type)

    private fun strLit(text: String): Operand = expr(constString(text))

    @Test
    fun simpleEnumRendersConstantsAndHidesSynthetics() {
        val code = generate(simpleColorEnum())
        assertThatCode(code)
            .containsOne("public enum Color {")
            .doesNotContain("extends Enum")
            .containsOne("RED,")
            .containsOne("GREEN")
            .doesNotContain("\$VALUES") // synthetic backing array hidden
            .doesNotContain("values(") // synthetic values()/valueOf hidden
            .doesNotContain("static {") // enum-construction <clinit> fully suppressed ⇒ no static block
            .doesNotContain("Color(") // synthetic-only constructor omitted
    }

    @Test
    fun enumWithConstructorArgsAndFieldStripsSyntheticParams() {
        val cls = irClass("e.Size", accessFlags = Flags.PUBLIC or Flags.FINAL or ACC_ENUM, superType = enumSuper)
        val sizeT = IrType.objectType("e.Size")
        val arrT = IrType.array(sizeT)
        enumField(cls, "\$VALUES", arrT, staticFinal or 0x1000).also { it.add(AttrFlag.SYNTHETIC) }
        enumField(cls, "SMALL", sizeT, enumConst)
        enumField(cls, "LARGE", sizeT, enumConst)
        enumField(cls, "value", IrType.INT, Flags.PUBLIC or Flags.FINAL) // real instance field

        val small = Ssa(0, sizeT)
        val large = Ssa(1, sizeT)
        val arr = Ssa(2, arrT)
        val ctorParams = listOf(IrType.STRING, IrType.INT, IrType.INT)
        val smallCtor = ctorCall(sizeT, ctorParams, listOf(strLit("SMALL"), intLit(0), intLit(1)), small.def())
        val largeCtor = ctorCall(sizeT, ctorParams, listOf(strLit("LARGE"), intLit(1), intLit(5)), large.def())
        val arrInsn = filledNewArray(arrT, listOf(small.use(), large.use()), arr.def())
        clinitOf(
            cls,
            smallCtor, staticPut(fieldRef(cls, "SMALL", sizeT), small.use()),
            largeCtor, staticPut(fieldRef(cls, "LARGE", sizeT), large.use()),
            arrInsn, staticPut(fieldRef(cls, "\$VALUES", arrT), arr.use()),
        )
        // Real ctor <init>(String,int,int): super(name,ord); this.value = v.
        cls.method("<init>", argTypes = ctorParams, accessFlags = Flags.PRIVATE) {
            this[com.jadxmp.codegen.CodegenKeys.PARAM_NAMES] = listOf("name", "ordinal", "v")
            val self = Local(0, sizeT, isThis = true)
            val name = Local(1, IrType.STRING, name = "name", isParam = true)
            val ord = Local(2, IrType.INT, name = "ordinal", isParam = true)
            val v = Local(3, IrType.INT, name = "v", isParam = true)
            val superCall = InvokeInstruction(
                MethodRef(enumSuper, MethodRef.CONSTRUCTOR_NAME, enumSuper, listOf(IrType.STRING, IrType.INT)),
                InvokeKind.DIRECT,
                null,
                listOf(self.ref(), name.ref(), ord.ref()),
            )
            body(superCall, instancePut(self.ref(), v.ref(), fieldRef(cls, "value", IrType.INT)), ret())
        }
        cls.method("values", returnType = arrT, accessFlags = Flags.PUBLIC or Flags.STATIC)
        cls.method("valueOf", returnType = sizeT, argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC or Flags.STATIC)

        assertThatCode(generate(cls))
            .containsOne("public enum Size {")
            .containsOne("SMALL(1),") // ordinal-stripped arg recovered (SMALL got extra arg 1)
            .containsOne("LARGE(5);") // trailing ';' because the `value` field follows
            .containsOne("public final int value;")
            .containsOne("Size(int v) {") // synthetic name/ordinal params stripped
            .doesNotContain("super(") // enum super() call dropped
            .containsOne("this.value = v;")
    }

    @Test
    fun enumWithResidualStaticInitKeepsStaticBlock() {
        val cls = irClass("e.Mode", accessFlags = Flags.PUBLIC or Flags.FINAL or ACC_ENUM, superType = enumSuper)
        val modeT = IrType.objectType("e.Mode")
        val arrT = IrType.array(modeT)
        enumField(cls, "\$VALUES", arrT, staticFinal or 0x1000).also { it.add(AttrFlag.SYNTHETIC) }
        enumField(cls, "ON", modeT, enumConst)
        enumField(cls, "COUNT", IrType.INT, Flags.PUBLIC or Flags.STATIC) // non-final static, real init

        val on = Ssa(0, modeT)
        val arr = Ssa(1, arrT)
        val onCtor = ctorCall(modeT, listOf(IrType.STRING, IrType.INT), listOf(strLit("ON"), intLit(0)), on.def())
        val arrInsn = filledNewArray(arrT, listOf(on.use()), arr.def())
        clinitOf(
            cls,
            onCtor, staticPut(fieldRef(cls, "ON", modeT), on.use()),
            arrInsn, staticPut(fieldRef(cls, "\$VALUES", arrT), arr.use()),
            staticPut(fieldRef(cls, "COUNT", IrType.INT), intLit(42)),
        )
        cls.method("<init>", argTypes = listOf(IrType.STRING, IrType.INT), accessFlags = Flags.PRIVATE) {
            val self = Local(0, modeT, isThis = true)
            val name = Local(1, IrType.STRING, isParam = true)
            val ord = Local(2, IrType.INT, isParam = true)
            val superCall = InvokeInstruction(
                MethodRef(enumSuper, MethodRef.CONSTRUCTOR_NAME, enumSuper, listOf(IrType.STRING, IrType.INT)),
                InvokeKind.DIRECT,
                null,
                listOf(self.ref(), name.ref(), ord.ref()),
            )
            body(superCall, ret())
        }

        assertThatCode(generate(cls))
            .containsOne("public enum Mode {")
            .containsOne("ON;") // one constant, then the static field/block follow
            .containsOne("public static int COUNT;")
            .containsOne("static {")
            .containsOne("Mode.COUNT = 42;") // residual (non-enum) static init preserved
            .doesNotContain("new Mode(") // enum construction suppressed inside the static block
    }

    private fun nullLit(type: IrType): Operand = LiteralOperand(0L, type)

    private fun arrayPut(value: Operand, array: Operand, index: Operand): Instruction =
        Instruction(IrOpcode.ARRAY_PUT, args = listOf(value, array, index))

    private fun syntheticEnumCtor(cls: IrClass, t: IrType, params: List<IrType>) {
        cls.method("<init>", argTypes = params, accessFlags = Flags.PRIVATE) {
            val self = Local(0, t, isThis = true)
            val name = Local(1, IrType.STRING, isParam = true)
            val ord = Local(2, IrType.INT, isParam = true)
            val superCall = InvokeInstruction(
                MethodRef(enumSuper, MethodRef.CONSTRUCTOR_NAME, enumSuper, listOf(IrType.STRING, IrType.INT)),
                InvokeKind.DIRECT,
                null,
                listOf(self.ref(), name.ref(), ord.ref()),
            )
            body(superCall, ret())
        }
    }

    /**
     * A minimal, cleanly-reconstructable two-constant enum (`A`, `B`) with its backing array (named
     * [valuesName], possibly obfuscated), enum-construction `<clinit>` and synthetic ctor — the shared
     * skeleton the method-classification tests below layer their synthetics/user methods onto.
     */
    private fun buildTwoConstantEnum(cls: IrClass, t: IrType, valuesName: String = "\$VALUES") {
        val arrT = IrType.array(t)
        enumField(cls, valuesName, arrT, staticFinal or 0x1000).also { it.add(AttrFlag.SYNTHETIC) }
        enumField(cls, "A", t, enumConst)
        enumField(cls, "B", t, enumConst)
        val a = Ssa(0, t)
        val b = Ssa(1, t)
        val arr = Ssa(2, arrT)
        val aCtor = ctorCall(t, listOf(IrType.STRING, IrType.INT), listOf(strLit("A"), intLit(0)), a.def())
        val bCtor = ctorCall(t, listOf(IrType.STRING, IrType.INT), listOf(strLit("B"), intLit(1)), b.def())
        val arrInsn = filledNewArray(arrT, listOf(a.use(), b.use()), arr.def())
        clinitOf(
            cls,
            aCtor, staticPut(fieldRef(cls, "A", t), a.use()),
            bCtor, staticPut(fieldRef(cls, "B", t), b.use()),
            arrInsn, staticPut(fieldRef(cls, valuesName, arrT), arr.use()),
        )
        syntheticEnumCtor(cls, t, listOf(IrType.STRING, IrType.INT))
    }

    /** A body wrapping the public `java.lang.Enum.valueOf` (a top-level invoke so structural detection sees it). */
    private fun enumValueOfBody(m: IrMethod, t: IrType) {
        val name = Local(0, IrType.STRING, name = "name", isParam = true)
        val res = Ssa(1, t)
        val call = staticInvoke(enumSuper, "valueOf", t, listOf(IrType.STRING), listOf(name.ref()), res.def())
        m.body(call, ret(res.use()))
    }

    /** A `$VALUES.clone()`-shaped synthetic `values()` clone reading [valuesName] (used to force the rename path). */
    private fun valuesCloneBody(m: IrMethod, cls: IrClass, arrT: IrType, valuesName: String) {
        val v = Ssa(0, arrT)
        m.body(
            staticGet(fieldRef(cls, valuesName, arrT), v.def()),
            virtualInvoke(v.use(), arrT, "clone", arrT, emptyList()),
            ret(),
        )
    }

    /**
     * A user helper `valueOf(String)` that wraps the PUBLIC `Enum.valueOf` must NOT be misdetected as the
     * synthetic and hidden — that would silently drop a real user method. Canonical-name-first: the method
     * literally named `valueOf` is THE synthetic (hidden); the identically-shaped user wrapper survives.
     */
    @Test
    fun userEnumValueOfWrapperSurvivesAndSyntheticValueOfHidden() {
        val cls = irClass("e.Look", accessFlags = Flags.PUBLIC or Flags.FINAL or ACC_ENUM, superType = enumSuper)
        val t = IrType.objectType("e.Look")
        val arrT = IrType.array(t)
        buildTwoConstantEnum(cls, t)
        // Real synthetic valueOf(String): literally named `valueOf`, wraps Enum.valueOf ⇒ hidden.
        cls.method("valueOf", returnType = t, argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC or Flags.STATIC) {
            enumValueOfBody(this, t)
        }
        // Synthetic values() (regenerated by the compiler) ⇒ hidden.
        cls.method("values", returnType = arrT, accessFlags = Flags.PUBLIC or Flags.STATIC)
        // User helper that ALSO wraps the public Enum.valueOf — same body-shape, different name.
        cls.method("lookup", returnType = t, argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC or Flags.STATIC) {
            enumValueOfBody(this, t)
        }

        val code = generate(cls)
        assertThatCode(code)
            .containsOne("public enum Look {")
            .doesNotContain("extends Enum")
            .doesNotContain("values(") // synthetic values()/valueOf hidden — no declaration or call survives
            .doesNotContain("Look valueOf") // the method literally named valueOf IS the synthetic ⇒ hidden
        assertTrue(code.contains("Look lookup("), "the user Enum.valueOf wrapper must survive under its own name")
    }

    /**
     * With genuine obfuscation (no method named `valueOf`) and TWO methods matching the Enum.valueOf-wrapper
     * shape, the exactly-one-match ambiguity guard hides NEITHER — hiding the wrong one is silent code loss,
     * and leaking the real clone/valueOf is uglier-but-correct (CLAUDE rule 4).
     */
    @Test
    fun ambiguousValueOfShapeHidesNeither() {
        val cls = irClass("e.Amb", accessFlags = Flags.PUBLIC or Flags.FINAL or ACC_ENUM, superType = enumSuper)
        val t = IrType.objectType("e.Amb")
        buildTwoConstantEnum(cls, t)
        cls.method("parse", returnType = t, argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC or Flags.STATIC) {
            enumValueOfBody(this, t)
        }
        cls.method("fromName", returnType = t, argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC or Flags.STATIC) {
            enumValueOfBody(this, t)
        }

        val code = generate(cls)
        assertThatCode(code).containsOne("public enum Amb {")
        assertTrue(code.contains("Amb parse("), "first valueOf-shaped user method must survive (ambiguity ⇒ hide neither)")
        assertTrue(code.contains("Amb fromName("), "second valueOf-shaped user method must survive (ambiguity ⇒ hide neither)")
    }

    /**
     * A CROSS-CLASS call to a renamed reserved-signature member must spell the NEW name the owning enum's
     * definition uses — otherwise the call would silently resolve to the compiler-regenerated `values()`.
     * Enum A keeps a user `values()` (renamed to `valuesCustom()` because an obfuscated clone exists); class
     * B's `A.values()` call must render `A.valuesCustom()`.
     */
    @Test
    fun crossClassCallToRenamedValuesUsesNewName() {
        val root = IrRoot()
        val a = irClass("e.A", accessFlags = Flags.PUBLIC or Flags.FINAL or ACC_ENUM, superType = enumSuper, root = root)
        val aT = IrType.objectType("e.A")
        val aArrT = IrType.array(aT)
        buildTwoConstantEnum(a, aT)
        // Obfuscated `values()` clone ⇒ the literal `values()` below is a USER method that must be renamed.
        a.method("vs", returnType = aArrT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            valuesCloneBody(this, a, aArrT, "\$VALUES")
        }
        a.method("values", returnType = aArrT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(nullLit(aArrT)))
        }

        val b = irClass("e.B", root = root)
        b.method("go", returnType = aArrT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(expr(staticInvoke(aT, "values", aArrT, emptyList(), emptyList()))))
        }

        val code = generate(b)
        assertTrue(code.contains("A.valuesCustom()"), "cross-class call must use the renamed definition name")
        assertTrue(!code.contains("A.values()"), "cross-class call must not use the reserved (renamed-away) name")
    }

    /**
     * The obfuscated-synthetic case: `$VALUES`/`values()`/`valueOf` obfuscated (`$VLS`/`vs`/`vo`). The
     * synthetics are hidden; USER references to them are rewritten to the regenerated `values()`/`valueOf`;
     * a colliding user `values()` is kept-and-renamed; NO user member is dropped (CLAUDE rule 4).
     */
    @Test
    fun obfuscatedSyntheticsHiddenUserRefsRewrittenAndCollisionRenamed() {
        val cls = irClass("e.Obf", accessFlags = Flags.PUBLIC or Flags.FINAL or ACC_ENUM, superType = enumSuper)
        val t = IrType.objectType("e.Obf")
        val arrT = IrType.array(t)
        buildTwoConstantEnum(cls, t, valuesName = "\$VLS")
        // Obfuscated clone `vs()` and valueOf `vo(String)` — both hidden.
        cls.method("vs", returnType = arrT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            valuesCloneBody(this, cls, arrT, "\$VLS")
        }
        cls.method("vo", returnType = t, argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC or Flags.STATIC) {
            enumValueOfBody(this, t)
        }
        // User method colliding with the regenerated `values()` ⇒ kept + renamed.
        cls.method("values", returnType = arrT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(nullLit(arrT)))
        }
        // User methods referencing the hidden synthetics — each reference must be rewritten.
        cls.method("refField", returnType = arrT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(expr(staticGet(fieldRef(cls, "\$VLS", arrT))))) // $VLS read ⇒ values()
        }
        cls.method("refClone", returnType = arrT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(expr(staticInvoke(t, "vs", arrT, emptyList(), emptyList())))) // vs() ⇒ values()
        }
        cls.method("refValueOf", returnType = t, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(expr(staticInvoke(t, "vo", t, listOf(IrType.STRING), listOf(strLit("A")))))) // vo("A") ⇒ valueOf("A")
        }

        val code = generate(cls)
        assertThatCode(code)
            .containsOne("public enum Obf {")
            .doesNotContain("\$VLS") // obfuscated backing array hidden and its reads rewritten
            .doesNotContain("vs(") // obfuscated clone hidden and its call rewritten to values()
            .doesNotContain("vo(") // obfuscated valueOf hidden and its call rewritten to valueOf(...)
        assertTrue(code.contains("valuesCustom("), "colliding user values() must be kept and renamed")
        assertTrue(code.contains("valueOf(\"A\")"), "user reference to the obfuscated valueOf must be rewritten")
        assertTrue(code.contains("values()"), "user references to \$VLS/clone must be rewritten to values()")
        assertTrue(code.contains("refField"), "user method refField must survive")
        assertTrue(code.contains("refClone"), "user method refClone must survive")
        assertTrue(code.contains("refValueOf"), "user method refValueOf must survive")
    }

    /**
     * MUST-FIX 1: a constant whose array arg is a `new String[n]` filled by SEPARATE `ARRAY_PUT`
     * statements is NOT a self-contained expression — inlining it would emit an empty array and silently
     * drop every element. The arg must bail to an honest marker instead.
     */
    @Test
    fun constantWithNewArrayArgBailsInsteadOfDroppingElements() {
        val cls = irClass("e.Holder", accessFlags = Flags.PUBLIC or Flags.FINAL or ACC_ENUM, superType = enumSuper)
        val t = IrType.objectType("e.Holder")
        val strArrT = IrType.array(IrType.STRING)
        val arrT = IrType.array(t)
        enumField(cls, "\$VALUES", arrT, staticFinal or 0x1000).also { it.add(AttrFlag.SYNTHETIC) }
        enumField(cls, "A", t, enumConst)
        enumField(cls, "tags", strArrT, Flags.PUBLIC or Flags.FINAL)

        val strArr = Ssa(5, strArrT)
        val a = Ssa(0, t)
        val vals = Ssa(1, arrT)
        val ctorParams = listOf(IrType.STRING, IrType.INT, strArrT)
        val newArr = newArray(strArrT, intLit(1), strArr.def())
        val put = arrayPut(strLit("x"), strArr.use(), intLit(0))
        val aCtor = ctorCall(t, ctorParams, listOf(strLit("A"), intLit(0), strArr.use()), a.def())
        val valsInsn = filledNewArray(arrT, listOf(a.use()), vals.def())
        clinitOf(
            cls,
            newArr, put,
            aCtor, staticPut(fieldRef(cls, "A", t), a.use()),
            valsInsn, staticPut(fieldRef(cls, "\$VALUES", arrT), vals.use()),
        )
        syntheticEnumCtor(cls, t, ctorParams)

        val code = generate(cls)
        assertThatCode(code)
            .containsOne("public enum Holder {")
            .containsOne("JADXMP ERROR") // honest bail, not a silent empty array
        assertTrue(!code.contains("A(new String["), "constant must not silently emit an empty array arg")
    }

    /**
     * MUST-FIX 2: a constant whose arg references ANOTHER enum constant (a register whose SSA def is the
     * enum-self CONSTRUCTOR) must NOT inline as `new EnumType(...)` — that is illegal Java and fabricates
     * a fresh instance. A codegen-only backend can't rewrite it to the constant's SGET, so it bails.
     */
    @Test
    fun constantReferencingAnotherEnumConstantBailsInsteadOfFabricatingInstance() {
        val cls = irClass("e.Ref", accessFlags = Flags.PUBLIC or Flags.FINAL or ACC_ENUM, superType = enumSuper)
        val t = IrType.objectType("e.Ref")
        val arrT = IrType.array(t)
        enumField(cls, "\$VALUES", arrT, staticFinal or 0x1000).also { it.add(AttrFlag.SYNTHETIC) }
        enumField(cls, "A", t, enumConst)
        enumField(cls, "B", t, enumConst)

        val a = Ssa(0, t)
        val b = Ssa(1, t)
        val vals = Ssa(2, arrT)
        val ctorParams = listOf(IrType.STRING, IrType.INT, t) // 3rd param is the enum type itself
        val aCtor = ctorCall(t, ctorParams, listOf(strLit("A"), intLit(0), nullLit(t)), a.def())
        val bCtor = ctorCall(t, ctorParams, listOf(strLit("B"), intLit(1), a.use()), b.def()) // B refs A
        val valsInsn = filledNewArray(arrT, listOf(a.use(), b.use()), vals.def())
        clinitOf(
            cls,
            aCtor, staticPut(fieldRef(cls, "A", t), a.use()),
            bCtor, staticPut(fieldRef(cls, "B", t), b.use()),
            valsInsn, staticPut(fieldRef(cls, "\$VALUES", arrT), vals.use()),
        )
        syntheticEnumCtor(cls, t, ctorParams)

        val code = generate(cls)
        assertThatCode(code)
            .containsOne("public enum Ref {")
            .containsOne("JADXMP ERROR")
        assertTrue(!code.contains("new Ref("), "must not fabricate a new enum instance for a constant reference")
    }

    /**
     * SHOULD-FIX: constants are ordered by their recovered ordinal (the ctor's 2nd synthetic arg), not
     * by `<clinit>` store order — so a reordered/obfuscated store sequence still emits ordinal order.
     */
    @Test
    fun constantsOrderedByOrdinalNotStoreOrder() {
        val cls = irClass("e.Ord", accessFlags = Flags.PUBLIC or Flags.FINAL or ACC_ENUM, superType = enumSuper)
        val t = IrType.objectType("e.Ord")
        val arrT = IrType.array(t)
        enumField(cls, "\$VALUES", arrT, staticFinal or 0x1000).also { it.add(AttrFlag.SYNTHETIC) }
        enumField(cls, "A", t, enumConst)
        enumField(cls, "B", t, enumConst)

        val a = Ssa(0, t)
        val b = Ssa(1, t)
        val vals = Ssa(2, arrT)
        // Stores out of ordinal order: B (ordinal 1) is stored BEFORE A (ordinal 0).
        val bCtor = ctorCall(t, listOf(IrType.STRING, IrType.INT), listOf(strLit("B"), intLit(1)), b.def())
        val aCtor = ctorCall(t, listOf(IrType.STRING, IrType.INT), listOf(strLit("A"), intLit(0)), a.def())
        val valsInsn = filledNewArray(arrT, listOf(a.use(), b.use()), vals.def())
        clinitOf(
            cls,
            bCtor, staticPut(fieldRef(cls, "B", t), b.use()),
            aCtor, staticPut(fieldRef(cls, "A", t), a.use()),
            valsInsn, staticPut(fieldRef(cls, "\$VALUES", arrT), vals.use()),
        )
        syntheticEnumCtor(cls, t, listOf(IrType.STRING, IrType.INT))

        val code = generate(cls)
        assertThatCode(code).containsOne("public enum Ord {")
        val ai = code.indexOf("A,")
        val bi = code.indexOf("B")
        assertTrue(ai in 0 until bi, "constants must be ordered by ordinal (A before B), not store order")
    }

    /**
     * TestEnumWithFields shape: alongside the real (constructed) constants the class carries `static
     * final` enum-typed ALIAS fields — `DEFAULT` assigned an existing constant via SGET, `MAX` assigned a
     * constant's own construction RESULT — plus a `values()`-cache array field. The reconstruction must:
     *  - keep DEFAULT/MAX/sVals as ordinary fields (never mistake an alias for a constant),
     *  - render the residual static block, rewriting the reused construction result to the constant name
     *    (`MAX = FIVE;`) rather than bailing on the now-suppressed constructor def.
     */
    @Test
    fun aliasFieldsAndValuesCachePreservedWithRewrittenResidual() {
        val cls = irClass("e.Cfg", accessFlags = Flags.PUBLIC or Flags.FINAL or ACC_ENUM, superType = enumSuper)
        val t = IrType.objectType("e.Cfg")
        val arrT = IrType.array(t)
        enumField(cls, "\$VALUES", arrT, staticFinal or 0x1000).also { it.add(AttrFlag.SYNTHETIC) }
        enumField(cls, "DISABLED", t, enumConst)
        enumField(cls, "FIVE", t, enumConst)
        // Alias fields: NOT `enum`-flagged, assigned an existing constant (not `new`).
        enumField(cls, "DEFAULT", t, staticFinal)
        enumField(cls, "MAX", t, staticFinal)
        enumField(cls, "sVals", arrT, staticFinal) // second values() cache array
        enumField(cls, "mRawValue", IrType.INT, Flags.PUBLIC or Flags.FINAL)

        val ctorParams = listOf(IrType.STRING, IrType.INT, IrType.INT)
        val disabled = Ssa(0, t)
        val five = Ssa(1, t)
        val arr = Ssa(2, arrT)
        val defRead = Ssa(3, t)
        val cache = Ssa(4, arrT)
        val disabledCtor = ctorCall(t, ctorParams, listOf(strLit("DISABLED"), intLit(0), intLit(0)), disabled.def())
        val fiveCtor = ctorCall(t, ctorParams, listOf(strLit("FIVE"), intLit(1), intLit(2)), five.def())
        val arrInsn = filledNewArray(arrT, listOf(disabled.use(), five.use()), arr.def())
        val sgetDisabled = staticGet(fieldRef(cls, "DISABLED", t), defRead.def())
        val valuesCall = staticInvoke(t, "values", arrT, emptyList(), emptyList(), cache.def())
        clinitOf(
            cls,
            disabledCtor, staticPut(fieldRef(cls, "DISABLED", t), disabled.use()),
            fiveCtor, staticPut(fieldRef(cls, "FIVE", t), five.use()),
            arrInsn, staticPut(fieldRef(cls, "\$VALUES", arrT), arr.use()),
            sgetDisabled, staticPut(fieldRef(cls, "DEFAULT", t), defRead.use()), // DEFAULT = DISABLED
            staticPut(fieldRef(cls, "MAX", t), five.use()), // MAX = <FIVE construction result>
            valuesCall, staticPut(fieldRef(cls, "sVals", arrT), cache.use()), // sVals = values()
        )
        // Real ctor <init>(String,int,int): super(name,ord); this.mRawValue = raw.
        cls.method("<init>", argTypes = ctorParams, accessFlags = Flags.PUBLIC) {
            this[com.jadxmp.codegen.CodegenKeys.PARAM_NAMES] = listOf("name", "ordinal", "raw")
            val self = Local(0, t, isThis = true)
            val name = Local(1, IrType.STRING, name = "name", isParam = true)
            val ord = Local(2, IrType.INT, name = "ordinal", isParam = true)
            val raw = Local(3, IrType.INT, name = "raw", isParam = true)
            val superCall = InvokeInstruction(
                MethodRef(enumSuper, MethodRef.CONSTRUCTOR_NAME, enumSuper, listOf(IrType.STRING, IrType.INT)),
                InvokeKind.DIRECT,
                null,
                listOf(self.ref(), name.ref(), ord.ref()),
            )
            body(superCall, instancePut(self.ref(), raw.ref(), fieldRef(cls, "mRawValue", IrType.INT)), ret())
        }
        cls.method("values", returnType = arrT, accessFlags = Flags.PUBLIC or Flags.STATIC)
        cls.method("valueOf", returnType = t, argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC or Flags.STATIC)

        val code = generate(cls)
        assertThatCode(code)
            .containsOne("public enum Cfg {")
            .doesNotContain("extends Enum")
            .containsOne("DISABLED(0),")
            .containsOne("FIVE(2);")
            .containsOne("public static final Cfg DEFAULT;")
            .containsOne("public static final Cfg MAX;")
            .containsOne("public static final Cfg[] sVals;")
            .containsOne("public final int mRawValue;")
            .containsOne("static {")
            .containsOne("MAX = FIVE;") // reused construction result rewritten to the constant name
            .doesNotContain("\$VALUES") // synthetic backing array still hidden
        // DEFAULT is assigned the existing DISABLED constant (directly or via a temp), never dropped.
        assertTrue(code.contains("DEFAULT ="), "alias field DEFAULT must be assigned in the static block")
        assertTrue(code.contains("DISABLED"), "the DISABLED constant referenced by DEFAULT must survive")
        // No fabricated construction survives in the static block (it is compiler-synthesized).
        assertTrue(!code.contains("new Cfg("), "enum construction must stay suppressed in the static block")
        assertTrue(code.contains("values()"), "values() cache assignment must survive")
    }

    @Test
    fun nonEnumClassUnaffected() {
        val cls = irClass("e.Plain")
        cls.fields.add(IrField(cls, "x", IrType.INT, Flags.PUBLIC))
        assertThatCode(generate(cls))
            .containsOne("public class Plain {")
            .containsOne("public int x;")
    }
}
