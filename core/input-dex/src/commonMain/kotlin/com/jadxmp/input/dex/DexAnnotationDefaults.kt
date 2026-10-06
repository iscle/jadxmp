package com.jadxmp.input.dex

import com.jadxmp.input.AccessFlags
import com.jadxmp.input.MethodData
import kotlin.coroutines.cancellation.CancellationException
import com.jadxmp.input.AnnotationData
import com.jadxmp.input.AnnotationVisibility
import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType

/** DEX's class-level system wrapper is normalized to the input SPI's per-method default. */
internal class DexAnnotationDefaults(
    private val classType: String,
    private val annotations: List<AnnotationData>,
    private val accessFlags: Int,
    private val methods: () -> List<MethodData>,
) {
    // Cache failures too: malformed shared metadata must not be rescanned for every method.
    private var remainingText = 1_000_000
    private val parsed: Result<Map<String, EncodedValue>> by lazy {
        try {
            Result.success(parse())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    fun value(name: String): EncodedValue? {
        val values = parsed.getOrThrow()
        if (values.isEmpty()) return null
        text(name) // Hashing a lookup key also costs work on JS; charge every use.
        return values[name]
    }

    private fun text(value: String) {
        require(value.length <= remainingText) { "annotation default text budget exhausted" }
        remainingText -= value.length
    }

    private fun parse(): Map<String, EncodedValue> {
        require(annotations.size <= 20_000) { "annotation default container budget exceeded" }
        val containers = annotations.filter {
            text(it.annotationType)
            it.annotationType == DEFAULT_ANNOTATION
        }
        if (containers.isEmpty()) return emptyMap()
        require(accessFlags and AccessFlags.ANNOTATION != 0) { "annotation defaults on ordinary class" }
        require(containers.size == 1) { "duplicate annotation default container" }
        val container = containers.single()
        require(container.visibility == AnnotationVisibility.SYSTEM) { "annotation default container is not system metadata" }
        require(container.values.size == 1) { "invalid annotation default container elements" }
        val value = container.values["value"]
        require(value?.type == EncodedValueType.ANNOTATION) { "annotation default container has no nested annotation" }
        val nested = value.value as? AnnotationData ?: throw IllegalArgumentException("invalid annotation default payload")
        text(nested.annotationType)
        text(classType)
        require(nested.annotationType == classType && nested.visibility == null) { "annotation default owner mismatch" }
        require(nested.values.size <= 20_000) { "annotation default element budget exceeded" }
        val declared = methods()
        require(declared.size <= 20_000) { "annotation default method budget exceeded" }
        val byName = HashMap<String, MethodData>()
        val ambiguous = HashSet<String>()
        for (method in declared) {
            val name = method.ref.name
            text(name)
            if (byName.put(name, method) != null) ambiguous.add(name)
        }
        val result = LinkedHashMap<String, EncodedValue>()
        for ((name, value) in nested.values) {
            text(name)
            val method = byName[name]
            require(method != null && name !in ambiguous && method.ref.parameterTypes.isEmpty() &&
                method.ref.returnType != "V" && !name.startsWith('<') &&
                method.accessFlags and (AccessFlags.PUBLIC or AccessFlags.ABSTRACT or AccessFlags.STATIC) ==
                    (AccessFlags.PUBLIC or AccessFlags.ABSTRACT)) { "annotation default does not name a unique eligible element" }
            result[name] = value
        }
        return result
    }

    private companion object {
        const val DEFAULT_ANNOTATION = "Ldalvik/annotation/AnnotationDefault;"
    }
}
