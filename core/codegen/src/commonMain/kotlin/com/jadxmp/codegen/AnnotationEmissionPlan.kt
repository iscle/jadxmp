package com.jadxmp.codegen

import com.jadxmp.ir.annotation.*
import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.attr.SourceAttributes
import com.jadxmp.ir.node.*
import com.jadxmp.ir.type.IrType
import kotlin.coroutines.cancellation.CancellationException

/**
 * Per-output semantic annotation planning. Both render passes share typed outcomes and declaration
 * indexes; source spellings are deliberately absent because imports/aliases finalize between passes.
 * Failures remain explicit, including metadata attached to nodes that reconstruction might hide.
 */
class AnnotationEmissionPlan(private val root: IrRoot?, private val maxWork: Int = 2_000_000) {
    data class NodePlan(
        val annotations: List<AnnotationMetadata<IrAnnotation>> = emptyList(),
        val deferred: List<IrAnnotation> = emptyList(),
        val parameters: List<List<AnnotationMetadata<IrAnnotation>>> = emptyList(),
        val default: AnnotationMetadata<IrAnnotationValue?> = AnnotationMetadata.Ready(null),
        val problems: List<String> = emptyList(),
    ) {
        val requiresEmission: Boolean get() = annotations.isNotEmpty() || problems.isNotEmpty() ||
            parameters.any { it.isNotEmpty() } || default is AnnotationMetadata.Unavailable ||
            (default as? AnnotationMetadata.Ready)?.value != null
    }

    enum class RetentionPolicy { SOURCE, CLASS, RUNTIME }
    data class DeclaredRetention(val policy: RetentionPolicy, val explicit: Boolean)

    private companion object {
        // Java SE Override declares SOURCE retention; runtime DEX can still encode an attachment.
        // Keep this immutable fact separate from unproved external declaration metadata.
        val PLATFORM_OVERRIDE_RETENTION = DeclaredRetention(RetentionPolicy.SOURCE, explicit = true)
    }

    private var remaining = maxWork
    private val nodes = HashMap<AttrNode, NodePlan>()
    private val elements = HashMap<IrClass, Map<String, IrMethod?>>()
    private val enumFields = HashMap<IrClass, Map<String, IrField?>>()
    private val retentions = HashMap<IrClass, AnnotationMetadata<DeclaredRetention>>()
    internal val retainedRetentionProofs: Int get() = retentions.size

    /** Only loaded declarations prove a contract. External declarations remain a separate boundary. */
    fun retention(declaration: IrClass): AnnotationMetadata<DeclaredRetention> {
        retentions[declaration]?.let { return it }
        val result = capture {
            charge()
            require(declaration.accessFlags and 0x2000 != 0) { "retention owner is not an annotation declaration" }
            val metadata = declaration[SourceAttributes.ANNOTATIONS]
            val entries = if (metadata == null) emptyList() else when (metadata) {
                is AnnotationMetadata.Ready -> metadata.value.entries
                is AnnotationMetadata.Unavailable -> error("annotation retention metadata unavailable: ${metadata.reason.take(128)}")
            }
            charge(entries.size)
            var found: IrAnnotation? = null
            for (entry in entries) {
                val annotation = when (entry) {
                    is AnnotationMetadata.Ready -> entry.value
                    is AnnotationMetadata.Unavailable -> error("annotation retention metadata unavailable: ${entry.reason.take(128)}")
                }
                charge(annotation.type.className.length)
                if (annotation.type.className != "java.lang.annotation.Retention") continue
                require(found == null) { "duplicate annotation retention metadata" }
                found = annotation
            }
            if (found == null) DeclaredRetention(RetentionPolicy.CLASS, explicit = false)
            else {
                require(found.visibility == IrAnnotationVisibility.RUNTIME) { "annotation retention metadata must be runtime-visible" }
                val values = when (val metadata = found.values) {
                    is AnnotationMetadata.Ready -> metadata.value
                    is AnnotationMetadata.Unavailable -> error("annotation retention value unavailable: ${metadata.reason.take(128)}")
                }
                require(values.size == 1) { "invalid annotation retention value" }
                val value = values["value"] as? IrAnnotationValue.EnumConstant ?: error("invalid annotation retention value")
                charge(value.owner.className.length.toLong() + value.name.length)
                require(value.owner.className == "java.lang.annotation.RetentionPolicy") { "invalid annotation retention enum owner" }
                val policy = when (value.name) {
                    "SOURCE" -> RetentionPolicy.SOURCE
                    "CLASS" -> RetentionPolicy.CLASS
                    "RUNTIME" -> RetentionPolicy.RUNTIME
                    else -> error("invalid annotation retention enum value")
                }
                DeclaredRetention(policy, explicit = true)
            }
        }
        if (remaining > 0) retentions[declaration] = result
        return result
    }

    fun node(node: AttrNode): NodePlan {
        nodes[node]?.let { return it }
        val result = try {
            charge()
            build(node)
        } catch (failure: Exception) {
            rethrowCancellation(failure)
            NodePlan(problems = listOf(reason(failure)))
        }
        // A hostile stream of new identities after exhaustion must not grow a failure cache.
        if (remaining > 0) nodes[node] = result
        return result
    }

    /** Null means an external declaration is absent; loaded ambiguity is an explicit failure. */
    fun element(type: IrType.Object, name: String): IrMethod? {
        charge(type.className.length)
        charge(name.length)
        val declaration = root?.findClass(type.className) ?: return null
        val index = elementIndex(declaration)
        require(index.containsKey(name)) { "unknown annotation element" }
        return requireNotNull(index[name]) { "ambiguous or invalid annotation element" }
    }

    fun enumField(type: IrType.Object, name: String): IrField? {
        charge(type.className.length)
        charge(name.length)
        val declaration = root?.findClass(type.className) ?: return null
        require(declaration.accessFlags and 0x4000 != 0) { "annotation enum owner is not an enum" }
        val index = enumFields[declaration] ?: run {
            charge(declaration.fields.size)
            val result = LinkedHashMap<String, IrField?>()
            for (field in declaration.fields) {
                charge(field.name.length)
                if (result.containsKey(field.name)) result[field.name] = null
                else result[field.name] = field.takeIf {
                    it.accessFlags and 0x4008 == 0x4008 &&
                        OwnedFieldTypes.equal(it.type, type) { amount -> charge(amount); true } == true
                }
            }
            result.also { enumFields[declaration] = it }
        }
        require(index.containsKey(name)) { "unknown annotation enum constant" }
        return requireNotNull(index[name]) { "ambiguous or invalid annotation enum constant" }
    }

    private fun elementIndex(cls: IrClass): Map<String, IrMethod?> {
        elements[cls]?.let { return it }
        require(cls.accessFlags and 0x2000 != 0) { "annotation owner is not an annotation declaration" }
        charge(cls.methods.size)
        val result = LinkedHashMap<String, IrMethod?>()
        for (method in cls.methods) {
            charge(method.name.length)
            if (method.isStatic || method.name.startsWith("<")) continue
            if (result.containsKey(method.name)) result[method.name] = null
            else result[method.name] = method.takeIf(::isElement)
        }
        return result.also { elements[cls] = it }
    }

    private fun isElement(method: IrMethod): Boolean = method.declaringClass.accessFlags and 0x2000 != 0 &&
        method.accessFlags and 0x409 == 0x401 && method.argTypes.isEmpty() &&
        method.returnType != IrType.VOID && !method.name.startsWith("<")

    private fun build(node: AttrNode): NodePlan {
        val problems = ArrayList<String>()
        val deferred = ArrayList<IrAnnotation>()
        val annotations = attachments(node[SourceAttributes.ANNOTATIONS], node, deferred, problems)
        if (node is IrClass && node.accessFlags and 0x2000 != 0) {
            // A source annotation implicitly extends exactly Annotation. JVM bytecode can expose
            // extra interfaces (or omit it), so silently dropping that graph changes assignability.
            charge(node.interfaces.size)
            val parent = node.interfaces.singleOrNull() as? IrType.Object
            if (parent != null) charge(parent.className.length)
            if (parent?.className != "java.lang.annotation.Annotation")
                problems.add("annotation superinterfaces cannot be preserved in source")
            charge(node.methods.size)
            for (method in node.methods) {
                if (method.isStatic) continue // Static bodies use the ordinary member path.
                charge(method.blocks.size)
                if (method.accessFlags != 0x401 || method.name.startsWith("<") || method.argTypes.isNotEmpty() ||
                    method.returnType == IrType.VOID || method.blocks.any { it.instructions.isNotEmpty() }) {
                    problems.add("annotation instance method cannot be preserved as a source element")
                }
            }
            val retention = retention(node)
            if (retention is AnnotationMetadata.Unavailable) problems.add(retention.reason)
        }
        var parameters = emptyList<List<AnnotationMetadata<IrAnnotation>>>()
        var default: AnnotationMetadata<IrAnnotationValue?> = AnnotationMetadata.Ready(null)
        if (node is IrMethod) {
            when (val metadata = node[SourceAttributes.PARAMETER_ANNOTATIONS]) {
                is AnnotationMetadata.Unavailable -> problems.add(metadata.reason.take(256))
                is AnnotationMetadata.Ready -> {
                    if (metadata.value.size > node.argTypes.size) problems.add("annotation parameter count exceeds descriptor")
                    else if (metadata.value.isNotEmpty() && metadata.value.size != node.argTypes.size)
                        problems.add("annotation parameter count differs from descriptor; source cannot preserve raw parameter mapping")
                    else {
                        charge(metadata.value.size)
                        parameters = metadata.value.map { attachments(AnnotationMetadata.Ready(it), node, deferred, problems) }
                    }
                }
                null -> Unit
            }
            default = when (val metadata = node[SourceAttributes.ANNOTATION_DEFAULT]) {
                null -> AnnotationMetadata.Ready(null)
                is AnnotationMetadata.Unavailable -> metadata
                is AnnotationMetadata.Ready -> capture {
                    metadata.value?.also {
                        require(isElement(node)) { "annotation default outside a valid annotation element" }
                        value(it, 0, node.returnType)
                    }
                }
            }
        }
        return NodePlan(annotations, deferred, parameters, default, problems)
    }

    private fun attachments(metadata: AnnotationMetadata<IrAnnotationSet>?, owner: AttrNode,
        deferred: MutableList<IrAnnotation>, problems: MutableList<String>): List<AnnotationMetadata<IrAnnotation>> {
        val set = when (metadata) {
            null -> return emptyList()
            is AnnotationMetadata.Unavailable -> return listOf(metadata)
            is AnnotationMetadata.Ready -> metadata.value
        }
        charge(set.entries.size)
        val result = ArrayList<AnnotationMetadata<IrAnnotation>>()
        var defaults = 0
        for (entry in set.entries) {
            if (entry is AnnotationMetadata.Unavailable) { result.add(entry); continue }
            val annotation = (entry as AnnotationMetadata.Ready).value
            charge(annotation.type.className.length)
            when (AnnotationSourceRouting.route(annotation)) {
                AnnotationSourceRouting.Route.DECLARATION -> result.add(capture { checked(annotation, 0) })
                AnnotationSourceRouting.Route.UNSUPPORTED -> result.add(AnnotationMetadata.Unavailable(
                    "unsupported annotation metadata ${annotation.type.className.take(128)}"))
                AnnotationSourceRouting.Route.DEFAULTS -> {
                    defaults++
                    if (defaults > 1) problems.add("duplicate annotation default container")
                    val checked = capture { checkDefaults(owner, annotation) }
                    if (checked is AnnotationMetadata.Unavailable) problems.add(checked.reason)
                }
                else -> deferred.add(annotation)
            }
        }
        return result
    }

    private fun checkDefaults(owner: AttrNode, annotation: IrAnnotation) {
        require(owner is IrClass && owner.accessFlags and 0x2000 != 0) { "annotation default container outside annotation class" }
        val entries = ready(annotation.values)
        require(entries.size == 1) { "invalid annotation default container" }
        val nested = entries["value"] as? IrAnnotationValue.Nested ?: error("invalid annotation default value")
        charge(nested.annotation.type.className.length.toLong() + owner.fullName.length)
        require(nested.annotation.type.className == owner.fullName && nested.annotation.visibility == null) { "wrong annotation default owner" }
        val defaults = ready(nested.annotation.values)
        charge(defaults.size)
        val index = elementIndex(owner)
        for ((name, default) in defaults) {
            charge(name.length)
            require(index.containsKey(name)) { "unknown annotation default" }
            val member = requireNotNull(index[name]) { "ambiguous or invalid annotation default member" }
            value(default, 0, member.returnType)
        }
    }

    private fun checked(annotation: IrAnnotation, depth: Int): IrAnnotation {
        enter(depth)
        charge(annotation.type.className.length)
        val declaration = root?.findClass(annotation.type.className)
        // Nested annotation values have no independent visibility/retention attachment.
        if (annotation.visibility != null) {
            // Only this fixed platform declaration is intrinsic. It proves retention, not source
            // naming or ancestor scope. Explicitly loaded metadata remains authoritative.
            val contract = if (declaration != null) ready(retention(declaration))
                else if (annotation.type.className == "java.lang.Override") PLATFORM_OVERRIDE_RETENTION else null
            if (contract != null) {
                val retention = contract.policy
                val matches = retention == RetentionPolicy.CLASS && annotation.visibility == IrAnnotationVisibility.BUILD ||
                    retention == RetentionPolicy.RUNTIME && annotation.visibility == IrAnnotationVisibility.RUNTIME
                require(matches) {
                    if (declaration != null) "annotation attachment visibility differs from loaded retention contract"
                    else "annotation java.lang.Override: encoded ${annotation.visibility} visibility conflicts with $retention retention"
                }
            }
        }
        val values = ready(annotation.values)
        charge(values.size)
        for ((name, value) in values) {
            val member = element(annotation.type, name)
            value(value, depth + 1, member?.returnType)
        }
        // A loaded declaration proves which omitted elements need defaults. Do not fabricate
        // values, or let a malformed default silently turn an incomplete annotation into a valid one.
        if (declaration != null) {
            for ((name, candidate) in elementIndex(declaration)) {
                charge(name.length + 1)
                val member = requireNotNull(candidate) { "ambiguous or invalid annotation element" }
                if (name in values) continue
                val metadata = member[SourceAttributes.ANNOTATION_DEFAULT]
                    ?: error("missing required annotation element ${name.take(128)}")
                val default = ready(metadata)
                    ?: error("missing required annotation element ${name.take(128)}")
                value(default, depth + 1, member.returnType)
            }
        }
        return annotation
    }

    private fun value(value: IrAnnotationValue, depth: Int, expected: IrType? = null) {
        enter(depth)
        if (expected != null) {
            type(expected, depth + 1)
            val matches = when (value) {
                is IrAnnotationValue.Primitive -> value.type == expected && expected != IrType.VOID
                is IrAnnotationValue.Str -> expected == IrType.STRING
                is IrAnnotationValue.ClassLiteral -> expected == IrType.objectType("java.lang.Class")
                is IrAnnotationValue.EnumConstant -> expected == value.owner
                is IrAnnotationValue.Nested -> expected == value.annotation.type
                is IrAnnotationValue.ArrayValue -> expected is IrType.ArrayType && expected.element !is IrType.ArrayType
            }
            require(matches) { "annotation value type differs from element declaration" }
        }
        when (value) {
            is IrAnnotationValue.Primitive -> Unit
            is IrAnnotationValue.Str -> charge(value.value.length)
            is IrAnnotationValue.ClassLiteral -> type(value.type, depth + 1)
            is IrAnnotationValue.EnumConstant -> enumField(value.owner, value.name)
            is IrAnnotationValue.ArrayValue -> {
                charge(value.values.size)
                value.values.forEach { value(it, depth + 1, (expected as? IrType.ArrayType)?.element) }
            }
            is IrAnnotationValue.Nested -> {
                require(value.annotation.visibility == null) { "nested annotation has declaration visibility" }
                checked(value.annotation, depth + 1)
            }
        }
    }

    private fun type(type: IrType, depth: Int) {
        enter(depth)
        when (type) {
            is IrType.Object -> charge(type.className.length)
            is IrType.ArrayType -> type(type.element, depth + 1)
            is IrType.Primitive -> Unit
            else -> error("unsupported annotation class literal type")
        }
    }

    private fun enter(depth: Int) {
        require(depth <= 32) { "annotation source nesting budget exhausted" }
        charge()
    }
    private fun charge(amount: Int = 1) = charge(amount.toLong())
    private fun charge(amount: Long) {
        if (amount < 0 || amount > remaining) {
            remaining = 0
            throw IllegalArgumentException("annotation source planning budget exhausted")
        }
        remaining -= amount.toInt()
    }
    private fun <T> ready(metadata: AnnotationMetadata<T>): T = when (metadata) {
        is AnnotationMetadata.Ready -> metadata.value
        is AnnotationMetadata.Unavailable -> error(metadata.reason.take(256))
    }
    private fun <T> capture(action: () -> T): AnnotationMetadata<T> = try {
        AnnotationMetadata.Ready(action())
    } catch (failure: Exception) {
        rethrowCancellation(failure)
        AnnotationMetadata.Unavailable(reason(failure))
    }
    private fun rethrowCancellation(failure: Exception) { if (failure is CancellationException) throw failure }
    private fun reason(failure: Exception): String = (failure.message ?: "annotation planning failed").take(256)
}
