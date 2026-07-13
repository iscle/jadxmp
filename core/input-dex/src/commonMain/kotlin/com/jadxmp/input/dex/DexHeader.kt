package com.jadxmp.input.dex

import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/**
 * The fixed-layout DEX header plus the two offsets (`call_site`, `method_handle`) that only appear in
 * the map list. All section offsets are absolute within the containing byte array — in a DEX v41
 * container the sub-dex header sits at [headerOffset] but its offsets still point into the whole file.
 *
 * jadx: DexHeader
 */
internal class DexHeader private constructor(
    val version: String,
    val stringIdsOff: Int,
    val stringIdsSize: Int,
    val typeIdsOff: Int,
    val typeIdsSize: Int,
    val protoIdsOff: Int,
    val fieldIdsOff: Int,
    val methodIdsOff: Int,
    val classDefsOff: Int,
    val classDefsSize: Int,
    val callSiteOff: Int,
    val methodHandleOff: Int,
) {
    companion object {
        // Map item type codes we care about (DEX map_list §"Type Codes").
        private const val TYPE_CALL_SITE_ID_ITEM = 0x0007
        private const val TYPE_METHOD_HANDLE_ITEM = 0x0008

        private fun checkPoolSize(size: Int, stride: Int, fileSize: Int, name: String) {
            if (size < 0) throw ByteReaderException("$name size is negative: $size")
            if (size.toLong() * stride > fileSize.toLong()) {
                throw ByteReaderException("$name size $size too large for a $fileSize-byte file")
            }
        }

        /** Seed a reader at a parsed (attacker-controlled) offset, normalizing a bad offset to [ByteReaderException]. */
        private fun readerAt(data: ByteArray, offset: Int, what: String): ByteReader {
            if (offset < 0 || offset > data.size) {
                throw ByteReaderException("$what offset out of range: $offset (file is ${data.size} bytes)")
            }
            return ByteReader(data, start = offset)
        }

        fun parse(data: ByteArray, headerOffset: Int): DexHeader {
            val r = readerAt(data, headerOffset, "header")
            val magic = r.readBytes(4)
            if (!DexConsts.startsWith(magic, DexConsts.DEX_MAGIC)) {
                throw ByteReaderException("not a DEX file: bad magic")
            }
            val version = r.readMutf8(3)
            r.skip(1) // magic's trailing 0 byte
            r.skip(4) // checksum
            r.skip(20) // signature
            r.skip(4) // file_size
            r.skip(4) // header_size
            val endianTag = r.readS32()
            if (endianTag != DexConsts.ENDIAN_CONSTANT) {
                throw ByteReaderException("unsupported DEX endianness: 0x${endianTag.toString(16)}")
            }
            r.skip(4) // link_size
            r.skip(4) // link_off
            val mapOff = r.readS32()
            val stringIdsSize = r.readS32()
            val stringIdsOff = r.readS32()
            val typeIdsSize = r.readS32()
            val typeIdsOff = r.readS32()
            r.skip(4) // proto_ids_size
            val protoIdsOff = r.readS32()
            r.skip(4) // field_ids_size
            val fieldIdsOff = r.readS32()
            r.skip(4) // method_ids_size
            val methodIdsOff = r.readS32()
            val classDefsSize = r.readS32()
            val classDefsOff = r.readS32()

            // Reject pool sizes that could not physically fit in the file, before anything is sized by
            // them (the caches in Dex, the class_def array). A pool of N entries of `stride` bytes each
            // needs N*stride bytes; a crafted huge size would otherwise trigger an OOM allocation.
            checkPoolSize(stringIdsSize, 4, data.size, "string_ids")
            checkPoolSize(typeIdsSize, 4, data.size, "type_ids")
            checkPoolSize(classDefsSize, 32, data.size, "class_defs")

            var callSiteOff = 0
            var methodHandleOff = 0
            if (mapOff != 0) {
                val m = readerAt(data, mapOff, "map_list")
                val size = m.readS32()
                repeat(size) {
                    val type = m.readU16()
                    m.skip(2) // unused
                    m.skip(4) // count
                    val off = m.readS32()
                    when (type) {
                        TYPE_CALL_SITE_ID_ITEM -> callSiteOff = off
                        TYPE_METHOD_HANDLE_ITEM -> methodHandleOff = off
                    }
                }
            }

            return DexHeader(
                version = version,
                stringIdsOff = stringIdsOff,
                stringIdsSize = stringIdsSize,
                typeIdsOff = typeIdsOff,
                typeIdsSize = typeIdsSize,
                protoIdsOff = protoIdsOff,
                fieldIdsOff = fieldIdsOff,
                methodIdsOff = methodIdsOff,
                classDefsOff = classDefsOff,
                classDefsSize = classDefsSize,
                callSiteOff = callSiteOff,
                methodHandleOff = methodHandleOff,
            )
        }
    }
}

/**
 * DEX v41 introduced a "container" format: several sub-DEX structures packed into one file. This
 * reads the container fields (present only when `header_size >= 120`) and enumerates the byte offset
 * of each sub-dex header so the loader can parse them as independent dex units.
 *
 * jadx: DexHeaderV41
 */
internal object DexContainer {
    private fun readU4(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > data.size) {
            throw ByteReaderException("container field offset out of range: $offset")
        }
        return ByteReader(data, start = offset).readS32()
    }

    /** Offsets of every sub-dex header, or a single `[0]` for a classic (non-container) dex. */
    fun subDexOffsets(data: ByteArray): List<Int> {
        // header_size lives at byte offset 36; the container fields start at 112.
        if (data.size < 120) return listOf(0)
        val headerSize = readU4(data, 36)
        if (headerSize < 120) return listOf(0)
        val containerSize = readU4(data, 112)
        val limit = minOf(containerSize, data.size)

        val offsets = ArrayList<Int>()
        var start = 0
        var end = readU4(data, 32) // file_size of the first sub-dex
        while (true) {
            offsets.add(start)
            start = end
            if (start >= limit || start < 0 || start + 36 > data.size) break
            val nextFileSize = readU4(data, start + 32)
            if (nextFileSize <= 0) break
            end = start + nextFileSize
        }
        return offsets
    }
}
