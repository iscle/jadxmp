package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.codegen.ImportCollector
import com.jadxmp.codegen.NameGenerator
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.type.IrType

/** Collision-safe aliases for JVM references and their Kotlin signature projections layered over ordinary shared class imports. */
internal class KotlinImports(packageName: String, cls: IrClass, aliasMap: AliasMap) {
    private val ordinary = ImportCollector(packageName)
    private var classAliases: Map<String, String>
    private val reservedNames = linkedSetOf<String>()
    private var discoveryComplete = false
    private val usedAliasedClasses = linkedSetOf<String>()

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
            current.fields.forEach { reserve(KotlinMemberAliases.aliasOf(it, aliasMap)) }
            for (method in current.methods) {
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
            for (type in listOfNotNull(current.superType) + current.interfaces) {
                (type as? IrType.Object)?.className?.let(cls.root::findClass)?.let(classes::add)
            }
        }
        classAliases = allocateAliases()
    }

    /** Finalize after the discarded render has discovered all referenced types, including raw/default-package types. */
    fun finishDiscovery() {
        classAliases = allocateAliases()
        discoveryComplete = true
    }

    private fun allocateAliases(): Map<String, String> {
        val names = NameGenerator()
        reservedNames.forEach(names::reserve)
        return (KotlinJvmStaticInvocationProjection.ownerNames + KotlinJvmBoxedTypes.aliasedOwners + KotlinJvmBoxedTypes.projectedOwners).associateWith { owner ->
            names.unique((if (owner.startsWith("kotlin.")) "Kotlin" else "Jvm") + owner.substringAfterLast('.'))
        }
    }

    fun aliasedClass(fullName: String): String {
        usedAliasedClasses.add(fullName)
        return classAliases.getValue(fullName)
    }

    /** An aliased Kotlin import removes the corresponding implicit simple-name import. */
    fun builtinName(simpleName: String): String {
        val fullName = "kotlin.$simpleName"
        return if (fullName in usedAliasedClasses) classAliases.getValue(fullName) else simpleName
    }

    fun reserveAliases(names: NameGenerator) {
        classAliases.values.forEach(names::reserve)
    }

    fun useClass(fullName: String): String {
        if (!discoveryComplete) {
            // Imports expose the top-level type, including when the reference denotes a nested type.
            // Reserving before alias finalization is essential for default-package classes: unlike a
            // packaged reference, they have no qualification that could disambiguate a shadowed name.
            reservedNames.add(fullName.substringAfterLast('.').substringBefore('$'))
        }
        return ordinary.useClass(fullName)
    }

    fun imports(): List<Pair<String, String?>> {
        val aliased = usedAliasedClasses.map { it to classAliases.getValue(it) }
        val plain = ordinary.imports().map { it to null }
        return (plain + aliased).sortedBy { it.first }
    }
}
