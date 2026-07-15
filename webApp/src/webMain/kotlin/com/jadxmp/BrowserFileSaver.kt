package com.jadxmp

import com.jadxmp.ui.client.FileSaver
import js.array.jsArrayOf
import js.typedarrays.toUint8Array
import web.blob.Blob
import web.blob.BlobPart
import web.file.Files

/**
 * Browser [FileSaver]: triggers a client-side download of [bytes] as [suggestedName] — the web analogue
 * of DesktopFileSaver. It wraps the bytes in a [Blob] (via a `Uint8Array` typed-array view) and hands it
 * to the kotlin-wrappers [Files.downloadFile] helper, which creates an `<a download>`, points it at an
 * object URL, clicks it, and cleans up — mirroring how BrowserFileOpener/BrowserFileDrop drive the DOM
 * from this shell (ui:app's commonMain never touches the DOM).
 *
 * The browser owns the actual save location (its download folder / "save as" prompt), so there is no
 * cancel signal to surface; [save] returns `false` only if building/triggering the download throws
 * (swallowed to a no-op, never an uncaught coroutine failure — rule 4).
 */
class BrowserFileSaver : FileSaver {

    // The typed-array / Blob interop is @RequiresOptIn(WARNING) as ExperimentalWasmJsInterop on the
    // wasmJs target only; suppress here rather than @OptIn so the shared js target (which does not mark
    // it experimental) still compiles — kotlin.Suppress resolves on both, an unused suppression is inert.
    @Suppress("OPT_IN_USAGE")
    override suspend fun save(suggestedName: String, bytes: ByteArray): Boolean = runCatching {
        val parts = jsArrayOf<BlobPart>(bytes.toUint8Array())
        Files.downloadFile(Blob(parts), suggestedName)
        true
    }.getOrDefault(false)
}
