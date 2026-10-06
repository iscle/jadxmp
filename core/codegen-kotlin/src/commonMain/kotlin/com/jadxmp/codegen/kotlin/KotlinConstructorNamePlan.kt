package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.AliasMap
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType

/**
 * A Kotlin source-only clash need not rename two distinct JVM members. K2 permits the exact names
 * when CONFLICTING_OVERLOADS is suppressed on each conflicting declaration and every construction
 * uses an imported class alias. This proof deliberately covers only empty parameter lists: no
 * inferred nullability, defaults, varargs or generic parameter projection participates in matching.
 */
internal class KotlinConstructorNamePlan(
    private val output: IrClass,
    private val aliases: AliasMap,
    workLimit: Int = 1_000_000,
) {
    private val declarations = mutableSetOf<IrMethod>()
    private val constructorClasses = mutableSetOf<IrClass>()
    private val problems = mutableMapOf<IrClass, String>()
    private var work = workLimit
    private var exhausted = false
    private val inspected = mutableSetOf<IrClass>()
    private val constructorLookups = mutableMapOf<String, IrClass?>()
    private var discoveryComplete = false
    private class Limit : IllegalStateException()

    init {
        try {
            val queue = ArrayDeque<IrClass>()
            val visited = mutableSetOf<IrClass>()
            queue.add(output)
            while (queue.isNotEmpty()) {
                charge(1)
                val cls = queue.removeFirst()
                if (!visited.add(cls)) continue
                charge(cls.methods.size + cls.innerClasses.size)
                queue.addAll(cls.innerClasses)
                if (inspected.add(cls)) inspect(cls)
            }
        } catch (_: Limit) {
            exhausted = true
            problems[output] = "constructor/function name analysis work limit exceeded"
        }
    }

    fun suppress(method: IrMethod): Boolean = method in declarations
    fun problem(cls: IrClass): String? = problems[cls]
    fun finishDiscovery() { discoveryComplete = true }

    fun constructorClass(type: IrType): IrClass? {
        if (exhausted || type !is IrType.Object) return null
        if (discoveryComplete) {
            check(constructorLookups.containsKey(type.className)) { "constructor was not discovered before rendering" }
            return constructorLookups[type.className]
        }
        try {
            charge(type.className.length + 1)
            if (constructorLookups.containsKey(type.className)) return constructorLookups[type.className]
            val cls = output.root.findClass(type.className)
            val outer = cls?.outerClass
            if (outer != null && inspected.add(outer)) {
                charge(outer.methods.size + outer.innerClasses.size + 1)
                inspect(outer)
            }
            val result = cls?.takeIf { it in constructorClasses }
            constructorLookups[type.className] = result
            return result
        } catch (_: Limit) {
            exhausted = true
            problems[output] = "constructor/function name analysis work limit exceeded"
            return null
        }
    }

    private fun inspect(cls: IrClass) {
        val methods = mutableMapOf<String, MutableList<IrMethod>>()
        for (method in cls.methods) {
            if (method.name.startsWith("<") || method.argTypes.isNotEmpty() || method.contains(AttrFlag.DONT_GENERATE)) continue
            charge(method.name.length + 1 + if (aliases.isEmpty) 0 else cls.fullName.length)
            val name = KotlinMemberAliases.aliasOf(method, aliases)
            charge(name.length + 1)
            methods.getOrPut(name) { mutableListOf() }.add(method)
        }
        val nested = mutableMapOf<String, MutableList<IrClass>>()
        for (child in cls.innerClasses) {
            charge(child.fullName.length + 1)
            val name = KotlinSourceName.sourceSimpleName(child, aliases)
            charge(name.length + 1)
            nested.getOrPut(name) { mutableListOf() }.add(child)
        }
        for ((name, children) in nested) {
            charge(name.length + 1)
            val functions = methods[name] ?: continue
            for (child in children) {
                charge(child.methods.size + child.fields.size + 1)
                val constructors = child.methods.filter { it.name == "<init>" && it.argTypes.isEmpty() && !it.contains(AttrFlag.DONT_GENERATE) }
                if (constructors.isEmpty()) continue
                val function = functions.singleOrNull()
                val constructor = constructors.singleOrNull()
                val eligible = children.size == 1 && function != null && constructor != null &&
                    !function.isStatic && function.accessFlags and KotlinModifiers.PUBLIC != 0 &&
                    constructor.accessFlags and 0x7 == KotlinModifiers.PUBLIC &&
                    child.accessFlags and (KotlinModifiers.PUBLIC or KotlinModifiers.STATIC) == (KotlinModifiers.PUBLIC or KotlinModifiers.STATIC) &&
                    child.accessFlags and (KotlinModifiers.ENUM or KotlinModifiers.INTERFACE or KotlinModifiers.ANNOTATION) == 0 &&
                    cls.accessFlags and (KotlinModifiers.ENUM or KotlinModifiers.INTERFACE or KotlinModifiers.ANNOTATION) == 0 &&
                    cls.superType == IrType.OBJECT && cls.interfaces.isEmpty() &&
                    function.name == name.removeSurrounding("`") && child.shortName == name.removeSurrounding("`") &&
                    preservesOwnerNames(child)
                if (!eligible) {
                    problems[cls] = "unsupported or ambiguous constructor/function source-name collision"
                    continue
                }
                declarations.add(function!!)
                declarations.add(constructor!!)
                constructorClasses.add(child)
            }
        }
    }

    private fun preservesOwnerNames(start: IrClass): Boolean {
        val seen = mutableSetOf<IrClass>()
        var cls: IrClass? = start
        while (cls != null) {
            charge(cls.fullName.length + cls.shortName.length + 1)
            if (!seen.add(cls)) return false
            if (KotlinSourceName.sourceSimpleName(cls, aliases).removeSurrounding("`") != cls.shortName) return false
            val parent = cls.outerClass
            if (parent != null && cls.fullName != parent.fullName + "$" + cls.shortName) return false
            cls = parent
        }
        return true
    }

    private fun charge(amount: Int) {
        if (amount > work || amount < 0) throw Limit()
        work -= amount
    }

    companion object { const val SUPPRESS = "kotlin.Suppress" }
}

/** Caught at the member boundary so an unproved alias cannot silently invoke an inherited value. */
internal class ConstructorNameScopeException : RuntimeException(
    "constructor alias requires a complete caller name scope",
)
