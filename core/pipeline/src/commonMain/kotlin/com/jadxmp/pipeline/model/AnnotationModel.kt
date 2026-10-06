package com.jadxmp.pipeline.model

import com.jadxmp.input.AnnotationData
import com.jadxmp.input.AnnotationVisibility
import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType
import com.jadxmp.input.FieldRef
import com.jadxmp.ir.annotation.AnnotationMetadata
import com.jadxmp.ir.annotation.IrAnnotation
import com.jadxmp.ir.annotation.IrAnnotationSet
import com.jadxmp.ir.annotation.IrAnnotationValue
import com.jadxmp.ir.annotation.IrAnnotationVisibility
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.pass.CancellationSignal
import kotlin.coroutines.cancellation.CancellationException

/** One bounded snapshot per class. Neither input-owned mutable containers nor Any payloads enter IR. */
internal class AnnotationModel {
    private var remainingNodes = 20_000
    private var remainingText = 1_000_000

    fun annotations(read: () -> List<AnnotationData>): AnnotationMetadata<IrAnnotationSet> = capture {
        annotationList(read(), 0)
    }

    fun parameters(count: Int, read: () -> List<List<AnnotationData>>): AnnotationMetadata<List<IrAnnotationSet>> = capture {
        val parameters = read()
        require(parameters.size <= count) { "annotation parameter count exceeds descriptor" }
        charge(parameters.size)
        parameters.map { annotationList(it, 0) }
    }

    fun default(read: () -> EncodedValue?): AnnotationMetadata<IrAnnotationValue?> = capture {
        read()?.let { value(it, 0) }
    }

    private fun annotationList(source: List<AnnotationData>, depth: Int): IrAnnotationSet {
        charge(source.size)
        return IrAnnotationSet(source.map { sourceAnnotation ->
            when (val result = capture { annotation(sourceAnnotation, depth, nested = false) }) {
                is AnnotationMetadata.Ready -> result
                is AnnotationMetadata.Unavailable -> result.copy(
                    reason = "annotation ${sourceAnnotation.annotationType.take(128)}: ${result.reason}".take(256),
                )
            }
        })
    }

    private fun annotation(source: AnnotationData, depth: Int, nested: Boolean): IrAnnotation {
        enter(depth)
        require((source.visibility == null) == nested) { "invalid annotation visibility for nesting context" }
        val type = type(source.annotationType) as? IrType.Object ?: error("annotation type is not a class")
        // A declaration's header can identify independently handled system metadata even if its
        // payload is not source-representable. Nested failures still invalidate their containing
        // value/default atomically; no partially decoded nested annotation is published as ready.
        val values = if (nested) AnnotationMetadata.Ready(annotationValues(source, depth))
            else capture { annotationValues(source, depth) }
        val visibility = when (source.visibility) {
            AnnotationVisibility.BUILD -> IrAnnotationVisibility.BUILD
            AnnotationVisibility.RUNTIME -> IrAnnotationVisibility.RUNTIME
            AnnotationVisibility.SYSTEM -> IrAnnotationVisibility.SYSTEM
            null -> null
        }
        return IrAnnotation(type, visibility, values)
    }

    private fun annotationValues(source: AnnotationData, depth: Int): Map<String, IrAnnotationValue> {
        charge(source.values.size)
        val values = LinkedHashMap<String, IrAnnotationValue>()
        for ((name, encoded) in source.values) {
            text(name)
            values[name] = value(encoded, depth + 1)
        }
        return values
    }

    private fun value(source: EncodedValue, depth: Int): IrAnnotationValue {
        enter(depth)
        fun primitive(bits: Long, type: IrType) = IrAnnotationValue.Primitive(bits, type)
        return when (source.type) {
            EncodedValueType.BOOLEAN -> primitive(if (source.value as Boolean) 1 else 0, IrType.BOOLEAN)
            EncodedValueType.BYTE -> primitive((source.value as Byte).toLong(), IrType.BYTE)
            EncodedValueType.SHORT -> primitive((source.value as Short).toLong(), IrType.SHORT)
            EncodedValueType.CHAR -> primitive((source.value as Char).code.toLong(), IrType.CHAR)
            EncodedValueType.INT -> primitive((source.value as Int).toLong(), IrType.INT)
            EncodedValueType.LONG -> primitive(source.value as Long, IrType.LONG)
            // The input SPI currently provides host numeric values; this cannot recover payload bits
            // already changed by an input plugin/host (notably signaling NaNs on JavaScript).
            EncodedValueType.FLOAT -> primitive((source.value as Float).toRawBits().toLong(), IrType.FLOAT)
            EncodedValueType.DOUBLE -> primitive((source.value as Double).toRawBits(), IrType.DOUBLE)
            EncodedValueType.STRING -> (source.value as String).let { text(it); IrAnnotationValue.Str(it) }
            EncodedValueType.TYPE -> IrAnnotationValue.ClassLiteral(type(source.value as String))
            EncodedValueType.ENUM -> {
                val ref = source.value as FieldRef
                val owner = type(ref.declaringClassType) as? IrType.Object ?: error("enum owner is not a class")
                require(type(ref.type) == owner) { "enum value type differs from owner" }
                text(ref.name)
                IrAnnotationValue.EnumConstant(owner, ref.name)
            }
            EncodedValueType.ARRAY -> {
                val values = source.value as List<*>
                charge(values.size)
                IrAnnotationValue.ArrayValue(values.map { value(it as EncodedValue, depth + 1) })
            }
            EncodedValueType.ANNOTATION -> IrAnnotationValue.Nested(annotation(source.value as AnnotationData, depth + 1, nested = true))
            else -> error("unsupported annotation value kind: ${source.type}")
        }
    }

    private fun type(descriptor: String): IrType {
        text(descriptor)
        require(descriptor.length in 1..65_535) { "invalid annotation type descriptor length" }
        if (descriptor.startsWith('[')) return Descriptors.parseArrayType(descriptor)
        if (descriptor.length == 1 && descriptor[0] in "VZBSCIJFD") return Descriptors.parseType(descriptor)
        require(descriptor.startsWith('L') && descriptor.endsWith(';') && descriptor.length > 2) { "invalid annotation type descriptor" }
        val name = descriptor.substring(1, descriptor.length - 1)
        require(!name.startsWith('/') && !name.endsWith('/') && "//" !in name &&
            name.none { it == '.' || it == ';' || it == '[' }) { "invalid annotation class name" }
        return Descriptors.parseClassType(descriptor)
    }

    private fun enter(depth: Int) {
        require(depth <= 32) { "annotation nesting limit exceeded" }
        charge(1)
    }

    private fun charge(nodes: Int) {
        require(nodes <= remainingNodes) { "annotation node budget exhausted" }
        remainingNodes -= nodes
    }

    private fun text(value: String) {
        require(value.length <= remainingText) { "annotation text budget exhausted" }
        remainingText -= value.length
    }

    private inline fun <T> capture(read: () -> T): AnnotationMetadata<T> = try {
        AnnotationMetadata.Ready(read())
    } catch (cancelled: CancellationSignal) {
        throw cancelled
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Bound third-party exception messages as well as parsed input. Bodies remain independently loadable.
        AnnotationMetadata.Unavailable((failure.message ?: "unreadable annotation metadata").take(256))
    }
}
