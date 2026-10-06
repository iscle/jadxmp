package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.test.assertEquals
import com.jadxmp.ir.node.IrField

class KotlinPlainInheritanceTest {
    private fun delegate(owner: IrType, types: List<IrType>, arguments: List<Operand>) = InvokeInstruction(
        MethodRef(owner, "<init>", IrType.VOID, types), InvokeKind.DIRECT, null, arguments,
    )

    private fun classes(): Pair<IrClass, IrClass> {
        val base = irClass("a.Base", accessFlags = Flags.PUBLIC, superType = IrType.OBJECT)
        base.method("<init>") { body(ret()) }
        val child = irClass("a.Child", root = base.root, superType = IrType.objectType(base.fullName))
        return base to child
    }

    @Test fun loadedNoArgSuperclassUsesSecondaryHeader() {
        val (base, child) = classes()
        val self = Local(0, IrType.objectType(child.fullName), isThis = true)
        child.method("<init>") { body(delegate(IrType.objectType(base.fullName), emptyList(), listOf(self.ref())), ret()) }
        assertThatCode(generate(child)).containsOne("class Child : Base {")
            .containsOne("constructor() : super() {").doesNotContain("JADXMP ERROR")
    }

    @Test fun everyConstructorMustBeProvenAndEffectsCannotMoveIntoHeader() {
        val (base, child) = classes()
        val self = Local(0, IrType.objectType(child.fullName), isThis = true)
        child.method("<init>") { body(delegate(IrType.objectType(base.fullName), emptyList(), listOf(self.ref())), ret()) }
        child.method("<init>", argTypes = listOf(IrType.INT)) {
            body(staticInvoke(IrType.objectType("a.Effects"), "mark", IrType.VOID, emptyList(), emptyList()),
                delegate(IrType.objectType(base.fullName), emptyList(), listOf(self.ref())), ret())
        }
        assertTrue(KotlinInheritancePlan().constructorDelegations(child).value.isEmpty())
        assertThatCode(generate(child)).contains("JADXMP ERROR").contains("Effects.mark()")
    }

    @Test fun thisDelegationCyclesAndWrongReceiverAreNotReconstructed() {
        val (_, child) = classes()
        val type = IrType.objectType(child.fullName)
        val self = Local(0, type, isThis = true)
        child.method("<init>") { body(delegate(type, emptyList(), listOf(self.ref())), ret()) }
        assertTrue(KotlinInheritancePlan().constructorDelegations(child).value.isEmpty())
        child.methods.clear()
        val other = Local(0, type, name = "this", isParam = true)
        child.method("<init>") { body(delegate(child.superType!!, emptyList(), listOf(other.ref())), ret()) }
        assertTrue(KotlinInheritancePlan().constructorDelegations(child).value.isEmpty())
    }

    @Test fun reassignedParameterDoesNotReferenceBodyLocalFromHeader() {
        val (base, child) = classes()
        base.method("<init>", argTypes = listOf(IrType.INT)) { body(ret()) }
        val self = Local(0, IrType.objectType(child.fullName), isThis = true)
        val value = Local(1, IrType.INT, isParam = true)
        child.method("<init>", argTypes = listOf(IrType.INT)) {
            body(delegate(child.superType!!, listOf(IrType.INT), listOf(self.ref(), value.ref())),
                assign(value.ref(), Instruction(IrOpcode.CONST, args = listOf(intLit(2)))), ret())
        }
        assertTrue(KotlinInheritancePlan().constructorDelegations(child).value.isEmpty())
    }

    @Test fun exactInheritedMethodsAreOverridesButUnrelatedPrivateAndStaticMembersAreNot() {
        val (base, child) = classes()
        base.method("value", IrType.INT, accessFlags = Flags.PUBLIC) { body(ret(intLit(1))) }
        base.method("hidden", IrType.INT, accessFlags = Flags.PRIVATE) { body(ret(intLit(1))) }
        base.method("staticMethod", IrType.INT, accessFlags = Flags.PUBLIC or Flags.STATIC) { body(ret(intLit(1))) }
        child.method("value", IrType.INT, accessFlags = Flags.PUBLIC or Flags.FINAL) { body(ret(intLit(2))) }
        child.method("hidden", IrType.INT) { body(ret(intLit(2))) }
        child.method("staticMethod", IrType.INT) { body(ret(intLit(2))) }
        child.method("value", IrType.INT, argTypes = listOf(IrType.INT)) { body(ret(intLit(2))) }
        assertThatCode(generate(child)).containsOne("final override fun value(): Int")
            .doesNotContain("override fun hidden").doesNotContain("override fun staticMethod")
            .doesNotContain("override fun value(i:")
    }

    @Test fun incompatibleReturnAndVisibilityContractsStayExplicit() {
        val (base, child) = classes()
        base.method("value", IrType.OBJECT, accessFlags = Flags.PUBLIC) { body(ret(lit(0, IrType.OBJECT))) }
        child.method("value", IrType.STRING, accessFlags = Flags.PROTECTED) { body(ret(lit(0, IrType.STRING))) }
        assertThatCode(generate(child)).contains("covariant or incompatible loaded override")
            .contains("loaded override reduces inherited public visibility")
    }
    @Test fun objectSuperclassStillHasItsPrimaryConstructorCall() {
        val (base, child) = classes()
        val type = IrType.objectType(child.fullName)
        child.fields.add(IrField(child, "INSTANCE", type, Flags.PUBLIC or Flags.STATIC or Flags.FINAL))
        val self = Local(0, type, isThis = true)
        child.method("<init>", accessFlags = Flags.PRIVATE) {
            body(delegate(IrType.objectType(base.fullName), emptyList(), listOf(self.ref())), ret())
        }
        assertThatCode(generate(child)).containsOne("object Child : Base() {")
    }

    @Test fun repeatedLongDescriptorsHaveAnAggregatePlanBudget() {
        val base = irClass("a.Base", accessFlags = Flags.PUBLIC)
        val longType = IrType.objectType("a." + "LongName".repeat(4000))
        base.method("match", longType, listOf(longType))
        val cached = KotlinInheritancePlan()
        val child = irClass("a.CachedChild", root = base.root, superType = IrType.objectType(base.fullName))
        val method = child.method("match", longType, listOf(longType))
        val targets = cached.overrideTargets(method)
        repeat(1000) { assertSame(targets, cached.overrideTargets(method)) }
        val plan = KotlinInheritancePlan()
        val limited = (0 until 1000).any { index ->
            val cls = irClass("a.Child$index", root = base.root, superType = IrType.objectType(base.fullName))
            val method = cls.method("match", longType, listOf(longType))
            plan.overrideTargets(method).problem?.contains("limit exceeded") == true
        }
        assertTrue(limited)

    }

    @Test fun constructorPlanLimitKeepsHealthySiblingMethodBody() {
        val (base, child) = classes()
        repeat(200) { index ->
            val type = IrType.objectType("library." + "LongName".repeat(2500) + index)
            base.method("<init>", argTypes = listOf(type)) { body(ret()) }
        }
        val self = Local(0, IrType.objectType(child.fullName), isThis = true)
        child.method("<init>") { body(delegate(child.superType!!, emptyList(), listOf(self.ref())), ret()) }
        child.method("healthy", IrType.INT) { body(ret(intLit(7))) }
        assertThatCode(generate(child)).contains("plain inheritance analysis work/storage limit exceeded")
            .containsOne("fun healthy(): Int").containsOne("return 7")
    }

    @Test fun malformedDeepTypesAreDiagnosedBeforeStructuralHashing() {
        val (base, child) = classes()
        var type: IrType = IrType.INT
        repeat(512) { type = IrType.array(type) }
        base.method("deep", IrType.INT, listOf(type))
        val method = child.method("deep", IrType.INT, listOf(type))
        assertTrue(KotlinInheritancePlan().overrideTargets(method).problem!!.contains("limit exceeded"))
    }

    @Test fun exhaustedAnalysisDoesNotRetainNewFailedPlans() {
        val (base, child) = classes()
        base.method("value", IrType.INT)
        val good = child.method("value", IrType.INT)
        val plan = KotlinInheritancePlan(workLimit = 1000, entryLimit = 20)
        val saved = plan.overrideTargets(good)
        assertEquals(1, saved.value.size)
        val tooLarge = child.method("large", IrType.objectType("a." + "x".repeat(2000)))
        assertTrue(plan.overrideTargets(tooLarge).problem!!.contains("limit exceeded"))
        val retained = plan.retainedPlanCount
        repeat(100) { index ->
            val next = irClass("a.Next$index", root = base.root, superType = child.superType)
            assertTrue(plan.constructorDelegations(next).problem != null)
            assertTrue(plan.overrideTargets(next.method("value", IrType.INT)).problem != null)
        }
        assertEquals(retained, plan.retainedPlanCount)
        assertSame(saved, plan.overrideTargets(good))
    }

    @Test fun superclassObjectArgumentAllowsOnlyProvenReferenceWidening() {
        for (type in listOf(IrType.STRING, IrType.array(IrType.INT))) {
            val (base, child) = classes()
            base.method("<init>", argTypes = listOf(IrType.OBJECT)) { body(ret()) }
            val self = Local(0, IrType.objectType(child.fullName), isThis = true)
            val value = Local(1, type, isParam = true)
            child.method("<init>", argTypes = listOf(type)) {
                body(delegate(child.superType!!, listOf(IrType.OBJECT), listOf(self.ref(), value.ref())), ret())
            }
            assertEquals(1, KotlinInheritancePlan().constructorDelegations(child).value.size)
            assertThatCode(generate(child)).contains(" as Any?) {").doesNotContain("JADXMP ERROR")
        }
    }

    @Test fun constructorDowncastsUnrelatedReferencesAndEffectfulArgumentsRemainUnsupported() {
        for (actual in listOf(IrType.OBJECT, IrType.objectType("a.Unrelated"))) {
            val (base, child) = classes()
            base.method("<init>", argTypes = listOf(IrType.STRING)) { body(ret()) }
            val self = Local(0, IrType.objectType(child.fullName), isThis = true)
            val value = Local(1, actual, isParam = true)
            child.method("<init>", argTypes = listOf(actual)) {
                body(delegate(child.superType!!, listOf(IrType.STRING), listOf(self.ref(), value.ref())), ret())
            }
            assertTrue(KotlinInheritancePlan().constructorDelegations(child).value.isEmpty())
        }
        val (base, child) = classes()
        base.method("<init>", argTypes = listOf(IrType.OBJECT)) { body(ret()) }
        val self = Local(0, IrType.objectType(child.fullName), isThis = true)
        child.method("<init>") {
            body(delegate(child.superType!!, listOf(IrType.OBJECT), listOf(self.ref(),
                expr(staticInvoke(IrType.objectType("a.Effects"), "next", IrType.STRING, emptyList(), emptyList())))), ret())
        }
        assertTrue(KotlinInheritancePlan().constructorDelegations(child).value.isEmpty())
    }

}
