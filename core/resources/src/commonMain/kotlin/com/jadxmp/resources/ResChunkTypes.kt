package com.jadxmp.resources

/**
 * Binary chunk type ids, mirroring AOSP `frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h`.
 *
 * Both `resources.arsc` and binary XML are streams of length-prefixed chunks; every chunk begins
 * with a `ResChunk_header` { type: u16, headerSize: u16, size: u32 }. These constants name the
 * `type` values we understand.
 */
internal object ResChunkTypes {
    const val NULL = 0x0000
    const val STRING_POOL = 0x0001
    const val TABLE = 0x0002
    const val XML = 0x0003

    // XML sub-chunks (0x0100..0x017f), plus the resource-map (0x0180).
    const val XML_START_NAMESPACE = 0x0100
    const val XML_END_NAMESPACE = 0x0101
    const val XML_START_ELEMENT = 0x0102
    const val XML_END_ELEMENT = 0x0103
    const val XML_CDATA = 0x0104
    const val XML_RESOURCE_MAP = 0x0180

    // Table sub-chunks.
    const val TABLE_PACKAGE = 0x0200
    const val TABLE_TYPE = 0x0201
    const val TABLE_TYPE_SPEC = 0x0202
    const val TABLE_LIBRARY = 0x0203
    const val TABLE_OVERLAY = 0x0204
    const val TABLE_OVERLAY_POLICY = 0x0205
    const val TABLE_STAGED_ALIAS = 0x0206
}

/** String-pool `flags` bits. */
internal object StringPoolFlags {
    const val SORTED = 1 shl 0
    const val UTF8 = 1 shl 8
}

/** `ResTable_type.flags` bits. */
internal object TypeFlags {
    /** Entry offsets are a sparse (idx, offset/4) array requiring binary search. Platform >= O. */
    const val SPARSE = 0x01

    /** Entry offsets are 16-bit (`real = offset * 4`); `0xFFFF` marks a hole. */
    const val OFFSET16 = 0x02
}

/** `ResTable_entry.flags` bits. */
internal object EntryFlags {
    /** Complex entry: a parent ref + an array of name/value `ResTable_map` pairs (a "bag"). */
    const val COMPLEX = 0x0001
    const val PUBLIC = 0x0002
    const val WEAK = 0x0004

    /** Compact entry (aapt2, platform >= U): the value type/data is packed into the entry header. */
    const val COMPACT = 0x0008
}

/** `0xFFFFFFFF` sentinel: no entry at this slot. */
internal const val NO_ENTRY: Int = -1
