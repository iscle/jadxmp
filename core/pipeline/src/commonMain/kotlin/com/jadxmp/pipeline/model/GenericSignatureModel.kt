package com.jadxmp.pipeline.model

import com.jadxmp.input.AccessFlags
import com.jadxmp.input.ClassData
import com.jadxmp.input.ClassNesting
import com.jadxmp.input.InvalidGenericSignatureEncoding
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.attr.DecompileError
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.generics.ClassSignature
import com.jadxmp.ir.generics.GenericAttributes
import com.jadxmp.ir.generics.GenericSignatureRecovery
import com.jadxmp.ir.generics.PendingConstructorSignature
import com.jadxmp.ir.generics.TypeParameter
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.pass.CancellationSignal
import kotlin.coroutines.cancellation.CancellationException

/** Validates optional source metadata after nesting is known; no descriptor or hierarchy is overwritten. */
internal object GenericSignatureModel {
    fun attach(classes: List<Pair<IrClass, ClassData>>) {
        val diagnostics = Diagnostics()
        val inputs = classes.toMap()
        val scopes = mutableMapOf<IrClass, SignatureScope>()
        var scopeEntries = 0
        var scopeWork = 0
        val budgets = mutableMapOf<IrClass, MetadataBudget>()
        fun budget(cls: IrClass) = budgets.getOrPut(cls) { MetadataBudget() }
        fun scope(cls: IrClass, depth: Int): SignatureScope {
            scopes[cls]?.let { return it }
            if (depth > 64) {
                diagnostics.fault(cls, UnsupportedGenericSignature("enclosing generic scope depth exceeded"))
                return SignatureScope.UNKNOWN
            }
            val data = inputs[cls]
            val enclosure = data?.reflectiveNesting
            // Reflection ignores source-name guesses when the format proves no enclosure. Method
            // captures are still unsupported, including names that shadow outer class variables.
            val parent = when {
                hasEnclosingMethod(data) -> SignatureScope.UNKNOWN
                enclosure == ClassNesting.TopLevel -> SignatureScope.EMPTY
                enclosure is ClassNesting.Nested -> {
                    val name = (Descriptors.parseClassType(enclosure.enclosingClassType) as? IrType.Object)?.className
                    name?.let { cls.root.findClass(it) }?.takeIf { it in inputs }?.let { scope(it, depth + 1) }
                        ?: SignatureScope.UNKNOWN
                }
                else -> (cls.outerClass?.let { scope(it, depth + 1) } ?: SignatureScope.UNKNOWN).copy(complete = false)
            }
            val enclosing = if (cls.accessFlags and AccessFlags.STATIC != 0) emptyList() else parent.parameters
            var headerKnown = false
            diagnostics.guarded(cls) {
                val text = data?.genericSignature
                if (text == null) headerKnown = true else {
                    cls[GenericAttributes.RAW_SIGNATURE] = text
                    budget(cls).charge(text, enclosing.size)
                    val work = text.length + enclosing.size
                    if (work > 1_000_000 - scopeWork)
                        throw UnsupportedGenericSignature("aggregate generic scope work limit exceeded")
                    scopeWork += work
                    val signature = try {
                        GenericSignatures.parseClass(text, enclosing)
                    } catch (unbound: UnboundGenericVariable) {
                        if (!parent.provesUndefined(unbound)) throw unbound
                        val parameters = unbound.classParameters ?: throw invalidVariable(unbound)
                        diagnostics.recover(cls, text,
                            "undefined type variable ${unbound.variableName} in complete reflective scope; erased hierarchy and valid formals retained")
                        ClassSignature(parameters, cls.superType ?: IrType.OBJECT, cls.interfaces)
                    }
                    val erasures = GenericSignatures.ErasureScope(enclosing + signature.parameters)
                    val matchingSuper = erasures.erase(signature.superType) == cls.superType
                    val erasedInterfaces = signature.interfaces.map(erasures::erase)
                    val matchingInterfaces = erasedInterfaces == cls.interfaces
                    // Retain only unambiguous metadata for each authoritative erased interface.
                    // One index avoids rescanning every parsed signature for every class-file entry.
                    val uniqueInterfaces = mutableMapOf<IrType, IrType?>()
                    if (!matchingInterfaces) for ((index, erased) in erasedInterfaces.withIndex()) {
                        uniqueInterfaces[erased] = if (erased in uniqueInterfaces) null else signature.interfaces[index]
                    }
                    if (!matchingSuper || !matchingInterfaces) {
                        diagnostics.recover(cls, text, "class signature disagrees with erased hierarchy; compatible formal parameters retained")
                    }
                    cls[GenericAttributes.CLASS] = signature.copy(
                        superType = if (matchingSuper) signature.superType else cls.superType ?: IrType.OBJECT,
                        interfaces = if (matchingInterfaces) signature.interfaces else cls.interfaces.map { erased ->
                            uniqueInterfaces[erased] ?: erased
                        },
                    )
                    headerKnown = true
                }
            }
            val parameters = cls[GenericAttributes.CLASS]?.parameters ?: emptyList()
            // Empty child declarations share the immutable parent collections. Distinct widened
            // scopes are bounded across the entire input, not merely per small child signature.
            val entries = if (parameters.isEmpty()) 0 else enclosing.size + parent.reflectiveNames.size + parameters.size * 2
            if (entries > 1_000_000 - scopeEntries) {
                diagnostics.fault(cls, UnsupportedGenericSignature("aggregate generic scope storage limit exceeded"))
                return SignatureScope.UNKNOWN.also { scopes[cls] = it }
            }
            scopeEntries += entries
            return SignatureScope(if (parameters.isEmpty()) enclosing else enclosing + parameters,
                if (parameters.isEmpty()) parent.reflectiveNames else parent.reflectiveNames + parameters.map { it.name },
                parent.complete && headerKnown).also { scopes[cls] = it }
        }
        for ((cls, data) in classes) {
            val scope = scope(cls, 0)
            val budget = budget(cls)
            val instanceErasures by lazy { GenericSignatures.ErasureScope(scope.parameters) }
            val staticErasures by lazy { GenericSignatures.ErasureScope(emptyList()) }
            for ((field, input) in cls.fields.zip(data.fields)) diagnostics.guarded(field, cls) {
                if (budget.exhausted) return@guarded
                input.genericSignature?.let { text ->
                    field[GenericAttributes.RAW_SIGNATURE] = text
                    val fieldScope = if (field.accessFlags and AccessFlags.STATIC == 0) scope.parameters else emptyList()
                    budget.charge(text, fieldScope.size)
                    val type = withReflectiveScope(scope) { GenericSignatures.parseField(text, fieldScope) }
                    val erasures = if (field.accessFlags and AccessFlags.STATIC == 0) instanceErasures else staticErasures
                    signatureRequire(erasures.erase(type) == field.type) { "field generic signature disagrees with erased descriptor" }
                    field[GenericAttributes.FIELD] = type
                }
            }
            for ((method, input) in cls.methods.zip(data.methods)) diagnostics.guarded(method) {
                if (budget.exhausted) return@guarded
                input.genericSignature?.let { text ->
                    method[GenericAttributes.RAW_SIGNATURE] = text
                    val methodScope = if (method.isStatic) emptyList() else scope.parameters
                    budget.charge(text, methodScope.size)
                    val signature = withReflectiveScope(scope) { GenericSignatures.parseMethod(text, methodScope) }
                    val erasures = if (signature.parameters.isNotEmpty()) GenericSignatures.ErasureScope(methodScope + signature.parameters)
                        else if (method.isStatic) staticErasures else instanceErasures
                    val erasedArguments = signature.argumentTypes.map(erasures::erase)
                    val prefix = if (erasedArguments == method.argTypes) emptyList() else constructorPrefix(method)
                    signatureRequire(erasures.erase(signature.returnType) == method.returnType) {
                        "method generic return signature disagrees with erased descriptor"
                    }
                    if (prefix + erasedArguments != method.argTypes && method.name == "<init>" &&
                        erasedArguments.size < method.argTypes.size) {
                        if (method.argTypes.takeLast(erasedArguments.size) != erasedArguments)
                            throw UnsupportedGenericSignature("constructor signature may omit non-prefix implicit parameters")
                        method[GenericAttributes.PENDING_CONSTRUCTOR] = PendingConstructorSignature(signature,
                            method.argTypes.dropLast(erasedArguments.size))
                    } else {
                        signatureRequire(prefix + erasedArguments == method.argTypes) {
                            "method generic signature disagrees with erased descriptor"
                        }
                        method[GenericAttributes.METHOD] = signature.copy(erasedPrefixTypes = prefix)
                    }
                }
            }
        }
        diagnostics.publish()
    }

    private data class SignatureScope(
        val parameters: List<TypeParameter>,
        val reflectiveNames: Set<String>,
        val complete: Boolean,
    ) {
        fun provesUndefined(variable: UnboundGenericVariable): Boolean = complete && variable.variableName !in reflectiveNames
        companion object {
            val EMPTY = SignatureScope(emptyList(), emptySet(), true)
            val UNKNOWN = EMPTY.copy(complete = false)
        }
    }

    private fun invalidVariable(variable: UnboundGenericVariable) = InvalidGenericSignature(
        "undefined type variable ${variable.variableName} in complete reflective scope")

    private inline fun <T> withReflectiveScope(scope: SignatureScope, parse: () -> T): T = try {
        parse()
    } catch (unbound: UnboundGenericVariable) {
        if (scope.provesUndefined(unbound)) throw invalidVariable(unbound)
        throw unbound
    }

    /** Bounds repeated large lexical scopes across tiny member signatures, not just each individual string. */
    private class MetadataBudget {
        private var remaining = 1_000_000
        var exhausted: Boolean = false
            private set
        fun charge(text: String, scopeSize: Int) {
            val work = text.length.toLong() + scopeSize
            if (work > remaining) {
                exhausted = true
                throw UnsupportedGenericSignature("aggregate generic signature work limit exceeded")
            }
            remaining -= work.toInt()
        }
    }

    private fun hasEnclosingMethod(data: ClassData?): Boolean {
        if ((data?.reflectiveNesting as? ClassNesting.Nested)?.enclosingMethod != null) return true
        if (data?.reflectiveNesting == ClassNesting.TopLevel) return false
        return data?.annotations?.any { it.annotationType == "Ldalvik/annotation/EnclosingMethod;" } == true
    }

    private fun constructorPrefix(method: IrMethod): List<IrType> {
        if (method.name != "<init>") return emptyList()
        val owner = method.declaringClass
        val expected = when {
            owner.accessFlags and AccessFlags.ENUM != 0 -> listOf(IrType.STRING, IrType.INT)
            owner.accessFlags and AccessFlags.STATIC == 0 && owner.outerClass != null ->
                listOf(IrType.objectType(owner.outerClass!!.fullName))
            else -> emptyList()
        }
        return expected.takeIf { method.argTypes.take(it.size) == it } ?: emptyList()
    }

    /** Append during attachment and publish immutable snapshots once; malformed fields must remain linear work. */
    private class Diagnostics {
        private data class Failures(val messages: MutableList<String>, val cause: Exception)
        private val recoveries = mutableMapOf<AttrNode, MutableList<GenericSignatureRecovery>>()
        private val failures = mutableMapOf<AttrNode, Failures>()
        inline fun guarded(node: AttrNode, owner: AttrNode? = null, action: () -> Unit) {
            try {
                action()
            } catch (cancelled: CancellationSignal) {
                throw cancelled
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: InvalidGenericSignature) {
                recover(node, node[GenericAttributes.RAW_SIGNATURE], invalid.message ?: "invalid signature")
                if (owner != null) recover(owner, node[GenericAttributes.RAW_SIGNATURE], "field metadata: ${invalid.message}")
            } catch (invalid: InvalidGenericSignatureEncoding) {
                recover(node, null, invalid.message ?: "invalid signature encoding")
                if (owner != null) recover(owner, null, "field metadata: ${invalid.message}")
            } catch (failure: Exception) {
                fault(node, failure)
                // Public decompilation diagnostics count classes/methods; retain field failures on their owner too.
                if (owner != null) fault(owner, failure)
            }
        }

        fun recover(node: AttrNode, signature: String?, reason: String) {
            recoveries.getOrPut(node) { node[GenericAttributes.RECOVERIES]?.toMutableList() ?: mutableListOf() }
                .add(GenericSignatureRecovery(signature, reason))
        }

        fun fault(node: AttrNode, failure: Exception) {
            val message = "invalid or unsupported generic signature: ${failure.message}"
            val accumulated = failures.getOrPut(node) {
                Failures(node[IrAttrs.ERROR]?.let { mutableListOf(it.message) } ?: mutableListOf(), failure)
            }
            accumulated.messages.add(message)
            node.add(AttrFlag.HAS_ERROR)
        }

        fun publish() {
            for ((node, values) in recoveries) node[GenericAttributes.RECOVERIES] = values.toList()
            for ((node, values) in failures) node[IrAttrs.ERROR] = DecompileError(values.messages.joinToString("; "), values.cause)
        }
    }
}
