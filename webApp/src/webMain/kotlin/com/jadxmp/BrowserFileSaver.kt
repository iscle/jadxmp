package com.jadxmp

import com.jadxmp.ui.client.FileSaver
import js.array.jsArrayOf
import js.typedarrays.toUint8Array
import web.blob.Blob
import web.blob.BlobPart
import web.dom.document
import web.html.HtmlTagName
import web.timers.setTimeout
import web.url.URL

/**
 * Browser [FileSaver]: triggers a client-side download of [bytes] as [suggestedName] — the web analogue
 * of DesktopFileSaver. It wraps the bytes in a [Blob] (via a `Uint8Array` typed-array view), points a
 * hidden `<a download>` at an object URL, clicks it, then revokes the URL — mirroring how
 * BrowserFileOpener/BrowserFileDrop drive the DOM from this shell (ui:app's commonMain never touches
 * the DOM).
 *
 * ## Why not the kotlin-wrappers `Files.downloadFile` helper
 * That helper (`web.file.Files.downloadFile`) creates the object URL with `URL.createObjectURL` but
 * never calls `URL.revokeObjectURL` — it only removes the anchor element. The object URL, and the
 * whole [Blob] it pins, would then leak for the lifetime of the page (one leak per saved file). So the
 * lifecycle is managed here explicitly and the URL is revoked after the click.
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
        val url = URL.createObjectURL(Blob(parts))
        val anchor = document.createElement(HtmlTagName.a).apply {
            href = url
            download = suggestedName
            style.visibility = "hidden"
        }
        document.body.appendChild(anchor)
        anchor.click()
        anchor.remove()
        // Revoke on a later task, not synchronously: the click starts the download in this task and
        // revoking too early can cancel an in-flight download in some browsers. Freeing a moment late
        // only releases memory. Guarded so a stray revoke failure never surfaces (rule 4).
        setTimeout({ runCatching { URL.revokeObjectURL(url) } }, REVOKE_DELAY_MS)
        true
    }.getOrDefault(false)

    private companion object {
        // Give the browser a beat to grab the blob for the download before the URL is released.
        const val REVOKE_DELAY_MS = 1000
    }
}
