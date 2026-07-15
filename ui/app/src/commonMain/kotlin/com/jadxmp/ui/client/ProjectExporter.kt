package com.jadxmp.ui.client

import com.jadxmp.api.ExportedFile
import com.jadxmp.api.SourceArchive

/**
 * One file of an exported project: a relative, `/`-separated [path] and its [bytes]. The UI-side mirror
 * of `core:api`'s `ExportedFile`, kept in `ui:app` so the platform shells (which see `ui:app` but not the
 * engine) can implement [ProjectExporter] without depending on `core:api`.
 */
class ExportFile(val path: String, val bytes: ByteArray)

/**
 * Everything a [ProjectExporter] needs to write a decompiled project to a user-chosen destination:
 *  - [files] — the whole project as `path → bytes`, for a shell that can write a **directory tree** (desktop);
 *  - [toZip] — lazily packages [files] into a single ZIP, for a shell that can only hand the user **one
 *    file** (a browser download, an android Downloads write). Lazy so a directory-writing shell never pays
 *    to build a ZIP it discards;
 *  - [zipName] — the suggested archive filename for that single-file path.
 */
class ExportRequest(
    val projectName: String,
    val files: List<ExportFile>,
    val zipName: String,
    val toZip: () -> ByteArray,
)

/**
 * Platform seam for exporting the whole decompiled project (P0#7), mirroring [FileSaver]/[FileOpener]:
 * `ui:app` defines the contract, each shell supplies the destination (a directory on desktop, a download
 * on web, Downloads on android). Returns `true` when the project was written, `false` when the user
 * cancelled or the write failed. `suspend` so a shell can run the dialog + write off the UI thread; the
 * workbench launches it on a cancelable scope. When none is wired (stub/preview), the "Export" affordance
 * is hidden/disabled.
 */
fun interface ProjectExporter {
    suspend fun export(request: ExportRequest): Boolean
}

/**
 * A [ProjectExporter] for single-file platforms: packages the project into a ZIP and hands it to a
 * [FileSaver] (a browser download / android Downloads write). Lets the web and android shells reuse their
 * existing Wave-2 [FileSaver] with no new file-writing code.
 */
class ZipDownloadExporter(private val saver: FileSaver) : ProjectExporter {
    override suspend fun export(request: ExportRequest): Boolean =
        saver.save(request.zipName, request.toZip())
}

/**
 * Package UI [files] into a ZIP through `core:api`'s [SourceArchive] (which delegates to
 * `core:binary-io`'s wasm-safe ZIP writer). The one place `ui:app` reaches the engine's archiver — kept
 * behind this helper so callers stay engine-agnostic. Rebuilding [ExportedFile]s here is cheap (it wraps
 * the same byte arrays, no copy) and avoids a second decompile.
 */
internal fun zipExport(files: List<ExportFile>): ByteArray =
    SourceArchive.zip(files.map { ExportedFile(it.path, it.bytes) })
