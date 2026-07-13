package com.jadxmp.io

import dev.karmakrafts.kompress.deflate.Inflater
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/** One extracted archive entry: its validated name and decompressed bytes. */
public class ExtractedZipEntry(
    public val name: String,
    public val bytes: ByteArray,
)

/**
 * Security limits applied when reading an untrusted archive. The decompression and central-directory
 * handling come from Kompress; these caps are the policy layer we own, to defend against zip-slip
 * (path traversal) and zip bombs (a small archive that inflates to gigabytes).
 */
public class ZipGuard(
    /** Refuse an archive with more than this many entries. */
    public val maxEntries: Int = 200_000,
    /** Refuse any single entry that inflates past this many bytes. */
    public val maxEntryBytes: Long = 512L * 1024 * 1024,
    /** Refuse once the total kept, inflated size passes this many bytes. */
    public val maxTotalBytes: Long = 4L * 1024 * 1024 * 1024,
    /** If true (default), an entry name that escapes the root aborts the read; if false, it is skipped. */
    public val rejectUnsafeNames: Boolean = true,
)

/**
 * Optional sink notified whenever [ZipReader.extract] cannot read a *wanted* entry and skips it
 * (fault isolation, rule 4) instead of aborting the whole archive. Skips are never silent: each is
 * reported here with the entry's (sanitized) name and a human-readable reason.
 *
 * This is diagnostics, not a security boundary: guard-limit breaches (zip bombs over the size caps,
 * too many entries, path traversal when [ZipGuard.rejectUnsafeNames]) remain policy and still abort
 * extraction with [ByteReaderException] rather than being reported here. Being a `fun interface`, a
 * caller can pass a lambda; existing callers that ignore diagnostics pass nothing.
 */
public fun interface ZipDiagnostics {
    /** Called once per skipped wanted entry. [reason] is human-readable, not a stable contract. */
    public fun onSkippedEntry(name: String, reason: String)
}

/**
 * Reads ZIP archives (APK/JAR are ZIPs) through Kompress, wrapped by our [ZipGuard] security policy.
 *
 * Kompress is a *streaming* unarchiver: it walks local file headers front-to-back and aborts the whole
 * pass on any bad entry (an unsupported compression method, an impossible DOS timestamp, a CRC
 * mismatch). Real/fake APKs contain such entries, and jadx tolerates them — one junk entry must never
 * cost us a valid `classes.dex`. So we drive extraction from the **central directory** instead: read
 * each entry's local-header offset, then decode that one entry in isolation. A single unreadable entry
 * is skipped (and reported via [ZipDiagnostics]); the rest are recovered. STORED (method 0) entries are
 * copied verbatim by us; DEFLATE (method 8) is inflated by us — we locate the raw deflate stream from
 * the entry's LOCAL header (like STORED) and feed only those bytes to Kompress's raw [Inflater], never
 * its zip-container parser (whose extra-field decoder throws on any local extra field but Zip64, which
 * would silently drop real JAR/APK entries carrying Info-ZIP timestamp/UID-GID or alignment padding).
 * Any other method skips that one entry. Pure `commonMain`.
 */
public object ZipReader {

    /**
     * Result of trying to read one entry. Guard breaches are NOT modeled here — they throw
     * [ByteReaderException] straight out. This keeps per-entry fault isolation explicit (a [Skipped]
     * value carries its reason to the caller's diagnostics) rather than smuggled through a thrown
     * exception caught far away.
     */
    private sealed interface EntryOutcome {
        /** Wanted entry, successfully read. */
        class Kept(val bytes: ByteArray) : EntryOutcome

        /** Nothing to hand back: an unwanted entry drained only for cap accounting. */
        object Drained : EntryOutcome

        /** This one entry could not be read; skip it and report [reason]. */
        class Skipped(val reason: String) : EntryOutcome
    }

    private class CentralRecord(
        val localHeaderOffset: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val method: Int,
        val name: String,
    )

    /** True if [bytes] begins with the `PK` local-file-header magic. */
    public fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0].toInt() == 0x50 && bytes[1].toInt() == 0x4B &&
            bytes[2].toInt() == 0x03 && bytes[3].toInt() == 0x04

    /**
     * Extract every entry whose (sanitized) name satisfies [nameFilter], as name→bytes.
     *
     * A single corrupt/unsupported/malformed entry is skipped, not fatal — and never silently: each
     * skipped *wanted* entry is reported to [diagnostics] (default none) with a reason, so a decoy junk
     * entry can't make a whole APK vanish while hiding that it happened. Guard-limit breaches (too many
     * entries, a bomb exceeding the size caps) and, when [ZipGuard.rejectUnsafeNames], path-traversal
     * names do throw [ByteReaderException] — those are policy, not recoverable per-entry errors.
     *
     * [diagnostics] is a defaulted, source-compatible addition; existing callers pass nothing.
     */
    public fun extract(
        bytes: ByteArray,
        guard: ZipGuard = ZipGuard(),
        diagnostics: ZipDiagnostics? = null,
        nameFilter: (String) -> Boolean = { true },
    ): List<ExtractedZipEntry> {
        // Neutralize invalid DOS date/time fields up front so a wanted entry with a zero timestamp still
        // decodes (Kompress would otherwise throw converting it to a LocalDateTime).
        val data = sanitizeDosTimestamps(bytes)
        val records = readCentralDirectory(data) ?: return emptyList()

        val result = ArrayList<ExtractedZipEntry>()
        var inflatedTotal = 0L
        records.forEachIndexed { index, record ->
            if (index + 1 > guard.maxEntries) {
                throw ByteReaderException("zip has too many entries (> ${guard.maxEntries})")
            }
            val safe = sanitizeName(record.name)
            if (safe == null) {
                if (guard.rejectUnsafeNames) {
                    throw ByteReaderException("unsafe zip entry name (path traversal): '${record.name}'")
                }
                return@forEachIndexed
            }
            val wanted = nameFilter(safe)
            // Every entry is drained (wanted or not) so the global bomb cap counts all decoded output —
            // an archive of huge, filtered-out entries must still trip the cap.
            val addGlobal: (Long) -> Unit = { delta ->
                inflatedTotal += delta
                if (inflatedTotal > guard.maxTotalBytes) {
                    throw ByteReaderException("zip total inflated size exceeds cap (${guard.maxTotalBytes})")
                }
            }
            // Per-entry fault isolation (rule 4): a corrupt / malformed / unsupported entry skips ONLY
            // itself, never aborting the archive — otherwise one decoy entry (e.g. a method-12 file that
            // passes the name filter) would cost us every real class. Guard breaches still throw.
            val outcome: EntryOutcome = try {
                when (record.method) {
                    // STORED: data is verbatim (no deflate stream). We copy it ourselves rather than route
                    // it through Kompress, whose strict streaming parser rejects real-APK STORED entries
                    // (zipalign extra-field padding, CRCs) — which previously vanished silently. Rule 4.
                    METHOD_STORED -> readStoredEntry(data, record, keep = wanted, guard = guard, addGlobal = addGlobal)
                    // DEFLATE: like STORED, we read the raw stream from the LOCAL header ourselves (so a
                    // local extra field can't confuse a container parser) and inflate it with Kompress's
                    // raw Inflater. A corrupt stream is isolated as Skipped by the catch below (rule 4).
                    METHOD_DEFLATE -> inflateEntry(data, record, keep = wanted, guard = guard, addGlobal = addGlobal)
                    // An unsupported method contributes nothing extractable: skip this one entry.
                    else -> EntryOutcome.Skipped("unsupported compression method ${record.method}")
                }
            } catch (e: ByteReaderException) {
                throw e // guard breach → not skippable
            } catch (e: Exception) {
                // A wanted DEFLATE entry that fails to inflate lands here — isolate it AND record it
                // below (no silent drop), symmetric with the STORED/unsupported skip reasons.
                EntryOutcome.Skipped(e.message ?: e::class.simpleName ?: "unreadable entry")
            }
            when (outcome) {
                is EntryOutcome.Kept -> result.add(ExtractedZipEntry(safe, outcome.bytes))
                is EntryOutcome.Skipped -> if (wanted) diagnostics?.onSkippedEntry(safe, outcome.reason)
                EntryOutcome.Drained -> Unit
            }
        }
        return result
    }

    /**
     * List the (sanitized) names of every central-directory entry, WITHOUT extracting any bytes.
     *
     * Cheap: it only parses the central directory, so a UI can build a resource tree (drawables, assets)
     * over a large archive without inflating anything. Unsafe/path-traversal names are dropped from the
     * listing (this is a convenience view, not the extraction security boundary — [extract] still applies
     * the full [ZipGuard] policy). Returns an empty list for a non-zip or unreadable directory.
     */
    public fun entryNames(bytes: ByteArray): List<String> {
        val records = readCentralDirectory(bytes) ?: return emptyList()
        return records.mapNotNull { sanitizeName(it.name) }
    }

    /**
     * Inflate a DEFLATE (method 8) entry in isolation, reading the raw stream straight from the entry's
     * LOCAL header (past its name + extra field) and feeding ONLY those [CentralRecord.compressedSize]
     * bytes to Kompress's raw [Inflater]. This deliberately bypasses Kompress's zip-container/extra-field
     * parser, which throws on any local extra field but Zip64 — so a common Info-ZIP timestamp/UID-GID or
     * alignment-padding extra field (present in JARs built with Unix `zip`, and in aligned APKs) no longer
     * costs us the entry, exactly as the STORED path is already immune.
     *
     * Guards mirror the STORED path: the claimed uncompressed size is bounded against the per-entry cap
     * BEFORE inflating (a bomb claiming a huge size aborts as a guard breach, never allocating it), the
     * decode is streamed through a fixed [CHUNK]-sized buffer (never the claimed size, so a lying size
     * cannot OOM), and the per-entry cap is re-checked against the *actual* inflated size as it grows.
     * Every inflated byte is added to the global bomb cap via [addGlobal] (even for a filtered-out entry).
     * Malformed conditions (bad local header, truncated stream, size mismatch) are per-entry
     * [EntryOutcome.Skipped]; a corrupt deflate stream throws and is isolated by the caller (rule 4).
     */
    private fun inflateEntry(
        data: ByteArray,
        record: CentralRecord,
        keep: Boolean,
        guard: ZipGuard,
        addGlobal: (Long) -> Unit,
    ): EntryOutcome {
        val claimed = record.uncompressedSize
        // Bound the claimed size before inflating so a bomb claiming a huge size aborts as a guard breach
        // (like STORED / an over-cap inflate), never allocating it. A zip64 sentinel size is unknown here
        // and is instead bounded by the streaming per-entry cap below.
        if (claimed < ZIP64_MARKER && claimed > guard.maxEntryBytes) {
            throw ByteReaderException("zip entry '${record.name}' exceeds size cap (${guard.maxEntryBytes})")
        }
        val dataStart = localDataStart(data, record.localHeaderOffset)
            ?: return EntryOutcome.Skipped("bad local header")
        val cs = record.compressedSize
        val end: Int = when {
            cs in 1 until ZIP64_MARKER -> {
                val e = dataStart.toLong() + cs
                // Truncated: the declared compressed bytes are not all present. Skip, keep the rest.
                if (e > data.size.toLong()) return EntryOutcome.Skipped("truncated deflate data")
                e.toInt()
            }
            // An empty compressed stream has no deflate blocks to read; honor it only if it claims 0 bytes.
            cs == 0L -> return if (claimed == 0L) {
                if (keep) EntryOutcome.Kept(ByteArray(0)) else EntryOutcome.Drained
            } else {
                EntryOutcome.Skipped("empty deflate stream but declared size $claimed")
            }
            // Unknown/zip64 compressed size: feed to the end of the archive; the inflater stops at the
            // stream's final block, ignoring any trailing central-directory bytes.
            else -> data.size
        }
        val slice = data.copyOfRange(dataStart, end)

        val out = if (keep) Buffer() else null
        val chunk = ByteArray(CHUNK)
        var produced = 0L
        Inflater().use { inflater ->
            inflater.setInput(slice)
            inflater.finish()
            while (true) {
                val n = inflater.decompress(chunk)
                if (n == 0) break
                produced += n
                addGlobal(n.toLong())
                if (produced > guard.maxEntryBytes) {
                    throw ByteReaderException("zip entry '${record.name}' exceeds size cap (${guard.maxEntryBytes})")
                }
                out?.write(chunk, 0, n)
            }
        }
        // A well-formed deflate entry produces exactly its declared uncompressed size; a mismatch is a
        // malformed/hostile entry (isolate it, keep the rest). Zip64 sentinel sizes aren't comparable
        // against the 32-bit CD field, so the check is skipped for them.
        if (claimed < ZIP64_MARKER && produced != claimed) {
            return EntryOutcome.Skipped("deflate size mismatch (declared=$claimed inflated=$produced)")
        }
        return if (out != null) EntryOutcome.Kept(out.readByteArray()) else EntryOutcome.Drained
    }

    /**
     * Read a STORED (method 0) entry: its bytes are held verbatim, so there is no deflate stream — we
     * copy [CentralRecord.uncompressedSize] bytes straight from the entry's local-header data offset.
     *
     * The same guards a deflate entry gets apply here: the claimed size is bounded against the per-entry
     * cap BEFORE any allocation (a crafted huge size throws as a guard breach, never OOM), and the copied
     * length is added to the global bomb cap. Malformed conditions (size mismatch, zip64 sentinel size,
     * bad local header, truncation) are per-entry [EntryOutcome.Skipped] — one bad entry never aborts the
     * archive (rule 4). For STORED the two sizes MUST agree, since there is no compression to explain a
     * difference; a mismatch is a malformed/hostile entry and is skipped with a recorded reason.
     */
    private fun readStoredEntry(
        data: ByteArray,
        record: CentralRecord,
        keep: Boolean,
        guard: ZipGuard,
        addGlobal: (Long) -> Unit,
    ): EntryOutcome {
        val size = record.uncompressedSize
        if (record.compressedSize != size) {
            return EntryOutcome.Skipped(
                "stored size mismatch (compressed=${record.compressedSize} uncompressed=$size)",
            )
        }
        // A zip64 sentinel size can't be honored from the 32-bit CD field alone: treat as unreadable.
        if (size >= ZIP64_MARKER) return EntryOutcome.Skipped("stored zip64 size unsupported")
        // Bound the claimed size before allocating so a crafted huge size fails as a guard breach (abort),
        // exactly like an over-cap deflate entry — a zip bomb is policy, not a recoverable per-entry error.
        if (size > guard.maxEntryBytes) {
            throw ByteReaderException("zip entry '${record.name}' exceeds size cap (${guard.maxEntryBytes})")
        }
        val dataStart = localDataStart(data, record.localHeaderOffset)
            ?: return EntryOutcome.Skipped("bad local header")
        val end = dataStart.toLong() + size
        // Truncated: not all declared bytes are present. Skip this entry, keep the rest of the zip.
        if (end > data.size.toLong()) return EntryOutcome.Skipped("truncated stored data")
        addGlobal(size) // count toward the global bomb cap exactly like an inflated entry
        return if (keep) EntryOutcome.Kept(data.copyOfRange(dataStart, end.toInt())) else EntryOutcome.Drained
    }

    /** Absolute offset of an entry's data (past its local header + name + extra), or null if invalid. */
    private fun localDataStart(data: ByteArray, localOffset: Int): Int? {
        if (localOffset !in 0..(data.size - 30)) return null
        if (readU32(data, localOffset) != LOCAL_HEADER_SIG) return null
        val nameLen = readU16(data, localOffset + 26)
        val extraLen = readU16(data, localOffset + 28)
        val dataStart = localOffset + 30 + nameLen + extraLen
        return if (dataStart in 0..data.size) dataStart else null
    }

    /** Parse the central directory into local-header offsets + sizes + names, or null if unreadable. */
    private fun readCentralDirectory(data: ByteArray): List<CentralRecord>? {
        return try {
            val eocd = findEocd(data) ?: return null
            val r = ByteReader(data, start = eocd)
            r.skip(4) // EOCD signature
            r.skip(2) // disk number
            r.skip(2) // disk with central dir
            r.skip(2) // entries on this disk
            val totalEntries = r.readU16()
            r.skip(4) // central dir size
            val cdOffset = r.readU32()
            if (totalEntries == 0xFFFF || cdOffset < 0 || cdOffset > data.size) return null // zip64/unsupported

            val records = ArrayList<CentralRecord>(minOf(totalEntries, data.size / 46 + 1))
            var pos = cdOffset.toInt()
            for (i in 0 until totalEntries) {
                if (pos < 0 || pos + 46 > data.size || readU32(data, pos) != CENTRAL_DIR_SIG) break
                val method = readU16(data, pos + 10)
                val compressedSize = readU32(data, pos + 20)
                val uncompressedSize = readU32(data, pos + 24)
                val nameLen = readU16(data, pos + 28)
                val extraLen = readU16(data, pos + 30)
                val commentLen = readU16(data, pos + 32)
                val localOffset = readU32(data, pos + 42).toInt()
                val nameEnd = pos + 46 + nameLen
                if (nameEnd > data.size) break
                val name = data.copyOfRange(pos + 46, nameEnd).decodeToString()
                records.add(CentralRecord(localOffset, compressedSize, uncompressedSize, method, name))
                pos += 46 + nameLen + extraLen + commentLen
            }
            records
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Normalize and validate an entry name. Returns the cleaned `/`-separated name, or null if it
     * escapes the archive root (absolute path or any `..` segment).
     */
    public fun sanitizeName(rawName: String): String? {
        val name = rawName.replace('\\', '/')
        if (name.startsWith("/")) return null
        // Drive-letter absolute paths (C:/…).
        if (name.length >= 2 && name[1] == ':') return null
        val segments = name.split('/')
        if (segments.any { it == ".." }) return null
        return name
    }

    /** Fixed inflate output buffer size; the decode is streamed through it so a lying size cannot OOM. */
    private const val CHUNK: Int = 64 * 1024

    /** 0xFFFFFFFF sentinel meaning "see zip64 extra field" for a 32-bit size/offset field. */
    private const val ZIP64_MARKER: Long = 0xFFFFFFFFL

    // --- DOS date/time tolerance ---
    // A ZIP local file header stores a 2-byte DOS time at +10 and a 2-byte DOS date at +12. Some tools
    // (and hostile/fake archives) write zero or garbage there; Kompress converts these to a LocalDateTime
    // eagerly and throws on an impossible one. We rewrite only invalid fields to a valid sentinel
    // (1980-01-01 00:00:00), leaving valid timestamps and all other bytes untouched. Local header offsets
    // come from the central directory, so we never guess where headers are.

    private const val CENTRAL_DIR_SIG = 0x02014b50L
    private const val LOCAL_HEADER_SIG = 0x04034b50L

    /** ZIP compression methods we handle explicitly; any other method is an honest error, not a drop. */
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATE = 8

    private const val VALID_DOS_DATE = 0x0021 // 1980-01-01
    private const val VALID_DOS_TIME = 0x0000 // 00:00:00

    private fun sanitizeDosTimestamps(input: ByteArray): ByteArray {
        return try {
            val eocd = findEocd(input) ?: return input
            val r = ByteReader(input, start = eocd)
            r.skip(4) // EOCD signature
            r.skip(2) // disk number
            r.skip(2) // disk with central dir
            r.skip(2) // entries on this disk
            val totalEntries = r.readU16()
            r.skip(4) // central dir size
            val cdOffset = r.readU32()
            // Zip64 (0xFFFF/0xFFFFFFFF markers) or an out-of-range offset: leave as-is and let Kompress try.
            if (totalEntries == 0xFFFF || cdOffset < 0 || cdOffset > input.size) return input

            val data = input.copyOf()
            var pos = cdOffset.toInt()
            for (i in 0 until totalEntries) {
                if (pos < 0 || pos + 46 > data.size) break
                if (readU32(data, pos) != CENTRAL_DIR_SIG) break
                val nameLen = readU16(data, pos + 28)
                val extraLen = readU16(data, pos + 30)
                val commentLen = readU16(data, pos + 32)
                val localOffset = readU32(data, pos + 42).toInt()
                if (localOffset in 0..(data.size - 14) && readU32(data, localOffset) == LOCAL_HEADER_SIG) {
                    fixDosFields(data, localOffset + 10) // time at +10, date at +12
                }
                pos += 46 + nameLen + extraLen + commentLen
            }
            data
        } catch (e: Exception) {
            // A malformed directory is not our problem to fix here — hand the original bytes to Kompress,
            // which degrades gracefully (and our outer catch normalizes any failure to ByteReaderException).
            input
        }
    }

    private fun fixDosFields(data: ByteArray, timeOffset: Int) {
        val time = readU16(data, timeOffset)
        val date = readU16(data, timeOffset + 2)
        if (!isValidDosTime(time)) writeU16(data, timeOffset, VALID_DOS_TIME)
        if (!isValidDosDate(date)) writeU16(data, timeOffset + 2, VALID_DOS_DATE)
    }

    private fun isValidDosDate(date: Int): Boolean {
        val month = (date ushr 5) and 0x0F
        val day = date and 0x1F
        return month in 1..12 && day in 1..31
    }

    private fun isValidDosTime(time: Int): Boolean {
        val hour = (time ushr 11) and 0x1F
        val minute = (time ushr 5) and 0x3F
        val secHalf = time and 0x1F // seconds / 2
        return hour <= 23 && minute <= 59 && secHalf <= 29
    }

    private fun findEocd(data: ByteArray): Int? {
        val minEocd = 22
        if (data.size < minEocd) return null
        val scanLimit = maxOf(0, data.size - minEocd - 0xFFFF)
        var pos = data.size - minEocd
        while (pos >= scanLimit) {
            if (data[pos].toInt() and 0xFF == 0x50 && data[pos + 1].toInt() and 0xFF == 0x4B &&
                data[pos + 2].toInt() and 0xFF == 0x05 && data[pos + 3].toInt() and 0xFF == 0x06
            ) {
                return pos
            }
            pos--
        }
        return null
    }

    private fun readU16(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)

    private fun readU32(data: ByteArray, off: Int): Long =
        ((data[off].toInt() and 0xFF).toLong()) or
            ((data[off + 1].toInt() and 0xFF).toLong() shl 8) or
            ((data[off + 2].toInt() and 0xFF).toLong() shl 16) or
            ((data[off + 3].toInt() and 0xFF).toLong() shl 24)

    private fun writeU16(data: ByteArray, off: Int, value: Int) {
        data[off] = (value and 0xFF).toByte()
        data[off + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
