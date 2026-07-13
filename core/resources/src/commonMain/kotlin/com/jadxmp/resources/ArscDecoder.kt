package com.jadxmp.resources

import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/**
 * Decodes a `resources.arsc` binary resource table into a [ResourceTable].
 *
 * Structure (AOSP `ResourceTypes.h`): a `RES_TABLE` chunk wraps a global value string pool and one
 * or more package chunks. Each package has a type-string pool, a key-string pool, and a series of
 * type-spec / type chunks; each type chunk holds an entry array, one entry per resource, each with a
 * value per configuration.
 *
 * Robustness (the cardinal rule): every chunk is parsed inside a `try`/`catch`. A malformed chunk is
 * recorded as a diagnostic and skipped by seeking to the chunk's declared end, so decoding salvages
 * everything that parses instead of aborting (jadx #1911). All reads go through [ByteReader], so a
 * hostile length yields a catchable [ByteReaderException], never an OOM/hang.
 */
public object ArscDecoder {

    /** Decode [bytes]; never throws for malformed input — check [ResourceTable.diagnostics]. */
    public fun decode(bytes: ByteArray): ResourceTable {
        val diagnostics = mutableListOf<String>()
        val packages = mutableListOf<ResourcePackage>()
        var globalStrings: StringPool? = null
        try {
            val reader = ByteReader(bytes)
            val header = readChunkHeader(reader)
            if (header.type != ResChunkTypes.TABLE) {
                diagnostics += "not a resource table (type=0x${header.type.toString(16)})"
                return ResourceTable(packages, null, diagnostics)
            }
            /* packageCount = */ reader.readU32()

            iterateChunks(reader, header.bodyStart, header.end, diagnostics) { child ->
                when (child.type) {
                    ResChunkTypes.STRING_POOL ->
                        globalStrings = StringPool.parse(reader, child.start, child.end)
                    ResChunkTypes.TABLE_PACKAGE ->
                        packages += parsePackage(reader, child, globalStrings, diagnostics)
                    else -> Unit // NULL / unknown top-level chunk: skip
                }
            }
        } catch (e: ByteReaderException) {
            diagnostics += "fatal: ${e.message}"
        }
        return ResourceTable(packages, globalStrings, diagnostics)
    }

    // ---- packages -------------------------------------------------------------------------------

    private fun parsePackage(
        reader: ByteReader,
        chunk: ChunkHeader,
        globalStrings: StringPool?,
        diagnostics: MutableList<String>,
    ): ResourcePackage {
        reader.seek(chunk.start + 8)
        val id = reader.readS32()
        val name = readFixedUtf16(reader, 128)
        val typeStringsOffset = reader.readU32().toInt()
        /* lastPublicType = */ reader.readS32()
        val keyStringsOffset = reader.readU32().toInt()
        /* lastPublicKey = */ reader.readS32()

        // Sub-chunks (type-spec/type) begin after both string pools; track the furthest pool end.
        var subStart = chunk.bodyStart
        var typeStrings: StringPool? = null
        var keyStrings: StringPool? = null
        if (typeStringsOffset != 0) {
            val (pool, end) = readStringPoolAt(reader, chunk.start + typeStringsOffset)
            typeStrings = pool
            subStart = maxOf(subStart, end)
        }
        if (keyStringsOffset != 0) {
            val (pool, end) = readStringPoolAt(reader, chunk.start + keyStringsOffset)
            keyStrings = pool
            subStart = maxOf(subStart, end)
        }

        val entries = mutableListOf<ResourceEntry>()
        reader.seek(subStart)
        iterateChunks(reader, subStart, chunk.end, diagnostics) { sub ->
            when (sub.type) {
                ResChunkTypes.TABLE_TYPE ->
                    parseType(reader, sub, id, name, typeStrings, keyStrings, globalStrings, entries, diagnostics)
                ResChunkTypes.TABLE_TYPE_SPEC,
                ResChunkTypes.TABLE_LIBRARY,
                ResChunkTypes.TABLE_OVERLAY,
                ResChunkTypes.TABLE_OVERLAY_POLICY,
                ResChunkTypes.TABLE_STAGED_ALIAS,
                ResChunkTypes.NULL,
                -> Unit // not needed to build the value table; skipped
                else -> diagnostics += "unknown package sub-chunk 0x${sub.type.toString(16)}"
            }
        }
        return ResourcePackage(id, name, entries)
    }

    /** Parse the string pool at absolute [at], returning it together with its chunk end offset. */
    private fun readStringPoolAt(reader: ByteReader, at: Int): Pair<StringPool, Int> {
        reader.seek(at)
        val header = readChunkHeader(reader)
        return StringPool.parse(reader, header.start, header.end) to header.end
    }

    // ---- type chunks ----------------------------------------------------------------------------

    private fun parseType(
        reader: ByteReader,
        chunk: ChunkHeader,
        pkgId: Int,
        pkgName: String,
        typeStrings: StringPool?,
        keyStrings: StringPool?,
        globalStrings: StringPool?,
        out: MutableList<ResourceEntry>,
        diagnostics: MutableList<String>,
    ) {
        reader.seek(chunk.start + 8)
        val typeId = reader.readU8()
        val flags = reader.readU8()
        val sparse = (flags and TypeFlags.SPARSE) != 0
        val offset16 = (flags and TypeFlags.OFFSET16) != 0
        reader.readU16() // reserved
        val entryCount = reader.readS32()
        val entriesStart = chunk.start + reader.readU32().toInt()
        val config = parseConfig(reader)
        val typeName = typeStrings?.get(typeId - 1) ?: "type$typeId"

        if (entryCount < 0) {
            diagnostics += "type $typeName: negative entry count"
            return
        }
        // Cap the offset table to what the chunk can hold: a sparse slot is a 4-byte
        // ResTable_sparseTypeEntry (idx u16 + offset u16), offset16 is 2 bytes, normal is 4.
        reader.requireAvailable(entryCount.toLong() * (if (offset16) 2L else 4L))

        // Resolve each slot to an (entryIndex, byteOffset-from-entriesStart) pair.
        val offsets = ArrayList<Pair<Int, Int>>(entryCount)
        if (sparse) {
            for (i in 0 until entryCount) {
                val idx = reader.readU16()
                val off = reader.readU16() * 4
                offsets += idx to off
            }
        } else if (offset16) {
            for (i in 0 until entryCount) {
                val off = reader.readU16()
                if (off != 0xFFFF) offsets += i to (off * 4)
            }
        } else {
            for (i in 0 until entryCount) {
                offsets += i to reader.readS32()
            }
        }

        val seen = HashSet<Int>()
        for ((index, offset) in offsets) {
            if (offset == NO_ENTRY) continue
            if (!seen.add(index)) continue // sparse chunks may repeat an index
            val entryStart = entriesStart + offset
            if (entryStart < chunk.start || entryStart >= chunk.end) continue // out-of-bounds → salvage the rest
            try {
                reader.seek(entryStart)
                val entry = parseEntry(reader, pkgId, pkgName, typeId, index, typeName, config, keyStrings, globalStrings)
                if (entry != null) out += entry
            } catch (e: ByteReaderException) {
                diagnostics += "type $typeName entry $index: ${e.message}"
            }
        }
    }

    private fun parseEntry(
        reader: ByteReader,
        pkgId: Int,
        pkgName: String,
        typeId: Int,
        entryId: Int,
        typeName: String,
        config: String,
        keyStrings: StringPool?,
        globalStrings: StringPool?,
    ): ResourceEntry? {
        val size = reader.readU16()
        val flags = reader.readU16()
        val complex = (flags and EntryFlags.COMPLEX) != 0
        val compact = (flags and EntryFlags.COMPACT) != 0

        val key = if (compact) size else reader.readS32()
        if (key == -1) return null

        val resId = (pkgId shl 24) or (typeId shl 16) or entryId
        val entryName = keyStrings?.get(key) ?: "res_0x${resId.toUInt().toString(16)}"

        return when {
            compact -> {
                val dataType = flags shr 8
                val data = reader.readS32()
                ResourceEntry(resId, pkgName, typeName, entryName, config, ResourceValue(dataType, data))
            }
            complex || size == 16 -> {
                val parentRef = reader.readS32()
                val count = reader.readS32()
                if (count < 0) throw ByteReaderException("negative bag count: $count")
                reader.requireAvailable(count.toLong() * 12L) // 4 (nameRef) + 8 (Res_value)
                val bag = ArrayList<ResourceBagItem>(count)
                repeat(count) {
                    val nameRef = reader.readS32()
                    bag += ResourceBagItem(nameRef, readResValue(reader))
                }
                ResourceEntry(resId, pkgName, typeName, entryName, config, value = null, bag = bag, parentRef = parentRef)
            }
            else ->
                ResourceEntry(resId, pkgName, typeName, entryName, config, readResValue(reader))
        }
    }

    /** Read a `Res_value` { size: u16, res0: u8, dataType: u8, data: u32 }. */
    private fun readResValue(reader: ByteReader): ResourceValue {
        reader.readU16() // size (always 8)
        reader.readU8() // res0
        val dataType = reader.readU8()
        val data = reader.readS32()
        return ResourceValue(dataType, data)
    }

    // ---- config ---------------------------------------------------------------------------------

    /**
     * Parse a `ResTable_config` into a values-dir qualifier suffix (`""`, `"en-rUS"`, `"xhdpi"`, …).
     * Only the widely-used axes are decoded; unknown trailing bytes are skipped. The whole config is
     * bounded by its own `size` field, so a bad size fails gracefully.
     */
    private fun parseConfig(reader: ByteReader): String {
        val start = reader.position
        val size = reader.readS32()
        if (size < 4) throw ByteReaderException("config size < 4: $size")
        val end = start + size
        reader.requireAvailable((size - 4).toLong())

        val parts = mutableListOf<String>()
        fun avail(bytes: Int) = reader.position + bytes <= end

        if (avail(4)) {
            val mcc = reader.readU16()
            val mnc = reader.readU16()
            if (mcc != 0) parts += "mcc$mcc"
            if (mnc != 0) parts += "mnc$mnc"
        }
        if (avail(4)) {
            val lang = unpackLocale(reader.readU8(), reader.readU8(), 'a')
            val region = unpackLocale(reader.readU8(), reader.readU8(), '0')
            if (lang.isNotEmpty()) {
                parts += lang
                if (region.isNotEmpty()) parts += "r$region"
            }
        }
        if (avail(4)) {
            val orientation = reader.readU8()
            reader.readU8() // touchscreen
            val density = reader.readU16()
            when (orientation) {
                1 -> parts += "port"
                2 -> parts += "land"
                3 -> parts += "square"
            }
            densityQualifier(density)?.let { parts += it }
        }
        if (avail(4)) {
            reader.readU8() // keyboard
            reader.readU8() // navigation
            reader.readU8() // inputFlags
            reader.readU8() // pad / grammatical inflection
        }
        if (avail(4)) {
            reader.readU16() // screenWidth
            reader.readU16() // screenHeight
        }
        if (avail(2)) {
            val sdk = reader.readU16()
            if (sdk != 0) parts += "v$sdk"
        }

        reader.seek(end)
        return parts.joinToString("-")
    }

    private fun densityQualifier(density: Int): String? = when (density) {
        0 -> null
        120 -> "ldpi"
        160 -> "mdpi"
        213 -> "tvdpi"
        240 -> "hdpi"
        320 -> "xhdpi"
        480 -> "xxhdpi"
        640 -> "xxxhdpi"
        0xFFFE -> "anydpi"
        0xFFFF -> "nodpi"
        else -> "${density}dpi"
    }

    /** Decode a 2-byte packed ISO language/region code (AOSP `ResTable_config` packing). */
    private fun unpackLocale(in0: Int, in1: Int, base: Char): String {
        if (in0 == 0 && in1 == 0) return ""
        if ((in0 and 0x80) != 0) {
            val first = in1 and 0x1F
            val second = ((in1 and 0xE0) shr 5) + ((in0 and 0x03) shl 3)
            val third = (in0 and 0x7C) shr 2
            return charArrayOf((first + base.code).toChar(), (second + base.code).toChar(), (third + base.code).toChar())
                .concatToString()
        }
        return charArrayOf(in0.toChar(), in1.toChar()).concatToString()
    }

}
