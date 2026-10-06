package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/** Raw bits retain NaN payloads and signed zero until instruction normalization. */
internal sealed interface JvmConstant {
    data class Utf8(val value: String) : JvmConstant
    data class IntegerValue(val value: Int) : JvmConstant
    data class FloatBits(val bits: Int) : JvmConstant
    data class LongValue(val value: Long) : JvmConstant
    data class DoubleBits(val bits: Long) : JvmConstant
    data class ClassRef(val nameIndex: Int) : JvmConstant
    data class StringRef(val stringIndex: Int) : JvmConstant
    data class MemberRef(val tag: Int, val classIndex: Int, val nameAndTypeIndex: Int) : JvmConstant
    data class NameAndType(val nameIndex: Int, val descriptorIndex: Int) : JvmConstant
    data class MethodHandle(val kind: Int, val referenceIndex: Int) : JvmConstant
    data class MethodType(val descriptorIndex: Int) : JvmConstant
    data class Dynamic(val tag: Int, val bootstrapIndex: Int, val nameAndTypeIndex: Int) : JvmConstant
    data class ModuleRef(val nameIndex: Int) : JvmConstant
    data class PackageRef(val nameIndex: Int) : JvmConstant
}

/**
 * JVMS 4.4 constant-pool reader. Read all entries before validating forward references. Resolution
 * checks each expected tag without recursive expansion, so cycles cannot cause stack overflow.
 * Bootstrap indices are deliberately retained for validation against the class attributes later.
 */
internal class JvmConstantPool private constructor(
    private val entries: List<JvmConstant?>,
    val majorVersion: Int,
) {
    // Each UTF8 entry is scanned at most once per semantic context. Repeating references to a
    // 65 KiB string must not turn a small class file into billions of validation steps.
    private val validated = IntArray(entries.size)
    private val parameterSlots = IntArray(entries.size)

    private enum class Syntax {
        CLASS, DECLARED_CLASS, FIELD_NAME, METHOD_NAME, FIELD_DESCRIPTOR, METHOD_DESCRIPTOR,
    }

    fun entry(index: Int): JvmConstant = entries.getOrNull(index)
        ?: throw ByteReaderException("invalid JVM constant-pool index $index (zero, out of range or wide hole)")

    fun utf8(index: Int): String = expect<JvmConstant.Utf8>(index).value
    fun className(index: Int, allowArray: Boolean = true): String = checkedUtf8(
        expect<JvmConstant.ClassRef>(index).nameIndex,
        if (allowArray) Syntax.CLASS else Syntax.DECLARED_CLASS,
    )

    fun memberName(index: Int, method: Boolean): String =
        checkedUtf8(index, if (method) Syntax.METHOD_NAME else Syntax.FIELD_NAME)

    fun descriptor(index: Int, method: Boolean): String =
        checkedUtf8(index, if (method) Syntax.METHOD_DESCRIPTOR else Syntax.FIELD_DESCRIPTOR)

    fun methodParameterSlots(index: Int): Int {
        descriptor(index, method = true)
        return parameterSlots[index]
    }

    private fun checkedUtf8(index: Int, syntax: Syntax): String {
        val value = utf8(index) // Tag/index validity is checked even for a cached syntax result.
        val mask = 1 shl syntax.ordinal
        if (validated[index] and mask != 0) return value
        when (syntax) {
            Syntax.CLASS -> JvmDescriptors.className(value)
            Syntax.DECLARED_CLASS -> JvmDescriptors.className(value, allowArray = false)
            Syntax.FIELD_NAME -> JvmDescriptors.memberName(value, method = false)
            Syntax.METHOD_NAME -> JvmDescriptors.memberName(value, method = true)
            Syntax.FIELD_DESCRIPTOR -> JvmDescriptors.field(value)
            Syntax.METHOD_DESCRIPTOR -> parameterSlots[index] = JvmDescriptors.method(value)
        }
        validated[index] = validated[index] or mask
        return value
    }

    private inline fun <reified T : JvmConstant> expect(index: Int): T = entry(index) as? T
        ?: throw ByteReaderException("wrong JVM constant-pool tag at index $index")

    private fun validate(majorVersion: Int) {
        for (value in entries) when (value) {
            null, is JvmConstant.Utf8, is JvmConstant.IntegerValue, is JvmConstant.FloatBits,
            is JvmConstant.LongValue, is JvmConstant.DoubleBits -> Unit
            is JvmConstant.ClassRef -> checkedUtf8(value.nameIndex, Syntax.CLASS)
            is JvmConstant.StringRef -> utf8(value.stringIndex)
            is JvmConstant.NameAndType -> {
                val descriptor = utf8(value.descriptorIndex)
                val isMethod = descriptor.startsWith('(')
                val name = memberName(value.nameIndex, isMethod)
                if (isMethod && name == "<clinit>") throw ByteReaderException("invalid JVM NameAndType method name $name")
                descriptor(value.descriptorIndex, isMethod)
                if (isMethod && name == "<init>" && !descriptor.endsWith(")V")) {
                    throw ByteReaderException("invalid JVM constructor descriptor $descriptor")
                }
            }
            is JvmConstant.MemberRef -> {
                className(value.classIndex)
                val name = expect<JvmConstant.NameAndType>(value.nameAndTypeIndex)
                val descriptor = descriptor(name.descriptorIndex, value.tag != 9)
                val text = memberName(name.nameIndex, value.tag != 9)
                if (value.tag != 9 && (text == "<clinit>" ||
                        text == "<init>" && (value.tag != 10 || !descriptor.endsWith(")V")))) {
                    throw ByteReaderException("invalid JVM method reference $text$descriptor")
                }
            }
            is JvmConstant.MethodHandle -> validateHandle(value, majorVersion)
            is JvmConstant.MethodType -> descriptor(value.descriptorIndex, method = true)
            is JvmConstant.Dynamic -> {
                val name = expect<JvmConstant.NameAndType>(value.nameAndTypeIndex)
                val text = utf8(name.nameIndex)
                if (text == "<init>" || text == "<clinit>") throw ByteReaderException("invalid dynamic name $text")
                descriptor(name.descriptorIndex, method = value.tag == 18)
            }
            is JvmConstant.ModuleRef -> utf8(value.nameIndex)
            is JvmConstant.PackageRef -> utf8(value.nameIndex)
        }
    }

    private fun validateHandle(handle: JvmConstant.MethodHandle, majorVersion: Int) {
        val target = expect<JvmConstant.MemberRef>(handle.referenceIndex)
        val validTag = when (handle.kind) {
            in 1..4 -> target.tag == 9
            5, 8 -> target.tag == 10
            6, 7 -> target.tag == 10 || majorVersion >= 52 && target.tag == 11
            9 -> target.tag == 11
            else -> false
        }
        if (!validTag) throw ByteReaderException("invalid JVM method handle kind ${handle.kind} / target tag ${target.tag}")
        val name = utf8(expect<JvmConstant.NameAndType>(target.nameAndTypeIndex).nameIndex)
        if (handle.kind == 8 && name != "<init>" ||
            handle.kind in 5..9 && handle.kind != 8 && name in setOf("<init>", "<clinit>")) {
            throw ByteReaderException("invalid JVM method handle target $name")
        }
    }

    companion object {
        fun read(reader: ByteReader, majorVersion: Int): JvmConstantPool {
            val count = reader.readU16BE()
            if (count == 0) throw ByteReaderException("JVM constant-pool count must include reserved index zero")
            // Even wide constants use more bytes than slots. Bound allocation before reading.
            reader.requireAvailable((count - 1).toLong())
            val entries = MutableList<JvmConstant?>(count) { null }
            var index = 1
            while (index < count) {
                val tag = reader.readU8()
                val minimumVersion = when (tag) {
                    15, 16, 18 -> 51
                    19, 20 -> 53
                    17 -> 55
                    else -> 45
                }
                if (majorVersion < minimumVersion) throw ByteReaderException("JVM constant tag $tag requires version $minimumVersion")
                entries[index] = when (tag) {
                    1 -> JvmConstant.Utf8(reader.readMutf8Bytes(reader.readU16BE()))
                    3 -> JvmConstant.IntegerValue(reader.readS32BE())
                    4 -> JvmConstant.FloatBits(reader.readS32BE())
                    5 -> JvmConstant.LongValue(reader.readS64BE())
                    6 -> JvmConstant.DoubleBits(reader.readS64BE())
                    7 -> JvmConstant.ClassRef(reader.readU16BE())
                    8 -> JvmConstant.StringRef(reader.readU16BE())
                    9, 10, 11 -> JvmConstant.MemberRef(tag, reader.readU16BE(), reader.readU16BE())
                    12 -> JvmConstant.NameAndType(reader.readU16BE(), reader.readU16BE())
                    15 -> JvmConstant.MethodHandle(reader.readU8(), reader.readU16BE())
                    16 -> JvmConstant.MethodType(reader.readU16BE())
                    17, 18 -> JvmConstant.Dynamic(tag, reader.readU16BE(), reader.readU16BE())
                    19 -> JvmConstant.ModuleRef(reader.readU16BE())
                    20 -> JvmConstant.PackageRef(reader.readU16BE())
                    else -> throw ByteReaderException("unknown JVM constant-pool tag $tag at index $index")
                }
                index++
                if (tag == 5 || tag == 6) {
                    if (index >= count) throw ByteReaderException("JVM wide constant is missing its second slot")
                    index++
                }
            }
            return JvmConstantPool(entries, majorVersion).also { it.validate(majorVersion) }
        }
    }
}
