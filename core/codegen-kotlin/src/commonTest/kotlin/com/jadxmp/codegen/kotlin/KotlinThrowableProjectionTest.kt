package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.jadxmp.ir.node.IrField
import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef

class KotlinThrowableProjectionTest {
    @Test fun exactPlatformConstructorAndGetterUseKotlinSurface() {
        val cls = irClass("a.Failure", superType = IrType.objectType("java.lang.Exception"))
        val self = Local(0, IrType.objectType(cls.fullName), isThis = true)
        cls.method("<init>") {
            body(InvokeInstruction(MethodRef(cls.superType!!, "<init>", IrType.VOID, emptyList()),
                InvokeKind.DIRECT, null, listOf(self.ref())), ret())
        }
        cls.method("getMessage", IrType.STRING) {
            body(ret(InstructionOperand(InvokeInstruction(MethodRef(cls.superType!!, "getMessage", IrType.STRING, emptyList()),
                InvokeKind.SUPER, null, listOf(self.ref())))))
        }
        assertThatCode(generate(cls)).contains("constructor() : super()")
            .contains("override val message: String?").contains("get() {")
            .contains("return super.message").doesNotContain("JADXMP ERROR")
    }

    @Test fun unknownExternalConstructorAndUnrelatedGetterRemainUnchanged() {
        val cls = irClass("a.Unknown", superType = IrType.objectType("external.Unknown"))
        val self = Local(0, IrType.objectType(cls.fullName), isThis = true)
        cls.method("<init>") {
            body(InvokeInstruction(MethodRef(cls.superType!!, "<init>", IrType.VOID, emptyList()),
                InvokeKind.DIRECT, null, listOf(self.ref())), ret())
        }
        cls.method("getMessage", IrType.STRING) { body(ret(lit(0, IrType.STRING))) }
        assertTrue(KotlinInheritancePlan().constructorDelegations(cls).value.isEmpty())
        assertThatCode(generate(cls)).contains("fun getMessage()").doesNotContain("val message")
    }

    @Test fun constructorProofStillRejectsEffectsAndUnknownDescriptors() {
        for (arguments in listOf(emptyList(), listOf(IrType.INT))) {
            val cls = irClass("a.Failure", superType = IrType.objectType("java.lang.Exception"))
            val self = Local(0, IrType.objectType(cls.fullName), isThis = true)
            cls.method("<init>") {
                val call = InvokeInstruction(MethodRef(cls.superType!!, "<init>", IrType.VOID, arguments),
                    InvokeKind.DIRECT, null, listOf(self.ref()) + arguments.map { intLit(1) })
                if (arguments.isEmpty()) body(staticInvoke(IrType.objectType("a.Effects"), "mark", IrType.VOID,
                    emptyList(), emptyList()), call, ret()) else body(call, ret())
            }
            assertTrue(KotlinInheritancePlan().constructorDelegations(cls).value.isEmpty())
            assertThatCode(generate(cls)).contains("constructor delegation not reconstructed")
        }
    }

    @Test fun propertyAndAliasCollisionsAreExplicitWithoutLosingHealthySiblings() {
        val cls = irClass("a.Failure", superType = IrType.objectType("java.lang.Exception"))
        cls.fields.add(IrField(cls, "message", IrType.STRING, Flags.PUBLIC))
        cls.method("getMessage", IrType.STRING) { body(ret(lit(0, IrType.STRING))) }
        cls.method("healthy", IrType.INT) { body(ret(intLit(7))) }
        assertThatCode(generate(cls)).contains("Throwable.message property conflicts")
            .contains("fun healthy").contains("return 7")
        cls.fields.clear()
        val rename = AliasMap.of(mapOf(MethodNodeRef(cls.fullName, "getMessage", emptyList()) to "different"))
        assertThatCode(KotlinCodeGenerator().generate(cls, rename).code).contains("renamed or incompatible Throwable")
        cls.fields.add(IrField(cls, "other", IrType.STRING, Flags.PUBLIC))
        val fieldRename = AliasMap.of(mapOf(FieldNodeRef(cls.fullName, "other") to "`message`"))
        assertThatCode(KotlinCodeGenerator().generate(cls, fieldRename).code).contains("Throwable.message property conflicts")
    }

    @Test fun loadedOwnerPrecedesKnownPlatformAndCyclesNeverProveLineage() {
        val shadow = irClass("java.lang.Exception", superType = IrType.OBJECT)
        val child = irClass("a.Failure", root = shadow.root, superType = IrType.objectType(shadow.fullName))
        val plan = KotlinThrowableProjection(shadow.root)
        assertFalse(plan.isThrowable(IrType.objectType(child.fullName)))
        val cycle = irClass("a.Cycle", root = shadow.root, superType = IrType.objectType("a.Cycle"))
        assertFalse(plan.isThrowable(IrType.objectType(cycle.fullName)))
    }

    @Test fun boundedProofDoesNotRetainFailuresAndChargesLongAliasesBeforeSanitizing() {
        val cls = irClass("a.Failure", superType = IrType.objectType("java.lang.Exception"))
        val method = cls.method("getMessage", IrType.STRING) { body(ret(lit(0, IrType.STRING))) }
        cls.fields.add(IrField(cls, "other", IrType.STRING, Flags.PUBLIC))
        val aliases = AliasMap.of(mapOf(FieldNodeRef(cls.fullName, "other") to "x".repeat(10_000)))
        val plan = KotlinThrowableProjection(cls.root, aliases, remaining = 100)
        assertFailsWith<IllegalStateException> { plan.declaration(method) }
        val before = plan.retainedProofCount
        repeat(1000) { assertFailsWith<IllegalStateException> { plan.declaration(method) } }
        assertEquals(before, plan.retainedProofCount)
    }


    @Test fun rawSynchronizedAndAbstractGetterModifiersApplyToAccessors() {
        val cls = irClass("a.Failure", superType = IrType.objectType("java.lang.Exception"), accessFlags = Flags.PUBLIC or 0x400)
        cls.method("getMessage", IrType.STRING, accessFlags = Flags.PUBLIC or KotlinModifiers.SYNCHRONIZED) {
            body(ret(lit(0, IrType.STRING)))
        }
        assertThatCode(generate(cls)).contains("@get:KotlinSynchronized").contains("override val message")
        cls.methods.clear()
        cls.method("getMessage", IrType.STRING, accessFlags = Flags.PUBLIC or 0x400)
        assertThatCode(generate(cls)).contains("abstract override val message: String?").doesNotContain("get()")
    }


    @Test fun previouslyProvenDeclarationsSurviveOtherWorkExhaustion() {
        val cls = irClass("a.Failure", superType = IrType.objectType("java.lang.Exception"))
        val method = cls.method("getMessage", IrType.STRING) { body(ret(lit(0, IrType.STRING))) }
        val plan = KotlinThrowableProjection(cls.root, remaining = 300)
        assertTrue(plan.declaration(method))
        assertFailsWith<IllegalStateException> { plan.isThrowable(IrType.objectType("x".repeat(1000))) }
        val retained = plan.retainedProofCount
        repeat(1000) {
            assertTrue(plan.declaration(method))
            assertFailsWith<IllegalStateException> { plan.isThrowable(IrType.objectType("x.$it")) }
        }
        assertEquals(retained, plan.retainedProofCount)
    }


    @Test fun repeatedLongOwnerAliasLookupsAreChargedBeforeHashing() {
        for (fields in listOf(false, true)) {
            val cls = irClass("a." + "x".repeat(200), superType = IrType.objectType("java.lang.Exception"))
            val method = cls.method("getMessage", IrType.STRING) { body(ret(lit(0, IrType.STRING))) }
            repeat(10) { index ->
                if (fields) cls.fields.add(IrField(cls, "f$index", IrType.INT, Flags.PUBLIC))
                else cls.method("f$index", IrType.INT) { body(ret(intLit(1))) }
            }
            val aliases = AliasMap.of(mapOf(FieldNodeRef("other.Class", "unrelated") to "renamed"))
            val plan = KotlinThrowableProjection(cls.root, aliases, remaining = 800)
            assertFailsWith<IllegalStateException> { plan.declaration(method) }
        }
    }

    @Test fun nullableLoadedOwnerCallIsExplicitUntilJvmLinkageCanBePreserved() {
        val failure = irClass("a.Failure", superType = IrType.objectType("java.lang.Exception"))
        val cls = irClass("a.Caller", root = failure.root)
        val value = Local(0, IrType.objectType(failure.fullName), isParam = true)
        cls.method("read", IrType.STRING, argTypes = listOf(IrType.objectType(failure.fullName))) {
            body(ret(InstructionOperand(InvokeInstruction(MethodRef(IrType.objectType(failure.fullName), "getMessage", IrType.STRING, emptyList()),
                InvokeKind.VIRTUAL, null, listOf(value.ref())))))
        }
        cls.method("healthy", IrType.INT) { body(ret(intLit(7))) }
        assertThatCode(generate(cls)).contains("nullable loaded-owner Throwable call cannot preserve JVM linkage")
            .contains("fun healthy").contains("return 7")
    }

}
