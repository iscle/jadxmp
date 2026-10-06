package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinPublicFieldAbiTest {
    @Test fun publicMutableInstanceAndCompanionFieldsUseJvmField() {
        val cls = irClass("fields.Fields", superType = IrType.OBJECT)
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC))
        cls.fields.add(IrField(cls, "total", IrType.LONG, Flags.PUBLIC or Flags.STATIC or 0x0040))
        cls.fields.add(IrField(cls, "payload", IrType.OBJECT, Flags.PUBLIC or 0x0080))
        val code = generate(cls)
        assertTrue(code.contains("import kotlin.jvm.JvmField as KotlinJvmField"), code)
        kotlin.test.assertEquals(3, Regex("@field:KotlinJvmField\\b").findAll(code).count(), code)
        assertTrue(code.contains("@field:KotlinVolatile"), code)
        assertTrue(code.contains("@field:KotlinTransient"), code)
    }

    @Test fun privateProtectedPackageFinalAndSyntheticFieldsAreNotNewAbiClaims() {
        val cls = irClass("fields.Excluded", superType = IrType.OBJECT)
        for ((index, flags) in listOf(Flags.PRIVATE, Flags.PROTECTED, 0, Flags.PUBLIC or Flags.FINAL,
            Flags.PUBLIC or 0x1000, Flags.PUBLIC or 0x4000).withIndex()) {
            cls.fields.add(IrField(cls, "f$index", IrType.INT, flags))
        }
        val code = generate(cls)
        assertFalse(code.contains("kotlin.jvm.JvmField"), code)
    }

    @Test fun incompleteAncestorCannotCaptureNewAnnotationAlias() {
        val cls = irClass("fields.Unknown", superType = IrType.objectType("absent.Base"))
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC))
        val code = generate(cls)
        // Existing uncovered field ABI stays visible in the readiness evidence; do not insert
        // an annotation whose identity could be captured by uninspected inherited types.
        assertFalse(code.contains("kotlin.jvm.JvmField"), code)
    }

    @Test fun loadedInheritedAnnotationTypeAndMemberNamesAreReserved() {
        val base = irClass("fields.Base", superType = IrType.OBJECT)
        val shadow = irClass("fields.Base$" + "KotlinJvmField", root = base.root, superType = IrType.OBJECT)
        shadow.outerClass = base
        base.innerClasses.add(shadow)
        val cls = irClass("fields.Fields", root = base.root, superType = IrType.objectType(base.fullName))
        cls.fields.add(IrField(cls, "value", IrType.INT, Flags.PUBLIC))
        cls.fields.add(IrField(cls, "KotlinJvmField2", IrType.INT, Flags.PUBLIC))
        val code = generate(cls)
        assertTrue(code.contains("import kotlin.jvm.JvmField as KotlinJvmField3"), code)
        assertTrue(code.contains("@field:KotlinJvmField3"), code)
    }
    @Test fun generatedInstancePropertyHidingStaysOutsideFirstProjection() {
        val base = irClass("fields.Base", superType = IrType.OBJECT)
        base.fields.add(IrField(base, "value", IrType.INT, Flags.PUBLIC))
        val cls = irClass("fields.Child", root = base.root, superType = IrType.objectType(base.fullName))
        val hidden = IrField(cls, "value", IrType.INT, Flags.PUBLIC).also(cls.fields::add)
        val ordinary = IrField(cls, "other", IrType.INT, Flags.PUBLIC).also(cls.fields::add)
        val plan = KotlinRawFieldPlan(KotlinImports("fields", cls, com.jadxmp.codegen.AliasMap.EMPTY), com.jadxmp.codegen.AliasMap.EMPTY)
        kotlin.test.assertEquals(KotlinRawFieldPlan.Decision.OUTSIDE_SCOPE, plan.decision(hidden))
        kotlin.test.assertEquals(KotlinRawFieldPlan.Decision.RAW_FIELD, plan.decision(ordinary))
    }

}
