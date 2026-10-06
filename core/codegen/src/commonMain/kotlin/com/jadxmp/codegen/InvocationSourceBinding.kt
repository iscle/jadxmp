package com.jadxmp.codegen

import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType

/**
 * Per-output source overload binding, without changing SSA types or virtual dispatch. Only loaded
 * overloads and proven reference widening are considered; unknown library/generic/boxing relations
 * are not evidence for a new runtime cast. Both emitters consume the same argument-position plan.
 */
class InvocationSourceBinding(private val root: IrRoot?, private val maxWork: Int = 10_000_000) {
    private data class Lookup(val owner: String, val receiver: String?, val name: String, val arity: Int)
    private val overloads = HashMap<Lookup, List<List<IrType>>>()
    private data class Call(val lookup: Lookup, val parameters: List<IrType>)
    private val conflicts = HashMap<Call, Set<Int>>()
    private val widening = HashMap<Pair<IrType, IrType>, Boolean>()
    private val ancestors = HashMap<String, Set<String>>()
    private var work = 0

    fun arguments(ref: MethodRef, receiver: IrType?, sourceTypes: List<IrType>): Set<Int> {
        val owner = (ref.declaringType as? IrType.Object)?.className ?: return emptySet()
        if (root == null || sourceTypes.size != ref.paramTypes.size) return emptySet()
        if (ref.paramTypes.none { it is IrType.Object || it is IrType.ArrayType }) return emptySet()
        if (ref.paramTypes.any { !plainType(it) }) return emptySet()
        val lookup = Lookup(owner, if (ref.isConstructor) null else (receiver as? IrType.Object)?.className,
            ref.name, ref.paramTypes.size)
        // JS structural hashes rescan strings even for cache hits. Charge key text before every
        // lookup, independently of the graph work avoided by memoization.
        charge(2 * (lookup.owner.length + (lookup.receiver?.length ?: 0) + lookup.name.length))
        ref.paramTypes.forEach(::chargeType)
        val positions = conflicts.getOrPut(Call(lookup, ref.paramTypes)) {
            val candidates = overloads.getOrPut(lookup) { collect(lookup) }
            if (candidates.none { candidate ->
                candidate.forEach(::chargeType)
                ref.paramTypes.forEach(::chargeType)
                candidate == ref.paramTypes
            }) emptySet() else
                ref.paramTypes.indices.filterTo(LinkedHashSet()) { index ->
                    val target = ref.paramTypes[index]
                    reference(target) && candidates.any {
                        chargeType(it[index])
                        chargeType(target)
                        it[index] != target && reference(it[index])
                    }
                }
        }
        return positions.filterTo(LinkedHashSet()) { index ->
            if (!plainType(sourceTypes[index])) return@filterTo false
            chargeType(sourceTypes[index])
            chargeType(ref.paramTypes[index])
            widening.getOrPut(sourceTypes[index] to ref.paramTypes[index]) {
                charge()
                widens(sourceTypes[index], ref.paramTypes[index])
            }
        }
    }

    private fun collect(key: Lookup): List<List<IrType>> {
        val pending = ArrayDeque<String>()
        pending.add(key.owner)
        key.receiver?.let(pending::add)
        val seen = HashSet<String>()
        val result = LinkedHashSet<List<IrType>>()
        while (pending.isNotEmpty()) {
            charge()
            val name = pending.removeLast()
            charge(3 * name.length)
            if (!seen.add(name)) continue
            val cls = root?.findClass(name) ?: continue
            for (method in cls.methods) {
                charge(method.name.length + key.name.length)
                if (method.name != key.name || method.argTypes.size != key.arity) continue
                // Varargs source invocation has additional applicability phases; leave it outside
                // this exact-arity proof, including when it competes with a fixed-arity method.
                if (method.accessFlags and 0x80 != 0) return emptyList()
                if (method.argTypes.any { !plainType(it) }) continue
                method.argTypes.forEach(::chargeType)
                result.add(method.argTypes)
            }
            if (key.name != MethodRef.CONSTRUCTOR_NAME) {
                (cls.superType as? IrType.Object)?.className?.let(pending::add)
                cls.interfaces.forEach { (it as? IrType.Object)?.className?.let(pending::add) }
            }
        }
        return result.toList()
    }

    private fun widens(source: IrType, target: IrType): Boolean {
        var sub = source
        var sup = target
        while (sub is IrType.ArrayType && sup is IrType.ArrayType) {
            charge()
            sub = sub.element
            sup = sup.element
        }
        if (sub == sup) return reference(source)
        if (!reference(sub) || !reference(sup)) return false
        if (sup == IrType.OBJECT) return true
        val from = (sub as? IrType.Object)?.className ?: return false
        val to = (sup as? IrType.Object)?.className ?: return false
        charge(from.length + to.length)
        return to in ancestors.getOrPut(from) { superNames(from) }
    }

    private fun superNames(start: String): Set<String> {
        val pending = ArrayDeque<String>()
        val seen = HashSet<String>()
        pending.add(start)
        while (pending.isNotEmpty()) {
            charge()
            val name = pending.removeLast()
            charge(3 * name.length)
            if (!seen.add(name)) continue
            val cls = root?.findClass(name)
            if (cls != null) {
                (cls.superType as? IrType.Object)?.className?.let(pending::add)
                cls.interfaces.forEach { (it as? IrType.Object)?.className?.let(pending::add) }
            } else {
                // Fixed Java platform ancestry. A loaded declaration always takes precedence.
                PLATFORM_SUPERS[name]?.let(pending::add)
            }
        }
        seen.remove(start)
        return seen
    }

    private fun reference(type: IrType): Boolean {
        var element = type
        while (element is IrType.ArrayType) {
            charge()
            element = element.element
        }
        return when (element) {
            is IrType.Object -> element.generics.isEmpty()
            is IrType.Primitive -> type is IrType.ArrayType
            else -> false
        }
    }

    private fun plainType(type: IrType): Boolean {
        var leaf = type
        var depth = 0
        while (leaf is IrType.ArrayType) {
            charge()
            check(depth++ < 256) { "source invocation binding array depth exceeded" }
            leaf = leaf.element
        }
        return leaf is IrType.Primitive || leaf is IrType.Object && leaf.generics.isEmpty()
    }

    private fun chargeType(type: IrType) {
        check(plainType(type)) { "unsupported source invocation binding type" }
        var leaf = type
        while (leaf is IrType.ArrayType) leaf = leaf.element
        charge(if (leaf is IrType.Object) leaf.className.length else 1)
    }

    private fun charge(amount: Int = 1) {
        check(amount >= 0 && amount <= maxWork - work) { "source invocation binding work limit exceeded" }
        work += amount
    }

    private companion object {
        val PLATFORM_SUPERS = mapOf(
            "java.lang.Exception" to "java.lang.Throwable",
            "java.lang.RuntimeException" to "java.lang.Exception",
            "java.lang.Error" to "java.lang.Throwable",
        )
    }
}
