package com.jadxmp.io

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray

/**
 * A named source of bytes — a file, a zip entry, an upload. The rest of the engine consumes inputs
 * through this rather than touching a real filesystem, so the same loading code runs on desktop
 * (real files) and in the browser (in-memory uploads) unchanged.
 */
public interface ByteSource {
    public val name: String

    public fun readBytes(): ByteArray
}

/** A [ByteSource] over an in-memory [ByteArray]. */
public class ByteArraySource(
    override val name: String,
    private val bytes: ByteArray,
) : ByteSource {
    override fun readBytes(): ByteArray = bytes
}

/**
 * The minimal filesystem surface the loaders need, kept behind an interface so platform I/O
 * (kotlinx-io `SystemFileSystem` on JVM/Native, uploads on web) is injected rather than assumed.
 * Intentionally read-only and small: the engine only ever *reads* inputs.
 */
public interface FileSystem {
    public fun exists(path: String): Boolean

    public fun readBytes(path: String): ByteArray

    /** List immediate child paths of a directory (empty if [path] is not a directory). */
    public fun list(path: String): List<String>

    public fun source(path: String): ByteSource = ByteArraySource(path, readBytes(path))
}

/**
 * An in-memory [FileSystem], handy for tests and for the browser where "files" are uploaded blobs.
 * Paths are treated as `/`-separated; [list] returns direct children.
 */
public class InMemoryFileSystem(
    files: Map<String, ByteArray>,
) : FileSystem {
    private val files: Map<String, ByteArray> = files.toMap()

    override fun exists(path: String): Boolean = files.containsKey(normalize(path))

    override fun readBytes(path: String): ByteArray =
        files[normalize(path)] ?: throw ByteReaderException("no such file: $path")

    override fun list(path: String): List<String> {
        val prefix = if (path.isEmpty() || path == "/") "" else normalize(path).trimEnd('/') + "/"
        return files.keys
            .filter { it.startsWith(prefix) && it != prefix }
            .map { key ->
                val rest = key.substring(prefix.length)
                val slash = rest.indexOf('/')
                if (slash < 0) prefix + rest else prefix + rest.substring(0, slash)
            }
            .distinct()
    }

    private fun normalize(path: String): String = path.removePrefix("./").trimStart('/')
}

/** Read all remaining bytes from a kotlinx-io [Source]. Keeps kotlinx-io usage inside binary-io. */
public fun Source.readAllBytes(): ByteArray = readByteArray()

/** Wrap raw bytes as a kotlinx-io [Source], for interop with APIs that consume one. */
public fun ByteArray.asSource(): Source = Buffer().also { it.write(this) }
