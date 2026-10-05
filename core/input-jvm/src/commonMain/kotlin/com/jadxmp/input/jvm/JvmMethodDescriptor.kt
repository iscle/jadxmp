package com.jadxmp.input.jvm

/** Parsed once at a method boundary, shared by frame initialization and the native input adapter. */
internal class JvmMethodDescriptor private constructor(
    val text: String,
    val parameterTypes: List<String>,
    val returnType: String,
    val argumentSlots: Int,
) {
    companion object {
        fun parse(descriptor: String): JvmMethodDescriptor {
            val words = JvmDescriptors.method(descriptor)
            val parameters = mutableListOf<String>()
            var position = 1
            while (descriptor[position] != ')') {
                val start = position
                while (descriptor[position] == '[') position++
                position = if (descriptor[position] == 'L') descriptor.indexOf(';', position) + 1 else position + 1
                parameters += descriptor.substring(start, position)
            }
            return JvmMethodDescriptor(descriptor, parameters, descriptor.substring(position + 1), words)
        }
    }
}
