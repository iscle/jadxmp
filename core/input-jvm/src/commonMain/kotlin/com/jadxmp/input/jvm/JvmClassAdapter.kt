package com.jadxmp.input.jvm

import com.jadxmp.input.AccessFlags
import com.jadxmp.input.AnnotationData
import com.jadxmp.input.ClassData
import com.jadxmp.input.CodeReader
import com.jadxmp.input.FieldData
import com.jadxmp.input.MethodData
import com.jadxmp.input.MethodRef
import com.jadxmp.io.ByteReaderException
import kotlin.coroutines.cancellation.CancellationException

/** Native declaration adapter. No DEX annotations or instructions are manufactured here. */
internal object JvmClassAdapter {
    fun adapt(file: ClassFile, name: String): ClassData {
        validateClass(file)
        checkAttributes(file.attributes)
        val metadata = JvmClassMetadata.read(file)
        val owner = "L${file.name};"
        val descriptors = mutableMapOf<String, JvmMethodDescriptor>()
        val fields = file.fields.map { field ->
            checkAttributes(field.attributes)
            val flags = flags(field.accessFlags, field.attributes)
            checkVisibility(flags)
            if (flags and AccessFlags.FINAL != 0 && flags and AccessFlags.VOLATILE != 0) invalid("final volatile field ${field.name}")
            val constant = JvmFieldConstants.read(field, file.constants)
            object : FieldData {
                override val declaringClassType = owner
                override val name = field.name
                override val type = field.descriptor
                override val accessFlags = flags
                override val annotations = emptyList<AnnotationData>()
                override val constValue = constant
            }
        }
        val methods = file.methods.map { method ->
            val descriptor = descriptors.getOrPut(method.descriptor) { JvmMethodDescriptor.parse(method.descriptor) }
            val ref = object : MethodRef {
                override val declaringClassType = owner
                override val name = method.name
                override val parameterTypes = descriptor.parameterTypes
                override val returnType = descriptor.returnType
            }
            // Semantic declaration checks belong in the guarded body provider too: an unsupported
            // annotation or malformed Code on one method must not discard valid sibling bodies.
            val body = JvmMethodBody {
                checkAttributes(method.attributes)
                validateMethod(method, file)
                val code = method.attributes.filter { it.name == "Code" }
                val absent = method.accessFlags and (AccessFlags.ABSTRACT or AccessFlags.NATIVE) != 0
                if (absent) {
                    if (code.isNotEmpty()) invalid("abstract/native method ${method.name} has Code")
                    null
                } else {
                    if (code.size != 1) invalid("method ${method.name} requires exactly one Code attribute")
                    val parsedCode = JvmCodeAttribute.parse(code.single(), file.constants)
                    // Type-use annotations may live inside Code (for local variables/casts), not
                    // on the declaration. They require the same explicit unsupported diagnostic.
                    checkAttributes(parsedCode.attributes)
                    JvmPrimitiveNormalizer.normalize(file.name, method, descriptor, file.constants, parsedCode)
                }
            }
            object : MethodData {
                override val ref = ref
                override val accessFlags = method.accessFlags or
                    (if (method.name == "<init>") AccessFlags.CONSTRUCTOR else 0) or
                    (if (method.attributes.any { it.name == "Synthetic" }) AccessFlags.SYNTHETIC else 0)
                override val annotations = emptyList<AnnotationData>()
                override val parameterAnnotations = List(descriptor.parameterTypes.size) { emptyList<AnnotationData>() }
                override val codeReader get() = body.read()
            }
        }
        return object : ClassData {
            override val type = owner
            override val accessFlags = flags(file.accessFlags, file.attributes)
            override val nesting = metadata.nesting
            override val innerAccessFlags = metadata.innerAccessFlags
            override val superType = file.superName?.let { "L$it;" }
            override val interfaces = file.interfaces.map { "L$it;" }
            override val sourceFile = metadata.sourceFile
            override val fields = fields
            override val methods = methods
            override val annotations = emptyList<AnnotationData>()
            override val inputFileName = name
            override fun disassemble(): String = disassemble(file)
        }
    }

    private fun validateClass(file: ClassFile) {
        // JvmMember currently retains strings rather than pool identities. Bound hashing and
        // repeated materialization before any duplicate/signature maps scan those shared strings.
        var characters = file.name.length.toLong() + (file.superName?.length ?: 0)
        for (type in file.interfaces) characters += type.length
        for (attribute in file.attributes) characters += attribute.name.length
        for (member in file.fields + file.methods) {
            characters += member.name.length + member.descriptor.length
            for (attribute in member.attributes) characters += attribute.name.length
        }
        if (characters > 10_000_000) invalid("declaration work limit exceeded")
        val flags = file.accessFlags
        if (flags and AccessFlags.MODULE != 0) invalid("module descriptors are not supported yet")
        if (flags and AccessFlags.FINAL != 0 && flags and AccessFlags.ABSTRACT != 0) invalid("final abstract class")
        val isInterface = flags and AccessFlags.INTERFACE != 0
        if (isInterface && (flags and AccessFlags.ABSTRACT == 0 ||
                flags and (AccessFlags.FINAL or AccessFlags.SUPER or AccessFlags.ENUM) != 0 || file.superName != "java/lang/Object")) {
            invalid("illegal interface declaration")
        }
        if (flags and AccessFlags.ANNOTATION != 0) invalid("annotation declarations are not supported yet")
        if (file.name == file.superName || (file.superName == null) != (file.name == "java/lang/Object")) invalid("illegal superclass")
        if (file.name in file.interfaces || file.interfaces.size != file.interfaces.toSet().size) invalid("illegal interface list")
        if (file.fields.map { it.name to it.descriptor }.toSet().size != file.fields.size) invalid("duplicate field declaration")
        if (file.methods.map { it.name to it.descriptor }.toSet().size != file.methods.size) invalid("duplicate method declaration")
    }

    private fun validateMethod(method: JvmMember, file: ClassFile) {
        val flags = flags(method.accessFlags, method.attributes)
        checkVisibility(flags)
        if (flags and AccessFlags.ABSTRACT != 0) {
            val forbidden = AccessFlags.PRIVATE or AccessFlags.STATIC or AccessFlags.FINAL or AccessFlags.SYNCHRONIZED or AccessFlags.NATIVE
            if (flags and forbidden != 0 || file.accessFlags and AccessFlags.ABSTRACT == 0) invalid("illegal abstract method ${method.name}")
        }
        if (method.name == "<init>" && (method.descriptor.last() != 'V' ||
                flags and (AccessFlags.STATIC or AccessFlags.ABSTRACT or AccessFlags.NATIVE) != 0 ||
                file.accessFlags and AccessFlags.INTERFACE != 0)) invalid("illegal constructor declaration")
        if (method.name == "<clinit>" && (method.descriptor != "()V" ||
                file.majorVersion >= 51 && flags and AccessFlags.STATIC == 0)) invalid("illegal class initializer declaration")
    }

    private fun flags(flags: Int, attributes: List<JvmAttribute>): Int {
        val synthetic = attributes.filter { it.name == "Synthetic" }
        if (synthetic.size > 1 || synthetic.any { it.bytes.isNotEmpty() }) invalid("malformed Synthetic attribute")
        return flags or if (synthetic.isEmpty()) 0 else AccessFlags.SYNTHETIC
    }

    private fun checkVisibility(flags: Int) {
        if ((flags and 7).countOneBits() > 1) invalid("contradictory visibility flags")
    }

    private fun checkAttributes(attributes: List<JvmAttribute>) {
        for (attribute in attributes) {
            if (attribute.name in UNSUPPORTED_DECLARATIONS) {
                invalid("unsupported semantic declaration attribute ${attribute.name}")
            }
        }
    }

    private fun disassemble(file: ClassFile): String = buildString {
        append("class ").append(file.name).append('\n')
        for (method in file.methods) {
            append("method ").append(method.name).append(method.descriptor).append('\n')
            try {
                val attributes = method.attributes.filter { it.name == "Code" }
                if (attributes.size > 1) invalid("duplicate Code attribute")
                attributes.singleOrNull()?.let { attribute ->
                    for (instruction in JvmCodeAttribute.parse(attribute, file.constants).decodeInstructions()) {
                        append("  ").append(instruction.offset).append(": ").append(JvmOpcodeNames.name(instruction))
                        if (instruction.operand != JvmOperand.None) append(' ').append(instruction.operand)
                        append('\n')
                    }
                }
            } catch (failure: ByteReaderException) {
                append("  ERROR: ").append(failure.message).append('\n')
            }
        }
    }

    private val UNSUPPORTED_DECLARATIONS = setOf(
        "Signature", "Exceptions", "AnnotationDefault", "MethodParameters", "Deprecated",
        "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations",
        "RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations",
        "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations",
        "Record", "PermittedSubclasses", "NestHost", "NestMembers",
    )
    private fun invalid(message: String): Nothing = throw ByteReaderException("JVM class input: $message")
}

/** Cache successful readers and ordinary failures. Cancellation never poisons later access. */
internal class JvmMethodBody(private val provider: () -> CodeReader?) {
    private var result: Result<CodeReader?>? = null
    fun read(): CodeReader? {
        result?.let { return it.getOrThrow() }
        val loaded = try {
            Result.success(provider())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        result = loaded
        return loaded.getOrThrow()
    }
}
