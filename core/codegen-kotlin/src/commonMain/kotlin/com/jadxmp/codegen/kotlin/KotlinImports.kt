package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.codegen.ImportCollector
import com.jadxmp.codegen.NameGenerator
import com.jadxmp.codegen.OwnedFieldTypes
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.type.IrType

/** Collision-safe imports for JVM type projections and generated Kotlin intrinsics, alongside ordinary class imports. */
internal class KotlinImports(
    packageName: String, private val cls: IrClass, private val aliasMap: AliasMap,
    private val constructorNames: KotlinConstructorNamePlan = KotlinConstructorNamePlan(cls, aliasMap),
    private var ownFieldWork: Long = 10_000_000,
) {
    private val constructorOwners = mutableMapOf<String, String>()
    private val nameScope = KotlinNameScope()

    fun hasCompleteNameScope(context: IrClass): Boolean = nameScope.hasCompleteNameScope(context)

    fun constructorAlias(type: IrType, context: IrClass = cls): String? {
        val cls = constructorNames.constructorClass(type) ?: return null
        if (!hasCompleteNameScope(context)) throw ConstructorNameScopeException()
        val owner = constructorOwners.getOrPut(cls.fullName) {
            KotlinSourceName.sourceQualifiedName(cls, aliasMap)
        }
        return aliasedClass(owner, cls.fullName)
    }

    private val ordinary = ImportCollector(packageName)
    private var symbolAliases: Map<String, String>
    private val reservedNames = linkedSetOf<String>()
    private var discoveryComplete = false
    private val usedAliasedSymbols = linkedSetOf<String>()
    private val dynamicAliasedOwners = linkedSetOf<String>()
    private val aliasedReferenceOwners = mutableMapOf<String, String>()
    private val monitorOwners = linkedSetOf<String>()

    private data class OwnField(val type: IrType, val eligible: Boolean)
    private val ownFields = mutableMapOf<IrClass, Map<String, OwnField?>>()
    enum class Ownership { OWNED, NOT_APPLICABLE, UNAVAILABLE }

    init {
        fun reserve(name: String?) {
            if (name != null) reservedNames.add(KotlinIdentifiers.sanitize(name).removeSurrounding("`"))
        }
        // Keep real declarations intact: allocate import aliases around their names, then reserve the
        // aliases in each method's allocator so generated temporaries cannot capture imported owners.
        val classes = ArrayDeque<IrClass>()
        classes.add(cls)
        val seenClasses = mutableSetOf<IrClass>()
        while (classes.isNotEmpty()) {
            val current = classes.removeFirst()
            if (!seenClasses.add(current)) continue
            reserve(KotlinSourceName.sourceSimpleName(current, aliasMap))
            recordOwnFields(current)
            current.fields.forEach { reserve(KotlinMemberAliases.aliasOf(it, aliasMap)) }
            for (method in current.methods) {
                if (method.isStatic && KotlinJvmModifiers.isSynchronized(method)) {
                    monitorOwners += KotlinSourceName.sourceQualifiedName(current, aliasMap)
                }
                reserve(KotlinMemberAliases.aliasOf(method, aliasMap))
                method[CodegenKeys.PARAM_NAMES]?.forEach(::reserve)
                method.ssaValues.forEach { reserve(it.localVar?.name) }
                val instructions = ArrayDeque<Instruction>()
                method.blocks.forEach { instructions.addAll(it.instructions) }
                val seenInstructions = mutableSetOf<Instruction>()
                while (instructions.isNotEmpty()) {
                    val instruction = instructions.removeFirst()
                    if (!seenInstructions.add(instruction)) continue
                    reserve(instruction.result?.ssaValue?.localVar?.name)
                    for (index in 0 until instruction.argCount) {
                        when (val operand = instruction.getArg(index)) {
                            is RegisterOperand -> reserve(operand.ssaValue?.localVar?.name)
                            is InstructionOperand -> instructions.add(operand.instruction)
                            else -> Unit
                        }
                    }
                }
            }
            classes.addAll(current.innerClasses)
            current.outerClass?.let(classes::add)
            (current.superType as? IrType.Object)?.className?.let(cls.root::findClass)?.let(classes::add)
            for (type in current.interfaces) {
                (type as? IrType.Object)?.className?.let(cls.root::findClass)?.let(classes::add)
            }
        }
        symbolAliases = allocateAliases()
    }

    /** Finalize after the discarded render has discovered all referenced types, including raw/default-package types. */
    fun finishDiscovery() {
        constructorNames.finishDiscovery()
        symbolAliases = allocateAliases()
        discoveryComplete = true
    }

    private fun allocateAliases(): Map<String, String> {
        val names = NameGenerator()
        reservedNames.forEach(names::reserve)
        return (KotlinJvmStaticInvocationProjection.ownerNames + KotlinJvmBoxedTypes.aliasedOwners + KotlinJvmBoxedTypes.projectedOwners + setOf("java.lang.UnsupportedOperationException") + KotlinJvmModifiers.aliasedSymbols + monitorOwners + dynamicAliasedOwners).associateWith { owner ->
            val base = when {
                owner == KotlinJvmModifiers.SYNCHRONIZED_FUNCTION -> "kotlinSynchronized"
                owner in monitorOwners -> "JvmMonitorOwner"
                else -> (if (owner.startsWith("kotlin.")) "Kotlin" else "Jvm") + owner.substringAfterLast('.')
            }
            names.unique(base)
        }
    }

    fun aliasedClass(fullName: String, referenceName: String = fullName): String {
        if (referenceName != fullName) {
            val previous = aliasedReferenceOwners[referenceName]
            check(previous == null || previous == fullName) { "conflicting aliased source identities" }
            check(!discoveryComplete || previous == fullName) { "aliased identity was not discovered before rendering" }
            if (previous == null) aliasedReferenceOwners[referenceName] = fullName
        }
        usedAliasedSymbols.add(fullName)
        symbolAliases[fullName]?.let { return it }
        check(!discoveryComplete) { "aliased owner was not discovered before rendering" }
        dynamicAliasedOwners.add(fullName)
        // Pass-one text is discarded. Allocate all discovered owners once, after ordinary imports
        // and declarations have reserved their names; repeated rebuilding would be quadratic.
        return fullName
    }

    private fun ownCharge(amount: Long): Boolean {
        if (amount > ownFieldWork) { ownFieldWork = 0; return false }
        ownFieldWork -= amount
        return true
    }

    private fun recordOwnFields(context: IrClass) {
        if (!ownCharge(context.fields.size.toLong() + 1)) return
        val fields = mutableMapOf<String, OwnField?>()
        for (field in context.fields) {
            val type = field.type
            if (!ownCharge(field.name.length.toLong() + 1)) return
            val eligible = field.accessFlags and 0x0008 != 0 && field.accessFlags and 0x0010 == 0 && field.constValue == null
            fields[field.name] = if (field.name in fields) null else OwnField(type, eligible)
        }
        ownFields[context] = fields
    }

    /** Exact owned mutable metadata only; unrelated/static-final resolution remains unchanged. */
    fun ownedMutableField(context: IrClass, reference: com.jadxmp.ir.insn.FieldRef): Ownership {
        val owner = (reference.declaringType as? IrType.Object)?.className ?: return Ownership.NOT_APPLICABLE
        if (!ownCharge(owner.length.toLong() + context.fullName.length + 1)) return Ownership.UNAVAILABLE
        if (owner != context.fullName) return Ownership.NOT_APPLICABLE
        if (!ownCharge(reference.name.length.toLong() + 1)) return Ownership.UNAVAILABLE
        val fields = ownFields[context] ?: return Ownership.UNAVAILABLE
        val field = fields[reference.name] ?: return Ownership.NOT_APPLICABLE
        if (!field.eligible) return Ownership.NOT_APPLICABLE
        return when (OwnedFieldTypes.equal(reference.type, field.type, ::ownCharge)) {
            null -> Ownership.UNAVAILABLE
            true -> Ownership.OWNED
            false -> Ownership.NOT_APPLICABLE
        }
    }

    fun aliasedFunction(fullName: String): String {
        require(fullName == KotlinJvmModifiers.SYNCHRONIZED_FUNCTION)
        usedAliasedSymbols.add(fullName)
        return symbolAliases.getValue(fullName)
    }

    /** An aliased Kotlin import removes the corresponding implicit simple-name import. */
    fun builtinName(simpleName: String): String {
        val fullName = "kotlin.$simpleName"
        return if (fullName in usedAliasedSymbols) symbolAliases.getValue(fullName) else simpleName
    }

    fun reserveAliases(names: NameGenerator) {
        symbolAliases.values.forEach(names::reserve)
    }

    fun useClass(fullName: String): String {
        // Import aliases remove ordinary source spellings. Route the exact registered binary
        // identity too; blindly replacing '$' would confuse real package and literal-dollar names.
        aliasedReferenceOwners[fullName]?.let { return aliasedClass(it) }
        if (fullName in dynamicAliasedOwners) return aliasedClass(fullName)
        if (!discoveryComplete) {
            // Imports expose the top-level type, including when the reference denotes a nested type.
            // Reserving before alias finalization is essential for default-package classes: unlike a
            // packaged reference, they have no qualification that could disambiguate a shadowed name.
            reservedNames.add(fullName.substringAfterLast('.').substringBefore('$'))
        }
        return ordinary.useClass(fullName)
    }

    fun imports(): List<Pair<String, String?>> {
        val aliased = usedAliasedSymbols.map { it to symbolAliases.getValue(it) }
        val plain = ordinary.imports().map { it to null }
        return (plain + aliased).sortedBy { it.first }
    }
}
