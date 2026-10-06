package com.jadxmp.ir.declaration

/** Independent declaration capabilities: absence is never evidence of an empty source scope. */
sealed interface NamespaceFact<out T> {
    data class Available<T>(val value: T) : NamespaceFact<T>
    data object Unknown : NamespaceFact<Nothing>
    data class Invalid(val reason: String) : NamespaceFact<Nothing>
}

data class NamespaceHierarchy(val superType: String?, val interfaces: List<String>)
data class NamespaceMemberType(val type: String, val innerName: String, val accessFlags: Int)
data class NamespaceEnclosingMethod(val owner: String, val name: String, val parameters: List<String>, val returnType: String)
sealed interface NamespaceNesting {
    data object TopLevel : NamespaceNesting
    data class Nested(val owner: String, val innerName: String?, val method: NamespaceEnclosingMethod?) : NamespaceNesting
}

/** Exact descriptor identities, without generic reconstruction or executable program nodes. */
data class NamespaceDeclaration(
    val type: String,
    val accessFlags: Int,
    val innerAccessFlags: Int?,
    val hierarchy: NamespaceFact<NamespaceHierarchy>,
    val nesting: NamespaceFact<NamespaceNesting>,
    val memberTypes: NamespaceFact<List<NamespaceMemberType>>,
)

/** Caller-owned work budget; sharing one instance bounds a whole proof, including cache hits. */
class NamespaceWorkBudget(work: Long = 10_000_000) {
    init { require(work >= 0) }
    private var remaining = work
    fun charge(amount: Long): Boolean {
        require(amount >= 0)
        if (amount > remaining) { remaining = 0; return false }
        remaining -= amount
        return true
    }
}

sealed interface NamespaceLookup {
    data class Known(val declaration: NamespaceDeclaration) : NamespaceLookup
    data object Missing : NamespaceLookup
    data class Unavailable(val reason: String) : NamespaceLookup
}

/**
 * Declaration-only lookup, deliberately separate from optional generic-signature proofs.
 * Records must first be validated/snapshotted by the input model boundary. A consumer must prefer
 * loaded program declarations and prove the entire relevant ancestry; Known alone is not that proof.
 */
class NamespaceDeclarationIndex(records: List<NamespaceDeclaration>, budget: NamespaceWorkBudget) {
    private val byType: Map<String, NamespaceDeclaration>
    val size: Int get() = byType.size

    init {
        require(budget.charge(records.size.toLong())) { "namespace declaration work limit exceeded" }
        val entries = HashMap<String, NamespaceDeclaration>()
        for (record in records) {
            require(budget.charge(record.type.length.toLong() + 1)) { "namespace declaration work limit exceeded" }
            require(entries.put(record.type, record) == null) { "duplicate namespace declaration" }
        }
        byType = entries
    }

    fun lookup(type: String, budget: NamespaceWorkBudget): NamespaceLookup {
        // JS/Wasm string hashing is linear even for a repeated existing key.
        if (!budget.charge(type.length.toLong() + 1)) return NamespaceLookup.Unavailable("namespace lookup work limit exceeded")
        return byType[type]?.let(NamespaceLookup::Known) ?: NamespaceLookup.Missing
    }
}
