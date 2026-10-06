package com.jadxmp.input.jvm

import com.jadxmp.input.ClassDeclarationData
import com.jadxmp.input.FieldDeclarationData
import com.jadxmp.input.MethodDeclarationData

/** Reads classpath type information without adapting or lowering executable method bodies. */
internal object JvmDeclarationReader {
    fun read(file: ClassFile): ClassDeclarationData {
        JvmDeclarationLimits.check(file)
        val metadata = JvmClassMetadata.read(file)
        val descriptors = mutableMapOf<String, JvmMethodDescriptor>()
        return ClassDeclarationData(
            type = "L${file.name};",
            accessFlags = file.accessFlags,
            superType = file.superName?.let { "L$it;" },
            interfaces = file.interfaces.map { "L$it;" },
            fields = file.fields.map {
                FieldDeclarationData(it.name, it.descriptor, it.accessFlags, JvmGenericSignatures.read(it.attributes, file.constants))
            },
            methods = file.methods.map {
                val descriptor = descriptors.getOrPut(it.descriptor) { JvmMethodDescriptor.parse(it.descriptor) }
                MethodDeclarationData(it.name, descriptor.parameterTypes, descriptor.returnType, it.accessFlags,
                    JvmGenericSignatures.read(it.attributes, file.constants))
            },
            genericSignature = JvmGenericSignatures.read(file.attributes, file.constants),
            nesting = metadata.nesting,
            innerAccessFlags = metadata.innerAccessFlags,
        )
    }
}
