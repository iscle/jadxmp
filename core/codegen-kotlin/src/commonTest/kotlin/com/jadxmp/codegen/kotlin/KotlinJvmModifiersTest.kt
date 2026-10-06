package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinJvmModifiersTest {
    @Test fun instanceSynchronizedMethodPreservesJvmMonitorEntry() {
        val cls = irClass("sample.Locked")
        cls.method("value", IrType.INT, accessFlags = Flags.PUBLIC or 0x20) { body(ret(intLit(7))) }
        val source = generate(cls)
        assertTrue(source.contains("@KotlinSynchronized"), source)
        assertTrue(source.contains("return 7"), source)
    }

    @Test fun staticSynchronizedMethodUsesDeclaringClassInsteadOfCompanionMonitor() {
        val cls = irClass("sample.Locked")
        cls.method("value", IrType.INT, accessFlags = Flags.PUBLIC or Flags.STATIC or 0x20) { body(ret(intLit(7))) }
        val source = generate(cls)
        assertTrue(source.contains("kotlinSynchronized("), source)
        assertTrue(source.contains("::class.java"), source)
        assertFalse(source.contains("@KotlinSynchronized"), source)
    }

    @Test fun singletonFieldModifiersPreventImplicitFieldReplacement() {
        val cls = irClass("sample.FlaggedObject")
        cls.fields += IrField(cls, "INSTANCE", IrType.objectType(cls.fullName), Flags.PUBLIC or Flags.STATIC or Flags.FINAL or 0x80)
        cls.method("<init>", accessFlags = Flags.PRIVATE) { body(ret()) }
        val source = generate(cls)
        assertFalse(source.contains("object FlaggedObject"), source)
        assertTrue(source.contains("@field:KotlinTransient"), source)
    }

    @Test fun synchronizedEnumSyntheticMethodIsExplicitlyDiagnosed() {
        val cls = irClass("sample.LockedEnum", Flags.PUBLIC or Flags.FINAL or Flags.ENUM)
        cls.method("values", IrType.array(IrType.objectType(cls.fullName)), accessFlags = Flags.PUBLIC or Flags.STATIC or 0x20) { body(ret()) }
        val source = generate(cls)
        assertTrue(source.contains("JADXMP ERROR"), source)
        assertTrue(source.contains("synchronized enum"), source)
    }

    @Test fun declaredSynchronizedDexFlagDoesNotAddAnotherMonitor() {
        val cls = irClass("sample.ExplicitMonitor")
        cls.method("value", IrType.INT, accessFlags = Flags.PUBLIC or 0x20000) { body(ret(intLit(7))) }
        val source = generate(cls)
        assertFalse(source.contains("KotlinSynchronized"), source)
        assertFalse(source.contains("kotlinSynchronized"), source)
    }

    @Test fun volatileAndTransientFlagsTargetBackingFields() {
        val cls = irClass("sample.Fields")
        cls.fields += IrField(cls, "value", IrType.INT, Flags.PUBLIC or 0x40)
        cls.fields += IrField(cls, "scratch", IrType.INT, Flags.PUBLIC or 0x80)
        cls.fields += IrField(cls, "both", IrType.INT, Flags.PUBLIC or Flags.STATIC or 0xc0)
        val source = generate(cls)
        assertTrue(source.contains("@field:KotlinVolatile"), source)
        assertTrue(source.contains("@field:KotlinTransient"), source)
    }
}
