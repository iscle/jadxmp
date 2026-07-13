package com.jadxmp.input.dex

/** Format constants for the DEX container. */
internal object DexConsts {
    /** `dex\n` — the first 4 bytes of every DEX file. */
    val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0a)

    /** `PK` — a local ZIP header, i.e. an APK/JAR wrapping dex files. */
    val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** `0x12345678` written to the header's endian tag; anything else means big-endian/corrupt. */
    const val ENDIAN_CONSTANT: Int = 0x12345678

    /** Sentinel index meaning "absent" (stored as 0xFFFFFFFF). */
    const val NO_INDEX: Int = -1

    fun startsWith(data: ByteArray, magic: ByteArray, offset: Int = 0): Boolean {
        if (data.size - offset < magic.size) return false
        for (i in magic.indices) {
            if (data[offset + i] != magic[i]) return false
        }
        return true
    }
}
