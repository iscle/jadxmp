package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/**
 * Bounded class-file envelope parser (JVMS 4.1, 4.5–4.7), independent of bytecode lowering.
 * Supports format versions through Java 21, including preview minor versions. This is structural
 * parsing, not bytecode verification: method bodies and recognized attributes are decoded separately.
 */
internal object ClassFileParser {
    fun parse(bytes: ByteArray): ClassFile {
        val reader = ByteReader(bytes)
        if (reader.readU32BE() != 0xCAFEBABEL) throw ByteReaderException("invalid JVM class-file magic")
        val minor = reader.readU16BE()
        val major = reader.readU16BE()
        if (major !in 45..65 || major >= 56 && minor != 0 && minor != 65535) {
            throw ByteReaderException("unsupported JVM class-file version $major.$minor")
        }
        val constants = JvmConstantPool.read(reader, major)
        val flags = reader.readU16BE()
        val name = constants.className(reader.readU16BE(), allowArray = false)
        val superIndex = reader.readU16BE()
        val superName = if (superIndex == 0) null else constants.className(superIndex, allowArray = false)
        val interfaceCount = reader.readU16BE()
        reader.requireAvailable(interfaceCount.toLong() * 2)
        val interfaces = List(interfaceCount) {
            constants.className(reader.readU16BE(), allowArray = false)
        }
        val fields = members(reader, constants, methods = false)
        val methods = members(reader, constants, methods = true)
        val attributes = attributes(reader, constants)
        if (reader.remaining != 0) throw ByteReaderException("trailing bytes after JVM class file")
        return ClassFile(minor, major, constants, flags, name, superName, interfaces, fields, methods, attributes)
    }

    private fun members(reader: ByteReader, constants: JvmConstantPool, methods: Boolean): List<JvmMember> {
        val count = reader.readU16BE()
        reader.requireAvailable(count.toLong() * 8)
        return List(count) {
            val flags = reader.readU16BE()
            val name = constants.memberName(reader.readU16BE(), methods)
            val descriptorIndex = reader.readU16BE()
            val descriptor = constants.descriptor(descriptorIndex, methods)
            if (methods && flags and 0x0008 == 0 && constants.methodParameterSlots(descriptorIndex) >= 255) {
                throw ByteReaderException("JVM instance method parameter slots exceed 255 including receiver")
            }
            if (methods && (name == "<init>" && !descriptor.endsWith(")V") ||
                    name == "<clinit>" && descriptor != "()V")) {
                throw ByteReaderException("invalid JVM initializer descriptor $name$descriptor")
            }
            JvmMember(flags, name, descriptor, attributes(reader, constants))
        }
    }

    internal fun attributes(reader: ByteReader, constants: JvmConstantPool): List<JvmAttribute> {
        val count = reader.readU16BE()
        reader.requireAvailable(count.toLong() * 6)
        return List(count) {
            val name = constants.utf8(reader.readU16BE())
            val length = reader.readU32BE()
            reader.requireAvailable(length)
            val offset = reader.position
            JvmAttribute(name, offset, reader.readBytes(length.toInt()))
        }
    }
}
