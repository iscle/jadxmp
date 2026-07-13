package com.jadxmp.input.dex

/** Append-only little-endian byte writer for crafting DEX structures in tests. */
internal class ByteWriter {
    private val buf = ArrayList<Byte>()
    val size: Int get() = buf.size

    fun u8(v: Int) = apply { buf.add(v.toByte()) }
    fun u16(v: Int) = apply { u8(v and 0xFF); u8((v ushr 8) and 0xFF) }
    fun i32(v: Int) = apply { u8(v); u8(v ushr 8); u8(v ushr 16); u8(v ushr 24) }
    fun uleb(v: Int) = apply {
        var x = v
        do {
            var b = x and 0x7F
            x = x ushr 7
            if (x != 0) b = b or 0x80
            u8(b)
        } while (x != 0)
    }

    fun raw(bytes: ByteArray) = apply { for (b in bytes) buf.add(b) }
    fun toByteArray(): ByteArray = buf.toByteArray()
}

internal fun buildBytes(block: ByteWriter.() -> Unit): ByteArray = ByteWriter().apply(block).toByteArray()

/** Encode a BMP string as DEX modified UTF-8 (no trailing terminator). */
internal fun mutf8(s: String): ByteArray {
    val out = ArrayList<Byte>()
    for (c in s) {
        val code = c.code
        when {
            code in 0x01..0x7F -> out.add(code.toByte())
            code == 0 || code in 0x80..0x7FF -> {
                out.add((0xC0 or (code shr 6)).toByte())
                out.add((0x80 or (code and 0x3F)).toByte())
            }
            else -> {
                out.add((0xE0 or (code shr 12)).toByte())
                out.add((0x80 or ((code shr 6) and 0x3F)).toByte())
                out.add((0x80 or (code and 0x3F)).toByte())
            }
        }
    }
    return out.toByteArray()
}

/**
 * Builds a minimal but structurally valid DEX with real string/type/proto/field/method/class pools,
 * so parser paths that resolve indices (encoded values, annotations, refs, dedup) can be exercised
 * with hand-crafted inputs.
 */
internal class DexBuilder {
    private val strings = ArrayList<String>()
    private val types = ArrayList<Int>() // type index -> string index
    private val protos = ArrayList<IntArray>() // [shortyStrIdx, returnTypeIdx, paramsOff]
    private val fields = ArrayList<IntArray>() // [classTypeIdx, typeIdx, nameStrIdx]
    private val methods = ArrayList<IntArray>() // [classTypeIdx, protoIdx, nameStrIdx]
    private val classes = ArrayList<IntArray>() // [classTypeIdx, superTypeIdx]

    fun addString(s: String): Int {
        strings.add(s)
        return strings.size - 1
    }

    fun addType(descriptor: String): Int {
        val strIdx = addString(descriptor)
        types.add(strIdx)
        return types.size - 1
    }

    fun addProto(returnTypeIdx: Int): Int {
        protos.add(intArrayOf(0, returnTypeIdx, 0))
        return protos.size - 1
    }

    fun addField(classTypeIdx: Int, typeIdx: Int, nameStrIdx: Int): Int {
        fields.add(intArrayOf(classTypeIdx, typeIdx, nameStrIdx))
        return fields.size - 1
    }

    fun addMethod(classTypeIdx: Int, protoIdx: Int, nameStrIdx: Int): Int {
        methods.add(intArrayOf(classTypeIdx, protoIdx, nameStrIdx))
        return methods.size - 1
    }

    fun addClass(classTypeIdx: Int, superTypeIdx: Int): Int {
        classes.add(intArrayOf(classTypeIdx, superTypeIdx))
        return classes.size - 1
    }

    fun build(): ByteArray {
        val stringIdsOff = 112
        val typeIdsOff = stringIdsOff + strings.size * 4
        val protoIdsOff = typeIdsOff + types.size * 4
        val fieldIdsOff = protoIdsOff + protos.size * 12
        val methodIdsOff = fieldIdsOff + fields.size * 8
        val classDefsOff = methodIdsOff + methods.size * 8
        val dataOff = classDefsOff + classes.size * 32

        val blobs = strings.map { s -> buildBytes { uleb(s.length); raw(mutf8(s)); u8(0) } }
        val stringDataOffsets = IntArray(strings.size)
        var cur = dataOff
        for (i in strings.indices) {
            stringDataOffsets[i] = cur
            cur += blobs[i].size
        }
        val totalSize = cur

        val out = ByteWriter()
        out.raw("dex\n035".encodeToByteArray()).u8(0)
        repeat(24) { out.u8(0) } // checksum + signature
        out.i32(totalSize).i32(112).i32(DexConsts.ENDIAN_CONSTANT)
        out.i32(0).i32(0) // link
        out.i32(0) // map_off
        out.i32(strings.size).i32(stringIdsOff)
        out.i32(types.size).i32(typeIdsOff)
        out.i32(protos.size).i32(protoIdsOff)
        out.i32(fields.size).i32(fieldIdsOff)
        out.i32(methods.size).i32(methodIdsOff)
        out.i32(classes.size).i32(classDefsOff)
        out.i32(0).i32(dataOff) // data_size, data_off
        check(out.size == 112) { "header size ${out.size} != 112" }

        for (o in stringDataOffsets) out.i32(o)
        for (t in types) out.i32(t)
        for (p in protos) { out.i32(p[0]); out.i32(p[1]); out.i32(p[2]) }
        for (f in fields) { out.u16(f[0]); out.u16(f[1]); out.i32(f[2]) }
        for (m in methods) { out.u16(m[0]); out.u16(m[1]); out.i32(m[2]) }
        for (c in classes) {
            out.i32(c[0]).i32(0x1).i32(c[1]).i32(0).i32(DexConsts.NO_INDEX).i32(0).i32(0).i32(0)
        }
        for (b in blobs) out.raw(b)
        return out.toByteArray()
    }
}
