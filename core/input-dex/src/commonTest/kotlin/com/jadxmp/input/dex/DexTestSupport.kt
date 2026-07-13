package com.jadxmp.input.dex

/** Little-endian byte-poking helpers and minimal DEX fixtures for hand-crafted parser tests. */
internal object DexTestSupport {

    fun ByteArray.setIntLe(off: Int, v: Int): ByteArray {
        this[off] = v.toByte()
        this[off + 1] = (v ushr 8).toByte()
        this[off + 2] = (v ushr 16).toByte()
        this[off + 3] = (v ushr 24).toByte()
        return this
    }

    fun ByteArray.getIntLe(off: Int): Int =
        (this[off].toInt() and 0xFF) or
            ((this[off + 1].toInt() and 0xFF) shl 8) or
            ((this[off + 2].toInt() and 0xFF) shl 16) or
            ((this[off + 3].toInt() and 0xFF) shl 24)

    fun ByteArray.setShortLe(off: Int, v: Int): ByteArray {
        this[off] = v.toByte()
        this[off + 1] = (v ushr 8).toByte()
        return this
    }

    /** A structurally valid 112-byte DEX header with all pool sections empty. */
    fun minimalHeader(): ByteArray {
        val h = ByteArray(112)
        "dex\n035".encodeToByteArray().copyInto(h) // magic + version; h[7] stays 0
        h.setIntLe(36, 112) // header_size
        h.setIntLe(40, DexConsts.ENDIAN_CONSTANT)
        return h
    }

    fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }
}
