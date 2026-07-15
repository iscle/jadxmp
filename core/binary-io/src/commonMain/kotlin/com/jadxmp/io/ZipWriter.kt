package com.jadxmp.io

import dev.karmakrafts.kompress.crc.CRC32
import dev.karmakrafts.kompress.crc.once
import dev.karmakrafts.kompress.deflate.Deflater
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeIntLe
import kotlinx.io.writeShortLe

/**
 * Writes a ZIP archive from in-memory `name → bytes` entries — the write-side companion to [ZipReader],
 * and the packaging layer behind "export decompiled sources" on targets that can only hand the user a
 * single file (a browser download, an android Downloads write). Pure `commonMain`: it builds the whole
 * archive in a kotlinx-io [Buffer] using Kompress for DEFLATE + CRC-32, so it compiles for jvm + wasmJs
 * + js with no `java.util.zip`.
 *
 * ## Format
 * A minimal but standards-correct ZIP: for each entry a local file header + data, then the central
 * directory, then the end-of-central-directory record. Each entry is emitted **DEFLATE** (method 8) when
 * that is smaller than the input and **STORED** (method 0) otherwise, so already-incompressible or tiny
 * inputs never grow. Every header carries an accurate CRC-32 and both sizes, so the output opens in any
 * conformant unzip (the OS, `jadx`, `unzip`) — not only in our lenient [ZipReader] — and round-trips
 * through [ZipReader.extract] byte-for-byte.
 *
 * ## Limits (documented, not enforced)
 * No ZIP64: the archive must stay under 4 GiB and 65 535 entries (the 16/32-bit header fields). That is
 * far above any realistic decompiled-project export; a larger export is a follow-on, not a correctness
 * bug here. Entry order is preserved (the central directory mirrors write order), so output is
 * deterministic for a given input.
 */
public object ZipWriter {

    /**
     * Package [entries] (relative `/`-separated name → raw bytes, in order) into a single ZIP byte array.
     * When [compress] is true (default) each entry is DEFLATEd if that shrinks it, else STORED verbatim;
     * `compress = false` forces STORED for every entry (useful when the inputs are already compressed).
     * Names are normalized to forward slashes with any leading `/` stripped; the caller owns path safety
     * (these come from the engine's sanitized source/resource paths).
     */
    public fun write(entries: List<Pair<String, ByteArray>>, compress: Boolean = true): ByteArray {
        val out = Buffer()
        val central = ArrayList<CentralEntry>(entries.size)
        var offset = 0L
        for ((rawName, data) in entries) {
            val nameBytes = normalizeName(rawName).encodeToByteArray()
            val crc = CRC32().once(data).toInt()
            // DEFLATE only when it actually helps; a tiny/incompressible entry stays STORED so the archive
            // never grows past the raw bytes. An empty entry is always STORED (no deflate stream to write).
            val deflated = if (compress && data.isNotEmpty()) Deflater.compress(data, DEFLATE_LEVEL) else null
            // `useDeflate` implies `deflated != null`; the compiler smart-casts it non-null below.
            val useDeflate = deflated != null && deflated.size < data.size
            val method = if (useDeflate) METHOD_DEFLATE else METHOD_STORED
            val payload = if (useDeflate) deflated else data

            // Local file header (30 bytes) + name + data.
            out.writeIntLe(LOCAL_HEADER_SIG)
            out.writeShortLe(VERSION.toShort())
            out.writeShortLe(0) // general-purpose flags
            out.writeShortLe(method.toShort())
            out.writeShortLe(DOS_TIME.toShort())
            out.writeShortLe(DOS_DATE.toShort())
            out.writeIntLe(crc)
            out.writeIntLe(payload.size)
            out.writeIntLe(data.size)
            out.writeShortLe(nameBytes.size.toShort())
            out.writeShortLe(0) // extra-field length
            out.write(nameBytes)
            out.write(payload)

            central += CentralEntry(nameBytes, method, crc, payload.size, data.size, offset)
            offset += LOCAL_HEADER_BYTES + nameBytes.size + payload.size
        }

        val centralStart = offset
        var centralSize = 0L
        for (e in central) {
            // Central-directory file header (46 bytes) + name.
            out.writeIntLe(CENTRAL_DIR_SIG)
            out.writeShortLe(VERSION.toShort()) // version made by
            out.writeShortLe(VERSION.toShort()) // version needed
            out.writeShortLe(0) // general-purpose flags
            out.writeShortLe(e.method.toShort())
            out.writeShortLe(DOS_TIME.toShort())
            out.writeShortLe(DOS_DATE.toShort())
            out.writeIntLe(e.crc)
            out.writeIntLe(e.compressedSize)
            out.writeIntLe(e.uncompressedSize)
            out.writeShortLe(e.name.size.toShort())
            out.writeShortLe(0) // extra-field length
            out.writeShortLe(0) // comment length
            out.writeShortLe(0) // disk number start
            out.writeShortLe(0) // internal attributes
            out.writeIntLe(0) // external attributes
            out.writeIntLe(e.localHeaderOffset.toInt())
            out.write(e.name)
            centralSize += CENTRAL_DIR_BYTES + e.name.size
        }

        // End of central directory.
        out.writeIntLe(EOCD_SIG)
        out.writeShortLe(0) // this disk number
        out.writeShortLe(0) // disk with central directory
        out.writeShortLe(central.size.toShort()) // entries on this disk
        out.writeShortLe(central.size.toShort()) // total entries
        out.writeIntLe(centralSize.toInt())
        out.writeIntLe(centralStart.toInt())
        out.writeShortLe(0) // archive comment length
        return out.readByteArray()
    }

    /** Fold `\` to `/` and strip a leading `/` so entries are archive-relative (mirrors [ZipReader.sanitizeName]). */
    private fun normalizeName(name: String): String = name.replace('\\', '/').trimStart('/')

    /** One entry's central-directory facts, captured while writing its local header for later emission. */
    private class CentralEntry(
        val name: ByteArray,
        val method: Int,
        val crc: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Long,
    )

    private const val LOCAL_HEADER_SIG = 0x04034b50
    private const val CENTRAL_DIR_SIG = 0x02014b50
    private const val EOCD_SIG = 0x06054b50

    /** Size of a fixed local-file-header / central-directory-header before the variable name + extra. */
    private const val LOCAL_HEADER_BYTES = 30
    private const val CENTRAL_DIR_BYTES = 46

    /** "Version needed / made by" 2.0 — the minimum that supports DEFLATE. */
    private const val VERSION = 20
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATE = 8

    /** A fixed, valid DOS timestamp (1980-01-01 00:00) — the same sentinel [ZipReader] treats as valid. */
    private const val DOS_TIME = 0x0000
    private const val DOS_DATE = 0x0021

    /** Balanced default DEFLATE level; source text compresses well without the slowest setting. */
    private const val DEFLATE_LEVEL = 6
}
