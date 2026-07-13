package com.jadxmp.resources

/** Tiny little-endian byte builder for hand-assembling binary XML / ARSC test fixtures. */
internal class Buf {
    private val data = ArrayList<Byte>()
    val size: Int get() = data.size

    fun u8(v: Int) { data.add((v and 0xff).toByte()) }
    fun u16(v: Int) { u8(v); u8(v shr 8) }
    fun u32(v: Int) { u16(v); u16(v shr 16) }
    fun bytes(b: ByteArray) { for (x in b) data.add(x) }
    fun padTo(target: Int) { while (data.size < target) u8(0) }

    fun patchU32(pos: Int, v: Int) {
        data[pos] = (v and 0xff).toByte()
        data[pos + 1] = ((v shr 8) and 0xff).toByte()
        data[pos + 2] = ((v shr 16) and 0xff).toByte()
        data[pos + 3] = ((v shr 24) and 0xff).toByte()
    }

    fun toByteArray(): ByteArray = data.toByteArray()
}

/** Build a self-contained UTF-8 `ResStringPool` chunk holding [strings] (each shorter than 128). */
internal fun utf8StringPool(strings: List<String>): ByteArray {
    val b = Buf()
    b.u16(ResChunkTypes.STRING_POOL)
    b.u16(0x1c) // headerSize
    val sizePos = b.size; b.u32(0)
    b.u32(strings.size) // stringCount
    b.u32(0) // styleCount
    b.u32(StringPoolFlags.UTF8) // flags
    val strStartPos = b.size; b.u32(0) // stringsStart
    b.u32(0) // stylesStart
    val offsetsPos = b.size
    repeat(strings.size) { b.u32(0) }
    val dataStart = b.size
    val offsets = IntArray(strings.size)
    for ((i, s) in strings.withIndex()) {
        offsets[i] = b.size - dataStart
        val enc = s.encodeToByteArray()
        require(s.length < 128 && enc.size < 128) { "test helper only supports short strings" }
        b.u8(s.length) // character count
        b.u8(enc.size) // byte count
        b.bytes(enc)
        b.u8(0) // NUL terminator
    }
    b.padTo(((b.size + 3) / 4) * 4) // 4-byte align
    b.patchU32(sizePos, b.size)
    b.patchU32(strStartPos, dataStart)
    for ((i, o) in offsets.withIndex()) b.patchU32(offsetsPos + i * 4, o)
    return b.toByteArray()
}

/** Assemble a minimal binary XML whose sole namespace declaration uses [prefix] over `http://e`. */
internal object HostileXml {
    fun withNamespacePrefix(prefix: String): ByteArray {
        val pool = utf8StringPool(listOf(prefix, "http://e", "a"))
        val none = 0xffffffff.toInt()
        val b = Buf()
        b.u16(ResChunkTypes.XML); b.u16(8); val sizePos = b.size; b.u32(0)
        b.bytes(pool)
        // START_NAMESPACE: header(16) + prefix ref(0) + uri ref(1)
        b.u16(ResChunkTypes.XML_START_NAMESPACE); b.u16(0x10); b.u32(0x18)
        b.u32(1); b.u32(none); b.u32(0); b.u32(1)
        // START_ELEMENT "a" (name ref 2), no attributes
        b.u16(ResChunkTypes.XML_START_ELEMENT); b.u16(0x10); b.u32(36)
        b.u32(1); b.u32(none)
        b.u32(none); b.u32(2) // ns=-1, name=2
        b.u16(0x14); b.u16(0x14); b.u16(0); b.u16(0); b.u16(0); b.u16(0)
        // END_ELEMENT
        b.u16(ResChunkTypes.XML_END_ELEMENT); b.u16(0x10); b.u32(0x18)
        b.u32(1); b.u32(none); b.u32(none); b.u32(2)
        // END_NAMESPACE
        b.u16(ResChunkTypes.XML_END_NAMESPACE); b.u16(0x10); b.u32(0x18)
        b.u32(1); b.u32(none); b.u32(0); b.u32(1)
        b.patchU32(sizePos, b.size)
        return b.toByteArray()
    }
}

/**
 * Assemble a minimal `resources.arsc` with one package (`0x7f`, type `t`, key `e0`) holding a single
 * INT_DEC-42 entry, encoded via the requested offset-table / entry strategy. Used to cover the
 * sparse / offset16 / compact strides that the real corpus fixture does not exercise.
 */
internal object ArscFixtureBuilder {
    enum class Encoding { NORMAL, SPARSE, OFFSET16, COMPACT }

    fun build(encoding: Encoding): ByteArray {
        val typeChunk = buildTypeChunk(encoding)
        val pkg = buildPackage(typeChunk)
        val globalPool = utf8StringPool(emptyList())
        val b = Buf()
        b.u16(ResChunkTypes.TABLE); b.u16(12); val sizePos = b.size; b.u32(0); b.u32(1)
        b.bytes(globalPool)
        b.bytes(pkg)
        b.patchU32(sizePos, b.size)
        return b.toByteArray()
    }

    private fun buildPackage(typeChunk: ByteArray): ByteArray {
        val typeStrings = utf8StringPool(listOf("t")) // typeId 1 -> index 0
        val keyStrings = utf8StringPool(listOf("e0")) // key 0
        val b = Buf()
        b.u16(ResChunkTypes.TABLE_PACKAGE); b.u16(0x11c); val sizePos = b.size; b.u32(0)
        b.u32(0x7f) // id
        b.u16('p'.code); repeat(127) { b.u16(0) } // name (256 bytes)
        val tsoPos = b.size; b.u32(0) // typeStringsOffset
        b.u32(0) // lastPublicType
        val ksoPos = b.size; b.u32(0) // keyStringsOffset
        b.u32(0) // lastPublicKey
        val tso = b.size // == 284
        b.bytes(typeStrings)
        val kso = b.size
        b.bytes(keyStrings)
        b.bytes(typeChunk)
        b.patchU32(sizePos, b.size)
        b.patchU32(tsoPos, tso)
        b.patchU32(ksoPos, kso)
        return b.toByteArray()
    }

    private fun buildTypeChunk(encoding: Encoding): ByteArray {
        val flags = when (encoding) {
            Encoding.SPARSE -> TypeFlags.SPARSE
            Encoding.OFFSET16 -> TypeFlags.OFFSET16
            else -> 0
        }
        val entry = if (encoding == Encoding.COMPACT) compactEntry() else simpleEntry()
        val b = Buf()
        b.u16(ResChunkTypes.TABLE_TYPE); b.u16(28); val sizePos = b.size; b.u32(0)
        b.u8(1) // typeId
        b.u8(flags)
        b.u16(0) // reserved
        b.u32(1) // entryCount
        b.u32(32) // entriesStart (relative)
        b.u32(8); b.u32(0) // config: size 8 + zeroed body
        // offset table at pos 28
        when (encoding) {
            Encoding.SPARSE -> { b.u16(0); b.u16(0) } // idx 0, offset/4 = 0
            Encoding.OFFSET16 -> b.u16(0) // offset/4 = 0
            else -> b.u32(0) // offset 0
        }
        b.padTo(32) // entries start
        b.bytes(entry)
        b.patchU32(sizePos, b.size)
        return b.toByteArray()
    }

    private fun simpleEntry(): ByteArray {
        val b = Buf()
        b.u16(8); b.u16(0); b.u32(0) // size, flags, key
        b.u16(8); b.u8(0); b.u8(ResValueType.INT_DEC); b.u32(42) // Res_value
        return b.toByteArray()
    }

    private fun compactEntry(): ByteArray {
        val b = Buf()
        b.u16(0) // size field doubles as key index (0)
        b.u16(EntryFlags.COMPACT or (ResValueType.INT_DEC shl 8)) // flags: compact + dataType
        b.u32(42) // data
        return b.toByteArray()
    }
}
