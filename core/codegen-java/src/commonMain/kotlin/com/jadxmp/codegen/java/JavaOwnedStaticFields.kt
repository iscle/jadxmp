package com.jadxmp.codegen.java

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.OwnedFieldTypes
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.type.IrType

/** Bounded per-output names for exact owned mutable static fields; no foreign-resolution claims. */
internal class JavaOwnedStaticFields(private val aliases: AliasMap, private var work: Long = 10_000_000) {
    sealed interface Result {
        data object NotApplicable : Result
        data object Unavailable : Result
        data class Owned(val name: String) : Result
    }
    private data class Field(val type: IrType, val name: String, val eligible: Boolean)
    private val fields = mutableMapOf<IrClass, Map<String, Field?>>()
    private val qualifiers = mutableMapOf<IrClass, String?>()
    internal val retainedContextCount: Int get() = fields.size

    fun lookup(context: IrClass, reference: FieldRef): Result {
        val owner = (reference.declaringType as? IrType.Object)?.className ?: return Result.NotApplicable
        if (!charge(owner.length.toLong() + context.fullName.length + 1)) return Result.Unavailable
        if (owner != context.fullName) return Result.NotApplicable
        if (!charge(reference.name.length.toLong() + 1)) return Result.Unavailable
        val table = fields[context] ?: build(context)?.also { fields[context] = it } ?: return Result.Unavailable
        val field = table[reference.name] ?: return Result.NotApplicable
        if (!field.eligible) return Result.NotApplicable
        when (OwnedFieldTypes.equal(reference.type, field.type, ::charge)) {
            null -> return Result.Unavailable
            false -> return Result.NotApplicable
            true -> Unit
        }
        return Result.Owned(field.name)
    }

    /** The current top-level declaration's type name, with no inherited/lexical type scopes. */
    fun simpleQualifier(context: IrClass): String? {
        if (context in qualifiers) return qualifiers[context]
        if (!charge(1)) return null
        fun reject(): String? {
            if (work > 0) qualifiers[context] = null
            return null
        }
        if (context.outerClass != null || context.interfaces.isNotEmpty()) return reject()
        val parent = context.superType
        if (parent != null && OwnedFieldTypes.equal(parent, IrType.OBJECT, ::charge) != true) return reject()
        // SourceSimpleName may disambiguate top-level classes. Charge its existing worst-case scan.
        val count = context.root.classes.size.toLong()
        for (cls in context.root.classes) {
            if (!charge((cls.fullName.length.toLong() + 1) * count)) return null
            val aliasLength = aliases.aliasOf(ClassNodeRef(cls.fullName))?.length ?: 0
            if (!charge(aliasLength.toLong() * count)) return null
        }
        val name = JavaSourceName.sourceSimpleName(context, aliases)
        for (nested in context.innerClasses) {
            if (!charge(nested.fullName.length.toLong() + name.length + 1)) return null
            val aliasLength = aliases.aliasOf(ClassNodeRef(nested.fullName))?.length ?: 0
            if (!charge(aliasLength.toLong())) return null
            if (JavaSourceName.sourceSimpleName(nested, aliases) == name) return reject()
        }
        qualifiers[context] = name
        return name
    }

    private fun build(context: IrClass): Map<String, Field?>? {
        if (!charge(context.fields.size.toLong() * context.fields.size + 1)) return null
        for (field in context.fields) {
            if (!charge((context.fullName.length.toLong() + field.name.length) * context.fields.size)) return null
            val alias = aliases.aliasOf(FieldNodeRef(context.fullName, field.name))
            if (alias != null && !charge(alias.length.toLong() * context.fields.size)) return null
        }
        val names = JavaMemberAliases.buildFieldAliases(context, aliases)
        val table = mutableMapOf<String, Field?>()
        for (field in context.fields) {
            val type = field.type
            val name = names.getValue(field)
            if (!charge(field.name.length.toLong() + name.length + 1)) return null
            val eligible = field.accessFlags and 0x0008 != 0 && field.accessFlags and 0x0010 == 0 && field.constValue == null
            table[field.name] = if (field.name in table) null else Field(type, name, eligible)
        }
        return table
    }

    private fun charge(amount: Long): Boolean {
        if (amount > work) { work = 0; return false }
        work -= amount
        return true
    }
}
