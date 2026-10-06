package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinRawFieldPlanTest {
    @Test fun aliasesDuplicateNamesAndUnknownTypesAreNotExactFieldAbi() {
        val cls = irClass("fields.Names", superType = IrType.OBJECT)
        val renamed = IrField(cls, "original", IrType.INT, Flags.PUBLIC).also(cls.fields::add)
        val duplicate = IrField(cls, "same", IrType.INT, Flags.PUBLIC).also(cls.fields::add)
        cls.fields.add(IrField(cls, "same", IrType.LONG, Flags.PUBLIC))
        val unknown = IrField(cls, "unknown", IrType.UNKNOWN, Flags.PUBLIC).also(cls.fields::add)
        val aliases = AliasMap.of(mapOf(FieldNodeRef(cls.fullName, "original") to "changed"))
        val plan = KotlinRawFieldPlan(KotlinImports("fields", cls, aliases), aliases)
        for (field in listOf(renamed, duplicate, unknown)) assertEquals(KotlinRawFieldPlan.Decision.OUTSIDE_SCOPE, plan.decision(field))
    }

    @Test fun exhaustedStorageKeepsPriorProofsWithoutGrowing() {
        val cls = irClass("fields.Bounded", superType = IrType.OBJECT)
        val field = IrField(cls, "value", IrType.INT, Flags.PUBLIC).also(cls.fields::add)
        val plan = KotlinRawFieldPlan(KotlinImports("fields", cls, AliasMap.EMPTY), AliasMap.EMPTY, entryLimit = 1)
        assertEquals(KotlinRawFieldPlan.Decision.RAW_FIELD, plan.decision(field))
        repeat(1000) { index ->
            assertEquals(KotlinRawFieldPlan.Decision.WORK_LIMIT, plan.decision(IrField(cls, "new$index", IrType.INT, Flags.PUBLIC)))
        }
        assertEquals(1, plan.retainedDecisions)
        assertEquals(KotlinRawFieldPlan.Decision.RAW_FIELD, plan.decision(field))
    }

    @Test fun deepTypesAndLongNamesStopBeforeRenderingOrHashing() {
        val cls = irClass("fields.Bounded", superType = IrType.OBJECT)
        var type: IrType = IrType.INT
        repeat(10_000) { type = IrType.array(type) }
        val field = IrField(cls, "value", type, Flags.PUBLIC).also(cls.fields::add)
        val plan = KotlinRawFieldPlan(KotlinImports("fields", cls, AliasMap.EMPTY), AliasMap.EMPTY, work = 20)
        assertEquals(KotlinRawFieldPlan.Decision.WORK_LIMIT, plan.decision(field))
        val long = IrField(cls, "long".repeat(1000), IrType.INT, Flags.PUBLIC)
        assertEquals(KotlinRawFieldPlan.Decision.WORK_LIMIT, plan.decision(long))
        assertEquals(1, plan.retainedDecisions)
    }
}
