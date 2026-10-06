package com.jadxmp.codegen.java

import com.jadxmp.codegen.AliasMap
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JavaOwnedStaticFieldsTest {
    @Test fun longCachedLookupsAreChargedAndTerminalFailuresAreNotRetained() {
        val cls = irClass("Owner")
        val name = "long".repeat(25)
        cls.fields.add(IrField(cls, name, IrType.INT, Flags.PUBLIC or Flags.STATIC))
        val fields = JavaOwnedStaticFields(AliasMap.EMPTY, work = 1000)
        val ref = FieldRef(IrType.objectType(cls.fullName), name, IrType.INT)
        assertIs<JavaOwnedStaticFields.Result.Owned>(fields.lookup(cls, ref))
        repeat(20) { fields.lookup(cls, ref) }
        assertEquals(JavaOwnedStaticFields.Result.Unavailable, fields.lookup(cls, ref))
        repeat(1000) {
            val other = irClass("Other$it")
            assertEquals(JavaOwnedStaticFields.Result.Unavailable, fields.lookup(other, FieldRef(IrType.objectType(other.fullName), "value", IrType.INT)))
        }
        assertEquals(1, fields.retainedContextCount)
    }

    @Test fun foreignAndFinalFieldsAreOutsideTheOwnedMutableProof() {
        val cls = irClass("Owner")
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC or Flags.STATIC or Flags.FINAL))
        val fields = JavaOwnedStaticFields(AliasMap.EMPTY)
        assertEquals(JavaOwnedStaticFields.Result.NotApplicable, fields.lookup(cls, FieldRef(IrType.objectType("Other"), "value", IrType.INT)))
        assertEquals(JavaOwnedStaticFields.Result.NotApplicable, fields.lookup(cls, FieldRef(IrType.objectType(cls.fullName), "value", IrType.INT)))
    }
    @Test fun cachedLongOwnerComparisonConsumesWorkBeforeEquality() {
        val cls = irClass("Owner".repeat(100))
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC or Flags.STATIC))
        val fields = JavaOwnedStaticFields(AliasMap.EMPTY, work = 3000)
        val ref = FieldRef(IrType.objectType(cls.fullName.toCharArray().concatToString()), "value", IrType.INT)
        assertIs<JavaOwnedStaticFields.Result.Owned>(fields.lookup(cls, ref))
        repeat(5) { fields.lookup(cls, ref) }
        assertEquals(JavaOwnedStaticFields.Result.Unavailable, fields.lookup(cls, ref))
    }

    @Test fun rejectedQualifierCachesLongSuperclassProof() {
        val cls = irClass("Owner", superType = IrType.objectType("unknown.Base".repeat(30)))
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC or Flags.STATIC))
        val fields = JavaOwnedStaticFields(AliasMap.EMPTY, work = 1000)
        repeat(1000) { kotlin.test.assertNull(fields.simpleQualifier(cls)) }
        assertIs<JavaOwnedStaticFields.Result.Owned>(fields.lookup(cls,
            FieldRef(IrType.objectType(cls.fullName), "value", IrType.INT)))
    }

}
