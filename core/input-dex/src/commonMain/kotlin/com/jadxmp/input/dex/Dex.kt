package com.jadxmp.input.dex

import com.jadxmp.input.FieldRef
import com.jadxmp.input.MethodHandle
import com.jadxmp.input.MethodHandleType
import com.jadxmp.input.MethodProto
import com.jadxmp.input.MethodRef
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/**
 * A single parsed DEX unit: the backing bytes, the [DexHeader], and the pool resolvers that turn the
 * format's integer indices into strings, type descriptors, and references.
 *
 * A DEX file is a graph of offset-linked sections rather than a linear stream, so every resolver
 * opens a fresh [ByteReader] cursor at the right absolute offset. Cursor construction is O(1) (it
 * shares the backing array), which lets us resolve, say, a type while mid-way through the class-data
 * section without disturbing that walk. Strings and types are memoized because they are looked up
 * repeatedly.
 *
 * jadx: DexReader + SectionReader
 */
internal class Dex(
    val data: ByteArray,
    val fileName: String,
    headerOffset: Int,
) {
    val header: DexHeader = DexHeader.parse(data, headerOffset)

    private val stringCache = arrayOfNulls<String>(header.stringIdsSize.coerceAtLeast(0))
    private val typeCache = arrayOfNulls<String>(header.typeIdsSize.coerceAtLeast(0))

    /**
     * A cursor seeked to an absolute byte offset in the file.
     *
     * Offsets in a DEX are attacker-controlled (map_off, string data offsets, class_data_off, …), so a
     * hostile value is normalized to [ByteReaderException] here rather than the [IllegalArgumentException]
     * that `ByteReader`'s constructor would raise — every hostile path must converge on one catchable type.
     */
    fun cursor(absoluteOffset: Int): ByteReader {
        if (absoluteOffset < 0 || absoluteOffset > data.size) {
            throw ByteReaderException("offset out of range: $absoluteOffset (file is ${data.size} bytes)")
        }
        return ByteReader(data, start = absoluteOffset)
    }

    fun string(idx: Int): String? {
        if (idx == DexConsts.NO_INDEX) return null
        if (idx < 0 || idx >= stringCache.size) throw ByteReaderException("string index out of range: $idx")
        stringCache[idx]?.let { return it }
        val dataOff = cursor(header.stringIdsOff + idx * 4).readS32()
        val c = cursor(dataOff)
        val utf16Len = c.readUleb128().toInt()
        return c.readMutf8(utf16Len).also { stringCache[idx] = it }
    }

    fun type(idx: Int): String? {
        if (idx == DexConsts.NO_INDEX) return null
        if (idx < 0 || idx >= typeCache.size) throw ByteReaderException("type index out of range: $idx")
        typeCache[idx]?.let { return it }
        val strIdx = cursor(header.typeIdsOff + idx * 4).readS32()
        return string(strIdx).also { typeCache[idx] = it }
    }

    /** A `type_list` at [offset]: a size-prefixed array of type indices. */
    fun typeList(offset: Int): List<String> {
        if (offset == 0) return emptyList()
        val c = cursor(offset)
        val size = c.readS32()
        if (size == 0) return emptyList()
        return ArrayList<String>(Bounds.capacity(size, stride = 2, reader = c)).apply {
            repeat(size) { add(type(c.readU16()) ?: "?") }
        }
    }

    fun fieldRef(idx: Int): FieldRef {
        val c = cursor(header.fieldIdsOff + idx * 8)
        val classIdx = c.readU16()
        val typeIdx = c.readU16()
        val nameIdx = c.readS32()
        return DexFieldRef(
            declaringClassType = type(classIdx) ?: "?",
            name = string(nameIdx) ?: "?",
            type = type(typeIdx) ?: "?",
        )
    }

    fun methodRef(idx: Int): MethodRef {
        val c = cursor(header.methodIdsOff + idx * 8)
        val classIdx = c.readU16()
        val protoIdx = c.readU16()
        val nameIdx = c.readS32()
        val proto = proto(protoIdx)
        return DexMethodRef(
            declaringClassType = type(classIdx) ?: "?",
            name = string(nameIdx) ?: "?",
            returnType = proto.returnType,
            parameterTypes = proto.parameterTypes,
        )
    }

    fun proto(idx: Int): MethodProto {
        val c = cursor(header.protoIdsOff + idx * 12)
        c.skip(4) // shorty_idx
        val returnTypeIdx = c.readS32()
        val paramsOff = c.readS32()
        return DexMethodProto(
            returnType = type(returnTypeIdx) ?: "?",
            parameterTypes = typeList(paramsOff),
        )
    }

    /** Parameter descriptors of a method by method index (used to seed debug-info arg naming). */
    fun methodParamTypes(methodIdx: Int): List<String> {
        val c = cursor(header.methodIdsOff + methodIdx * 8 + 2)
        val protoIdx = c.readU16()
        val p = cursor(header.protoIdsOff + protoIdx * 12 + 8)
        val paramsOff = p.readS32()
        return typeList(paramsOff)
    }

    fun methodHandle(idx: Int): MethodHandle {
        if (header.methodHandleOff == 0) throw ByteReaderException("no method_handle section")
        val c = cursor(header.methodHandleOff + idx * 8)
        val kind = methodHandleType(c.readU16())
        c.skip(2)
        val refId = c.readU16()
        return if (kind.isField) {
            DexMethodHandle(kind, fieldRef(refId), null)
        } else {
            DexMethodHandle(kind, null, methodRef(refId))
        }
    }

    fun callSite(idx: Int): DexCallSite {
        if (header.callSiteOff == 0) throw ByteReaderException("no call_site section")
        val c = cursor(header.callSiteOff + idx * 4)
        val itemOff = c.readS32()
        val values = EncodedValueParser(this).parseArray(cursor(itemOff))
        return DexCallSite(values)
    }

    private fun methodHandleType(raw: Int): MethodHandleType = when (raw) {
        0x00 -> MethodHandleType.STATIC_PUT
        0x01 -> MethodHandleType.STATIC_GET
        0x02 -> MethodHandleType.INSTANCE_PUT
        0x03 -> MethodHandleType.INSTANCE_GET
        0x04 -> MethodHandleType.INVOKE_STATIC
        0x05 -> MethodHandleType.INVOKE_INSTANCE
        0x06 -> MethodHandleType.INVOKE_CONSTRUCTOR
        0x07 -> MethodHandleType.INVOKE_DIRECT
        0x08 -> MethodHandleType.INVOKE_INTERFACE
        else -> throw ByteReaderException("unknown method handle type: $raw")
    }
}
