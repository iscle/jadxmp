package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.*
import com.jadxmp.ir.node.*
import com.jadxmp.ir.type.IrType
import kotlin.test.*

class KotlinEnumConstructionPlanTest {
    @Test fun valuesArrayMayReuseExactAlreadyStoredEntryLocals() {
        for (count in 1..2) {
            val fixture = Fixture()
            val (array, values) = fixture.useEntryLocals(count)
            val plan = assertNotNull(fixture.plan())
            assertEquals(count, plan.entries.size)
            assertTrue(array in plan.consumedInitializers)
            assertEquals(count, plan.entries.values.toSet().size)
            assertTrue(values.all { value -> plan.entries.values.any { it === value.assign.parent } })
        }
    }

    @Test fun reusedEntryArrayRejectsWrongOrderDuplicatesUnknownOrWrappedConstruction() {
        for (mutation in 0..5) {
            val fixture = Fixture()
            val (array, values) = fixture.useEntryLocals(2)
            fun read(value: SsaValue) = reg(value.regNum, fixture.type).also { it.ssaValue = value }
            when (mutation) {
                0 -> { array.setArg(0, read(values[1])); array.setArg(1, read(values[0])) }
                1 -> array.setArg(1, read(values[0]))
                2 -> array.setArg(0, lit(0, fixture.type))
                3 -> array.setArg(0, expr(fixture.construction))
                4 -> array.setArg(0, reg(values[0].regNum, fixture.type).also {
                    it.ssaValue = SsaValue(values[0].regNum, 99, reg(values[0].regNum, fixture.type))
                })
                5 -> array.setArg(0, expr(Instruction(IrOpcode.MOVE, args = listOf(read(values[0])))))
            }
            assertNull(fixture.plan(), "mutation $mutation")
        }
    }

    @Test fun entryLocalReuseCannotHideRemainingUsesOrInterveningEffects() {
        for (beforeArray in listOf(false, true)) {
            val fixture = Fixture()
            val (_, values) = fixture.useEntryLocals(1)
            val read = reg(values[0].regNum, fixture.type).also { it.ssaValue = values[0] }
            val statements = fixture.clinit.blocks.single().instructions
            statements.add(if (beforeArray) 2 else statements.lastIndex, effect(read))
            assertNull(fixture.plan())
        }
    }

    @Test fun unrelatedLazyBodiesCannotChangeTheOutputPlanButNestedBodiesAreChecked() {
        val fixture = Fixture()
        val sibling = IrClass(fixture.cls.root, "sample.Other", Flags.PUBLIC)
        fixture.cls.root.classes.add(sibling)
        val method = sibling.method("backing", returnType = IrType.OBJECT, accessFlags = Flags.STATIC)
        assertNotNull(fixture.plan())
        val read = FieldInstruction(FieldRef(fixture.type, "\$VALUES", IrType.array(fixture.type)), true, false)
        method.body(Instruction(IrOpcode.RETURN, args = listOf(expr(read))))
        assertNotNull(fixture.plan(), "unrelated output lowering must not affect the proof")
        // A nested body is part of this fully lowered output and must still prevent suppression.
        sibling.outerClass = fixture.cls
        fixture.cls.innerClasses.add(sibling)
        assertNull(fixture.plan())
    }

    @Test fun exactPrefixAndCanonicalHelpersAreRequired() {
        assertNotNull(Fixture().plan())
        Fixture().also { it.call.setArg(1, lit(0, IrType.STRING)); assertNull(it.plan()) }
        Fixture().also { it.call.setArg(2, intLit(0)); assertNull(it.plan()) }
        Fixture().also { it.call.setArg(0, reg(0, it.type)); assertNull(it.plan()) }
        Fixture().also { it.construction.setArg(1, intLit(9)); assertNull(it.plan()) }
        Fixture().also { it.construction.setArg(0, expr(ConstStringInstruction("RENAMED"))); assertNull(it.plan()) }
        Fixture().also { it.values.blocks.single().instructions.add(0, effect()); assertNull(it.plan()) }
    }

    @Test fun constructorEffectsBeforeDelegationAndHiddenParameterReadsReject() {
        Fixture().also { it.constructor.blocks.single().instructions.add(0, effect()); assertNull(it.plan()) }
        Fixture().also {
            it.constructor.blocks.single().instructions.add(1, effect(it.parameter(0)))
            assertNull(it.plan())
        }
    }

    @Test fun constructorCyclesAndUnknownTargetsReject() {
        Fixture().also {
            val recursive = InvokeInstruction(MethodRef(it.type, "<init>", IrType.VOID, it.constructor.argTypes),
                InvokeKind.DIRECT, args = listOf(it.thisValue(), it.parameter(0), it.parameter(1), it.parameter(2)))
            it.constructor.blocks.single().instructions[0] = recursive
            assertNull(it.plan())
        }
        Fixture().also {
            it.constructor.blocks.single().instructions[0] = InvokeInstruction(
                MethodRef(IrType.OBJECT, "<init>", IrType.VOID, emptyList()), InvokeKind.DIRECT, args = listOf(it.thisValue()))
            assertNull(it.plan())
        }
    }

    @Test fun sideEffectsCannotMoveAcrossEntryArgumentsOrRepeat() {
        Fixture().also { it.clinit.blocks.single().instructions.add(0, effect()); assertNull(it.plan()) }
        Fixture().also {
            val output = RegisterOperand(12, IrType.LONG)
            val value = SsaValue(12, 0, output)
            val call = effect().also { instruction -> instruction.result = output }
            it.clinit.blocks.single().instructions.add(0, call)
            it.construction.setArg(2, RegisterOperand(12, IrType.LONG).also { operand -> operand.ssaValue = value })
            assertNotNull(it.plan())
            // The same value survives outside the hoisted entry and cannot be evaluated twice.
            it.clinit.blocks.single().instructions.add(2, effect(RegisterOperand(12, IrType.LONG).also { operand -> operand.ssaValue = value }))
            assertNull(it.plan())
        }
    }

    @Test fun deepTypesAndAggregateBudgetRejectWithoutRecursiveHashing() {
        var type: IrType = IrType.INT
        repeat(20_000) { type = IrType.array(type) }
        Fixture().also {
            it.cls.method("<init>", argTypes = listOf(IrType.STRING, IrType.INT, type), accessFlags = Flags.PRIVATE)
            assertNull(it.plan())
        }
        val budget = KotlinEnumConstructionPlan.Budget(2000)
        var successes = 0
        repeat(100) { if (Fixture().plan(budget) != null) successes++ }
        assertTrue(successes in 1..99)
        assertTrue(budget.exhausted)
        assertNull(Fixture().plan(budget))
    }

    @Test fun fieldStoresNeedUniqueExactDeclaredTypes() {
        Fixture().also {
            it.cls.fields.add(IrField(it.cls, "value", IrType.LONG, Flags.PRIVATE or Flags.FINAL))
            it.constructor.blocks.single().instructions.add(1, FieldInstruction(FieldRef(it.type, "value", IrType.INT),
                false, true, args = listOf(it.thisValue(), intLit(7))))
            assertNull(it.plan())
        }
        Fixture().also {
            it.cls.fields.add(IrField(it.cls, "value", IrType.LONG, Flags.PRIVATE))
            it.cls.fields.add(IrField(it.cls, "value", IrType.INT, Flags.PRIVATE))
            assertNull(it.plan())
        }
    }

    @Test fun longSignatureKeysChargeSharedBudgetBeforeHashing() {
        val budget = KotlinEnumConstructionPlan.Budget(100_000)
        val longType = IrType.objectType("sample." + "T".repeat(65_000))
        repeat(2) {
            val fixture = Fixture()
            fixture.cls.method("<init>", argTypes = listOf(IrType.STRING, IrType.INT, longType), accessFlags = Flags.PRIVATE)
            assertNull(fixture.plan(budget))
        }
        assertTrue(budget.exhausted)
        repeat(100) { assertFalse(budget.reserve()) }
    }

    @Test fun hiddenPrefixCannotBecomeAnExplicitDelegationArgument() {
        for (useName in listOf(false, true)) Fixture(if (useName) IrType.STRING else IrType.LONG).also { fixture ->
            val method = fixture.cls.method("<init>", argTypes = listOf(IrType.STRING, IrType.INT), accessFlags = Flags.PRIVATE)
            val self = SsaValue(0, 0, reg(0, fixture.type)).also { method.thisArg = it; method.ssaValues.add(it) }
            val name = SsaValue(1, 0, reg(1, IrType.STRING)).also { it.add(AttrFlag.METHOD_ARGUMENT); method.ssaValues.add(it) }
            val ordinal = SsaValue(2, 0, reg(2, IrType.INT)).also { it.add(AttrFlag.METHOD_ARGUMENT); method.ssaValues.add(it) }
            fun use(value: SsaValue, type: IrType) = reg(value.regNum, type).also { it.ssaValue = value }
            val widened = if (useName) use(name, IrType.STRING) else expr(Instruction(IrOpcode.CAST, reg(9, IrType.LONG), listOf(use(ordinal, IrType.INT))))
            method.body(InvokeInstruction(MethodRef(fixture.type, "<init>", IrType.VOID, fixture.constructor.argTypes),
                InvokeKind.DIRECT, args = listOf(use(self, fixture.type), use(name, IrType.STRING), use(ordinal, IrType.INT), widened)), ret())
            assertNull(fixture.plan())
        }
    }

    @Test fun reassignedCoalescedHiddenParameterCannotCreateAnUndeclaredCopy() {
        Fixture().also { fixture ->
            val hidden = fixture.parameter(0).ssaValue!!
            val local = LocalVar().also { it.name = "name"; it.type = IrType.STRING; it.add(AttrFlag.METHOD_ARGUMENT); it.addSsaValue(hidden) }
            val output = reg(hidden.regNum, IrType.STRING)
            val assigned = SsaValue(hidden.regNum, 1, output).also { local.addSsaValue(it) }
            fixture.constructor.ssaValues.add(assigned)
            fixture.constructor.blocks.single().instructions.add(1, Instruction(IrOpcode.CONST, output, listOf(lit(0, IrType.STRING))))
            assertNull(fixture.plan())
        }
    }

    @Test fun synchronizedValuesAreNotConsumed() {
        Fixture().also { fixture ->
            val old = fixture.values
            val replacement = IrMethod(fixture.cls, old.name, old.returnType, old.argTypes, old.accessFlags or 0x20)
            replacement.blocks.addAll(old.blocks)
            fixture.cls.methods[fixture.cls.methods.indexOf(old)] = replacement
            assertNull(fixture.plan())
        }
    }

    @Test fun unusedStringResolutionIsNotConsumed() {
        Fixture().also { fixture ->
            fixture.constructor.blocks.single().instructions.add(0, ConstStringInstruction("must resolve"))
            assertNull(fixture.plan())
        }
    }

    @Test fun failingEntryExpressionRetainsHealthyMembersWithDiagnostic() {
        val fixture = Fixture()
        val owner = irClass("sample.Library", root = fixture.cls.root)
        owner.method("use", returnType = IrType.LONG, argTypes = listOf(IrType.OBJECT), accessFlags = Flags.PUBLIC or Flags.STATIC)
        repeat(400) { index ->
            owner.method("use", returnType = IrType.LONG,
                argTypes = listOf(IrType.objectType("sample." + "T".repeat(30_000) + index)), accessFlags = Flags.PUBLIC or Flags.STATIC)
        }
        fixture.construction.setArg(2, expr(InvokeInstruction(MethodRef(IrType.objectType(owner.fullName), "use", IrType.LONG,
            listOf(IrType.OBJECT)), InvokeKind.STATIC, args = listOf(lit(0, IrType.OBJECT)))))
        fixture.cls.method("healthy", returnType = IrType.INT, accessFlags = Flags.PUBLIC or Flags.STATIC) { body(ret(intLit(7))) }
        assertNotNull(fixture.plan())
        val output = generate(fixture.cls)
        assertTrue(output.contains("JADXMP ERROR"), output)
        assertTrue(output.contains("return 7"), output)
    }

    @Test fun regeneratedEntryNameCannotAlsoBeAUserArgument() {
        val fixture = Fixture(IrType.STRING)
        val name = (fixture.construction.getArg(0) as InstructionOperand).instruction
        fixture.construction.setArg(2, expr(Instruction(IrOpcode.MOVE, args = listOf(expr(name)))))
        assertNull(fixture.plan())
    }

    @Test fun ordinaryStringResolutionCannotCrossAnArgumentCall() {
        val fixture = Fixture(IrType.STRING)
        val output = reg(20, IrType.STRING)
        val value = SsaValue(20, 0, output)
        fixture.clinit.blocks.single().instructions.add(0, ConstStringInstruction("held", output))
        val read = reg(20, IrType.STRING).also { it.ssaValue = value }
        val call = InvokeInstruction(MethodRef(IrType.objectType("sample.Trace"), "text", IrType.STRING, emptyList()), InvokeKind.STATIC)
        fixture.construction.setArg(2, expr(InvokeInstruction(MethodRef(IrType.objectType("sample.Trace"), "combine", IrType.STRING,
            listOf(IrType.STRING, IrType.STRING)), InvokeKind.STATIC, args = listOf(expr(call), read))))
        assertNull(fixture.plan())
    }

    @Test fun backingArrayAccessAndLateEntryStoresCannotLoseTheirMembers() {
        Fixture().also { fixture ->
            val backing = fixture.cls.fields.single { it.name == "\$VALUES" }
            fixture.cls.method("backing", returnType = backing.type, accessFlags = Flags.PUBLIC or Flags.STATIC) { body(ret(fixture.read(backing))) }
            assertNull(fixture.plan())
        }
        Fixture().also { fixture ->
            val entry = fixture.cls.fields.single { it.name == "FIRST" }
            fixture.clinit.blocks.single().instructions.add(2,
                FieldInstruction(FieldRef(fixture.type, entry.name, entry.type), true, true, args = listOf(lit(0, fixture.type))))
            assertNull(fixture.plan())
        }
        Fixture().also { fixture ->
            val entry = fixture.cls.fields.single { it.name == "FIRST" }
            fixture.construction.setArg(2, fixture.read(entry))
            assertNull(fixture.plan())
        }
    }

    private fun effect(vararg args: Operand) = InvokeInstruction(
        MethodRef(IrType.objectType("sample.Trace"), "effect", IrType.LONG, args.map { it.type }),
        InvokeKind.STATIC, args = args.toList())

    private class Fixture(userType: IrType = IrType.LONG) {
        val cls = irClass("sample.Choice", Flags.PUBLIC or Flags.FINAL or Flags.ENUM, IrType.objectType("java.lang.Enum"))
        val type = IrType.objectType(cls.fullName)
        private val array = IrType.array(type)
        private val entry = IrField(cls, "FIRST", type, Flags.PUBLIC or Flags.STATIC or Flags.FINAL or Flags.ENUM)
        private val valuesField = IrField(cls, "\$VALUES", array, Flags.PRIVATE or Flags.STATIC or Flags.FINAL)
        val constructor = cls.method("<init>", argTypes = listOf(IrType.STRING, IrType.INT, userType), accessFlags = Flags.PRIVATE)
        private val arguments = constructor.argTypes.mapIndexed { index, type ->
            SsaValue(index + 1, 0, reg(index + 1, type)).also { it.add(AttrFlag.METHOD_ARGUMENT); constructor.ssaValues.add(it) }
        }
        private val receiver = SsaValue(0, 0, reg(0, type)).also { constructor.thisArg = it; constructor.ssaValues.add(it) }
        fun thisValue() = reg(0, type).also { it.ssaValue = receiver }
        fun parameter(index: Int) = reg(index + 1, constructor.argTypes[index]).also { it.ssaValue = arguments[index] }
        val call = InvokeInstruction(MethodRef(IrType.objectType("java.lang.Enum"), "<init>", IrType.VOID,
            listOf(IrType.STRING, IrType.INT)), InvokeKind.DIRECT, args = listOf(thisValue(), parameter(0), parameter(1)))
        val construction = InvokeInstruction(MethodRef(type, "<init>", IrType.VOID, constructor.argTypes), InvokeKind.DIRECT,
            args = listOf(expr(ConstStringInstruction("FIRST")), intLit(0), if (userType == IrType.STRING) expr(ConstStringInstruction("value")) else lit(7, IrType.LONG)), opcode = IrOpcode.CONSTRUCTOR)
        val clinit = cls.method("<clinit>", accessFlags = Flags.STATIC)
        val values = cls.method("values", returnType = array, accessFlags = Flags.PUBLIC or Flags.STATIC)
        init {
            cls.fields.addAll(listOf(entry, valuesField))
            constructor.body(call, Instruction(IrOpcode.RETURN))
            clinit.body(put(entry, expr(construction)), put(valuesField, expr(TypeInstruction(IrOpcode.FILLED_NEW_ARRAY, array,
                args = listOf(read(entry))))), Instruction(IrOpcode.RETURN))
            values.body(Instruction(IrOpcode.RETURN, args = listOf(expr(TypeInstruction(IrOpcode.CHECK_CAST, array,
                args = listOf(expr(InvokeInstruction(MethodRef(array, "clone", IrType.OBJECT, emptyList()),
                    InvokeKind.VIRTUAL, args = listOf(read(valuesField))))))))))
            val valueOf = cls.method("valueOf", returnType = type, argTypes = listOf(IrType.STRING), accessFlags = Flags.PUBLIC or Flags.STATIC)
            val argument = SsaValue(0, 0, reg(0, IrType.STRING)).also { it.add(AttrFlag.METHOD_ARGUMENT); valueOf.ssaValues.add(it) }
            val enumType = IrType.objectType("java.lang.Enum")
            val invoke = InvokeInstruction(MethodRef(enumType, "valueOf", enumType, listOf(IrType.CLASS, IrType.STRING)),
                InvokeKind.STATIC, args = listOf(expr(TypeInstruction(IrOpcode.CONST_CLASS, type)), reg(0, IrType.STRING).also { it.ssaValue = argument }))
            valueOf.body(Instruction(IrOpcode.RETURN, args = listOf(expr(TypeInstruction(IrOpcode.CHECK_CAST, type, args = listOf(expr(invoke)))))))
        }
        fun useEntryLocals(count: Int): Pair<TypeInstruction, List<SsaValue>> {
            val statements = clinit.blocks.single().instructions
            statements.clear()
            val values = mutableListOf<SsaValue>()
            repeat(count) { index ->
                val field = if (index == 0) entry else IrField(cls, "SECOND", type,
                    Flags.PUBLIC or Flags.STATIC or Flags.FINAL or Flags.ENUM).also(cls.fields::add)
                val call = if (index == 0) construction else InvokeInstruction(construction.methodRef,
                    InvokeKind.DIRECT, args = listOf(expr(ConstStringInstruction(field.name)), intLit(index), lit(8, IrType.LONG)),
                    opcode = IrOpcode.CONSTRUCTOR)
                val result = reg(20 + index, type)
                call.result = result
                val value = SsaValue(result.regNum, 0, result)
                values.add(value)
                statements.add(call)
                statements.add(put(field, reg(value.regNum, type).also { it.ssaValue = value }))
            }
            val contents = TypeInstruction(IrOpcode.FILLED_NEW_ARRAY, array,
                args = values.map { value -> reg(value.regNum, type).also { it.ssaValue = value } })
            statements.add(put(valuesField, expr(contents)))
            statements.add(Instruction(IrOpcode.RETURN))
            return contents to values
        }
        private fun put(field: IrField, operand: Operand) = FieldInstruction(FieldRef(type, field.name, field.type), true, true, args = listOf(operand))
        fun read(field: IrField) = expr(FieldInstruction(FieldRef(type, field.name, field.type), true, false))
        fun plan(budget: KotlinEnumConstructionPlan.Budget = KotlinEnumConstructionPlan.Budget()) =
            KotlinEnumConstructionPlan.analyze(cls, cls.fields.filter { it.accessFlags and Flags.ENUM != 0 }, clinit, valuesField, budget)
    }
}
