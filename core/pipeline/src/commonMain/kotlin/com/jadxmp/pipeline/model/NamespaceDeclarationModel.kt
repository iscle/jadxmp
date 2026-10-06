package com.jadxmp.pipeline.model

import com.jadxmp.input.ClassDeclarationData
import com.jadxmp.input.ClassNesting
import com.jadxmp.ir.declaration.*

/** Validates source-namespace capabilities independently of generic signatures and method bodies. */
object NamespaceDeclarationModel {
    fun build(input: List<ClassDeclarationData>, budget: NamespaceWorkBudget = NamespaceWorkBudget()): NamespaceDeclarationIndex {
        fun charge(amount: Long) = require(budget.charge(amount)) { "namespace declaration work limit exceeded" }
        fun text(value: String) { charge(value.length.toLong() + 1) }
        fun objectType(value: String): Boolean {
            text(value)
            if (value.length < 3 || value.first() != 'L' || value.last() != ';') return false
            var segment = false
            for (i in 1 until value.lastIndex) {
                val c = value[i]
                if (c == '/') { if (!segment) return false; segment = false }
                else { if (c == '.' || c == ';' || c == '[') return false; segment = true }
            }
            return segment
        }
        fun descriptor(value: String, allowVoid: Boolean): Boolean {
            text(value)
            var dimensions = 0
            while (dimensions < value.length && value[dimensions] == '[') dimensions++
            if (dimensions > 255 || dimensions == value.length) return false
            val kind = value[dimensions]
            if (kind == 'L') return objectType(value.substring(dimensions))
            return dimensions + 1 == value.length && (kind in "ZBCSIJFD" || (kind == 'V' && allowVoid && dimensions == 0))
        }
        fun unqualifiedName(value: String): Boolean {
            text(value)
            return value.isNotEmpty() && value.none { it == '.' || it == ';' || it == '[' || it == '/' }
        }
        fun nesting(data: ClassDeclarationData): NamespaceFact<NamespaceNesting> = when (val nesting = data.nesting) {
            null -> NamespaceFact.Unknown
            ClassNesting.TopLevel -> NamespaceFact.Available(NamespaceNesting.TopLevel)
            is ClassNesting.Nested -> {
                if (!objectType(nesting.enclosingClassType) || nesting.innerName?.let { !unqualifiedName(it) } == true) {
                    NamespaceFact.Invalid("malformed enclosing declaration")
                } else {
                    val method = nesting.enclosingMethod?.let {
                        val parameters = it.parameterTypes
                        charge(parameters.size.toLong())
                        val name = it.name
                        var valid = unqualifiedName(name) && descriptor(it.returnType, allowVoid = true)
                        // EnclosingMethod denotes a real method/constructor. Class initializers use
                        // an absent method reference, never a <clinit> NameAndType (JVMS 4.7.7).
                        text(name)
                        if (name == "<init>") {
                            text(it.returnType)
                            if (it.returnType != "V") valid = false
                        } else if (name.any { c -> c == '<' || c == '>' }) valid = false
                        for (type in parameters) if (!descriptor(type, allowVoid = false)) valid = false
                        val owner = it.declaringClassType
                        if (!objectType(owner)) valid = false
                        text(owner); text(nesting.enclosingClassType)
                        if (!valid || owner != nesting.enclosingClassType) return NamespaceFact.Invalid("malformed enclosing method identity")
                        NamespaceEnclosingMethod(owner, it.name, parameters.toList(), it.returnType)
                    }
                    NamespaceFact.Available(NamespaceNesting.Nested(nesting.enclosingClassType, nesting.innerName, method))
                }
            }
        }
        charge(input.size.toLong())
        val byType = HashMap<String, Int>()
        val records = ArrayList<NamespaceDeclaration>()
        for (data in input) {
            require(objectType(data.type)) { "malformed namespace class descriptor" }
            text(data.type)
            require(byType.put(data.type, records.size) == null) { "duplicate namespace class declaration" }
            charge(data.interfaces.size.toLong())
            text(data.type)
            var validHierarchy = data.superType?.let(::objectType)
                ?: (data.type == "Ljava/lang/Object;" || data.accessFlags and 0x0200 != 0)
            val interfaces = HashSet<String>()
            for (type in data.interfaces) {
                if (!objectType(type)) validHierarchy = false
                text(type)
                if (!interfaces.add(type)) validHierarchy = false
            }
            val hierarchy = if (validHierarchy) NamespaceFact.Available(NamespaceHierarchy(data.superType, data.interfaces.toList()))
                else NamespaceFact.Invalid("malformed or duplicate hierarchy type")
            val members = data.memberTypes?.let { entries ->
                charge(entries.size.toLong())
                var valid = true
                val types = HashSet<String>()
                val names = HashSet<String>()
                val result = ArrayList<NamespaceMemberType>()
                for (entry in entries) {
                    if (!objectType(entry.type) || !unqualifiedName(entry.innerName)) valid = false
                    text(entry.type); text(entry.innerName); text(data.type)
                    if (!types.add(entry.type) || !names.add(entry.innerName) || entry.type == data.type) valid = false
                    result.add(NamespaceMemberType(entry.type, entry.innerName, entry.accessFlags))
                }
                if (valid) NamespaceFact.Available(result.toList()) else NamespaceFact.Invalid("malformed or contradictory member type inventory")
            } ?: NamespaceFact.Unknown
            records.add(NamespaceDeclaration(data.type, data.accessFlags, data.innerAccessFlags, hierarchy, nesting(data), members))
        }
        // Independent class files must agree when both sides of a named member relationship are
        // supplied. Missing child declarations remain missing; do not manufacture their enclosure.
        val badInventories = BooleanArray(records.size)
        val badNesting = BooleanArray(records.size)
        for ((ownerIndex, owner) in records.withIndex()) {
            val members = (owner.memberTypes as? NamespaceFact.Available)?.value ?: continue
            charge(members.size.toLong())
            for (member in members) {
                text(member.type)
                val childIndex = byType[member.type] ?: continue
                val nesting = (records[childIndex].nesting as? NamespaceFact.Available)?.value ?: continue
                val matches = if (nesting is NamespaceNesting.Nested) {
                    text(nesting.owner); text(owner.type); text(member.innerName)
                    nesting.innerName?.let(::text)
                    nesting.owner == owner.type && nesting.innerName == member.innerName && nesting.method == null &&
                        (records[childIndex].innerAccessFlags == null || records[childIndex].innerAccessFlags == member.accessFlags)
                } else false
                if (!matches) { badInventories[ownerIndex] = true; badNesting[childIndex] = true }
            }
        }
        for (i in records.indices) records[i] = records[i].copy(
            memberTypes = if (badInventories[i]) NamespaceFact.Invalid("member inventory contradicts own nesting") else records[i].memberTypes,
            nesting = if (badNesting[i]) NamespaceFact.Invalid("own nesting contradicts member inventory") else records[i].nesting,
        )
        // Iterative graph coloring avoids recursive hostile ancestry. Unknown external vertices are
        // left unresolved, not invented roots; complete-scope consumers must inspect them later.
        fun cycles(edges: (NamespaceDeclaration) -> List<String>): BooleanArray {
            val adjacency = records.map { record ->
                val names = edges(record)
                charge(names.size.toLong())
                names.mapNotNull { name -> text(name); byType[name] }.toIntArray()
            }
            val state = ByteArray(records.size)
            val activePosition = IntArray(records.size) { -1 }
            val bad = BooleanArray(records.size)
            val stack = ArrayList<Int>()
            val next = ArrayList<Int>()
            for (start in records.indices) {
                if (state[start].toInt() != 0) continue
                stack.add(start); next.add(0); state[start] = 1; activePosition[start] = 0
                while (stack.isNotEmpty()) {
                    charge(1)
                    val position = stack.lastIndex
                    val node = stack[position]
                    val index = next[position]
                    if (index == adjacency[node].size) {
                        state[node] = 2; activePosition[node] = -1
                        stack.removeAt(position); next.removeAt(position)
                        continue
                    }
                    next[position] = index + 1
                    val target = adjacency[node][index]
                    when (state[target].toInt()) {
                        0 -> { state[target] = 1; activePosition[target] = stack.size; stack.add(target); next.add(0) }
                        1 -> for (i in activePosition[target]..stack.lastIndex) { charge(1); bad[stack[i]] = true }
                    }
                }
            }
            return bad
        }
        val hierarchyCycles = cycles {
            val value = (it.hierarchy as? NamespaceFact.Available)?.value
            if (value == null) emptyList() else {
                charge(value.interfaces.size.toLong() + 1)
                listOfNotNull(value.superType) + value.interfaces
            }
        }
        val nestingCycles = cycles {
            val value = (it.nesting as? NamespaceFact.Available)?.value
            if (value is NamespaceNesting.Nested) listOf(value.owner) else emptyList()
        }
        val result = records.mapIndexed { i, record -> record.copy(
            hierarchy = if (hierarchyCycles[i]) NamespaceFact.Invalid("cyclic hierarchy") else record.hierarchy,
            nesting = if (nestingCycles[i]) NamespaceFact.Invalid("cyclic lexical enclosure") else record.nesting,
        ) }
        return NamespaceDeclarationIndex(result, budget)
    }
}
