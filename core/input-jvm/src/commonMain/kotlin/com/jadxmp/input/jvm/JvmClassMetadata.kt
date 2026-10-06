package com.jadxmp.input.jvm

import com.jadxmp.input.ClassNesting
import com.jadxmp.input.MethodRef
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/** Class declaration metadata, decoded separately from the structural envelope and method bodies. */
internal data class JvmClassMetadata(
    val sourceFile: String?,
    val nesting: ClassNesting?,
    val innerAccessFlags: Int?,
) {
    companion object {
        fun read(file: ClassFile): JvmClassMetadata {
            val names = Names(file)
            val sourceFile = single(file, "SourceFile")?.let { attribute ->
                decode(attribute) { file.constants.utf8(readU16BE()) }
            }
            val inner = single(file, "InnerClasses")?.let { readInnerClasses(file, it, names) }
            val enclosing = single(file, "EnclosingMethod")?.let { attribute ->
                decode(attribute) {
                    val owner = names.className(readU16BE())
                    if (owner.isCurrent) invalid("self enclosing class")
                    val methodIndex = readU16BE()
                    val method = if (methodIndex == 0) null else {
                        val ref = file.constants.entry(methodIndex) as? JvmConstant.NameAndType
                            ?: invalid("EnclosingMethod requires a NameAndType constant")
                        val name = file.constants.memberName(ref.nameIndex, method = true)
                        val descriptor = JvmMethodDescriptor.parse(file.constants.descriptor(ref.descriptorIndex, method = true))
                        JvmMetadataMethodRef("L${owner.value};", name, descriptor)
                    }
                    ClassNesting.Nested("L${owner.value};", method, inner?.name?.value)
                }
            }
            if (inner?.owner != null && enclosing != null) invalid("member class also has EnclosingMethod metadata")
            val nesting = when {
                enclosing != null -> enclosing
                inner?.owner != null -> ClassNesting.Nested("L${inner.owner.value};", innerName = inner.name?.value)
                // Older class files can describe an anonymous/local class without EnclosingMethod.
                // Retain unknown enclosure rather than asserting a false top-level relationship.
                inner != null -> null
                else -> ClassNesting.TopLevel
            }
            return JvmClassMetadata(sourceFile, nesting, inner?.flags)
        }

        private fun single(file: ClassFile, name: String): JvmAttribute? {
            val attributes = file.attributes.filter { it.name == name }
            if (attributes.size > 1) invalid("duplicate $name attribute")
            return attributes.singleOrNull()
        }

        private fun readInnerClasses(file: ClassFile, attribute: JvmAttribute, names: Names): Inner? = decode(attribute) {
            val count = readU16BE()
            requireAvailable(count.toLong() * 8)
            var own: Inner? = null
            val seen = HashSet<Int>()
            repeat(count) {
                val classIndex = readU16BE()
                if (!seen.add(classIndex)) invalid("duplicate InnerClasses entry")
                val name = names.className(classIndex)
                val outerIndex = readU16BE()
                val outer = if (outerIndex == 0) null else names.className(outerIndex)
                if (outer == name) invalid("self InnerClasses enclosure")
                val nameIndex = readU16BE()
                val innerName = if (nameIndex == 0) null else names.text(nameIndex)
                if (file.majorVersion >= 51 && innerName == null && outer != null) invalid("anonymous class has member owner")
                val flags = readU16BE()
                if (name.isCurrent) {
                    // Reserved bits are ignored as required by JVMS 4.7.6.
                    val entry = Inner(outer, innerName, flags and 0x761f)
                    // Distinct Class constants may name the same class. Their declaration metadata
                    // must agree, but rejecting matching rows would reject valid JVM class files.
                    if (own != null && own != entry) invalid("contradictory InnerClasses entries for current class")
                    own = entry
                }
            }
            own
        }

        private inline fun <T> decode(attribute: JvmAttribute, read: ByteReader.() -> T): T {
            val reader = ByteReader(attribute.bytes)
            val result = reader.read()
            if (reader.remaining != 0) invalid("trailing bytes in ${attribute.name} attribute")
            return result
        }

        private fun invalid(message: String): Nothing = throw ByteReaderException("invalid JVM class metadata: $message")
    }

    private data class Inner(val owner: Name?, val name: Name?, val flags: Int)

    /** Canonical identities keep repeated long shared UTF8 names out of the per-entry comparisons. */
    private class Name(val value: String, val isCurrent: Boolean)

    private class Names(private val file: ClassFile) {
        private val byIndex = HashMap<Int, Name>()
        private val byValue = HashMap<String, Name>()

        fun text(index: Int): Name = byIndex.getOrPut(index) {
            val value = file.constants.utf8(index)
            byValue.getOrPut(value) { Name(value, value == file.name) }
        }

        fun className(index: Int): Name {
            val ref = file.constants.entry(index) as? JvmConstant.ClassRef
                ?: throw ByteReaderException("invalid JVM class metadata: expected class constant at $index")
            file.constants.className(index, allowArray = false)
            return text(ref.nameIndex)
        }
    }
}

private class JvmMetadataMethodRef(
    override val declaringClassType: String,
    override val name: String,
    private val descriptor: JvmMethodDescriptor,
) : MethodRef {
    override val parameterTypes get() = descriptor.parameterTypes
    override val returnType get() = descriptor.returnType
}
