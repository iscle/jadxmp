package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.OwnedFieldTypes
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.type.TypeKind

/** Per-output proof for the initial raw-public-mutable-field projection, shared by both passes. */
internal class KotlinRawFieldPlan(
    private val imports: KotlinImports,
    private val aliases: AliasMap,
    private var work: Long = 1_000_000,
    private val entryLimit: Int = 100_000,
) {
    enum class Decision { RAW_FIELD, OUTSIDE_SCOPE, INCOMPLETE_NAME_SCOPE, WORK_LIMIT }
    private val decisions = mutableMapOf<IrField, Decision>()
    private val duplicateNames = mutableMapOf<IrClass, Set<String>>()
    private val inheritedNames = mutableMapOf<IrClass, Set<String>>()
    private var exhausted = false
    internal val retainedDecisions: Int get() = decisions.size

    fun decision(field: IrField): Decision {
        decisions[field]?.let { return it }
        if (exhausted || decisions.size >= entryLimit) { exhausted = true; return Decision.WORK_LIMIT }
        val result = try { prove(field) } catch (_: Limit) { exhausted = true; Decision.WORK_LIMIT }
        decisions[field] = result
        return result
    }

    private fun prove(field: IrField): Decision {
        charge(1)
        val allowed = KotlinModifiers.PUBLIC or KotlinModifiers.STATIC or KotlinModifiers.VOLATILE or KotlinModifiers.TRANSIENT
        if (field.accessFlags and KotlinModifiers.PUBLIC == 0 || field.accessFlags and allowed.inv() != 0 ||
            field.constValue != null) return Decision.OUTSIDE_SCOPE
        // No generic/unknown/void descriptor or lateinit synthesis enters this first projection.
        if ((field.type as? IrType.Primitive)?.kind == TypeKind.VOID || OwnedFieldTypes.equal(field.type, field.type, ::charge) != true) {
            return Decision.OUTSIDE_SCOPE
        }
        val cls = field.declaringClass
        charge(cls.fullName.length.toLong() + field.name.length + 1)
        val name = KotlinMemberAliases.aliasOf(field, aliases)
        charge(name.length.toLong() + field.name.length + 1)
        if (name.removeSurrounding("`") != field.name) return Decision.OUTSIDE_SCOPE
        val duplicates = duplicateNames[cls] ?: findDuplicateNames(cls).also { duplicateNames[cls] = it }
        charge(field.name.length.toLong() + 1)
        if (field.name in duplicates) return Decision.OUTSIDE_SCOPE
        // An inherited annotation TYPE can capture even an import alias. Alias allocation reserves
        // all loaded names, while incomplete ancestry cannot justify adding the intrinsic annotation.
        if (!imports.hasCompleteNameScope(cls)) return Decision.INCOMPLETE_NAME_SCOPE
        if (field.accessFlags and KotlinModifiers.STATIC == 0) {
            val inherited = inheritedNames[cls] ?: inheritedInstanceProperties(cls).also { inheritedNames[cls] = it }
            charge(name.length.toLong() + 1)
            if (name in inherited) return Decision.OUTSIDE_SCOPE
        }
        return Decision.RAW_FIELD
    }

    private fun inheritedInstanceProperties(cls: IrClass): Set<String> {
        val names = hashSetOf<String>()
        val pending = ArrayDeque<IrClass>()
        val seen = mutableSetOf<IrClass>()
        fun enqueue(owner: IrClass, type: IrType?) {
            val name = (type as? IrType.Object)?.className ?: return
            charge(name.length.toLong() + 1)
            if (name == "java.lang.Object") return
            owner.root.findClass(name)?.let(pending::add)
        }
        fun parents(owner: IrClass) {
            charge(owner.interfaces.size.toLong() + 1)
            enqueue(owner, owner.superType)
            for (type in owner.interfaces) enqueue(owner, type)
        }
        parents(cls)
        while (pending.isNotEmpty()) {
            charge(1)
            val parent = pending.removeLast()
            if (!seen.add(parent)) continue
            charge(parent.fields.size.toLong() + 1)
            for (field in parent.fields) {
                if (field.accessFlags and KotlinModifiers.STATIC != 0) continue
                charge(parent.fullName.length.toLong() + field.name.length + 1)
                val name = KotlinMemberAliases.aliasOf(field, aliases)
                charge(name.length.toLong() + 1)
                names.add(name)
            }
            parents(parent)
        }
        return names
    }

    private fun findDuplicateNames(cls: IrClass): Set<String> {
        charge(cls.fields.size.toLong() + 1)
        val seen = hashSetOf<String>()
        val duplicates = hashSetOf<String>()
        for (field in cls.fields) {
            charge(2 * field.name.length.toLong() + 1)
            if (!seen.add(field.name)) duplicates.add(field.name)
        }
        return duplicates
    }

    private fun charge(amount: Long): Boolean {
        if (amount > work) { work = 0; throw Limit() }
        work -= amount
        return true
    }

    private class Limit : RuntimeException()
    companion object { const val ANNOTATION = "kotlin.jvm.JvmField" }
}
