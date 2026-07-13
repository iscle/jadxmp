package com.jadxmp.resources

/**
 * A raw typed value as stored in a `Res_value` { size: u16, res0: u8, dataType: u8, data: u32 }.
 *
 * [type] is one of [ResValueType]; [data] is a 32-bit payload whose meaning depends on [type]
 * (a raw int, a float bit-pattern, a string-pool index, a resource-id reference, …). Formatting a
 * value into human text needs a string source and a reference resolver — see [ValueFormatter].
 */
public data class ResourceValue(val type: Int, val data: Int)

/** One name→value pair inside a complex/bag entry (a `ResTable_map`). */
public data class ResourceBagItem(val nameRef: Int, val value: ResourceValue)

/**
 * `Res_value.dataType` constants (AOSP `ResourceTypes.h`).
 */
public object ResValueType {
    const val NULL = 0x00
    const val REFERENCE = 0x01
    const val ATTRIBUTE = 0x02
    const val STRING = 0x03
    const val FLOAT = 0x04
    const val DIMENSION = 0x05
    const val FRACTION = 0x06
    const val DYNAMIC_REFERENCE = 0x07
    const val DYNAMIC_ATTRIBUTE = 0x08

    const val INT_DEC = 0x10
    const val INT_HEX = 0x11
    const val INT_BOOLEAN = 0x12

    const val INT_COLOR_ARGB8 = 0x1c
    const val INT_COLOR_RGB8 = 0x1d
    const val INT_COLOR_ARGB4 = 0x1e
    const val INT_COLOR_RGB4 = 0x1f
}
